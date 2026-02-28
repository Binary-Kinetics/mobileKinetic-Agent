# Vault Credential Flow vs. Context Injection Pipeline: Compatibility Analysis

**Date**: 2026-02-25
**Author**: Claude (Opus 4.6, RLM-assisted analysis)
**Source files analyzed**:
- `A:/Android-Studio-SDK/mK:a/app/src/main/assets/scripts/context_injector.py`
- `A:/Android-Studio-SDK/mK:a/app/src/main/assets/scripts/agent_orchestrator.py`
- `A:/Android-Studio-SDK/mK:a/app/src/main/assets/scripts/conversation_processor.py`

**Question**: Would the Vault credential flow (biometric-gated Gemma conduit) disrupt mK:a's existing pre-prompt/context injection pipeline?

**Bottom-line answer**: **Compatible, but only if integrated at the right layer.** The Vault flow operates at the tool-call dispatch layer (agent_orchestrator.py), which is architecturally separate from the context injection pipeline (context_injector.py). Inserting the biometric gate at the wrong layer would be catastrophic. Inserting it at the correct layer is clean and non-disruptive.

---

## 1. How the Current Injection Pipeline Works (Tiers 0-3)

### Architecture

`context_injector.py` implements a **4-tier token-budgeted system prompt assembler**. Before every Claude API call, the orchestrator calls `build_context(user_message)` which constructs a system prompt from four tiers, each with a hard token budget:

| Tier | Name | Token Budget | Source | Latency |
|------|------|-------------|--------|---------|
| 0 | Core Identity / Static Personality | 800 tokens (~3,200 chars) | Hardcoded strings: mK:a identity, MEMORY_TAGGING_PROTOCOL, device capabilities, operator identity, voice/TTS instructions | ~0ms (sync) |
| 1 | RAG Memory Retrieval | 400 tokens (~1,600 chars) | Async HTTP to `localhost:5562/search` with user message as query. Returns semantically similar past memories. | 50-200ms (async HTTP) |
| 2 | SQLite Memory Index | 400 tokens (~1,600 chars) | Async executor query to `memory_index.db` at `/data/user/0/com.mobilekinetic.agent/files/memory/memory_index.db`. Returns tagged/indexed facts. | 10-50ms (async executor) |
| 3 | Trigger-Based Context | 400 tokens (~1,600 chars) | `TriggerStore.evaluate(user_message)` pattern-matches against registered triggers and returns matched context blocks. | ~1ms (sync) |
| **Total** | | **2,000 tokens (~8,000 chars)** | | **~50-250ms** |

### Token Budget Manager

Each tier's content is passed through `TokenBudgetManager.allocate(tier, content)`:
- Estimates tokens as `len(content) // 4`
- Truncates at sentence boundaries (finds last `.` if >70% of budget)
- Appends `[...truncated to fit token budget]` if truncated

### Final System Prompt Structure

```
[Tier 0: mK:a Identity + Memory Tagging Protocol]    <- 800 tokens
[Tier 1: RAG-retrieved semantically similar memories]        <- 400 tokens
[Tier 2: SQLite-tagged explicit facts]                       <- 400 tokens
[Tier 3: Trigger-matched dynamic context]                    <- 400 tokens
```

This is set as the `system` parameter in the Anthropic API call.

### Message Flow

```
User input
    |
    v
build_context(user_message) --- Tier 0 (sync) + Tier 1 (async HTTP) + Tier 2 (async DB) + Tier 3 (sync)
    |
    v
_assemble_system_prompt(t0, t1, t2, t3)
    |
    v
anthropic_client.messages.stream(system=assembled_context, messages=conversation_history, tools=REGISTERED_TOOLS)
    |
    v
Claude response (potentially with tool_use blocks)
    |
    v
MEMORY_TAG extraction -> writes back to RAG + SQLite (read-write memory cycle)
```

### Key observation

The injection pipeline is **purely a pre-prompt assembly step**. It runs BEFORE the Claude API call, and its output is a system prompt string. It has zero interaction with tool calls, biometric gates, or credential flows. It reads from data stores; it never writes secrets into the prompt.

---

## 2. Where Vault Calls Would Enter the Pipeline

### The Vault flow does NOT enter the injection pipeline at all

