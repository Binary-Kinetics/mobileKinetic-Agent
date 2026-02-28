# mK:a On-Device RLM + MCP Server Design
## Recursive Language Model for Android with On-Device Claude

**Author**: [Author] & Claude
**Date**: 2026-02-23
**Status**: Design Document (Pre-Implementation)
**Target Device**: Pixel device (Tensor G4, 16GB RAM)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [How the Desktop RLM Works](#2-how-the-desktop-rlm-works)
3. [mK:a On-Device RLM Design](#3-mka-on-device-rlm-design)
4. [On-Device MCP Server Design](#4-on-device-mcp-server-design)
5. [Kotlin Implementation Plan](#5-kotlin-implementation-plan)
6. [Integration Points](#6-integration-points)
7. [Considerations and Mitigations](#7-considerations-and-mitigations)
8. [API Reference](#8-api-reference)

---

## 1. Executive Summary

### What This Gives mK:a

mK:a already runs Claude on-device via `ClaudeProcessManager`, which spawns a Node.js subprocess wrapping the Claude CLI through `agent_orchestrator.py`. Today, Claude handles chat interaction in a single-shot query-response pattern: user sends a message, Claude responds (possibly using tools like Bash, RAG search, or Device API calls), and returns a result. There is no mechanism for **iterative deep analysis** -- a structured loop where Claude can recursively explore a large context, search RAG, read files, refine its approach, and converge on a comprehensive answer across multiple focused iterations.

An on-device RLM changes this. It gives Claude a purpose-built recursive REPL loop that enables:

- **Deep recursive analysis**: Claude iteratively explores context, searches RAG, reads files, examines results, decides what else to investigate, and synthesizes findings across multiple iterations -- all with the full power of Claude's 100K+ token context window.
- **Large context processing**: Break down content that exceeds practical limits by using the desktop RLM's proven approach -- loading context as an explorable variable and navigating it programmatically via a Python REPL.
- **Structured tool access**: Each iteration gives Claude access to on-device tools (RAG search, session memory, file operations, embedding similarity) in a controlled REPL environment.
- **Privacy-aware analysis**: Gemma handles input/output privacy filtering while Claude does the actual reasoning, ensuring sensitive queries are screened before and after processing.
- **Local MCP endpoint**: The desktop Claude can call `POST /rlm/analyze` on the device (via `adb forward`) for on-device recursive analysis, mirroring the desktop's `localhost:6100` RLM MCP.
- **Switchboard escalation**: If on-device analysis is insufficient (e.g., Claude's response indicates it needs desktop tools), the engine can escalate to the desktop Claude via the Switchboard.

### Why Claude, Not Gemma

The previous design used Gemma 3 1B as the reasoning engine. This was wrong. Gemma 3 1B is a 1-billion parameter model with a ~4K token context window and limited instruction-following capability. It cannot write Python code reliably, cannot reason through multi-step problems, and requires extensive scaffolding (structured `TOOL_CALL` syntax, aggressive context truncation, 3-iteration limits) to produce even basic results.

Claude is already running on-device via `ClaudeProcessManager`. It has:
- **100K+ token context window** (vs Gemma's ~4K)
- **Excellent code generation** -- can write and self-correct Python in the REPL
- **Deep multi-step reasoning** -- matches the desktop RLM's quality
- **Native tool access** -- already uses Bash, RAG, Device API via the orchestrator
- **Proven architecture** -- the desktop RLM already works this way (Claude + REPL)

Gemma's role in the RLM is now limited to:
- **Embedding generation** via `GemmaEmbeddingProvider` (for RAG search and similarity)
- **Optional privacy filtering** via `GemmaPrivacyFilter` (screen inputs/outputs)

### Architecture at a Glance

```
Desktop Claude ──adb forward──> localhost:5563 ──> DeviceApiServer
                                                        |
                                                   POST /rlm/analyze
                                                        |
                                                        v
                                            RecursiveLanguageEngine
                                              +---------+---------+
                                              |   REPL Loop (N)   |
                                              |                   |
                                              |  ClaudeProcess    |
                                              |  Manager ------>  Python REPL:
                                              |  (Node.js/CLI)     - context variable
                                              |       |            - RAG search
                                              |       v            - Memory lookup
                                              |  Parse response    - File read
                                              |  (Python code      - recursive_llm()
                                              |   or FINAL())      - read_file()
                                              |       |            - glob_files()
                                              |       v
                                              |  Execute code in namespace
                                              |       |
                                              |       v
                                              |  Feed stdout back to Claude
                                              +-------------------+
                                                        |
                                                   FINAL() detected
                                                        |
                                                        v
                                                  JSON response

Privacy Layer (Gemma):
  Input  --> GemmaPrivacyFilter.check(query) --> block/allow
  Output --> GemmaPrivacyFilter.check(result) --> block/allow
```

---

## 2. How the Desktop RLM Works

### Source Files

- **RLM Tool**: `P:\Tools\rlm_tool.py` (core engine, classes `RLM`, `RLMWithRecursion`, `RLMCodebase`)
- **RLM MCP Server**: `P:\MCP\rlm_mcp_server.py` (FastAPI HTTP wrapper on port 6100)

### 2.1 The REPL Loop Concept

The desktop RLM (`rlm_tool.py`) implements a Read-Eval-Print Loop where Claude explores a large context programmatically rather than receiving it all at once. The key insight:

> Instead of: `claude.completion(query + massive_context)` -- hits token limits
> We do: `rlm.completion(query, context_as_variable)` -- code navigates it

**How the loop works:**

1. The `context` variable (the large text) is loaded into a Python namespace.
2. Claude receives a system prompt instructing it to write Python code to explore `context`.
3. Each iteration: Claude writes a ````python``` block, the engine executes it, captures stdout, and feeds the output back as the next prompt.
4. Claude tracks discoveries across iterations and builds urgency as iterations run low.
5. When Claude has enough information, it calls `FINAL('''answer here''')` to terminate the loop.
6. If max iterations are reached, partial discoveries are returned.

**Iteration budget management:**
- Remaining iterations are shown to the model each turn.
- At 5 remaining: "Start wrapping up your analysis."
- At 2 remaining: "STOP EXPLORING. Synthesize ALL your discoveries... NOW."

### 2.2 Three Modes

| Mode | Input | Class | What the REPL Gets |
|------|-------|-------|-------------------|
| **File** | `context_file` path | `RLMWithRecursion` | `context` (str), `re`, `json`, `os`, `Path`, `recursive_llm()` |
| **Text** | `context_text` string | `RLMWithRecursion` | Same as file mode (text written to temp file) |
| **Codebase** | `directory` path | `RLMCodebase` | All of the above + `read_file()`, `list_files()`, `glob_files()`, `directory` variable |

### 2.3 How It Delegates to Claude Models

The engine calls the Claude Code CLI (`claude.cmd`) as a subprocess:

```python
cmd = [CLAUDE_BINARY, '-p', '--system-prompt', system_prompt_clean,
       '--output-format', 'json', '--model', self.model,
       '--max-turns', '1', '--tools', '']
```

Key design decisions:
- **Prompt piped via stdin** to avoid Windows CMD interpretation issues.
- **Single turn only** (`--max-turns 1`) -- each iteration is one stateless Claude call.
- **No built-in tools** (`--tools ''`) -- all tool access is through the Python namespace.
- **Run from temp directory** to avoid CLAUDE.md context pollution.
- **Output truncated to 7000 chars** to stay under CLI limits.

### 2.4 Recursive Sub-Calls

`RLMWithRecursion` adds `recursive_llm(sub_query, text, model="haiku")` to the namespace. This spawns a child Claude call for semantic analysis of a text chunk -- useful when the REPL extracts a section and wants it analyzed semantically rather than programmatically.

### 2.5 The MCP HTTP Wrapper

`rlm_mcp_server.py` is a FastAPI server on port 6100 that wraps the CLI tool:

**Endpoints:**
| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Server health, Python version, tool existence |
| `/tools` | GET | Endpoint documentation |
| `/rlm/analyze` | POST | Synchronous analysis (returns JSON when complete) |
| `/rlm/analyze/stream` | POST | SSE streaming with progress events |

**SSE Events (streaming mode):**
- `status`: Starting message with timestamp
- `progress`: Per-iteration updates with elapsed time and ETA
- `complete`: Final result with answer, iterations, cost, total time
- `error`: Failure information

**Enrichment:** Every SSE event is enriched with `started_at`, `elapsed_seconds`, `timestamp`, and (for progress events) `estimated_remaining_seconds`.

**Request schema:**
```json
{
    "query": "string (required)",
    "context_file": "string (optional)",
    "context_text": "string (optional)",
    "directory": "string (optional)",
    "model": "haiku|sonnet|opus (default: sonnet)",
    "max_iterations": "int (default: 5)",
    "timeout": "int seconds (default: 180, max: 600)",
    "extensions": "[string] (optional, for codebase mode)"
}
```

---

## 3. mK:a On-Device RLM Design

### 3.1 Core Principle: On-Device Claude as the Reasoning Engine

The desktop RLM shells out to the Claude CLI for each iteration. The on-device RLM does the **exact same thing** -- it shells out to the on-device Claude CLI running under `ClaudeProcessManager`. This is the same architecture, adapted for Android.

**How Claude runs on-device (existing infrastructure):**

1. `ClaudeProcessManager` spawns `agent_orchestrator.py` as a persistent Python subprocess.
2. `agent_orchestrator.py` uses the `claude_agent_sdk` (`ClaudeSDKClient`) to run the Claude CLI.
3. The Claude CLI runs as a Node.js process (pinned to v2.0.37) with MCP server access.
4. Communication: JSON over stdin/stdout between Android and the orchestrator.

**For the RLM, we use the same Claude CLI but in a different mode:**

| Property | Desktop RLM (rlm_tool.py) | On-Device RLM |
|----------|--------------------------|---------------|
| Claude binary | `claude.cmd` (Windows) | `~/.local/bin/claude` (Termux) |
| Invocation | `subprocess.run()` per iteration | `subprocess.run()` per iteration |
| Model | haiku/sonnet/opus (selectable) | sonnet (default, configurable) |
| Context window | 100K-200K tokens | Same (100K-200K tokens) |
| Code generation | Excellent | Excellent (same Claude) |
| Reasoning depth | Deep, multi-step | Same (same Claude) |
| REPL style | Python code execution | Python code execution |
| Tools in namespace | `context`, `re`, `json`, `os`, `Path`, `recursive_llm()` | Same + `rag_search()`, `session_context()`, `embed_similarity()` |
| Latency per turn | 5-30s (API call) | 5-30s (API call, same backend) |
| Privacy | Data sent to Anthropic API | Same (Claude API), but inputs/outputs screened by Gemma |

### 3.2 REPL Loop Design (Matching Desktop)

Because on-device Claude is the same model as the desktop, the on-device RLM uses the **same free-form Python code execution loop** as `rlm_tool.py`. No need for the structured `TOOL_CALL` format that was required for Gemma.

**The loop (identical to desktop `rlm_tool.py`):**

```
Iteration 0:
  Input:  System prompt + user query + context preview (first 1500 chars)
  Claude: Writes ```python``` code block to explore `context`
  Engine: Executes code, captures stdout

Iteration 1..N:
  Input:  Query reminder + iteration budget + previous code output + discovery summary
  Claude: Writes more ```python``` code or calls FINAL('''answer''')
  Engine: If code -> execute and feed back. If FINAL -> return answer.

Termination:
  - FINAL('''answer''') detected in output -> return answer
  - Max iterations reached -> return partial discoveries
```

### 3.3 Extended Python Namespace

The on-device RLM extends the desktop namespace with mK:a-specific tools. Claude can call these directly in its Python code blocks:

| Function | Signature | Source | Description |
|----------|-----------|--------|-------------|
| `context` | str variable | Engine | The large text being analyzed |
| `re` | module | stdlib | Regular expressions |
| `json` | module | stdlib | JSON parsing |
| `os` | module | stdlib | OS operations |
| `Path` | class | pathlib | Path operations |
| `recursive_llm` | `(query, text, model="haiku")` | Engine | Spawn sub-Claude call for semantic analysis |
| `rag_search` | `(query, top_k=5)` | RagRepository | Search on-device RAG knowledge base |
| `rag_context` | `(query, top_k=10)` | RagRepository | Get formatted RAG context |
| `session_context` | `()` | SessionMemoryRepository | Get Ebbinghaus-weighted session memory |
| `embed_similarity` | `(text1, text2)` | GemmaEmbeddingProvider | Compute cosine similarity via Gemma embeddings |
| `read_file` | `(path)` | Engine (sandboxed) | Read a file from app storage |
| `list_files` | `(directory)` | Engine (sandboxed) | List files in a directory |
| `glob_files` | `(pattern, directory)` | Engine (sandboxed) | Find files matching glob pattern |
| `directory` | str variable | Engine (codebase mode) | Root directory being analyzed |

**Example of Claude using these in the REPL:**

```python
# Claude's first iteration -- explore RAG for context
results = rag_search("network topology devices")
for r in results:
    print(f"[{r['category']}] ({r['score']:.0%}) {r['text'][:200]}")
```

```python
# Claude's second iteration -- check session memory for recent context
ctx = session_context()
print(ctx)
```

```python
# Claude's third iteration -- synthesize and deliver answer
FINAL('''Based on RAG and session memory, the network topology is:
- Gateway: UDM at gateway-ip
- Home Assistant: ha-server-ip
- PiHole: pihole-ip
- ...''')
```

### 3.4 Context Management

Claude has a 100K+ token context window. This eliminates the tight budget management required by the old Gemma design. The on-device RLM follows the desktop approach:

- **First iteration**: System prompt + query + context preview (first 1500 chars of the full context).
- **Subsequent iterations**: Query reminder + iteration count + previous code output + discovery summary.
- **Context variable**: The full text is stored in the Python namespace as `context`. Claude reads it via code (`context[:5000]`, `context.count('error')`, `re.findall(pattern, context)`, etc.).
- **No aggressive truncation needed**: Claude can handle large tool outputs and long discovery chains natively.

**Token budget per iteration (generous):**
```
+-----------------------------------------------+
| System Prompt (~800 tokens, fixed)            |
+-----------------------------------------------+
| Query + Iteration State (~200 tokens)         |
+-----------------------------------------------+
| Previous Code Output (~2000-5000 tokens)      |
+-----------------------------------------------+
| Discovery Summary (~1000-3000 tokens)         |
+-----------------------------------------------+
| Action Directive (~100 tokens)                |
+-----------------------------------------------+
| [Claude generation budget: ~4000+ tokens]     |
+-----------------------------------------------+
Total: ~8000-12000 tokens per turn (well within 100K+)
```

### 3.5 Configuration

```kotlin
data class RlmConfig(
    val maxIterations: Int = 10,          // Default 10 (same scale as desktop's 15)
    val model: String = "sonnet",         // Claude model to use
    val temperature: Float = 0.3f,        // Lower for focused reasoning
    val timeoutMs: Long = 300_000L,       // 5 minutes total (Claude iterations take longer)
    val perIterationTimeoutMs: Long = 60_000L, // 60s per Claude call
    val enableRecursiveLlm: Boolean = true,    // Allow recursive_llm() sub-calls
    val enableRagTools: Boolean = true,        // Include rag_search/rag_context in namespace
    val enableSessionMemory: Boolean = true,   // Include session_context() in namespace
    val privacyFilterInput: Boolean = true,    // Screen input query through Gemma privacy filter
    val privacyFilterOutput: Boolean = true,   // Screen output through Gemma privacy filter
    val escalateOnFailure: Boolean = false     // Escalate to desktop Claude via Switchboard on failure
)
```

### 3.6 How RecursiveLanguageEngine Calls Claude

The engine does NOT use `ClaudeProcessManager.sendMessage()` (which is designed for the interactive chat flow with streaming). Instead, it calls the Claude CLI directly as a subprocess, mirroring how `rlm_tool.py` works on the desktop:

```kotlin
// Pseudocode for single-iteration Claude call
val cmd = listOf(
    claudeCliPath,              // e.g., "$HOME/.local/bin/claude"
    "-p",                       // prompt via stdin
    "--system-prompt", systemPrompt,
    "--output-format", "json",
    "--model", config.model,
    "--max-turns", "1",
    "--tools", ""               // No built-in tools -- namespace provides them
)

val process = ProcessBuilder(cmd)
    .directory(tempDir)         // Avoid CLAUDE.md pollution
    .redirectErrorStream(false)
    .start()

process.outputStream.write(prompt.toByteArray())
process.outputStream.close()

val output = process.inputStream.bufferedReader().readText()
val result = Json.parseToJsonElement(output)
val responseText = result.jsonObject["result"]?.jsonPrimitive?.content ?: ""
```

This approach:
- Keeps each iteration **stateless** (no conversation history accumulation).
- Avoids interfering with the main chat pipeline.
- Mirrors the proven desktop `_call_claude()` implementation exactly.
- Uses the same CLI binary that `agent_orchestrator.py` already validates exists.

---

## 4. On-Device MCP Server Design

### 4.1 New Endpoints on DeviceApiServer

These endpoints are added to `DeviceApiServer.kt` (port 5563) alongside the existing endpoints. They follow the same NanoHTTPD pattern used throughout the codebase.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `POST /rlm/analyze` | POST | Run recursive Claude analysis (synchronous) |
| `POST /rlm/analyze/stream` | POST | Run recursive Claude analysis (SSE streaming) |
| `GET /rlm/status` | GET | RLM engine state, Claude readiness, iteration limits |
| `POST /rlm/cancel` | POST | Cancel a running RLM analysis |

### 4.2 Integration with Existing Endpoints

The RLM endpoints are a higher-level abstraction. They use Claude (via CLI subprocess) for reasoning and Gemma (via existing providers) for embeddings and privacy:

```
/gemma/status          -- Raw Gemma model state (already exists)
/gemma/generate        -- Single-shot Gemma generation (already exists)
/gemma/initialize      -- Force Gemma model load (already exists)
/gemma/release         -- Unload Gemma model (already exists)

/rlm/analyze           -- Recursive Claude analysis loop (NEW)
/rlm/analyze/stream    -- Streaming recursive analysis (NEW)
/rlm/status            -- RLM-specific state (NEW)
/rlm/cancel            -- Cancel running analysis (NEW)
```

### 4.3 Request Schema

**POST /rlm/analyze**

```json
{
    "query": "What do I know about network topology?",
    "context_text": "optional large text to analyze",
    "context_file": "/data/user/0/com.mobilekinetic.agent/files/home/some_file.txt",
    "directory": "/data/user/0/com.mobilekinetic.agent/files/home/project",
    "mode": "rag|text|file|codebase|hybrid",
    "model": "sonnet",
    "max_iterations": 10,
    "timeout_ms": 300000,
    "extensions": [".kt", ".py", ".json"],
    "categories": ["network", "device"],
    "include_session_memory": true,
    "privacy_filter": true,
    "escalate_on_failure": false
}
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `query` | string | Yes | -- | The question or analysis task |
| `context_text` | string | No | null | Direct text to analyze (text mode) |
| `context_file` | string | No | null | Path to file to analyze (file mode) |
| `directory` | string | No | null | Directory for codebase analysis (codebase mode) |
| `mode` | string | No | `"rag"` | `rag` = search RAG; `text` = analyze text; `file` = analyze file; `codebase` = analyze directory; `hybrid` = RAG + text/file |
| `model` | string | No | `"sonnet"` | Claude model: haiku, sonnet, opus |
| `max_iterations` | int | No | 10 | Max REPL iterations (1-15) |
| `timeout_ms` | long | No | 300000 | Total timeout in milliseconds |
| `extensions` | [string] | No | null | File extensions for codebase mode |
| `categories` | [string] | No | null | RAG categories to pre-fetch |
| `include_session_memory` | bool | No | true | Include Ebbinghaus session context in namespace |
| `privacy_filter` | bool | No | true | Screen input/output through Gemma privacy filter |
| `escalate_on_failure` | bool | No | false | Escalate to desktop Claude via Switchboard on failure |

### 4.4 Response Schema

**Synchronous response (POST /rlm/analyze):**

```json
{
    "success": true,
    "result": "Based on RAG and session memory, the network uses a local-subnet subnet with...",
    "iterations": 3,
    "tools_used": ["rag_search", "session_context", "recursive_llm"],
    "elapsed_ms": 45000,
    "model": "sonnet",
    "cost_usd": 0.045,
    "escalated": false,
    "discoveries": [
        "RAG returned 5 documents about network topology with high similarity",
        "Session memory contains recent gateway configuration at gateway-ip",
        "Found 7 device entries in the local-subnet subnet"
    ],
    "execution_log": [
        {"iteration": 1, "type": "code_execution", "output_preview": "RAG Search Results (5 hits)..."},
        {"iteration": 2, "type": "code_execution", "output_preview": "Session context: gateway=gateway-ip..."},
        {"iteration": 3, "type": "final", "output_preview": "Based on RAG and session memory..."}
    ]
}
```

**Error response:**

```json
{
    "success": false,
    "error": "Claude CLI not found at /data/user/0/com.mobilekinetic.agent/files/home/.local/bin/claude",
    "elapsed_ms": 50
}
```

### 4.5 SSE Streaming

**POST /rlm/analyze/stream** returns a `text/event-stream` response:

```
event: status
data: {"status":"starting","message":"Initializing RLM engine...","timestamp":"2026-02-23T14:30:00","started_at":"2026-02-23T14:30:00"}

event: status
data: {"status":"running","message":"Privacy filter passed, starting analysis...","elapsed_seconds":0.5}

event: progress
data: {"status":"running","iteration":1,"max_iterations":10,"message":"Claude writing Python code...","elapsed_seconds":2.0}

event: progress
data: {"status":"running","iteration":1,"message":"Executing code: rag_search('network topology')","elapsed_seconds":5.0}

event: progress
data: {"status":"running","iteration":2,"max_iterations":10,"message":"Claude analyzing RAG results...","elapsed_seconds":12.0,"estimated_remaining_seconds":48.0}

event: progress
data: {"status":"running","iteration":3,"message":"FINAL answer detected","elapsed_seconds":25.0}

event: complete
data: {"status":"complete","result":"Based on RAG...","iterations":3,"cost_usd":0.045,"elapsed_seconds":25.0,"total_seconds":25.0}
```

NanoHTTPD SSE implementation via chunked transfer encoding:

```kotlin
val response = newChunkedResponse(
    Response.Status.OK,
    "text/event-stream",
    inputStreamFromChannel(sseChannel)
)
response.addHeader("Cache-Control", "no-cache")
response.addHeader("Connection", "keep-alive")
```

### 4.6 Status Endpoint

**GET /rlm/status**

```json
{
    "engine_ready": true,
    "claude_cli_found": true,
    "claude_cli_path": "/data/user/0/com.mobilekinetic.agent/files/home/.local/bin/claude",
    "orchestrator_running": true,
    "rag_available": true,
    "rag_document_count": 42,
    "session_memory_available": true,
    "embedding_ready": true,
    "embedding_dim": 768,
    "privacy_filter_available": true,
    "config": {
        "max_iterations": 10,
        "default_model": "sonnet",
        "default_temperature": 0.3,
        "default_timeout_ms": 300000
    },
    "running_analysis": null
}
```

---

## 5. Kotlin Implementation Plan

### 5.1 File Layout

```
com/mobilekinetic/agent/
+-- claude/
|   +-- ClaudeProcessManager.kt       (existing -- manages orchestrator lifecycle)
+-- data/
|   +-- gemma/
|   |   +-- GemmaTextGenerator.kt      (existing -- NOT used for RLM reasoning)
|   |   +-- GemmaModelManager.kt       (existing)
|   |   +-- GemmaEmbeddingProvider.kt   (existing -- used for embed_similarity)
|   |   +-- GemmaPrivacyFilter.kt      (existing -- used for input/output screening)
|   +-- rag/
|   |   +-- RagRepository.kt           (existing -- used for rag_search/rag_context)
|   |   +-- RagHttpServer.kt           (existing)
|   +-- memory/
|   |   +-- SessionMemoryRepository.kt (existing -- used for session_context)
|   +-- rlm/                           (NEW PACKAGE)
|       +-- RecursiveLanguageEngine.kt  (core REPL loop -- calls Claude CLI)
|       +-- RlmNamespaceProvider.kt    (Python namespace builder with tool functions)
|       +-- RlmCodeExecutor.kt         (Python code execution via subprocess)
|       +-- RlmConfig.kt               (configuration data class)
|       +-- RlmResult.kt               (result data class)
+-- device/
|   +-- api/
|       +-- DeviceApiServer.kt         (modified -- add /rlm/* routes)
+-- di/
    +-- AppModule.kt                   (modified -- wire RLM dependencies)
```

### 5.2 RlmConfig.kt

```kotlin
package com.mobilekinetic.agent.data.rlm

data class RlmConfig(
    val maxIterations: Int = 10,
    val model: String = "sonnet",
    val temperature: Float = 0.3f,
    val timeoutMs: Long = 300_000L,
    val perIterationTimeoutMs: Long = 60_000L,
    val enableRecursiveLlm: Boolean = true,
    val enableRagTools: Boolean = true,
    val enableSessionMemory: Boolean = true,
    val privacyFilterInput: Boolean = true,
    val privacyFilterOutput: Boolean = true,
    val escalateOnFailure: Boolean = false
)
```

### 5.3 RlmResult.kt

```kotlin
package com.mobilekinetic.agent.data.rlm

data class RlmResult(
    val success: Boolean,
    val response: String,
    val iterations: Int,
    val toolsUsed: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
    val costUsd: Float = 0f,
    val discoveries: List<String> = emptyList(),
    val executionLog: List<Map<String, Any>> = emptyList(),
    val escalated: Boolean = false,
    val error: String? = null
)
```

### 5.4 RlmNamespaceProvider.kt

This class builds the Python helper functions that are injected into the REPL namespace. The functions are written as Python code strings that call back into the on-device APIs via HTTP (localhost:5563 for Device API, localhost:5562 for RAG).

```kotlin
package com.mobilekinetic.agent.data.rlm

import android.content.Context
import com.mobilekinetic.agent.data.rag.RagRepository
import com.mobilekinetic.agent.data.memory.SessionMemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Python helper functions injected into the RLM REPL namespace.
 *
 * These functions are defined as Python source code strings that get prepended
 * to the context file. When Claude writes `rag_search("network")` in its code block,
 * the function executes an HTTP call to the on-device RAG server.
 *
 * This approach mirrors how the desktop RLM injects `recursive_llm()`, `read_file()`,
 * etc. into the Python namespace.
 */
@Singleton
class RlmNamespaceProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val ragRepository: RagRepository,
    private val sessionMemoryRepository: SessionMemoryRepository
) {
    companion object {
        private const val RAG_ENDPOINT = "http://127.0.0.1:5562"
        private const val DEVICE_API_ENDPOINT = "http://127.0.0.1:5563"
    }

    /**
     * Build the Python helper code that defines all namespace functions.
     * This code is written to a temp file and imported before each REPL iteration.
     */
    fun buildNamespaceCode(config: RlmConfig): String = buildString {
        appendLine("# RLM Namespace Helpers (auto-generated)")
        appendLine("import urllib.request, urllib.error, json, os, re")
        appendLine("from pathlib import Path")
        appendLine()

        // rag_search function
        if (config.enableRagTools) {
            appendLine("""
def rag_search(query, top_k=5):
    '''Search on-device RAG knowledge base. Returns list of dicts with text, category, score.'''
    try:
        data = json.dumps({"query": query, "top_k": top_k}).encode()
        req = urllib.request.Request("$RAG_ENDPOINT/search", data=data,
              headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            results = json.loads(resp.read().decode())
            return results.get("results", [])
    except Exception as e:
        return [{"error": str(e)}]

def rag_context(query, top_k=10):
    '''Get formatted context from on-device RAG. Returns a string.'''
    try:
        data = json.dumps({"query": query, "top_k": top_k}).encode()
        req = urllib.request.Request("$RAG_ENDPOINT/context", data=data,
              headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode())
            return result.get("context", "")
    except Exception as e:
        return f"Error: {e}"
""".trimIndent())
        }

        // session_context function
        if (config.enableSessionMemory) {
            appendLine("""
def session_context():
    '''Get Ebbinghaus-weighted session memory context. Returns a string.'''
    try:
        req = urllib.request.Request("$DEVICE_API_ENDPOINT/session/context", method="GET")
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read().decode())
            return result.get("context", "")
    except Exception as e:
        return f"Error: {e}"
""".trimIndent())
        }

        // embed_similarity function (uses Gemma embeddings via Device API)
        appendLine("""
def embed_similarity(text1, text2):
    '''Compute cosine similarity between two texts using Gemma embeddings. Returns float 0-1.'''
    try:
        data = json.dumps({"text1": text1, "text2": text2}).encode()
        req = urllib.request.Request("$DEVICE_API_ENDPOINT/embedding/similarity", data=data,
              headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read().decode())
            return result.get("similarity", 0.0)
    except Exception as e:
        print(f"embed_similarity error: {e}")
        return 0.0
""".trimIndent())

        // read_file function (sandboxed to app storage)
        appendLine("""
def read_file(path):
    '''Read a file. For security, only app storage paths are allowed.'''
    try:
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            return f.read()
    except Exception as e:
        return f"Error reading {path}: {e}"

def list_files(directory):
    '''List files in a directory.'''
    try:
        return os.listdir(directory)
    except Exception as e:
        return [f"Error: {e}"]

def glob_files(pattern, directory="."):
    '''Find files matching a glob pattern.'''
    try:
        return [str(p) for p in Path(directory).glob(pattern)]
    except Exception as e:
        return [f"Error: {e}"]
""".trimIndent())
    }

    /**
     * Get the description of available functions for the system prompt.
     */
    fun getNamespaceDescription(config: RlmConfig): String = buildString {
        appendLine("Available in namespace: context (str), re, json, os, Path")
        if (config.enableRagTools) {
            appendLine("  rag_search(query, top_k=5) -- Search on-device RAG, returns list of dicts")
            appendLine("  rag_context(query, top_k=10) -- Get formatted RAG context string")
        }
        if (config.enableSessionMemory) {
            appendLine("  session_context() -- Get Ebbinghaus-weighted session memory")
        }
        if (config.enableRecursiveLlm) {
            appendLine("  recursive_llm(query, text, model='haiku') -- Spawn sub-Claude call for analysis")
        }
        appendLine("  embed_similarity(text1, text2) -- Cosine similarity via Gemma embeddings (0-1)")
        appendLine("  read_file(path) -- Read a file from app storage")
        appendLine("  list_files(directory) -- List files in a directory")
        appendLine("  glob_files(pattern, directory) -- Find files matching glob")
    }
}
```

### 5.5 RlmCodeExecutor.kt

Handles Python code execution in the REPL namespace:

```kotlin
package com.mobilekinetic.agent.data.rlm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes Python code in a subprocess with the RLM namespace helpers loaded.
 *
 * Each call creates a temp Python script that:
 * 1. Imports the namespace helpers (rag_search, etc.)
 * 2. Loads the context variable from a temp file
 * 3. Executes Claude's code block
 * 4. Prints stdout (captured by the engine)
 */
@Singleton
class RlmCodeExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "RlmCodeExecutor"
        private const val MAX_OUTPUT_CHARS = 7000
    }

    private val python3Path: String by lazy {
        File(appContext.filesDir, "usr/bin/python3").absolutePath
    }

    private val homeDir: String by lazy {
        File(appContext.filesDir, "home").absolutePath
    }

    /**
     * Execute a Python code block with the RLM namespace loaded.
     *
     * @param code The Python code to execute (from Claude's ```python``` block)
     * @param contextFilePath Path to temp file containing the context variable
     * @param namespaceFilePath Path to temp file containing namespace helpers
     * @param timeoutMs Timeout for execution
     * @return Captured stdout output, truncated to MAX_OUTPUT_CHARS
     */
    suspend fun execute(
        code: String,
        contextFilePath: String,
        namespaceFilePath: String,
        timeoutMs: Long = 30_000L
    ): String = withContext(Dispatchers.IO) {
        // Build the execution script
        val scriptContent = buildString {
            appendLine("import sys")
            appendLine("sys.path.insert(0, '${homeDir}')")
            appendLine()
            // Import namespace helpers
            appendLine("exec(open('$namespaceFilePath', encoding='utf-8').read())")
            appendLine()
            // Load context variable
            appendLine("with open('$contextFilePath', 'r', encoding='utf-8', errors='ignore') as _f:")
            appendLine("    context = _f.read()")
            appendLine()
            // Execute Claude's code
            appendLine(code)
        }

        val scriptFile = File.createTempFile("rlm_exec_", ".py", File(homeDir, "tmp"))
        try {
            scriptFile.writeText(scriptContent, Charsets.UTF_8)

            val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
            val prefixDir = File(appContext.filesDir, "usr").absolutePath
            val ldPath = "$nativeLibDir:$prefixDir/lib"

            val pb = ProcessBuilder(
                "$nativeLibDir/libbash.so", "-c",
                "export LD_LIBRARY_PATH='$ldPath' && exec $python3Path ${scriptFile.absolutePath}"
            )
            pb.directory(File(homeDir))
            pb.environment().apply {
                put("HOME", homeDir)
                put("PREFIX", prefixDir)
                put("PYTHONUNBUFFERED", "1")
                put("PATH", "$nativeLibDir:$prefixDir/bin")
                put("LD_LIBRARY_PATH", ldPath)
                put("LD_PRELOAD", "$nativeLibDir/libtermux-exec.so")
                put("TMPDIR", "$prefixDir/tmp")
            }
            pb.redirectErrorStream(true) // Merge stderr into stdout for simplicity

            val process = pb.start()
            val output = withTimeout(timeoutMs) {
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }

            // Truncate long output (same as desktop rlm_tool.py)
            if (output.length > MAX_OUTPUT_CHARS) {
                output.take(MAX_OUTPUT_CHARS) +
                    "\n... (truncated, ${output.length} total chars)"
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "Code execution failed", e)
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            scriptFile.delete()
        }
    }
}
```

### 5.6 RecursiveLanguageEngine.kt (Core REPL Loop)

```kotlin
package com.mobilekinetic.agent.data.rlm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core REPL loop engine for on-device recursive analysis using Claude.
 *
 * This mirrors the architecture of P:\Tools\rlm_tool.py:
 * 1. Load context into a Python namespace variable
 * 2. Prompt Claude to write Python code to explore it
 * 3. Execute the code, capture stdout
 * 4. Feed output back to Claude for the next iteration
 * 5. Repeat until FINAL() is detected or max iterations reached
 *
 * The key difference from the desktop: Claude CLI is invoked via Termux's
 * bash/python environment rather than Windows cmd.
 */
@Singleton
class RecursiveLanguageEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val namespaceProvider: RlmNamespaceProvider,
    private val codeExecutor: RlmCodeExecutor
) {
    companion object {
        private const val TAG = "RecursiveLanguageEngine"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val homeDir: String by lazy {
        File(appContext.filesDir, "home").absolutePath
    }

    private val claudeCliPath: String? by lazy {
        val candidates = listOf(
            File(homeDir, ".local/bin/claude"),
            File(appContext.filesDir, "usr/bin/claude"),
            File(homeDir, ".npm-global/bin/claude")
        )
        candidates.find { it.exists() }?.absolutePath
    }

    // Observable state for /rlm/status
    @Volatile
    var currentAnalysis: AnalysisState? = null
        private set

    data class AnalysisState(
        val query: String,
        val iteration: Int,
        val maxIterations: Int,
        val startTimeMs: Long,
        val lastAction: String? = null
    )

    data class ProgressEvent(
        val type: String,   // "status", "progress", "complete", "error"
        val iteration: Int = 0,
        val maxIterations: Int = 0,
        val message: String = "",
        val elapsedMs: Long = 0L
    )

    /**
     * Run a recursive analysis using Claude as the reasoning engine.
     */
    suspend fun analyze(
        query: String,
        contextText: String? = null,
        contextFile: String? = null,
        directory: String? = null,
        config: RlmConfig = RlmConfig(),
        mode: String = "rag",
        categories: List<String>? = null,
        extensions: List<String>? = null,
        includeSessionMemory: Boolean = true,
        progressChannel: Channel<ProgressEvent>? = null
    ): RlmResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()
        val discoveries = mutableListOf<String>()
        val executionLog = mutableListOf<Map<String, Any>>()
        var totalCost = 0f

        fun elapsed() = System.currentTimeMillis() - startTime

        fun emitProgress(event: ProgressEvent) {
            progressChannel?.trySend(event.copy(elapsedMs = elapsed()))
        }

        // Verify Claude CLI exists
        val cliPath = claudeCliPath
        if (cliPath == null) {
            val error = "Claude CLI not found. Checked: $homeDir/.local/bin/claude"
            emitProgress(ProgressEvent("error", message = error))
            progressChannel?.close()
            return@coroutineScope RlmResult(
                success = false, response = "", iterations = 0,
                elapsedMs = elapsed(), error = error
            )
        }

        try {
            withTimeout(config.timeoutMs) {
                emitProgress(ProgressEvent("status", message = "Initializing RLM engine..."))

                // Prepare context
                val context = when {
                    contextText != null -> contextText
                    contextFile != null -> File(contextFile).readText(Charsets.UTF_8)
                    directory != null -> buildCodebaseIndex(directory, extensions)
                    mode == "rag" || mode == "hybrid" -> {
                        // Pre-fetch RAG context as the starting context
                        fetchRagContext(query, categories)
                    }
                    else -> ""
                }

                // Write context and namespace helpers to temp files
                val tmpDir = File(homeDir, "tmp").also { it.mkdirs() }
                val contextTmpFile = File.createTempFile("rlm_ctx_", ".txt", tmpDir)
                val namespaceTmpFile = File.createTempFile("rlm_ns_", ".py", tmpDir)

                try {
                    contextTmpFile.writeText(context, Charsets.UTF_8)
                    namespaceTmpFile.writeText(
                        namespaceProvider.buildNamespaceCode(config),
                        Charsets.UTF_8
                    )

                    // Build system prompt (mirrors desktop rlm_tool.py)
                    val available = namespaceProvider.getNamespaceDescription(config)
                    val systemPrompt = buildSystemPrompt(context.length, available, config)

                    // Build first prompt
                    val contextPreview = if (context.length > 1500) {
                        context.take(1500)
                    } else context

                    var currentPrompt = buildString {
                        appendLine("TASK: $query")
                        appendLine()
                        if (context.isNotBlank()) {
                            appendLine("CONTEXT PREVIEW (first 1500 chars of ${context.length} total):")
                            appendLine("---")
                            appendLine(contextPreview)
                            appendLine("---")
                        }
                        appendLine()
                        appendLine("Write Python code to explore `context` and find the answer. Start now.")
                    }

                    currentAnalysis = AnalysisState(
                        query = query,
                        iteration = 0,
                        maxIterations = config.maxIterations,
                        startTimeMs = startTime
                    )

                    // Main REPL loop
                    for (iteration in 0 until config.maxIterations) {
                        if (!isActive) break

                        currentAnalysis = currentAnalysis?.copy(
                            iteration = iteration + 1,
                            lastAction = "calling_claude"
                        )

                        emitProgress(ProgressEvent(
                            type = "progress",
                            iteration = iteration + 1,
                            maxIterations = config.maxIterations,
                            message = "Iteration ${iteration + 1}/${config.maxIterations}: Claude analyzing..."
                        ))

                        Log.d(TAG, "=== Iteration ${iteration + 1}/${config.maxIterations} ===")

                        // Call Claude CLI (single-turn, no tools, prompt via stdin)
                        val claudeResponse = callClaude(
                            cliPath = cliPath,
                            prompt = currentPrompt,
                            systemPrompt = systemPrompt,
                            model = config.model,
                            timeoutMs = config.perIterationTimeoutMs
                        )

                        val text = claudeResponse.text
                        totalCost += claudeResponse.costUsd

                        Log.d(TAG, "Claude response (${text.length} chars): ${text.take(500)}")

                        executionLog.add(mapOf(
                            "iteration" to (iteration + 1),
                            "type" to "response",
                            "content_preview" to text.take(500),
                            "cost" to claudeResponse.costUsd
                        ))

                        // Check for FINAL answer (same regex as desktop rlm_tool.py)
                        val finalAnswer = parseFinalAnswer(text)
                        if (finalAnswer != null) {
                            Log.i(TAG, "FINAL answer at iteration ${iteration + 1}")

                            emitProgress(ProgressEvent(
                                type = "complete",
                                iteration = iteration + 1,
                                maxIterations = config.maxIterations,
                                message = "Analysis complete"
                            ))
                            progressChannel?.close()
                            currentAnalysis = null

                            return@withTimeout RlmResult(
                                success = true,
                                response = finalAnswer,
                                iterations = iteration + 1,
                                toolsUsed = toolsUsed.distinct(),
                                elapsedMs = elapsed(),
                                costUsd = totalCost,
                                discoveries = discoveries,
                                executionLog = executionLog
                            )
                        }

                        // Check for Python code block (same regex as desktop)
                        val codeBlock = parseCodeBlock(text)
                        if (codeBlock != null) {
                            // Track which namespace tools are called
                            val toolsInCode = detectToolUsage(codeBlock)
                            toolsUsed.addAll(toolsInCode)

                            currentAnalysis = currentAnalysis?.copy(
                                lastAction = "executing_code"
                            )

                            emitProgress(ProgressEvent(
                                type = "progress",
                                iteration = iteration + 1,
                                maxIterations = config.maxIterations,
                                message = "Executing code (tools: ${toolsInCode.joinToString()})"
                            ))

                            // Execute the code
                            val output = codeExecutor.execute(
                                code = codeBlock,
                                contextFilePath = contextTmpFile.absolutePath,
                                namespaceFilePath = namespaceTmpFile.absolutePath,
                                timeoutMs = 30_000L
                            )

                            Log.d(TAG, "Code output (${output.length} chars): ${output.take(500)}")

                            executionLog.add(mapOf(
                                "iteration" to (iteration + 1),
                                "type" to "code_execution",
                                "code_preview" to codeBlock.take(300),
                                "output_preview" to output.take(500)
                            ))

                            // Track discoveries
                            if (output.isNotBlank() && !output.startsWith("Error:")) {
                                discoveries.add(output.take(500))
                            }

                            // Build next prompt (matches desktop rlm_tool.py logic)
                            val remaining = config.maxIterations - (iteration + 1)
                            val discoverySummary = if (discoveries.isNotEmpty()) {
                                "\n\nDISCOVERIES SO FAR:\n" +
                                    discoveries.takeLast(3).joinToString("\n---\n")
                            } else ""

                            val actionDirective = when {
                                remaining <= 2 -> "STOP EXPLORING. You are almost out of iterations. " +
                                    "Synthesize ALL your discoveries into a comprehensive answer and call " +
                                    "FINAL('''your complete answer here''') NOW."
                                remaining <= 5 -> "Iterations are running low. Start wrapping up your analysis. " +
                                    "Write final code if needed, then call FINAL('''your answer''')."
                                else -> "Continue analyzing. Write more ```python``` code or " +
                                    "FINAL('''your answer''') when you have enough findings."
                            }

                            currentPrompt = buildString {
                                appendLine("TASK: $query")
                                appendLine()
                                appendLine("Iteration ${iteration + 1}/${config.maxIterations} ($remaining remaining).")
                                appendLine()
                                appendLine("Previous code output:")
                                appendLine(output)
                                append(discoverySummary)
                                appendLine()
                                appendLine()
                                appendLine(actionDirective)
                            }
                        } else {
                            // No FINAL and no code block -- re-anchor
                            val remaining = config.maxIterations - (iteration + 1)
                            val fallbackDirective = if (remaining <= 2) {
                                "You are almost out of iterations. Synthesize your findings and call " +
                                    "FINAL('''your complete answer here''') NOW."
                            } else {
                                "Please write ```python``` code to explore the `context` variable, " +
                                    "or FINAL('''your answer''') if you have enough findings."
                            }
                            currentPrompt = buildString {
                                appendLine("TASK: $query")
                                appendLine()
                                appendLine("Iteration ${iteration + 1}/${config.maxIterations} ($remaining remaining).")
                                appendLine()
                                appendLine(fallbackDirective)
                            }
                        }
                    }

                    // Max iterations reached
                    val partial = if (discoveries.isNotEmpty()) {
                        "Max iterations reached. Partial findings:\n" +
                            discoveries.takeLast(5).joinToString("\n---\n")
                    } else {
                        "Max iterations reached without finding an answer."
                    }

                    emitProgress(ProgressEvent(
                        type = "complete",
                        iteration = config.maxIterations,
                        maxIterations = config.maxIterations,
                        message = "Max iterations reached"
                    ))
                    progressChannel?.close()
                    currentAnalysis = null

                    RlmResult(
                        success = true,
                        response = partial,
                        iterations = config.maxIterations,
                        toolsUsed = toolsUsed.distinct(),
                        elapsedMs = elapsed(),
                        costUsd = totalCost,
                        discoveries = discoveries,
                        executionLog = executionLog
                    )
                } finally {
                    contextTmpFile.delete()
                    namespaceTmpFile.delete()
                }
            }
        } catch (e: CancellationException) {
            currentAnalysis = null
            progressChannel?.close()
            RlmResult(
                success = false, response = "", iterations = 0,
                elapsedMs = elapsed(), error = "Analysis cancelled",
                discoveries = discoveries, executionLog = executionLog
            )
        } catch (e: Exception) {
            Log.e(TAG, "RLM analysis failed", e)
            currentAnalysis = null
            emitProgress(ProgressEvent("error", message = "Error: ${e.message}"))
            progressChannel?.close()
            RlmResult(
                success = false, response = "", iterations = 0,
                elapsedMs = elapsed(), error = e.message,
                discoveries = discoveries, executionLog = executionLog
            )
        }
    }

    // --- Claude CLI Invocation ---

    private data class ClaudeResponse(
        val text: String,
        val costUsd: Float,
        val isError: Boolean
    )

    /**
     * Call Claude CLI for a single REPL iteration.
     * Mirrors rlm_tool.py's _call_claude() method.
     */
    private suspend fun callClaude(
        cliPath: String,
        prompt: String,
        systemPrompt: String,
        model: String,
        timeoutMs: Long
    ): ClaudeResponse = withContext(Dispatchers.IO) {
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        val prefixDir = File(appContext.filesDir, "usr").absolutePath
        val ldPath = "$nativeLibDir:$prefixDir/lib"

        // Create temp directory (avoids CLAUDE.md context pollution, same as desktop)
        val tmpDir = File(homeDir, "tmp/rlm_work").also { it.mkdirs() }

        // Collapse newlines in system prompt for CLI arg compatibility
        val systemPromptClean = systemPrompt.replace('\n', ' ')

        // Build command: same flags as desktop rlm_tool.py
        val shellCmd = buildString {
            append("export LD_LIBRARY_PATH='$ldPath' && ")
            append("cd '${tmpDir.absolutePath}' && ")
            append("exec $cliPath ")
            append("-p ")
            append("--system-prompt '${systemPromptClean.replace("'", "'\\''")}' ")
            append("--output-format json ")
            append("--model $model ")
            append("--max-turns 1 ")
            append("--tools ''")
        }

        val pb = ProcessBuilder(
            "$nativeLibDir/libbash.so", "-c", shellCmd
        )
        pb.directory(tmpDir)
        pb.environment().apply {
            put("HOME", homeDir)
            put("PREFIX", prefixDir)
            put("PYTHONUNBUFFERED", "1")
            put("PATH", "$nativeLibDir:$prefixDir/bin:$prefixDir/bin/applets")
            put("LD_LIBRARY_PATH", ldPath)
            put("LD_PRELOAD", "$nativeLibDir/libtermux-exec.so")
            put("TMPDIR", "$prefixDir/tmp")
            put("LANG", "en_US.UTF-8")
            put("TERM", "xterm-256color")
            put("NO_COLOR", "1")
            put("FORCE_COLOR", "0")
            put("CI", "1")
        }

        val process = pb.start()

        // Write prompt to stdin (piped, avoids shell interpretation issues)
        try {
            process.outputStream.write(prompt.toByteArray(Charsets.UTF_8))
            process.outputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write prompt to Claude CLI stdin", e)
        }

        // Read stdout with timeout
        val stdout = try {
            withTimeout(timeoutMs) {
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            return@withContext ClaudeResponse(
                text = "Error: Claude CLI timed out after ${timeoutMs}ms",
                costUsd = 0f,
                isError = true
            )
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            return@withContext ClaudeResponse(
                text = "CLI Error (exit $exitCode): $stderr",
                costUsd = 0f,
                isError = true
            )
        }

        // Parse JSON output
        try {
            val parsed = json.parseToJsonElement(stdout).jsonObject
            val result = parsed["result"]?.jsonPrimitive?.content ?: stdout
            val cost = parsed["total_cost_usd"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
            ClaudeResponse(text = result, costUsd = cost, isError = false)
        } catch (e: Exception) {
            ClaudeResponse(text = stdout, costUsd = 0f, isError = false)
        }
    }

    // --- Parsing Helpers (matching desktop rlm_tool.py regexes) ---

    private fun parseFinalAnswer(text: String): String? {
        val patterns = listOf(
            Regex("""FINAL\('''(.+?)'''\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""FINAL\(""{3}(.+?)""{3}\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""FINAL\('([^']+)'\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""FINAL\("([^"]+)"\)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""FINAL\(([^)]+)\)""", RegexOption.DOT_MATCHES_ALL)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return null
    }

    private fun parseCodeBlock(text: String): String? {
        val regex = Regex("""```(?:python|py)?\s*\n(.+?)```""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun detectToolUsage(code: String): List<String> {
        val tools = mutableListOf<String>()
        if ("rag_search" in code) tools.add("rag_search")
        if ("rag_context" in code) tools.add("rag_context")
        if ("session_context" in code) tools.add("session_context")
        if ("embed_similarity" in code) tools.add("embed_similarity")
        if ("recursive_llm" in code) tools.add("recursive_llm")
        if ("read_file" in code) tools.add("read_file")
        if ("list_files" in code) tools.add("list_files")
        if ("glob_files" in code) tools.add("glob_files")
        return tools
    }

    // --- Prompt Building ---

    private fun buildSystemPrompt(contextLen: Int, available: String, config: RlmConfig): String {
        return """PYTHON REPL MODE. Variable `context` has $contextLen chars.
You have a LIMITED iteration budget. You MUST call FINAL() before iterations run out.

YOUR TASK: Answer the user's query by exploring the `context` variable with Python code.
Focus on the user's actual question. Extract, summarize, or analyze whatever they asked for.

STRATEGY: Spend early iterations gathering data. When you have enough evidence, STOP exploring
and call FINAL() with a comprehensive answer. Do NOT try to be exhaustive -- be thorough enough
to answer well, then deliver.

RULES:
1. Write ONE ```python``` block per turn, then STOP
2. WAIT for output before continuing
3. When you have enough findings, write FINAL('''your answer here''') using TRIPLE QUOTES
4. IMPORTANT: Always use triple quotes in FINAL() to avoid parsing issues with parentheses
5. Example: FINAL('''The system uses a 3-tier architecture with components like foo() and bar()''')
6. Do NOT search for secrets, flags, passwords, or CTF patterns unless the user specifically asked for that
7. Do NOT reassign the `context` variable -- it is read-only
8. You can call on-device tools: rag_search(), rag_context(), session_context(), embed_similarity(), etc.

$available"""
    }

    // --- Helper: Build codebase index ---

    private fun buildCodebaseIndex(directory: String, extensions: List<String>?): String {
        val exts = extensions ?: listOf(
            ".py", ".js", ".ts", ".kt", ".kts", ".java", ".json", ".yaml", ".yml",
            ".xml", ".gradle", ".sh", ".md", ".txt"
        )

        val fileList = mutableListOf<String>()
        var totalSize = 0L

        val dir = File(directory)
        val skipDirs = setOf(".git", "node_modules", "__pycache__", "venv", ".venv",
            "dist", "build", ".idea", ".vscode")

        dir.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && exts.any { ext -> it.name.endsWith(ext) } }
            .forEach { file ->
                val size = file.length()
                totalSize += size
                val relPath = file.relativeTo(dir).path
                fileList.add("$relPath ($size bytes)")
            }

        return buildString {
            appendLine("CODEBASE INDEX")
            appendLine("Directory: $directory")
            appendLine("Total files: ${fileList.size}")
            appendLine("Total size: $totalSize bytes")
            appendLine()
            appendLine("AVAILABLE FUNCTIONS (call these in your Python code):")
            appendLine("- read_file(path) -- Read full contents of a file")
            appendLine("- list_files(dir) -- List filenames in a directory")
            appendLine("- glob_files(pattern, dir) -- Find files matching a glob pattern")
            appendLine("- recursive_llm(query, text) -- Send text to sub-Claude for analysis")
            appendLine()
            appendLine("The variable `directory` = '$directory' is available in the namespace.")
            appendLine("IMPORTANT: The FILE LIST shows names and sizes ONLY. Call read_file() for code.")
            appendLine()
            appendLine("FILE LIST:")
            fileList.forEach { appendLine(it) }
        }
    }

    // --- Helper: Fetch RAG context ---

    private suspend fun fetchRagContext(query: String, categories: List<String>?): String =
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://127.0.0.1:5562/context")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 10000

                val body = org.json.JSONObject().apply {
                    put("query", query)
                    put("top_k", 10)
                }
                conn.outputStream.write(body.toString().toByteArray())

                val response = conn.inputStream.bufferedReader().readText()
                val parsed = org.json.JSONObject(response)
                parsed.optString("context", "")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch RAG context: ${e.message}")
                ""
            }
        }
}
```

### 5.7 DeviceApiServer.kt Modifications

Add `RecursiveLanguageEngine` as a constructor dependency and add the `/rlm/*` routes.

**Constructor change:**

```kotlin
class DeviceApiServer(
    private val context: Context,
    private val privacyGate: PrivacyGate,
    private val gemmaTextGenerator: GemmaTextGenerator,
    private val gemmaModelManager: GemmaModelManager,
    private val rlmEngine: RecursiveLanguageEngine,  // NEW
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {
```

**New routes:**

```kotlin
// RLM (Recursive Language Model) endpoints
uri == "/rlm/status" -> if (method == Method.GET) {
    try {
        val analysis = rlmEngine.currentAnalysis
        val json = JSONObject().apply {
            put("engine_ready", rlmEngine.claudeCliPath != null)
            put("claude_cli_found", rlmEngine.claudeCliPath != null)
            put("claude_cli_path", rlmEngine.claudeCliPath ?: "not found")
            put("orchestrator_running", claudeProcessManager.isRunning())
            put("rag_available", true)
            put("session_memory_available", true)
            put("embedding_ready", gemmaModelManager.embeddingStatus.value.state == ModelState.READY)
            put("embedding_dim", 768)
            put("privacy_filter_available", gemmaTextGenerator.isReady)
            put("config", JSONObject().apply {
                put("default_max_iterations", 10)
                put("default_model", "sonnet")
                put("default_temperature", 0.3)
                put("default_timeout_ms", 300000)
            })
            if (analysis != null) {
                put("running_analysis", JSONObject().apply {
                    put("query", analysis.query.take(100))
                    put("iteration", analysis.iteration)
                    put("max_iterations", analysis.maxIterations)
                    put("elapsed_ms", System.currentTimeMillis() - analysis.startTimeMs)
                    put("last_action", analysis.lastAction)
                })
            } else {
                put("running_analysis", JSONObject.NULL)
            }
        }
        newFixedLengthResponse(Response.Status.OK, MIME_JSON, json.toString())
    } catch (e: Exception) {
        Log.e(TAG, "RLM status error", e)
        errorResponse(e)
    }
} else methodNotAllowed()

uri == "/rlm/analyze" -> if (method == Method.POST) {
    // [Same pattern as existing endpoints: parse body, extract params, call rlmEngine.analyze()]
    // See Section 4.3 for full request schema
} else methodNotAllowed()

uri == "/rlm/analyze/stream" -> if (method == Method.POST) {
    // [SSE via chunked response with progressChannel]
    // See Section 4.5 for SSE event format
} else methodNotAllowed()
```

### 5.8 AppModule.kt DI Wiring

```kotlin
@Provides
@Singleton
fun provideRlmNamespaceProvider(
    @ApplicationContext context: Context,
    ragRepository: RagRepository,
    sessionMemoryRepository: SessionMemoryRepository
): RlmNamespaceProvider {
    return RlmNamespaceProvider(context, ragRepository, sessionMemoryRepository)
}

@Provides
@Singleton
fun provideRlmCodeExecutor(
    @ApplicationContext context: Context
): RlmCodeExecutor {
    return RlmCodeExecutor(context)
}

@Provides
@Singleton
fun provideRecursiveLanguageEngine(
    @ApplicationContext context: Context,
    namespaceProvider: RlmNamespaceProvider,
    codeExecutor: RlmCodeExecutor
): RecursiveLanguageEngine {
    return RecursiveLanguageEngine(context, namespaceProvider, codeExecutor)
}
```

---

## 6. Integration Points

### 6.1 RAG System (RagRepository)

The RLM's primary data source. Two integration paths:

**Pre-fetch (before loop starts):** In `rag` and `hybrid` modes, the engine pre-fetches RAG context via `http://127.0.0.1:5562/context` and uses it as the initial `context` variable. This gives Claude immediate context without burning an iteration.

**In-loop via namespace functions:** `rag_search()` and `rag_context()` are Python functions injected into the REPL namespace that call the RAG HTTP server. Claude can refine its search terms based on previous results -- this is the core value of recursive reasoning.

**Example Claude code in REPL:**
```python
# First search broad
results = rag_search("network devices")
for r in results:
    print(f"[{r['category']}] {r['text'][:100]}")

# Then refine based on what was found
results2 = rag_search("local-subnet subnet assignments")
for r in results2:
    print(r['text'])
```

**Key files:**
- `A:\Android-Studio-SDK\mK:a\app\src\main\kotlin\com\mobilekinetic\agent\data\rag\RagRepository.kt`
- `A:\Android-Studio-SDK\mK:a\app\src\main\kotlin\com\mobilekinetic\agent\data\rag\GemmaEmbeddingProvider.kt`

### 6.2 Session Memory (Ebbinghaus Decay)

`SessionMemoryRepository` provides weighted memory context. The RLM integrates this via:

1. **Namespace function:** `session_context()` is available in the REPL, allowing Claude to pull in session memory at any iteration.
2. **Pre-fetch option:** When `include_session_memory` is true, session context can be included in the initial RAG pre-fetch.

**Key file:**
- `A:\Android-Studio-SDK\mK:a\app\src\main\kotlin\com\mobilekinetic\agent\data\memory\SessionMemoryRepository.kt`

### 6.3 Privacy Filter (Gemma)

`GemmaPrivacyFilter` is invoked at two points around the Claude analysis:

1. **Before analysis:** Screen the inbound query against the blacklist. If blocked, return an error without invoking Claude.
2. **After analysis:** Screen the final answer. If the result contains blacklisted content, redact or block.

This is the ONLY role Gemma plays in the RLM pipeline. Gemma does NOT do reasoning.

```kotlin
// In DeviceApiServer, before calling rlmEngine.analyze():
if (config.privacyFilterInput) {
    val privacyCheck = privacyGate.check(query)
    if (privacyCheck.blocked) {
        return errorResponse("Query blocked by privacy filter: ${privacyCheck.reason}")
    }
}

// After getting result:
if (config.privacyFilterOutput) {
    val outputCheck = privacyGate.check(result.response)
    if (outputCheck.blocked) {
        return errorResponse("Response blocked by privacy filter")
    }
}
```

**Key file:**
- `A:\Android-Studio-SDK\mK:a\app\src\main\kotlin\com\mobilekinetic\agent\data\gemma\GemmaPrivacyFilter.kt`

### 6.4 Embedding Similarity (Gemma)

`GemmaEmbeddingProvider` powers the `embed_similarity()` namespace function. Claude can use this to compute semantic similarity between texts without needing a full RAG search:

```python
# In Claude's REPL code
sim = embed_similarity("network gateway router", "UDM at gateway-ip")
print(f"Similarity: {sim:.4f}")
```

The embedding computation runs on the Tensor G4's NPU via EmbeddingGemma 300M. This is independent of Claude's API calls.

### 6.5 Vault (Credential-Gated Queries)

When `GemmaCredentialBridge` is implemented (Phase 4), the REPL namespace could gain a `vault_query()` function. Claude could request credentials for authorized domains, with biometric auth required. The credential would be injected into a tool call, never exposed in the REPL output.

**This is future work.** The namespace approach makes it extensible -- new functions can be added to `RlmNamespaceProvider` without changing `RecursiveLanguageEngine`.

### 6.6 Desktop Claude (Switchboard Escalation)

When `escalate_on_failure` is true and the on-device RLM cannot answer the query (max iterations reached with no useful discoveries, or Claude CLI consistently fails), the engine can escalate to the desktop Claude via the Switchboard:

```kotlin
if (config.escalateOnFailure && result.response.contains("Max iterations reached without")) {
    val escalation = switchboardClient.sendMessage(
        from = "mka_rlm",
        to = "vscode_claude",
        type = "rlm_escalation",
        payload = mapOf(
            "query" to query,
            "on_device_discoveries" to discoveries,
            "reason" to "On-device RLM could not resolve this query"
        )
    )
}
```

**Switchboard endpoint:** `http://pc-ip:5559`

### 6.7 ClaudeProcessManager (Coexistence)

The RLM engine calls the Claude CLI directly as a subprocess, independent of `ClaudeProcessManager`. This means:

- **No interference with chat:** The main chat pipeline (`ClaudeProcessManager` -> `agent_orchestrator.py` -> `ClaudeSDKClient`) continues working normally.
- **Separate Claude instances:** Each RLM iteration spawns a fresh Claude CLI call (single-turn, stateless). These are separate from the persistent orchestrator session.
- **Resource consideration:** Both the chat Claude and RLM Claude make API calls to Anthropic. They do not compete for local GPU/NPU resources (those are only used by Gemma for embeddings/privacy).
- **CLI path sharing:** The RLM engine discovers the Claude CLI binary at the same paths the orchestrator checks (`~/.local/bin/claude`, etc.).

---

## 7. Considerations and Mitigations

### 7.1 API Cost

Unlike the old Gemma design (free local compute), the Claude-based RLM incurs API costs for every iteration. Each iteration is a separate Claude API call.

**Estimated costs per analysis:**
| Scenario | Iterations | Model | Estimated Cost |
|----------|-----------|-------|----------------|
| Simple RAG query | 2-3 | haiku | $0.01-0.03 |
| Medium analysis | 5-7 | sonnet | $0.05-0.15 |
| Deep codebase analysis | 10-15 | sonnet | $0.15-0.50 |
| Complex with sub-calls | 10 + subs | opus | $0.50-2.00 |

**Mitigations:**
- **Default model is sonnet** (good quality/cost balance).
- **Configurable iteration count** -- caller can limit to 3 for quick queries.
- **Cost tracking:** `RlmResult.costUsd` reports the total cost, allowing the caller to monitor spend.
- **Pre-fetched RAG context:** For simple queries, Claude may answer in iteration 1 from the pre-fetched context.

### 7.2 Latency

Each Claude API call takes 5-30 seconds depending on the model and prompt size. A 10-iteration analysis could take 50-300 seconds.

**Expected latency:**
| Operation | Expected Time |
|-----------|--------------|
| Claude CLI startup | 1-3s (first call), <1s (subsequent) |
| Single Claude iteration (sonnet) | 5-15s |
| Python code execution | 0.1-5s (depends on tool calls) |
| RAG search (via HTTP) | 0.2-0.5s |
| Full 3-iteration RLM run | 20-60s |
| Full 10-iteration RLM run | 60-180s |

**Mitigations:**
- **SSE streaming** keeps the caller informed of progress.
- **Per-iteration timeout** prevents individual calls from hanging.
- **Early termination:** Claude is instructed to call `FINAL()` as soon as it has enough information.

### 7.3 Process Management

Running Claude CLI as a subprocess on Android's Termux environment requires care:

- **LD_LIBRARY_PATH** must be set before exec (same issue `ClaudeProcessManager` solves via shell wrapper).
- **LD_PRELOAD** for `libtermux-exec.so` (required for execve from app data dirs).
- **Temp directory isolation:** Each RLM call runs Claude from a temp directory to avoid CLAUDE.md contamination.
- **Process cleanup:** If the RLM engine is cancelled, it must kill the Claude CLI subprocess to avoid orphans.

### 7.4 Concurrent Access

The RLM engine should support only one analysis at a time. `RecursiveLanguageEngine.currentAnalysis` tracks the running analysis, and `/rlm/status` exposes it. If a second request arrives while one is running, it should return a 409 Conflict.

### 7.5 Privacy Architecture

The privacy model is layered:

1. **Input screening (Gemma):** Query goes through `GemmaPrivacyFilter` before Claude sees it.
2. **Claude reasoning (API):** The actual analysis happens via Claude's API (data leaves device to Anthropic).
3. **Output screening (Gemma):** Result goes through `GemmaPrivacyFilter` before being returned.

**Important:** Unlike the old Gemma-only design, the Claude-based RLM sends data to Anthropic's API. For truly on-device-only analysis of sensitive content, the privacy filter should block the query and suggest using the Gemma-only `/gemma/generate` endpoint instead. The privacy filter's blacklist rules should be configured to route sensitive queries appropriately.

---

## 8. API Reference

### 8.1 GET /rlm/status

Returns the current state of the RLM engine.

```bash
curl -s http://localhost:5563/rlm/status | python -m json.tool
```

**Response (200 OK):**
```json
{
    "engine_ready": true,
    "claude_cli_found": true,
    "claude_cli_path": "/data/user/0/com.mobilekinetic.agent/files/home/.local/bin/claude",
    "orchestrator_running": true,
    "rag_available": true,
    "session_memory_available": true,
    "embedding_ready": true,
    "embedding_dim": 768,
    "privacy_filter_available": true,
    "config": {
        "default_max_iterations": 10,
        "default_model": "sonnet",
        "default_temperature": 0.3,
        "default_timeout_ms": 300000
    },
    "running_analysis": null
}
```

### 8.2 POST /rlm/analyze

Run a synchronous recursive analysis. Blocks until complete or timeout.

```bash
# RAG mode (default) -- search on-device knowledge base
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{"query": "What devices are on my network and what are their IPs?"}'
```

```bash
# Text mode -- analyze provided text recursively
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Summarize the key findings",
    "context_text": "Long text content to analyze...",
    "mode": "text",
    "max_iterations": 5
  }'
```

```bash
# File mode -- analyze a file on device
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Find all error handlers",
    "context_file": "/data/user/0/com.mobilekinetic.agent/files/home/app.py",
    "mode": "file",
    "max_iterations": 8
  }'
```

```bash
# Codebase mode -- analyze a directory
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "query": "List all classes and their responsibilities",
    "directory": "/data/user/0/com.mobilekinetic.agent/files/home/project",
    "mode": "codebase",
    "extensions": [".py", ".kt"],
    "max_iterations": 10
  }'
```

```bash
# Hybrid mode -- combine RAG knowledge with provided text
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does this log relate to known network issues?",
    "context_text": "ERROR: connection refused at gateway-ip...",
    "mode": "hybrid",
    "categories": ["network", "errors"],
    "max_iterations": 5,
    "model": "sonnet"
  }'
```

**Success Response (200 OK):**
```json
{
    "success": true,
    "result": "Based on RAG and session memory, your network has the following devices...",
    "iterations": 3,
    "tools_used": ["rag_search", "rag_context", "session_context"],
    "elapsed_ms": 45000,
    "model": "sonnet",
    "cost_usd": 0.045,
    "escalated": false,
    "discoveries": [
        "RAG returned 5 documents about network topology with high similarity",
        "Session memory contains recent gateway configuration",
        "Found 7 device entries in the local-subnet subnet"
    ],
    "execution_log": [
        {"iteration": 1, "type": "code_execution", "output_preview": "RAG Search Results..."},
        {"iteration": 2, "type": "code_execution", "output_preview": "Session context..."},
        {"iteration": 3, "type": "final", "output_preview": "Based on RAG..."}
    ]
}
```

### 8.3 POST /rlm/analyze/stream

Run a streaming recursive analysis with Server-Sent Events progress.

```bash
curl -s -N -X POST http://localhost:5563/rlm/analyze/stream \
  -H "Content-Type: application/json" \
  -d '{"query": "What do I know about mK:a architecture?"}'
```

**SSE Event Stream:**

```
event: status
data: {"status":"starting","message":"Initializing RLM engine...","started_at":"2026-02-23T14:30:00","elapsed_seconds":0.01}

event: status
data: {"status":"running","message":"Privacy filter passed, starting analysis...","elapsed_seconds":0.5}

event: progress
data: {"status":"running","iteration":1,"max_iterations":10,"message":"Iteration 1/10: Claude analyzing...","elapsed_seconds":1.0}

event: progress
data: {"status":"running","iteration":1,"message":"Executing code (tools: rag_search)","elapsed_seconds":8.0}

event: progress
data: {"status":"running","iteration":2,"max_iterations":10,"message":"Iteration 2/10: Claude analyzing...","elapsed_seconds":12.0,"estimated_remaining_seconds":48.0}

event: progress
data: {"status":"running","iteration":2,"message":"Executing code (tools: session_context)","elapsed_seconds":18.0}

event: progress
data: {"status":"running","iteration":3,"message":"FINAL answer detected","elapsed_seconds":28.0}

event: complete
data: {"status":"complete","result":"mK:a uses a 5-phase architecture...","iterations":3,"cost_usd":0.035,"elapsed_seconds":28.0,"total_seconds":28.0}
```

### 8.4 Accessing via ADB Forward (from Desktop)

```bash
# Set up ADB port forwarding (one-time per session)
adb forward tcp:5563 tcp:5563

# Now curl works from the desktop
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{"query": "What credentials do I have stored?", "model": "haiku", "max_iterations": 3}'
```

### 8.5 Comparison: Desktop RLM vs On-Device RLM

| Feature | Desktop (localhost:6100) | On-Device (localhost:5563) |
|---------|------------------------|---------------------------|
| Endpoint | `POST /rlm/analyze` | `POST /rlm/analyze` |
| Engine | Claude (haiku/sonnet/opus) | Claude (haiku/sonnet/opus) |
| REPL style | Python code execution | Python code execution |
| Modes | file, text, codebase | rag, text, file, codebase, hybrid |
| Max iterations | 15 (default 5) | 15 (default 10) |
| Context limit | 100K-200K tokens | 100K-200K tokens (same Claude) |
| Extra namespace tools | `recursive_llm()` | `recursive_llm()` + `rag_search()` + `session_context()` + `embed_similarity()` |
| Cost | Same API costs | Same API costs |
| Privacy | No screening | Gemma privacy filter on input/output |
| SSE streaming | Yes | Yes |
| Health check | `GET /health` | `GET /rlm/status` |
| Escalation | N/A (is the top tier) | To desktop Claude via Switchboard |
| Platform | Windows (Python + claude.cmd) | Android/Termux (Python + claude CLI) |

---

## Appendix A: Prompt Templates

### System Prompt (sent every iteration, same as desktop rlm_tool.py)

```
PYTHON REPL MODE. Variable `context` has {len} chars.
You have a LIMITED iteration budget. You MUST call FINAL() before iterations run out.

YOUR TASK: Answer the user's query by exploring the `context` variable with Python code.
Focus on the user's actual question. Extract, summarize, or analyze whatever they asked for.

STRATEGY: Spend early iterations gathering data. When you have enough evidence, STOP exploring
and call FINAL() with a comprehensive answer. Do NOT try to be exhaustive -- be thorough enough
to answer well, then deliver.

RULES:
1. Write ONE ```python``` block per turn, then STOP
2. WAIT for output before continuing
3. When you have enough findings, write FINAL('''your answer here''') using TRIPLE QUOTES
4. IMPORTANT: Always use triple quotes in FINAL() to avoid parsing issues with parentheses
5. Example: FINAL('''The system uses a 3-tier architecture with components like foo() and bar()''')
6. Do NOT search for secrets, flags, passwords, or CTF patterns unless the user specifically asked for that
7. Do NOT reassign the `context` variable -- it is read-only
8. You can call on-device tools: rag_search(), rag_context(), session_context(), embed_similarity(), etc.

Available in namespace: context (str), re, json, os, Path
  rag_search(query, top_k=5) -- Search on-device RAG, returns list of dicts
  rag_context(query, top_k=10) -- Get formatted RAG context string
  session_context() -- Get Ebbinghaus-weighted session memory
  recursive_llm(query, text, model='haiku') -- Spawn sub-Claude call for analysis
  embed_similarity(text1, text2) -- Cosine similarity via Gemma embeddings (0-1)
  read_file(path) -- Read a file from app storage
  list_files(directory) -- List files in a directory
  glob_files(pattern, directory) -- Find files matching glob
```

### First Iteration Prompt

```
TASK: {query}

CONTEXT PREVIEW (first 1500 chars of {context_length} total):
---
{context_preview}
---

Write Python code to explore `context` and find the answer. Start now.
```

### Subsequent Iteration Prompt

```
TASK: {query}

Iteration {N}/{max_iterations} ({remaining} remaining).

Previous code output:
{output}

DISCOVERIES SO FAR:
{discovery_1}
---
{discovery_2}
---
{discovery_3}

{urgency directive based on remaining iterations}
```

---

## Appendix B: Testing Plan

### Unit Tests

1. **RecursiveLanguageEngine.parseFinalAnswer()** -- Test FINAL() parsing with triple quotes, single/double quotes, bare
2. **RecursiveLanguageEngine.parseCodeBlock()** -- Test ```python``` and ```py``` extraction
3. **RecursiveLanguageEngine.detectToolUsage()** -- Test detection of namespace function calls in code
4. **RlmNamespaceProvider.buildNamespaceCode()** -- Verify generated Python is syntactically valid
5. **RlmCodeExecutor.execute()** -- Test code execution with mocked Python subprocess

### Integration Tests

1. **Full REPL loop with mocked Claude CLI** -- Verify iteration count, code execution, FINAL detection
2. **RAG integration** -- Verify `rag_search()` and `rag_context()` call the RAG HTTP server correctly
3. **Timeout handling** -- Verify the engine respects `timeoutMs` and `perIterationTimeoutMs`
4. **SSE streaming** -- Verify progress events are emitted correctly
5. **Privacy filter** -- Verify input/output screening via Gemma

### Manual Testing via curl

```bash
# 1. Check status
curl -s http://localhost:5563/rlm/status | python -m json.tool

# 2. Simple RAG query
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{"query": "What is my network configuration?"}'

# 3. Text analysis
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{"query": "Summarize this", "context_text": "mK:a is an Android app...", "mode": "text"}'

# 4. Codebase analysis
curl -s -X POST http://localhost:5563/rlm/analyze \
  -H "Content-Type: application/json" \
  -d '{"query": "List all API endpoints", "directory": "/data/user/0/com.mobilekinetic.agent/files/home", "mode": "codebase", "extensions": [".py"]}'

# 5. Streaming
curl -s -N -X POST http://localhost:5563/rlm/analyze/stream \
  -H "Content-Type: application/json" \
  -d '{"query": "Tell me about session memory"}'
```

---

## Appendix C: Migration from Gemma Design

The previous design document (authored 2026-02-23) used Gemma 3 1B as the reasoning engine. Here is what changed:

| Aspect | Old (Gemma) Design | New (Claude) Design |
|--------|-------------------|---------------------|
| **Reasoning engine** | Gemma 3 1B (GPU, MediaPipe) | On-device Claude (Node.js CLI) |
| **REPL style** | Structured TOOL_CALL/FINAL format | Free-form Python code execution |
| **Context window** | ~4K tokens (aggressive truncation) | 100K+ tokens (minimal truncation) |
| **Max iterations** | 3 (default) | 10 (default) |
| **Code generation** | Not supported (Gemma too small) | Full Python code execution |
| **Sub-calls** | `sub_generate` via Gemma | `recursive_llm()` via Claude |
| **Tool access** | Kotlin tool provider (parse TOOL_CALL) | Python namespace functions (HTTP) |
| **Cost** | $0 (local compute) | API cost per iteration |
| **Quality** | Limited (1B param model) | Full Claude quality |
| **Gemma's role** | Reasoning engine | Embeddings + privacy filter only |
| **Dependencies** | GemmaTextGenerator | Claude CLI + Python + Termux |

**Files removed/replaced:**
- `RlmToolProvider.kt` -- replaced by `RlmNamespaceProvider.kt` (Python-based tools)
- Structured TOOL_CALL parsing -- replaced by Python code block + FINAL() parsing
- Context budget management (4K sliding window) -- no longer needed with 100K+ context
- `GemmaTextGenerator` dependency in engine -- replaced by Claude CLI subprocess

**Files added:**
- `RlmNamespaceProvider.kt` -- builds Python helper functions
- `RlmCodeExecutor.kt` -- executes Python code via subprocess

---

## Appendix D: Future Enhancements

1. **Vault tool** (`vault_query()`): Credential-gated operations inside the REPL namespace (Phase 4 dependency)
2. **Inter-device escalation**: Automatic Switchboard escalation when on-device RLM cannot resolve
3. **Cost budgets**: Per-query cost limits that terminate analysis if exceeded
4. **Adaptive iteration count**: Start with 3, extend to 10 only if Claude requests more tools
5. **Persistent RLM sessions**: Save REPL state to disk, resume later
6. **Quality scoring**: Heuristic to estimate answer quality and auto-escalate below threshold
7. **Batch analysis**: Queue multiple queries and run them sequentially with shared context
8. **Model selection heuristic**: Auto-select haiku for simple queries, sonnet for medium, opus for complex
9. **On-device caching**: Cache Claude CLI responses for repeated queries (hash-based)
10. **Offline fallback to Gemma**: If no network/API access, degrade gracefully to the old Gemma-based RLM as a last resort