The Vault credential flow is a **tool-call-layer concern**, not an injection-layer concern. Here is the distinction:

| Layer | File | Responsible For | Vault Interaction |
|-------|------|-----------------|-------------------|
| **Injection Layer** | `context_injector.py` | Building system prompt (Tiers 0-3) | **NONE** |
| **Orchestration Layer** | `agent_orchestrator.py` | Tool dispatch, streaming, conversation loop | **YES - this is where Vault calls live** |
| **Post-Processing Layer** | `conversation_processor.py` | Batch memory extraction from JSONL logs | **NONE (or credential resolution for API keys)** |

### Vault call path (in agent_orchestrator.py)

```
Claude decides to call vault_get_credential
    |
    v
content_block_start (tool_use) -> content_block_delta (input JSON) -> content_block_stop
    |
    v
dispatch_tool({"name": "vault_get_credential", "input": {"key": "wifi_password"}, "id": "toolu_01..."})
    |
    v
VaultClient.fetch_credential("wifi_password")  -- HTTP GET to localhost:5565
    |
    v
VaultHttpServer (Ktor/CIO, Titan M2 decryption) returns credential
    |
    v
Tool result appended to conversation -> Claude called again with result
```

### Where the biometric-gated Gemma conduit would insert

In the proposed flow:
1. mK:a (Claude) calls `vault_get_credential` tool
2. Orchestrator dispatches to VaultClient
3. **NEW**: VaultClient sends command (minus credentials) to Vault endpoint
4. **NEW**: Vault encrypts request, sends to Gemma 3 1B
5. **NEW**: Gemma is gated by biometric auth (user must authenticate)
6. **NEW**: Gemma retrieves credentials from encrypted vault, executes command in sealed context
7. **NEW**: Only the result (not the credential) flows back
8. Tool result returned to Claude

This entire flow happens **inside `dispatch_tool()`** in the orchestrator. The injection pipeline (`build_context()`) has already completed and the system prompt is already assembled before any tool call is dispatched. The two systems are temporally and architecturally disjoint.

---

## 3. Whether Biometric Gates Would Cause Timeouts or Flow Disruption

### Current timeout landscape

| Layer | Mechanism | Default Timeout |
|-------|-----------|----------------|
| Individual tool call | `asyncio.wait_for(dispatch_tool(...), timeout=TOOL_TIMEOUT)` | 30s |
| Device API calls | `urllib.request.urlopen(..., timeout=10)` | 10s |
| VaultClient HTTP | HTTP timeout in VaultClient | 10s |
| Full agent turn | Outer `asyncio.wait_for` on streaming connection | 120s |
| Max agentic iterations | Loop counter guard | 10 turns |

### Risk: Biometric latency

A biometric authentication event involves:
- Device waking / screen unlocking
- Biometric prompt appearing
- User performing fingerprint/face scan
- Success/failure callback

**Realistic latency**: 1-15 seconds (fast if phone in hand and awake), up to 60+ seconds (phone in pocket, screen off, user distracted).

### Will it time out?

**With current defaults: YES, likely.**

- The VaultClient HTTP timeout is 10s. A biometric prompt that takes 12s would trigger a timeout.
- The tool dispatch timeout is 30s. A distracted user could exceed this.
- The streaming connection (120s) is safe unless multiple Vault calls chain.

### Mitigation: Timeout adjustments for biometric-gated tools

```python
# In dispatch_tool():
BIOMETRIC_TIMEOUT = 60  # seconds - generous for biometric UX
STANDARD_TIMEOUT = 30

timeout = BIOMETRIC_TIMEOUT if name in BIOMETRIC_REQUIRED_TOOLS else STANDARD_TIMEOUT
result = await asyncio.wait_for(execute_tool(tool_call), timeout=timeout)
```

### Mitigation: Async approval gate pattern

Rather than blocking synchronously on biometric:

```python
if name in BIOMETRIC_REQUIRED_TOOLS:
    # Emit approval request to Android UI via NapkinDab
    napkin_dab.emit({"type": "approval_required", "tool": name, "approval_id": tool_call["id"]})

    # Wait for Android to signal approval via asyncio.Event
    approved = await asyncio.wait_for(approval_gate.wait(tool_call["id"]), timeout=60.0)

    if not approved:
        return tool_result_error(tool_call["id"], "User denied biometric access")
```

This keeps the asyncio event loop responsive while waiting and allows the NapkinDab UI to show the user what is happening.

### Streaming connection concern

The Anthropic streaming connection is **already closed** when tool dispatch begins. The stream has emitted `message_stop` with `stop_reason="tool_use"`. Tool results are collected, then a NEW streaming call is made. There is no open stream that would time out during biometric auth.

**Verdict: No streaming timeout risk.** The biometric delay happens between two separate API calls, not during one.

---

## 4. Whether Gemma Mediation Changes What Context Claude Sees

### What Claude currently sees

When Claude calls `vault_get_credential`, it receives the raw credential value:

```json
{"value": "SuperSecret123!"}
```

Claude then uses this credential in subsequent tool calls (SSH, API calls, etc.), meaning **Claude currently sees every credential it requests**.

### What Claude would see with Gemma mediation

In the proposed flow, Gemma executes the credentialed command in a sealed context and returns only the result:

```json
{"status": "success", "output": "NAS mounted at /mnt/nas"}
```

Claude **never sees the credential**. It sees only the execution result.

### Impact on context injection pipeline

**Zero impact.** The context injection pipeline (Tiers 0-3) assembles the system prompt BEFORE any tool calls happen. It reads from RAG and SQLite -- neither of which would contain raw credentials (they contain memories and facts). The tool result that flows back from the Vault/Gemma conduit goes into the **conversation history** (as a `tool_result` message block), not into the system prompt injection pipeline.

The MEMORY_TAG extraction that runs after Claude responds would NOT capture the credential either, because:
1. Claude never saw the credential (Gemma consumed it)
2. The tool result contains only the execution outcome
3. Even if Claude emitted a MEMORY_TAG about the operation, it would record "NAS was mounted" not the password

### Does Gemma mediation change what context Tiers 0-3 contain?

**No.** The tiers are:
- Tier 0: Static identity (no credentials)
- Tier 1: RAG memories (semantic search, no credential storage)
- Tier 2: SQLite facts (tagged memories, no credential storage)
- Tier 3: Trigger context (pattern-matched, no credential storage)

None of these tiers read from or are influenced by the VaultHttpServer at :5565. The Vault is a separate subsystem accessed only via tool calls.

---

## 5. Specific Risks and Mitigations

### RISK 1: Tool dispatch timeout on biometric delay
- **Severity**: HIGH if not addressed
- **Mechanism**: `asyncio.wait_for(..., timeout=30)` fires before user completes biometric
- **Mitigation**: Increase timeout to 60s for `BIOMETRIC_REQUIRED_TOOLS`, use async approval gate pattern
- **Status**: Requires code change in `dispatch_tool()`

### RISK 2: Cascading tool call failures
- **Severity**: MEDIUM
- **Mechanism**: Claude may issue multiple tool calls in one turn (e.g., vault_get + shell_exec). If the vault call requires biometric and times out, the shell_exec may also fail if it depended on the credential.
- **Mitigation**: Orchestrator already handles tool errors gracefully (returns error JSON, Claude retries or explains). No cascading crash risk -- just wasted turns.

### RISK 3: User fatigue from repeated biometric prompts
- **Severity**: MEDIUM (UX, not technical)
- **Mechanism**: If Claude makes 3 vault calls in one conversation, user gets 3 biometric prompts
- **Mitigation**: Implement a biometric session cache (e.g., 5-minute grace period after successful auth, similar to AndroidInsider's `BiometricAuthManager` pattern). After first auth, subsequent vault calls within the grace window skip biometric.

### RISK 4: conversation_processor.py batch processing credential access
- **Severity**: LOW
- **Mechanism**: The batch memory extractor uses Claude Haiku API, which requires an API key. If that key moves into Vault, batch processing would need biometric auth to start.
- **Mitigation**: Gate biometric auth ONCE at pipeline startup, cache credentials for batch lifetime. This pipeline runs in background (no real-time user waiting), so a 5-15s biometric delay at startup is acceptable.

### RISK 5: NapkinDab UI not updated for biometric flow
- **Severity**: LOW (cosmetic)
- **Mechanism**: Currently NapkinDab emits `tool_start` and `tool_result`. A biometric gate adds a new state: `approval_required` / `approval_pending` / `approval_granted` / `approval_denied`.
- **Mitigation**: Extend NapkinDab event types. Android UI needs a new card type showing biometric prompt status.

### RISK 6: Gemma inference latency stacking on top of biometric latency
- **Severity**: LOW-MEDIUM
- **Mechanism**: Biometric (1-15s) + Gemma inference (1-5s for 1B model) + command execution (variable) = potentially 20+ seconds for a single vault tool call.
- **Mitigation**: Gemma 3 1B on GPU is fast (~1-3s for short command parsing). Biometric is the bottleneck. The 60s timeout accommodates both. Consider showing progress events ("Authenticating..." -> "Processing with Gemma..." -> "Executing...") via NapkinDab.

### NON-RISK: System prompt contamination
- **Severity**: NONE
- **Mechanism**: Credentials could leak into system prompt tiers
- **Reality**: The injection pipeline reads from RAG (port 5562) and SQLite, neither of which stores credentials. The Vault (port 5565) is a completely separate system. There is no data path from Vault -> injection tiers.

### NON-RISK: Context injection pipeline disruption
- **Severity**: NONE
- **Mechanism**: Biometric gate could block or delay context assembly
- **Reality**: `build_context()` completes before any tool calls are dispatched. The biometric gate lives in `dispatch_tool()`, which runs after the API call has started and Claude has decided to use a tool. These are temporally disjoint operations.

---

## 6. Recommendation: Disruptive or Compatible?

### COMPATIBLE -- with targeted modifications

The Vault credential flow (biometric-gated Gemma conduit) is **architecturally compatible** with mK:a's existing pre-prompt/context injection pipeline. The two systems operate at different layers and different times in the message lifecycle:

```
TIMELINE OF A SINGLE USER MESSAGE:

t=0ms     build_context() starts (Tier 0-3 assembly)
t=250ms   build_context() complete, system prompt ready
t=260ms   Claude API streaming call begins
t=500ms   Claude starts generating response
t=800ms   Claude emits tool_use: vault_get_credential     <-- VAULT ENTERS HERE
t=810ms   dispatch_tool() called
t=815ms   NapkinDab: approval_required
t=820ms   Biometric prompt shown on device                 <-- BIOMETRIC GATE
t=3000ms  User authenticates (fingerprint)
t=3100ms  Gemma receives encrypted command
t=4500ms  Gemma executes, returns result
t=4600ms  Tool result returned to orchestrator
t=4700ms  New Claude API call with tool result
t=5500ms  Claude generates final response
t=5600ms  MEMORY_TAG extraction runs
t=5700ms  Response displayed to user

                        injection pipeline has been DONE for 4+ seconds
                        before Vault is ever touched
```

### Required modifications (all in agent_orchestrator.py)

1. **Increase tool timeout** for biometric-gated tools to 60s
2. **Add async approval gate** pattern using `asyncio.Event` registry
3. **Extend NapkinDab** with biometric approval event types
4. **Implement biometric session cache** (5-minute grace period) to avoid repeated prompts
5. **Update VaultClient** to route through Gemma conduit instead of direct HTTP GET

### Files that need changes

| File | Change | Scope |
|------|--------|-------|
| `agent_orchestrator.py` | Biometric gate in `dispatch_tool()`, timeout adjustment, NapkinDab events | Medium |
| `vault_client.py` | Route through Gemma conduit instead of direct HTTP | Medium |
| `context_injector.py` | **NO CHANGES NEEDED** | None |
| `conversation_processor.py` | Optional: biometric auth at batch startup if API keys move to Vault | Small |

### What stays completely untouched

- Tier 0-3 content and assembly logic
- Token budget manager
- RAG query flow (port 5562)
- SQLite memory index queries
- Trigger store evaluation
- MEMORY_TAG extraction and persistence
- System prompt structure and format
- MessageBuffer conversation history management
- Streaming event processing loop

### Final assessment

The Vault credential flow is a **tool-layer enhancement**, not a pipeline-layer change. It adds a biometric gate and Gemma intermediary inside the tool dispatch function, which is architecturally isolated from the context injection pipeline. The injection pipeline will not know or care that credentials are being resolved through a biometric-gated Gemma conduit -- it finishes its work hundreds of milliseconds before any tool call is dispatched.

**The only real engineering challenge is timeout management**, not architectural incompatibility.
