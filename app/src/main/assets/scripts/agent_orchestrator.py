#!/usr/bin/env python3
"""
mK:a Agent Orchestrator

Persistent Python process that wraps the Claude Agent SDK.
Reads JSON from stdin (from Android ClaudeProcessManager),
writes JSON to stdout (responses, streaming events, results).

Designed to run inside the mK:a Termux Linux environment.

Usage: python3 agent_orchestrator.py
"""

import asyncio
import json
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
import urllib.error
from pathlib import Path
from context_injector import PriorityInjector
from mka_config import (
    DEVICE_API_PORT, DEVICE_API_URL,
    MCP_TIER2_PORT, MCP_TIER2_URL, RAG_PORT, RAG_URL,
)
from vault_client import VaultClient, fetch_credential  # Phase 9: Vault client for credential access — calls VaultHttpServer at :5565

# Force unbuffered stdout for real-time JSON streaming
os.environ["PYTHONUNBUFFERED"] = "1"

# ---------------------------------------------------------------------------
# Diagnostic logging helper (lightweight, single-line per event)
# ---------------------------------------------------------------------------

_diag_last = time.time()

def diag(stage, details=""):
    global _diag_last
    now = time.time()
    delta_ms = int((now - _diag_last) * 1000)
    ts = time.strftime("%H:%M:%S", time.localtime(now)) + f".{int(now*1000)%1000:03d}"
    print(f"[BA-DIAG] [{ts}] [+{delta_ms}ms] {stage}: {details}", file=sys.stderr, flush=True)
    _diag_last = now

# ---------------------------------------------------------------------------
# Port availability check (warn-only, does not kill)
# ---------------------------------------------------------------------------

def check_port_available(port):
    """Check if a port is available. Logs a warning if occupied.

    Unlike the mcp_shell_server version, this does NOT attempt to kill the
    occupying process -- it may be a legitimate service (e.g. Tier 1 API).
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        sock.close()
        diag("PORT_CHECK", f"port {port} is available")
        return True
    except OSError:
        sock.close()
        diag("PORT_CHECK", f"WARNING: port {port} is already in use")
        return False

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

RAG_ENDPOINT = os.environ.get("RAG_ENDPOINT", RAG_URL)

# ---------------------------------------------------------------------------
# Memory Tag parsing (Phase 5) — strips [MEMORY_TAG: ...] from responses,
# persists non-disposable tags to RAG + memory_facts
# ---------------------------------------------------------------------------

MEMORY_TAG_PATTERN = re.compile(
    r'\[MEMORY_TAG:\s*(permanent|midterm|short-term|disposable)\s*,\s*(\{[^}]*\})\s*,\s*"([^"]+)"\s*\]'
)

_TIER_TO_CATEGORY = {
    "permanent": "permanent_fact",
    "midterm": "session_insight",
    "short-term": "session_detail",
}

def _fix_json_keys(s: str) -> str:
    """Add quotes to unquoted JSON keys like {people: [...]}."""
    return re.sub(r'(\{|,)\s*(\w+)\s*:', r'\1 "\2":', s)


async def _extract_and_persist_tags(json_msg: dict, rag_endpoint: str) -> dict:
    """Scan assistant text blocks for [MEMORY_TAG:], strip them, persist to RAG.

    Modifies json_msg in-place (strips tags from text blocks).
    Persists non-disposable memories to RAG /memory and /capture_fact endpoints.
    All persistence is best-effort.
    """
    try:
        content_blocks = json_msg.get("message", {}).get("content", [])
        if not content_blocks:
            return json_msg

        extracted = []
        for block in content_blocks:
            if block.get("type") != "text":
                continue
            text = block.get("text", "")
            matches = MEMORY_TAG_PATTERN.findall(text)
            if not matches:
                continue
            for tier, metadata_str, summary in matches:
                if tier == "disposable":
                    continue
                try:
                    fixed = _fix_json_keys(metadata_str)
                    metadata = json.loads(fixed)
                except json.JSONDecodeError:
                    metadata = {}
                extracted.append((tier, metadata, summary))
            # Strip ALL tags (including disposable) from user-visible text
            cleaned = MEMORY_TAG_PATTERN.sub("", text).strip()
            block["text"] = cleaned

        # Persist extracted non-disposable memories (best-effort)
        if extracted:
            loop = asyncio.get_running_loop()
            for tier, metadata, summary in extracted:
                category = _TIER_TO_CATEGORY.get(tier, "general")
                # POST to RAG /memory
                try:
                    payload = json.dumps({
                        "text": summary,
                        "category": category,
                        "metadata": json.dumps(metadata),
                    }).encode("utf-8")
                    req = urllib.request.Request(
                        f"{rag_endpoint}/memory",
                        data=payload,
                        headers={"Content-Type": "application/json"},
                        method="POST",
                    )
                    await loop.run_in_executor(None, lambda r=req: urllib.request.urlopen(r, timeout=5))
                    diag("MEMORY_TAG", f"Persisted to RAG: tier={tier} summary={summary[:60]}")
                except Exception as e:
                    diag("MEMORY_TAG", f"RAG persist failed (non-fatal): {e}")

                # POST to /capture_fact for Kotlin memory_facts table
                try:
                    fact_payload = json.dumps({
                        "category": category,
                        "key": f"tag_{tier}_{int(time.time())}",
                        "value": summary,
                        "source": "memory_tag",
                    }).encode("utf-8")
                    fact_req = urllib.request.Request(
                        f"{rag_endpoint}/capture_fact",
                        data=fact_payload,
                        headers={"Content-Type": "application/json"},
                        method="POST",
                    )
                    await loop.run_in_executor(None, lambda r=fact_req: urllib.request.urlopen(r, timeout=5))
                except Exception as e:
                    diag("MEMORY_TAG", f"capture_fact failed (non-fatal): {e}")

    except Exception as e:
        diag("MEMORY_TAG", f"Tag extraction error (non-fatal): {e}")

    return json_msg

ORCHESTRATOR_VERSION = "1.0.0"
BUILD_TIMESTAMP = "2026-02-04T00:00:00"
HOME_DIR = os.environ.get("HOME", str(Path.home()))
PREFIX_DIR = os.environ.get("PREFIX", "/data/data/com.mobilekinetic.agent/files/usr")

SYSTEM_PROMPT = f"""You are Claude, running on the user's device inside the app mobileKinetic:Agent. \
You have a RAG memory system at {RAG_ENDPOINT} -- query it before acting. \
You have two Device APIs: Tier 1 (Kotlin, port {DEVICE_API_PORT}) for Android APIs, \
Tier 2 (Python, port {MCP_TIER2_PORT}) for shell/system ops. \
If a tier is offline consult your RAG for instructions regarding how to restart the disabled tier's server. \
Never touch /sdcard paths or run termux-* commands -- both hang forever, crashing the application. \
Home is {HOME_DIR}. Check RAG for endpoint docs, warnings, and context. \

---

## RESPONSES
Your responses will be spoken aloud via TTS. Keep ALL replies under 150 tokens.

Rules:
- One main idea per response
- Short sentences, conversational tone
- No emoji, markdown, bullet points, or code blocks unless explicitly requested
- If a topic needs more depth, offer to continue

When you need to explain something complex, break it into parts and ask if the user wants the next part.

---

## RAG -- YOUR MEMORY
You have RAG available as MCP tools (rag_search, rag_context, rag_add_memory, rag_health) \
AND via curl. Prefer MCP tools when available. Fall back to curl if MCP is unavailable.

Health: curl -s {RAG_ENDPOINT}/health
Query: curl -X POST {RAG_ENDPOINT}/search -H "Content-Type: application/json" -d '{{"query": "search terms", "top_k": 5}}'
Add: curl -X POST {RAG_ENDPOINT}/memory -H "Content-Type: application/json" -d '{{"text": "fact", "category": "cat"}}'
Context: curl -X POST {RAG_ENDPOINT}/context -H "Content-Type: application/json" -d '{{"query": "context query", "top_k": 10}}'

---

## MCP TOOLS -- CRITICAL FOR SUB-AGENTS
You have MCP servers that provide native tool access. When you spawn sub-agents via the \
Task tool, those sub-agents MUST use MCP tools to do their work. Native tools like Grep \
are broken on ARM64 and will cause sub-agents to hang indefinitely.

Available MCP tools:
- RAG: rag_search, rag_context, rag_add_memory, rag_health
- Shell Tools: shell_exec, grep_search, find_files, list_directory, read_file

Rules:
- Sub-agents MUST use Shell Tools MCP instead of Bash for: searching files (grep_search), \
finding files (find_files), listing directories (list_directory), reading files (read_file), \
and running commands (shell_exec). These work reliably -- raw Bash does not.
- NEVER use the Grep tool (ripgrep) -- it does not exist on ARM64 and will hang forever.
- NEVER spawn sub-agents that use the Task tool themselves (no nested sub-agents).
- Prefer MCP tools over Bash curl commands when both can do the job
- Sub-agents inherit your MCP tools -- they do NOT need curl for RAG
- If you or a sub-agent needs a capability that has no MCP wrapper, tell the user it needs one
- Debug log: all shell-tools calls are logged to ~/mcp_shell_debug.log

---

## NETWORK ENVIRONMENT
Network state is transient -- always discover live, never cache in RAG.
  curl -s {DEVICE_API_URL}/health   # Tier 1 (Kotlin API)
  curl -s {MCP_TIER2_URL}/health   # Tier 2 (Python API)
  curl -s {RAG_ENDPOINT}/health          # RAG

---

## NEVER
1. Access /sdcard or /storage/emulated -- hangs permanently
2. Run termux-* commands (termux-battery-status, termux-location, etc.) -- hangs forever, no IPC bridge
3. Install claude-code latest -- pin v2.0.37, newer versions silently fail on arm64
4. Use pkg/apt -- broken paths, use pip or manual install
5. Use the Grep tool -- ripgrep does NOT exist on ARM64. Use the shell-tools MCP (grep_search) instead
6. Use emojis in responses -- the operator dislikes them. Never use emojis in any communication

---

## WATCHDOG PROTECTION
A watchdog thread monitors sub-agent processes. If any child process runs longer than \
120 seconds, it will be killed and you will receive a [WATCHDOG TIMEOUT] alert injected \
into this conversation.

When you see a [WATCHDOG TIMEOUT] alert:
- Do NOT retry with the Task tool -- it will likely hang again
- Use your tools directly: Bash (with grep/find for searching), Read, Write, Glob
- If a capability is repeatedly blocked, tell the user it needs an MCP wrapper

---

## SESSION START
1. Check RAG health
2. Load context: query "warnings session history"
3. Discover network environment (always live, never from RAG)

---

## WORKFLOW
- Query RAG before every task
- Use MCP tools when available, Device API endpoints for Android-specific ops
- Test incrementally
- Persist discoveries to RAG after completing work
- Keep the user informed of progress
- No emojis, ever

---

Last Updated: 2026-02-04
User: (configured at runtime)
Device: (detected at runtime)
Home: {HOME_DIR}
PREFIX: {PREFIX_DIR}
Python: {sys.executable}
RAG: {RAG_ENDPOINT}
Tier 1 API: localhost:{DEVICE_API_PORT} (Kotlin, 45 endpoints)
Tier 2 API: localhost:{MCP_TIER2_PORT} (Python, 16 endpoints)
Claude Code: v2.0.37 (pinned)
Orchestrator v{ORCHESTRATOR_VERSION} ({BUILD_TIMESTAMP})
"""

# ---------------------------------------------------------------------------
# Phase 5: MessageBuffer — ring buffer for tracking message history
# ---------------------------------------------------------------------------

import uuid as _uuid_mod
from collections import deque as _deque
from typing import List as _List, Dict as _Dict, Any as _Any


class MessageBuffer:
    """Simple ring buffer tracking message history with token counts.

    Used by NapkinDab to score and optionally trim older messages.
    Token counting uses a word-split approximation (swap in tiktoken if needed).
    Does NOT modify persisted JSONL history — in-memory only.
    """

    def __init__(self, max_size: int = 200):
        self.max_size = max_size
        self._messages: _deque = _deque(maxlen=max_size)

    def add(self, message: _Dict[str, _Any]):
        """Append a message dict to the buffer. Oldest entries dropped when full."""
        self._messages.append(message)

    def get_recent(self, n: int) -> _List[_Dict[str, _Any]]:
        """Return the last n messages (or all if fewer than n exist)."""
        msgs = list(self._messages)
        return msgs[-n:] if n < len(msgs) else msgs

    def trim(self, keep_last_n: int):
        """Remove older messages, retaining only the most recent keep_last_n."""
        if keep_last_n <= 0:
            self._messages.clear()
            return
        recent = self.get_recent(keep_last_n)
        self._messages.clear()
        self._messages.extend(recent)

    @property
    def size(self) -> int:
        """Current number of messages in the buffer."""
        return len(self._messages)

    @property
    def token_count(self) -> int:
        """Rough token estimate (word split) across all buffered messages."""
        total = 0
        for msg in self._messages:
            content = msg.get("content", "")
            if isinstance(content, str):
                total += len(content.split())
            elif isinstance(content, list):
                for block in content:
                    if isinstance(block, dict):
                        total += len(str(block.get("text", block.get("content", ""))).split())
        return total

    def snapshot(self) -> _List[_Dict]:
        """Return a debug-safe snapshot (no full content, just preview)."""
        return [
            {
                "role": m.get("role", "?"),
                "token_estimate": len(m.get("content", "").split()) if isinstance(m.get("content"), str) else 0,
                "preview": str(m.get("content", ""))[:80],
            }
            for m in self._messages
        ]


# ---------------------------------------------------------------------------
# Phase 5: NapkinDab — mid-session cleanup monitor
# ---------------------------------------------------------------------------

import os as _os_napkin


class NapkinDab:
    """Mid-session context compaction monitor — 'dabbing' noise out of the buffer.

    Checks exchange_count against a threshold (default: every 50 exchanges).
    When the threshold fires it:
      1. Summarizes recent context via PriorityInjector (optional, best-effort)
      2. Optionally trims the MessageBuffer to keep_last_n messages
      3. Logs a [NAPKIN_DAB] summary marker to stderr

    What it does NOT do:
      - Does not delete session summaries or RAG memory facts
      - Does not modify persisted JSONL history (in-memory only)
      - Does not use os.statvfs (unavailable on Android)
      - Does not implement restart_cli_session (TODO: future phase)

    napkin_messages.json is loaded once at first fire and cached.
    """

    def __init__(
        self,
        buffer: MessageBuffer,
        threshold: int = 50,
        keep_last_n: int = 100,
        napkin_json_path: str = None,
    ):
        self.buffer = buffer
        self.threshold = threshold
        self.keep_last_n = keep_last_n
        self._last_fire_exchange = 0
        self._fire_count = 0
        self._messages_cfg: _Dict[str, _Any] = {}
        self._napkin_json_path = napkin_json_path or _os_napkin.path.join(
            _os_napkin.path.dirname(_os_napkin.path.abspath(__file__)),
            "..", "napkin_messages.json"
        )
        self._cfg_loaded = False

    def _load_cfg(self):
        """Load napkin_messages.json once, cache result. Safe to call repeatedly."""
        if self._cfg_loaded:
            return
        self._cfg_loaded = True
        try:
            with open(self._napkin_json_path, "r", encoding="utf-8") as f:
                self._messages_cfg = json.load(f)
            # Allow JSON to override threshold and max_buffer_size
            if "threshold_exchanges" in self._messages_cfg:
                self.threshold = int(self._messages_cfg["threshold_exchanges"])
            if "max_buffer_size" in self._messages_cfg:
                self.buffer.max_size = int(self._messages_cfg["max_buffer_size"])
            diag("NAPKIN_DAB", f"Config loaded from {self._napkin_json_path} threshold={self.threshold}")
        except FileNotFoundError:
            diag("NAPKIN_DAB", f"Config not found at {self._napkin_json_path} — using defaults")
        except Exception as e:
            diag("NAPKIN_DAB", f"Config load error: {e} — using defaults")

    def should_fire(self, exchange_count: int) -> bool:
        """Return True when exchange_count crosses the next threshold multiple."""
        self._load_cfg()
        if exchange_count <= 0:
            return False
        if exchange_count == self._last_fire_exchange:
            return False
        # Fire on every N-th exchange (e.g., 50, 100, 150, ...)
        return (exchange_count % self.threshold) == 0

    def fire(self, exchange_count: int, priority_injector=None):
        """Execute a napkin dab: summarize context and trim the message buffer.

        Args:
            exchange_count: Current exchange number (used in log marker).
            priority_injector: Optional PriorityInjector instance. If provided,
                its in-memory state is refreshed (best-effort). May be None.

        NOTE: restart_cli_session is TBD — not implemented in this phase.
        TODO: Implement restart_cli_session when Phase 6 architecture is defined.
        """
        self._load_cfg()
        self._last_fire_exchange = exchange_count
        self._fire_count += 1

        tokens_before = self.buffer.token_count
        size_before = self.buffer.size

        # Step 1: Trim the message buffer to keep_last_n messages
        self.buffer.trim(self.keep_last_n)

        tokens_after = self.buffer.token_count
        size_after = self.buffer.size

        # Step 2: Optional — refresh priority injector context (best-effort)
        if priority_injector is not None:
            try:
                # Reset injector's cache so next exchange gets fresh context
                if hasattr(priority_injector, "_context_cache"):
                    priority_injector._context_cache = None
                if hasattr(priority_injector, "_cache_timestamp"):
                    priority_injector._cache_timestamp = 0
                diag("NAPKIN_DAB", "PriorityInjector cache reset for fresh context next exchange")
            except Exception as e:
                diag("NAPKIN_DAB", f"PriorityInjector reset skipped: {e}")

        # Step 3: Emit [NAPKIN_DAB] summary marker
        summary = (
            f"[NAPKIN_DAB #{self._fire_count}] "
            f"exchange={exchange_count} "
            f"buffer={size_before}→{size_after} msgs "
            f"tokens_est={tokens_before}→{tokens_after} "
            f"threshold={self.threshold}"
        )
        diag("NAPKIN_DAB", summary)
        sys.stderr.write(f"[napkin_dab] {summary}\n")
        sys.stderr.flush()

        # Write a system event so the Kotlin layer can optionally display it
        write_json({
            "type": "system",
            "subtype": "napkin_dab",
            "data": {
                "fire_number": self._fire_count,
                "exchange_count": exchange_count,
                "messages_before": size_before,
                "messages_after": size_after,
                "tokens_before": tokens_before,
                "tokens_after": tokens_after,
                "summary": summary,
            }
        })

        return {
            "triggered": True,
            "fire_number": self._fire_count,
            "messages_before": size_before,
            "messages_after": size_after,
            "tokens_before": tokens_before,
            "tokens_after": tokens_after,
        }


# ---------------------------------------------------------------------------
# JSON protocol helpers
# ---------------------------------------------------------------------------

def write_json(obj: dict):
    """Write a JSON message to stdout (read by ClaudeProcessManager)."""
    try:
        line = json.dumps(obj, ensure_ascii=False)
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
        diag("ORCH_MSG_WRITTEN", f"type={obj.get('type', '?')} len={len(line)}")
    except Exception as e:
        sys.stderr.write(f"[orchestrator] Error writing JSON: {e}\n")
        sys.stderr.flush()


def write_error(error: str, details: str = None):
    """Write an error message to stdout."""
    msg = {"type": "error", "error": error}
    if details:
        msg["details"] = details
    write_json(msg)


def write_system(subtype: str, data: dict = None):
    """Write a system message to stdout."""
    msg = {"type": "system", "subtype": subtype}
    if data:
        msg["data"] = data
    write_json(msg)


def read_json_line() -> dict | None:
    """Read a single JSON line from stdin (sent by ClaudeProcessManager)."""
    try:
        line = sys.stdin.readline()
        if not line:
            return None  # EOF -- stdin closed
        line = line.strip()
        if not line:
            return None
        return json.loads(line)
    except json.JSONDecodeError as e:
        sys.stderr.write(f"[orchestrator] JSON parse error: {e}\n")
        sys.stderr.flush()
        return None
    except Exception as e:
        sys.stderr.write(f"[orchestrator] Error reading stdin: {e}\n")
        sys.stderr.flush()
        return None


# ---------------------------------------------------------------------------
# SDK message adapter
# ---------------------------------------------------------------------------

def sdk_message_to_json(msg) -> dict | None:
    """Convert an SDK message object to a JSON-serializable dict for stdout."""
    try:
        # Import SDK types lazily (they may not be available during startup checks)
        from claude_agent_sdk import (
            AssistantMessage,
            UserMessage,
            SystemMessage as SDKSystemMessage,
            ResultMessage,
            TextBlock,
            ThinkingBlock,
            ToolUseBlock,
            ToolResultBlock,
        )
        from claude_agent_sdk.types import StreamEvent

        if isinstance(msg, AssistantMessage):
            content_blocks = []
            for block in msg.content:
                if isinstance(block, TextBlock):
                    content_blocks.append({"type": "text", "text": block.text})
                elif isinstance(block, ThinkingBlock):
                    content_blocks.append({
                        "type": "thinking",
                        "thinking": block.thinking,
                        "signature": getattr(block, "signature", None)
                    })
                elif isinstance(block, ToolUseBlock):
                    content_blocks.append({
                        "type": "tool_use",
                        "id": block.id,
                        "name": block.name,
                        "input": block.input
                    })
                elif isinstance(block, ToolResultBlock):
                    content_blocks.append({
                        "type": "tool_result",
                        "tool_use_id": block.tool_use_id,
                        "content": block.content,
                        "is_error": getattr(block, "is_error", None)
                    })

            error_data = None
            if msg.error:
                error_data = {
                    "type": getattr(msg.error, "type", "unknown"),
                    "message": str(msg.error)
                }

            return {
                "type": "assistant",
                "message": {
                    "content": content_blocks,
                    "model": getattr(msg, "model", None),
                    "parent_tool_use_id": getattr(msg, "parent_tool_use_id", None),
                    "error": error_data
                }
            }

        elif isinstance(msg, UserMessage):
            content = msg.content
            if isinstance(content, list):
                content = str(content)
            return {
                "type": "user",
                "message": {
                    "content": content if isinstance(content, str) else str(content),
                    "uuid": getattr(msg, "uuid", None),
                    "parent_tool_use_id": getattr(msg, "parent_tool_use_id", None)
                }
            }

        elif isinstance(msg, SDKSystemMessage):
            return {
                "type": "system",
                "subtype": getattr(msg, "subtype", "unknown"),
                "data": getattr(msg, "data", {})
            }

        elif isinstance(msg, ResultMessage):
            return {
                "type": "result",
                "subtype": getattr(msg, "subtype", "unknown"),
                "duration_ms": getattr(msg, "duration_ms", 0),
                "duration_api_ms": getattr(msg, "duration_api_ms", 0),
                "is_error": getattr(msg, "is_error", False),
                "num_turns": getattr(msg, "num_turns", 0),
                "session_id": getattr(msg, "session_id", ""),
                "total_cost_usd": getattr(msg, "total_cost_usd", None),
                "usage": getattr(msg, "usage", None),
                "result": getattr(msg, "result", None)
            }

        elif isinstance(msg, StreamEvent):
            return {
                "type": "stream_event",
                "uuid": getattr(msg, "uuid", None),
                "session_id": getattr(msg, "session_id", None),
                "event": getattr(msg, "event", None),
                "parent_tool_use_id": getattr(msg, "parent_tool_use_id", None)
            }

        else:
            # Unknown message type -- serialize as system message
            return {
                "type": "system",
                "subtype": "unknown_sdk_message",
                "data": {"repr": repr(msg)}
            }

    except Exception as e:
        sys.stderr.write(f"[orchestrator] Error converting SDK message: {e}\n")
        sys.stderr.flush()
        return None


# ---------------------------------------------------------------------------
# Watchdog -- monitors for stuck sub-agent processes (the "second lane")
# ---------------------------------------------------------------------------

WATCHDOG_TIMEOUT = 120      # Kill child processes older than this (seconds)
WATCHDOG_POLL_INTERVAL = 5  # How often to check (seconds)

# Command-specific timeout table (SmartWatchdog)
COMMAND_TIMEOUTS = {
    'curl': 30,
    'grep': 45,
    'rg': 45,
    'find': 60,
    'ls': 15,
    'am': 20,
    'pgrep': 10,
    'claude': 600,
}
DEFAULT_TIMEOUT = 120
IDLE_TIMEOUT = 120  # Kill idle claude sub-agents after this many seconds of no activity
TIMEOUT_WARNING_THRESHOLD = 0.75

_event_loop = None           # Set in run_agent() for thread-safe async bridging
_alert_queue_ref = None      # asyncio.Queue ref for watchdog -> async loop
_pending_alerts: list = []   # Queued alerts waiting for CLI to finish tool processing
_pending_alerts_lock = threading.Lock()  # Thread-safe access to _pending_alerts


def _find_children(parent_pid: int, cmd_filter: str | None = None) -> list[int]:
    """Find direct child PIDs by reading /proc/<pid>/stat.

    If cmd_filter is set, only return children whose /proc/<pid>/cmdline
    contains that string (e.g. "claude" to skip MCP servers).
    """
    children = []
    try:
        for entry in os.listdir('/proc'):
            if not entry.isdigit():
                continue
            try:
                with open(f'/proc/{entry}/stat', 'r') as f:
                    stat_data = f.read()
                # /proc/pid/stat format: pid (comm) state ppid ...
                paren_end = stat_data.rfind(')')
                fields = stat_data[paren_end + 2:].split()
                ppid = int(fields[1])
                if ppid == parent_pid:
                    if cmd_filter is not None:
                        try:
                            with open(f'/proc/{entry}/cmdline', 'r') as f:
                                cmdline = f.read()
                            if cmd_filter not in cmdline:
                                continue
                        except IOError:
                            continue
                    children.append(int(entry))
            except (IOError, ValueError, IndexError):
                continue
    except OSError:
        pass
    return children


def _get_process_command(pid: int) -> str | None:
    """Read process identity with Android-aware fallbacks.

    On Android, argv[0] is often 'linker64'. The real binary is in argv[1].
    Falls back to /proc/<pid>/comm if cmdline fails.
    """
    # Layer 1: cmdline in binary mode, check argv[1] for real binary
    try:
        with open(f'/proc/{pid}/cmdline', 'rb') as f:
            raw = f.read()
        parts = [p.decode('utf-8', errors='replace') for p in raw.split(b'\x00') if p]
        if parts:
            base0 = os.path.basename(parts[0])
            if base0 == 'linker64' and len(parts) > 1:
                return os.path.basename(parts[1])
            return base0
    except (IOError, OSError, PermissionError):
        pass
    # Layer 2: /proc/<pid>/comm (kernel process name, max 15 chars)
    try:
        with open(f'/proc/{pid}/comm', 'r') as f:
            return f.read().strip()
    except (IOError, OSError, PermissionError):
        pass
    return None


def _get_timeout_for_command(cmd: str | None) -> int:
    """Get timeout for a command from COMMAND_TIMEOUTS table."""
    if not cmd:
        return DEFAULT_TIMEOUT
    if cmd in COMMAND_TIMEOUTS:
        return COMMAND_TIMEOUTS[cmd]
    for prefix, timeout in COMMAND_TIMEOUTS.items():
        if cmd.startswith(prefix):
            return timeout
    return DEFAULT_TIMEOUT


def _get_protected_mcp_pid() -> int | None:
    """Read MCP server's self-registered PID file."""
    pid_file = os.path.join(HOME_DIR, '.mcp_server.pid')
    try:
        with open(pid_file, 'r') as f:
            pid = int(f.read().strip())
        os.kill(pid, 0)  # Verify process is alive
        return pid
    except (FileNotFoundError, ValueError, ProcessLookupError, OSError):
        return None


def _watchdog_loop():
    """Background thread: detect and kill stuck sub-agent processes.

    Scans the process tree every WATCHDOG_POLL_INTERVAL seconds:
      orchestrator (us) -> CLI (child) -> sub-agents (grandchildren)

    Any grandchild alive longer than WATCHDOG_TIMEOUT gets terminated.
    The CLI handles the child death and returns an error to parent Claude,
    unblocking the main pipeline.
    """
    my_pid = os.getpid()
    tracked: dict[int, float] = {}  # pid -> first_seen_time
    last_activity: dict[int, float] = {}  # pid -> last_activity_time (for idle tracking)
    startup_pids: set[int] = set()  # PIDs first seen during startup window
    exempt_pids: set[int] = set()   # PIDs confirmed as infrastructure (MCP servers)
    watchdog_start_time = time.time()

    # PIDs appearing within STARTUP_WINDOW seconds are candidates for MCP servers.
    # If they survive INFRASTRUCTURE_THRESHOLD seconds, they're exempt forever.
    STARTUP_WINDOW = 30       # seconds after watchdog start
    INFRASTRUCTURE_THRESHOLD = 45  # seconds alive to confirm as infrastructure

    sys.stderr.write(
        f"[watchdog] Started (timeout={WATCHDOG_TIMEOUT}s, "
        f"poll={WATCHDOG_POLL_INTERVAL}s, orchestrator={my_pid})\n"
    )
    sys.stderr.flush()

    while True:
        try:
            time.sleep(WATCHDOG_POLL_INTERVAL)
            now = time.time()

            # Step 1: Find CLI process(es) -- direct children of orchestrator
            cli_pids = _find_children(my_pid, cmd_filter="claude")
            if not cli_pids:
                tracked.clear()
                continue

            # Step 2: Find all children of CLI (sub-agents, spawned processes)
            subagent_pids: set[int] = set()
            for cli_pid in cli_pids:
                for sa_pid in _find_children(cli_pid):
                    subagent_pids.add(sa_pid)

            # Step 2.5: PID-file exemption (primary MCP protection)
            protected_pid = _get_protected_mcp_pid()
            if protected_pid and protected_pid in subagent_pids:
                subagent_pids.discard(protected_pid)
                if protected_pid not in exempt_pids:
                    exempt_pids.add(protected_pid)
                    sys.stderr.write(
                        f"[watchdog] MCP server PID {protected_pid} "
                        f"exempted via PID file\n"
                    )
                    sys.stderr.flush()

            # Step 3: Track new sub-agents with startup-window MCP exemption
            # On Android, /proc/<pid>/cmdline is blocked by SELinux and all
            # processes appear as "linker64".  Instead we use a timing heuristic:
            # PIDs that appear within STARTUP_WINDOW seconds of watchdog start
            # are likely MCP servers (they launch with Claude CLI).  If they
            # survive INFRASTRUCTURE_THRESHOLD seconds they are exempt forever.
            for pid in subagent_pids:
                if pid in exempt_pids:
                    continue  # already confirmed as MCP / infrastructure
                # Check /proc/comm for mcp_server identity (Layer 2)
                try:
                    with open(f'/proc/{pid}/comm', 'r') as f:
                        if f.read().strip() == 'mcp_server':
                            exempt_pids.add(pid)
                            sys.stderr.write(
                                f"[watchdog] PID {pid} exempted via "
                                f"/proc/comm=mcp_server\n"
                            )
                            sys.stderr.flush()
                            continue
                except (IOError, OSError, PermissionError):
                    pass
                if pid not in tracked:
                    elapsed_since_start = now - watchdog_start_time
                    tracked[pid] = now
                    last_activity[pid] = now
                    if elapsed_since_start < STARTUP_WINDOW:
                        startup_pids.add(pid)
                        sys.stderr.write(
                            f"[watchdog] Startup PID {pid} "
                            f"(within {STARTUP_WINDOW}s window, may be MCP server)\n"
                        )
                        sys.stderr.flush()
                    else:
                        cmd = _get_process_command(pid)
                        timeout = _get_timeout_for_command(cmd)
                        cmd_display = cmd if cmd else "unknown"
                        sys.stderr.write(
                            f"[watchdog] Tracking sub-agent PID {pid} "
                            f"(cmd={cmd_display}, timeout={timeout}s)\n"
                        )
                        sys.stderr.flush()

            # Step 3.5: Detect hung CLI processes (H7)
            CLI_HANG_THRESHOLD = 300
            for cli_pid in cli_pids:
                try:
                    with open(f'/proc/{cli_pid}/stat', 'r') as f:
                        stat_data = f.read()
                    paren_end = stat_data.rfind(')')
                    fields = stat_data[paren_end + 2:].split()
                    starttime_ticks = int(fields[19])
                    with open('/proc/uptime', 'r') as f:
                        uptime_seconds = float(f.read().split()[0])
                    clock_ticks = os.sysconf(os.sysconf_names['SC_CLK_TCK'])
                    boot_time = uptime_seconds - (starttime_ticks / clock_ticks)
                    cli_age = uptime_seconds - boot_time
                    non_infra_pids = subagent_pids - exempt_pids
                    if cli_age > CLI_HANG_THRESHOLD and len(non_infra_pids) == 0:
                        sys.stderr.write(
                            f"[watchdog] WARNING: CLI process {cli_pid} potentially hung "
                            f"(alive {cli_age:.0f}s, no active sub-agents)\n"
                        )
                        sys.stderr.flush()
                        alert_msg = (
                            f"[CLI HANG DETECTION] CLI PID {cli_pid} alive {cli_age:.0f}s "
                            f"with no sub-agents. May need restart."
                        )
                        diag("WATCHDOG_ALERT", f"cli_hang pid={cli_pid} age={cli_age:.0f}s")
                        if _event_loop and _alert_queue_ref:
                            try:
                                _event_loop.call_soon_threadsafe(
                                    _alert_queue_ref.put_nowait, alert_msg
                                )
                            except RuntimeError:
                                pass
                except (IOError, ValueError, IndexError, KeyError):
                    pass

            # Step 4: Clean up exited sub-agents
            for pid in list(tracked):
                if pid not in subagent_pids:
                    elapsed = now - tracked[pid]
                    sys.stderr.write(
                        f"[watchdog] Sub-agent PID {pid} exited naturally "
                        f"after {elapsed:.1f}s\n"
                    )
                    sys.stderr.flush()
                    del tracked[pid]
                    last_activity.pop(pid, None)
                    startup_pids.discard(pid)  # clean up if it was a startup PID

            # Step 4.5: Flush pending alerts when CLI is idle (no grandchildren)
            if len(subagent_pids) == 0 and _pending_alerts:
                with _pending_alerts_lock:
                    n_pending = len(_pending_alerts)
                if n_pending > 0 and _event_loop and _alert_queue_ref:
                    sys.stderr.write(
                        f"[watchdog] Grandchildren cleared -- "
                        f"flushing {n_pending} pending alert(s)\n"
                    )
                    sys.stderr.flush()
                    # Re-enqueue pending alerts so alert_consumer can process them
                    # now that CLI is no longer busy with tool calls
                    with _pending_alerts_lock:
                        alerts_to_flush = list(_pending_alerts)
                        _pending_alerts.clear()
                    for alert in alerts_to_flush:
                        try:
                            _event_loop.call_soon_threadsafe(
                                _alert_queue_ref.put_nowait, alert
                            )
                        except RuntimeError:
                            pass

            # Step 4.6: Update activity for claude sub-agents with active children
            for pid in list(tracked):
                if pid in exempt_pids:
                    continue
                cmd = _get_process_command(pid)
                if cmd == 'claude' and _find_children(pid):
                    last_activity[pid] = now

            # Step 4.7: Emit status to stderr for Kotlin layer
            subagent_count = len(subagent_pids - exempt_pids)
            mcp_alive = False
            for epid in exempt_pids:
                try:
                    os.kill(epid, 0)
                    mcp_alive = True
                    break
                except ProcessLookupError:
                    pass

            active_list = []
            stale_list = []
            for pid in tracked:
                if pid in exempt_pids:
                    continue
                cmd = _get_process_command(pid)
                if cmd == 'claude':
                    idle = now - last_activity.get(pid, tracked[pid])
                    if idle > 60:
                        stale_list.append(str(pid))
                    else:
                        active_list.append(str(pid))

            sys.stderr.write(
                f"[status] subagent_count={subagent_count} "
                f"mcp_alive={'true' if mcp_alive else 'false'} "
                f"active_pids={','.join(active_list)} "
                f"stale_pids={','.join(stale_list)}\n"
            )
            sys.stderr.flush()

            # Step 5: Promote long-lived startup PIDs to infrastructure, then
            #         kill stuck sub-agents with command-specific timeouts
            for pid in list(tracked):
                elapsed = now - tracked[pid]

                # Promote startup PIDs that survived long enough
                if pid in startup_pids and elapsed >= INFRASTRUCTURE_THRESHOLD:
                    exempt_pids.add(pid)
                    startup_pids.discard(pid)
                    del tracked[pid]
                    last_activity.pop(pid, None)
                    sys.stderr.write(
                        f"[watchdog] Exempting PID {pid} — survived "
                        f"{elapsed:.0f}s, confirmed as MCP/infrastructure\n"
                    )
                    sys.stderr.flush()
                    continue

                # Don't kill startup PIDs during the threshold window
                if pid in startup_pids:
                    continue

                cmd = _get_process_command(pid)
                timeout = _get_timeout_for_command(cmd)

                should_kill = False
                kill_reason = ""
                if cmd == 'claude':
                    idle_time = now - last_activity.get(pid, tracked[pid])
                    if elapsed >= timeout:
                        should_kill = True
                        kill_reason = f"hard ceiling {elapsed:.0f}s >= {timeout}s"
                    elif idle_time >= IDLE_TIMEOUT:
                        should_kill = True
                        kill_reason = f"idle {idle_time:.0f}s"
                elif elapsed >= timeout:
                    should_kill = True
                    kill_reason = f"alive {elapsed:.0f}s, limit {timeout}s"

                if should_kill:
                    cmd_display = cmd if cmd else "unknown"
                    diag("WATCHDOG_TIMEOUT", f"pid={pid} cmd={cmd_display} elapsed={elapsed:.0f}s reason={kill_reason}")
                    sys.stderr.write(
                        f"[watchdog] TIMEOUT: Killing sub-agent PID {pid} "
                        f"(cmd={cmd_display}, {kill_reason})\n"
                    )
                    sys.stderr.flush()

                    # Call diagnostic before killing
                    try:
                        import subprocess
                        subprocess.run([
                            'python3',
                            os.path.join(HOME_DIR, 'watchdog_diagnostic.py'),
                            str(pid), cmd_display,
                            f"Timeout after {elapsed:.0f}s (limit {timeout}s)",
                            str(elapsed)
                        ], timeout=5)
                    except Exception:
                        pass

                    # Graceful SIGTERM first
                    try:
                        os.kill(pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass

                    sys.stderr.write(f"[event] subagent_crashed pid={pid}\n")
                    sys.stderr.flush()

                    time.sleep(2)
                    try:
                        os.kill(pid, 0)
                        os.kill(pid, signal.SIGKILL)
                        sys.stderr.write(f"[watchdog] Force-killed PID {pid}\n")
                        sys.stderr.flush()
                    except ProcessLookupError:
                        pass

        except Exception as e:
            sys.stderr.write(f"[watchdog] Error: {e}\n")
            sys.stderr.flush()
            time.sleep(WATCHDOG_POLL_INTERVAL)


# ---------------------------------------------------------------------------
# Startup health checks -- verify services before entering main loop
# ---------------------------------------------------------------------------

async def _check_service(name: str, url: str, max_retries: int) -> bool:
    """Check a single service endpoint with exponential backoff.

    Returns True if the service responded with HTTP 200, False otherwise.
    Retries up to *max_retries* times with delays of 2s, 4s, 8s, ...
    """
    loop = asyncio.get_running_loop()

    for attempt in range(1, max_retries + 1):
        try:
            def _probe():
                req = urllib.request.Request(url, method="GET")
                with urllib.request.urlopen(req, timeout=5) as resp:
                    return resp.status

            status = await loop.run_in_executor(None, _probe)
            if status == 200:
                diag("STARTUP_CHECK", f"{name} OK (attempt {attempt}/{max_retries})")
                return True
            else:
                diag("STARTUP_CHECK", f"{name} unexpected status {status} (attempt {attempt}/{max_retries})")
        except (urllib.error.URLError, urllib.error.HTTPError, OSError, Exception) as e:
            diag("STARTUP_CHECK", f"{name} unreachable: {e} (attempt {attempt}/{max_retries})")

        if attempt < max_retries:
            backoff = 2 ** attempt  # 2s, 4s, 8s, ...
            diag("STARTUP_CHECK", f"{name} retrying in {backoff}s")
            await asyncio.sleep(backoff)

    return False


async def startup_health_checks():
    """Run pre-flight health checks for dependent services.

    Called once after MCP servers are configured but before the main
    message loop begins.  Each service is checked independently --
    failures are logged as warnings but never block startup so the
    system can operate with degraded functionality.
    """
    diag("STARTUP_CHECK", "Beginning startup health checks")

    services = [
        ("Device API", f"{DEVICE_API_URL}/health", 5),
        ("RAG",        f"{RAG_ENDPOINT}/health",       3),
    ]

    results = {}
    for name, url, retries in services:
        ok = await _check_service(name, url, retries)
        results[name] = ok
        if not ok:
            diag("STARTUP_CHECK", f"WARNING: {name} did not become healthy after {retries} attempts -- continuing with degraded functionality")

    healthy = [n for n, ok in results.items() if ok]
    degraded = [n for n, ok in results.items() if not ok]
    summary = f"healthy=[{', '.join(healthy)}] degraded=[{', '.join(degraded)}]"
    diag("STARTUP_CHECK", f"Health checks complete: {summary}")

    write_system("startup_health", {
        "results": {name: ok for name, ok in results.items()},
        "healthy": healthy,
        "degraded": degraded,
    })


# ---------------------------------------------------------------------------
# MCP & Tier 2 health monitor -- background async task
# ---------------------------------------------------------------------------

MCP_HEALTH_INTERVAL = 30        # Seconds between health checks
MCP_MAX_RESTARTS = 3            # Max restart attempts before giving up
MCP_BACKOFF_SCHEDULE = [5, 15, 30]  # Exponential backoff between restarts (seconds)
TIER2_PORT = MCP_TIER2_PORT     # Tier 2 (device_api_mcp.py) HTTP port


def _is_pid_alive(pid: int) -> bool:
    """Check if a process is alive by probing /proc/<pid>/stat."""
    try:
        with open(f'/proc/{pid}/stat', 'r') as f:
            f.read(1)
        return True
    except (FileNotFoundError, IOError, OSError, PermissionError):
        return False


def _read_mcp_pid() -> int | None:
    """Read the MCP server PID from its PID file. Returns None if missing/invalid."""
    pid_file = os.path.join(HOME_DIR, '.mcp_server.pid')
    try:
        with open(pid_file, 'r') as f:
            return int(f.read().strip())
    except (FileNotFoundError, ValueError, OSError):
        return None


def _check_tier2_health(timeout: float = 5.0) -> bool:
    """HTTP health check for Tier 2 server. Returns True if healthy."""
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{TIER2_PORT}/health", method="GET"
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False


def _launch_mcp_server() -> subprocess.Popen | None:
    """Launch the unified_device_mcp.py MCP server as a detached process.

    Returns the Popen object on success, None on failure.
    Note: When launched standalone (not via Claude CLI stdio), the MCP server
    will not be connected to the CLI.  This restart is a best-effort attempt
    to restore the process so Claude can be informed to reconnect.
    """
    mcp_script = os.path.join(HOME_DIR, 'unified_device_mcp.py')
    python_bin = os.path.join(os.path.dirname(HOME_DIR), 'usr', 'bin', 'python3')
    if not os.path.isfile(python_bin):
        python_bin = sys.executable

    if not os.path.isfile(mcp_script):
        diag("MCP_RESTART_FAIL", f"MCP script not found: {mcp_script}")
        return None

    try:
        env = os.environ.copy()
        env["PYTHONUNBUFFERED"] = "1"
        proc = subprocess.Popen(
            [python_bin, mcp_script],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=env,
            start_new_session=True,
        )
        diag("MCP_RESTART", f"Launched MCP server pid={proc.pid}")
        return proc
    except Exception as e:
        diag("MCP_RESTART_FAIL", f"Failed to launch MCP server: {e}")
        return None


def _launch_tier2_server() -> subprocess.Popen | None:
    """Launch the device_api_mcp.py Tier 2 server as a detached process.

    Returns the Popen object on success, None on failure.
    """
    tier2_script = os.path.join(HOME_DIR, 'device_api_mcp.py')
    python_bin = os.path.join(os.path.dirname(HOME_DIR), 'usr', 'bin', 'python3')
    if not os.path.isfile(python_bin):
        python_bin = sys.executable

    if not os.path.isfile(tier2_script):
        diag("MCP_RESTART_FAIL", f"Tier 2 script not found: {tier2_script}")
        return None

    try:
        env = os.environ.copy()
        env["PYTHONUNBUFFERED"] = "1"
        proc = subprocess.Popen(
            [python_bin, tier2_script],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            env=env,
            start_new_session=True,
        )
        diag("MCP_RESTART", f"Launched Tier 2 server pid={proc.pid}")
        return proc
    except Exception as e:
        diag("MCP_RESTART_FAIL", f"Failed to launch Tier 2 server: {e}")
        return None


async def mcp_health_monitor():
    """Background task: periodically check MCP server and Tier 2 liveness.

    Runs alongside the main loop in run_agent().  Every MCP_HEALTH_INTERVAL
    seconds it:
      - Reads ~/.mcp_server.pid and checks /proc/<pid>/stat for the MCP server
      - HTTP GETs http://127.0.0.1:5564/health for Tier 2
    If either is dead, it attempts up to MCP_MAX_RESTARTS restarts with
    exponential backoff (5s, 15s, 30s).  All events go through diag().
    """
    loop = asyncio.get_running_loop()

    mcp_restart_count = 0
    tier2_restart_count = 0
    mcp_gave_up = False
    tier2_gave_up = False

    # Track the last-known MCP PID so we detect new deaths even if the PID
    # file hasn't changed after a successful restart.
    last_known_mcp_pid: int | None = None

    diag("MCP_HEALTH_OK", "MCP health monitor started")

    while True:
        try:
            await asyncio.sleep(MCP_HEALTH_INTERVAL)

            # --- MCP server (unified_device_mcp.py, stdio-based) ---
            if not mcp_gave_up:
                mcp_pid = _read_mcp_pid()
                if mcp_pid is not None:
                    alive = await loop.run_in_executor(None, _is_pid_alive, mcp_pid)
                    if alive:
                        # Healthy -- reset restart counter on sustained health
                        if mcp_restart_count > 0:
                            diag("MCP_RESTART_OK", f"MCP server recovered (pid={mcp_pid})")
                            mcp_restart_count = 0
                        last_known_mcp_pid = mcp_pid
                    else:
                        diag("MCP_DEAD", f"MCP server pid={mcp_pid} is not alive")
                        if mcp_restart_count < MCP_MAX_RESTARTS:
                            backoff = MCP_BACKOFF_SCHEDULE[min(mcp_restart_count, len(MCP_BACKOFF_SCHEDULE) - 1)]
                            diag("MCP_RESTART", f"Attempting MCP restart {mcp_restart_count + 1}/{MCP_MAX_RESTARTS} after {backoff}s backoff")
                            await asyncio.sleep(backoff)
                            proc = await loop.run_in_executor(None, _launch_mcp_server)
                            mcp_restart_count += 1
                            if proc is not None:
                                # Give it a moment to write its PID file
                                await asyncio.sleep(2)
                                new_pid = _read_mcp_pid()
                                if new_pid and _is_pid_alive(new_pid):
                                    diag("MCP_RESTART_OK", f"MCP server restarted successfully (pid={new_pid})")
                                    last_known_mcp_pid = new_pid
                                    mcp_restart_count = 0  # Reset on success
                                else:
                                    diag("MCP_RESTART_FAIL", f"MCP server restart attempt {mcp_restart_count} -- process did not stabilize")
                            else:
                                diag("MCP_RESTART_FAIL", f"MCP server restart attempt {mcp_restart_count} -- launch failed")
                        else:
                            diag("MCP_RESTART_FAIL", f"MCP server: exhausted {MCP_MAX_RESTARTS} restart attempts -- giving up")
                            mcp_gave_up = True
                elif last_known_mcp_pid is not None:
                    # PID file disappeared but we had a known PID -- check if it's still alive
                    alive = await loop.run_in_executor(None, _is_pid_alive, last_known_mcp_pid)
                    if not alive:
                        diag("MCP_DEAD", f"MCP server pid={last_known_mcp_pid} gone and PID file missing")
                        last_known_mcp_pid = None
                        # Attempt restart
                        if mcp_restart_count < MCP_MAX_RESTARTS:
                            backoff = MCP_BACKOFF_SCHEDULE[min(mcp_restart_count, len(MCP_BACKOFF_SCHEDULE) - 1)]
                            diag("MCP_RESTART", f"Attempting MCP restart {mcp_restart_count + 1}/{MCP_MAX_RESTARTS} after {backoff}s backoff")
                            await asyncio.sleep(backoff)
                            proc = await loop.run_in_executor(None, _launch_mcp_server)
                            mcp_restart_count += 1
                            if proc is not None:
                                await asyncio.sleep(2)
                                new_pid = _read_mcp_pid()
                                if new_pid and _is_pid_alive(new_pid):
                                    diag("MCP_RESTART_OK", f"MCP server restarted successfully (pid={new_pid})")
                                    last_known_mcp_pid = new_pid
                                    mcp_restart_count = 0
                                else:
                                    diag("MCP_RESTART_FAIL", f"MCP server restart attempt {mcp_restart_count} -- process did not stabilize")
                            else:
                                diag("MCP_RESTART_FAIL", f"MCP server restart attempt {mcp_restart_count} -- launch failed")
                        else:
                            diag("MCP_RESTART_FAIL", f"MCP server: exhausted {MCP_MAX_RESTARTS} restart attempts -- giving up")
                            mcp_gave_up = True

            # --- Tier 2 server (device_api_mcp.py, HTTP port 5564) ---
            if not tier2_gave_up:
                tier2_alive = await loop.run_in_executor(None, _check_tier2_health)
                if tier2_alive:
                    if tier2_restart_count > 0:
                        diag("MCP_RESTART_OK", f"Tier 2 server recovered (port={TIER2_PORT})")
                        tier2_restart_count = 0
                else:
                    diag("MCP_DEAD", f"Tier 2 server (port {TIER2_PORT}) health check failed")
                    if tier2_restart_count < MCP_MAX_RESTARTS:
                        backoff = MCP_BACKOFF_SCHEDULE[min(tier2_restart_count, len(MCP_BACKOFF_SCHEDULE) - 1)]
                        diag("MCP_RESTART", f"Attempting Tier 2 restart {tier2_restart_count + 1}/{MCP_MAX_RESTARTS} after {backoff}s backoff")
                        await asyncio.sleep(backoff)
                        proc = await loop.run_in_executor(None, _launch_tier2_server)
                        tier2_restart_count += 1
                        if proc is not None:
                            # Give FastAPI a few seconds to bind the port
                            await asyncio.sleep(3)
                            if _check_tier2_health(timeout=5.0):
                                diag("MCP_RESTART_OK", f"Tier 2 server restarted successfully (pid={proc.pid})")
                                tier2_restart_count = 0
                            else:
                                diag("MCP_RESTART_FAIL", f"Tier 2 restart attempt {tier2_restart_count} -- server did not become healthy")
                        else:
                            diag("MCP_RESTART_FAIL", f"Tier 2 restart attempt {tier2_restart_count} -- launch failed")
                    else:
                        diag("MCP_RESTART_FAIL", f"Tier 2 server: exhausted {MCP_MAX_RESTARTS} restart attempts -- giving up")
                        tier2_gave_up = True

            # Periodic healthy summary (only when both are fine)
            if not mcp_gave_up and not tier2_gave_up:
                mcp_pid = _read_mcp_pid()
                mcp_ok = mcp_pid is not None and _is_pid_alive(mcp_pid)
                tier2_ok = _check_tier2_health(timeout=3.0)
                if mcp_ok and tier2_ok:
                    diag("MCP_HEALTH_OK", f"All servers healthy (mcp_pid={mcp_pid}, tier2_port={TIER2_PORT})")

        except asyncio.CancelledError:
            diag("MCP_HEALTH_OK", "MCP health monitor cancelled -- shutting down")
            return
        except Exception as e:
            sys.stderr.write(f"[mcp-health] Unexpected error: {e}\n")
            sys.stderr.flush()
            # Don't crash the monitor; continue next cycle
            await asyncio.sleep(MCP_HEALTH_INTERVAL)


# ---------------------------------------------------------------------------
# Main agent loop
# ---------------------------------------------------------------------------

async def run_agent():
    """Main async agent loop using ClaudeSDKClient."""
    try:
        from claude_agent_sdk import (
            ClaudeSDKClient,
            ClaudeAgentOptions,
            ResultMessage,
        )
        from claude_agent_sdk.types import StreamEvent
    except ImportError as e:
        write_error(
            "claude_agent_sdk not installed",
            f"Install with: pip install claude-agent-sdk. Error: {e}"
        )
        return

    # Determine CLI path
    cli_path = None
    possible_paths = [
        Path(HOME_DIR) / ".local" / "bin" / "claude",
        Path(PREFIX_DIR) / "bin" / "claude",
        Path(HOME_DIR) / ".npm-global" / "bin" / "claude",
    ]
    for p in possible_paths:
        if p.exists():
            cli_path = str(p)
            break

    options = ClaudeAgentOptions(
        system_prompt=SYSTEM_PROMPT,
        allowed_tools=["Bash", "Read", "Write", "Glob"],
        permission_mode="bypassPermissions",
        include_partial_messages=True,
        cwd=Path(HOME_DIR),
        mcp_servers={
            "unified-device": {
                "command": f"{HOME_DIR}/../usr/bin/python3",
                "args": [f"{HOME_DIR}/unified_device_mcp.py"],
                "env": {
                    "LD_LIBRARY_PATH": os.environ.get("LD_LIBRARY_PATH", ""),
                    "LD_PRELOAD": os.environ.get("LD_PRELOAD", ""),
                    "HOME": HOME_DIR,
                    "PATH": os.environ.get("PATH", ""),
                    "PYTHONUNBUFFERED": "1",
                    "TMPDIR": os.environ.get("TMPDIR", "/tmp"),
                },
            },
        },
        stderr=lambda line: sys.stderr.write(f"[claude-cli] {line}\n"),
        env={
            "NO_COLOR": "1",
            "FORCE_COLOR": "0",
            "CI": "1",
            "TERM": "dumb",
            "COLUMNS": "80",
            "LINES": "24",
            "CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK": "1",
            "CLAUDE_CODE_STREAM_CLOSE_TIMEOUT": "120000",
            "NODE_OPTIONS": f"-r {HOME_DIR}/fs-guard.js",  # Prevents Android FUSE deadlocks
            "LD_LIBRARY_PATH": os.path.join(os.path.dirname(HOME_DIR), "usr", "lib"),
            "MCP_CONNECTION_NONBLOCKING": "1",
            "BASH_ENV": f"{HOME_DIR}/.bashrc",
        },
        extra_args={
            "debug-to-stderr": None,
        },
    )

    if cli_path:
        options.cli_path = cli_path
        sys.stderr.write(f"[orchestrator] Using CLI at: {cli_path}\n")
        sys.stderr.flush()

    diag("ORCH_STARTED", f"cli={cli_path} mcp_servers={list(options.mcp_servers.keys())} rag={RAG_ENDPOINT}")
    write_system("initializing", {"cli_path": cli_path, "rag_endpoint": RAG_ENDPOINT})

    current_session_id = "default"

    try:
        async with ClaudeSDKClient(options=options) as client:
            write_system("ready", {
                "status": "connected",
                "orchestrator_version": ORCHESTRATOR_VERSION,
                "build_timestamp": BUILD_TIMESTAMP,
            })

            # Run startup health checks before entering the main loop
            await startup_health_checks()

            # Phase 9: Vault client for credential access — calls VaultHttpServer at :5565
            _vault_client = VaultClient()
            # Priority injection
            priority_injector = PriorityInjector(
                rag_url=RAG_URL,
                vault_client=_vault_client,
            )
            exchange_count = 0
            _session_start_time = None

            # Phase 5: Napkin Dab — mid-session cleanup monitor
            _napkin_buffer = MessageBuffer(max_size=200)
            _napkin_dab = NapkinDab(
                buffer=_napkin_buffer,
                threshold=50,
                keep_last_n=100,
            )

            # Background task: receive and forward messages
            async def receive_loop():
                try:
                    async for msg in client.receive_messages():
                        msg_type = type(msg).__name__
                        tool_names = ""
                        try:
                            if hasattr(msg, 'content'):
                                tools = [b.name for b in msg.content if hasattr(b, 'name')]
                                if tools:
                                    tool_names = f" tools={tools}"
                        except Exception:
                            pass
                        diag("ORCH_SDK_MSG", f"type={msg_type}{tool_names}")
                        json_msg = sdk_message_to_json(msg)
                        if json_msg:
                            # Phase 5: Strip memory tags before forwarding to Android UI
                            if json_msg.get("type") == "assistant":
                                try:
                                    json_msg = await _extract_and_persist_tags(json_msg, RAG_ENDPOINT)
                                except Exception as _tag_err:
                                    diag("MEMORY_TAG", f"Tag extraction failed (non-fatal): {_tag_err}")
                            write_json(json_msg)
                            # Track session ID from results
                            if isinstance(msg, ResultMessage) and msg.session_id:
                                nonlocal current_session_id
                                current_session_id = msg.session_id
                            # Phase 5: Feed assistant messages into NapkinDab buffer
                            if json_msg.get("type") == "assistant":
                                try:
                                    _napkin_buffer.add({
                                        "role": "assistant",
                                        "content": json_msg,
                                    })
                                except Exception:
                                    pass
                except Exception as e:
                    diag("ORCH_ERROR", f"receive_loop: {e}")
                    sys.stderr.write(f"[orchestrator] Receive loop error: {e}\n")
                    sys.stderr.flush()

            # Start receive loop as a background task
            receive_task = asyncio.create_task(receive_loop())

            # Active query task reference (for cancellation support)
            active_query_task: asyncio.Task | None = None

            def _on_query_done(task: asyncio.Task):
                """Callback fired when a background client.query() completes or fails."""
                nonlocal active_query_task
                active_query_task = None
                try:
                    exc = task.exception()
                    if exc:
                        error_msg = f"Query failed: {exc}"
                        diag("ORCH_QUERY_ERROR", error_msg)
                        write_error(error_msg)
                except asyncio.CancelledError:
                    diag("ORCH_QUERY_CANCELLED", "Query task was cancelled")
                    write_system("query_cancelled")

                # Phase 5: Napkin Dab — check AFTER response is sent, not before
                # Fires on threshold multiples (e.g. exchange 50, 100, 150, ...)
                try:
                    if _napkin_dab.should_fire(exchange_count):
                        diag("NAPKIN_DAB", f"Threshold reached at exchange {exchange_count} — firing")
                        _napkin_dab.fire(exchange_count, priority_injector=priority_injector)
                except Exception as _nd_err:
                    diag("NAPKIN_DAB", f"NapkinDab check error (non-fatal): {_nd_err}")

            # Alert queue: bridges watchdog thread -> async conversation
            alert_queue = asyncio.Queue()
            global _event_loop, _alert_queue_ref
            _event_loop = asyncio.get_running_loop()
            _alert_queue_ref = alert_queue

            def _cli_has_active_grandchildren() -> bool:
                """Check if the CLI process currently has active grandchildren (tool calls)."""
                try:
                    my_pid = os.getpid()
                    cli_pids = _find_children(my_pid, cmd_filter="claude")
                    for cli_pid in cli_pids:
                        grandchildren = _find_children(cli_pid)
                        if grandchildren:
                            return True
                except Exception:
                    pass
                return False

            async def _flush_pending_alerts():
                """Flush any queued alerts that were deferred during tool processing."""
                global _pending_alerts
                with _pending_alerts_lock:
                    alerts_to_send = list(_pending_alerts)
                    _pending_alerts.clear()
                if alerts_to_send:
                    sys.stderr.write(
                        f"[orchestrator] Flushing {len(alerts_to_send)} pending alert(s)\n"
                    )
                    sys.stderr.flush()
                    for alert in alerts_to_send:
                        write_system("watchdog_alert", {"message": alert})
                        await client.query(alert, session_id=current_session_id)

            async def alert_consumer():
                """Consume watchdog alerts and inject them into Claude's conversation.

                If the CLI has active grandchildren (processing a tool call), the alert
                is deferred into _pending_alerts to avoid a race condition.  Deferred
                alerts are flushed by the watchdog when grandchildren drop to zero.
                """
                try:
                    while True:
                        alert = await alert_queue.get()
                        # Race-condition guard: don't inject while CLI is processing a tool call
                        if _cli_has_active_grandchildren():
                            sys.stderr.write(
                                "[orchestrator] CLI busy (active grandchildren) -- "
                                "deferring alert to _pending_alerts\n"
                            )
                            sys.stderr.flush()
                            with _pending_alerts_lock:
                                _pending_alerts.append(alert)
                            continue
                        diag("WATCHDOG_ALERT", f"injecting: {alert[:100]}")
                        sys.stderr.write(
                            "[orchestrator] Injecting watchdog alert into conversation\n"
                        )
                        sys.stderr.flush()
                        # Notify Android UI
                        write_system("watchdog_alert", {"message": alert})
                        # Inject alert as user message so Claude sees it
                        await client.query(alert, session_id=current_session_id)
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    sys.stderr.write(f"[orchestrator] Alert consumer error: {e}\n")
                    sys.stderr.flush()

            alert_task = asyncio.create_task(alert_consumer())

            # Start MCP/Tier 2 health monitor as background task
            health_monitor_task = asyncio.create_task(mcp_health_monitor())

            # Main loop: read from stdin
            while True:
                # Read next command from stdin (blocking via executor)
                input_data = await asyncio.get_event_loop().run_in_executor(
                    None, read_json_line
                )

                if input_data is None:
                    # stdin closed (Android process stopped)
                    sys.stderr.write("[orchestrator] stdin closed, shutting down\n")
                    sys.stderr.flush()
                    if active_query_task and not active_query_task.done():
                        active_query_task.cancel()
                    receive_task.cancel()
                    alert_task.cancel()
                    health_monitor_task.cancel()

                    # Phase 5: Archive session (best-effort)
                    if _session_start_time and exchange_count > 0:
                        try:
                            transcript_parts = []
                            for m in _napkin_buffer.get_recent(200):
                                role = m.get("role", "?")
                                c = m.get("content", "")
                                if isinstance(c, dict):
                                    blocks = c.get("message", {}).get("content", [])
                                    text = " ".join(
                                        b.get("text", "") for b in blocks
                                        if isinstance(b, dict) and b.get("type") == "text"
                                    )
                                    c = text or str(c)[:200]
                                elif not isinstance(c, str):
                                    c = str(c)[:200]
                                transcript_parts.append(f"[{role}] {c[:300]}")
                            transcript = "\n".join(transcript_parts)

                            archive_payload = json.dumps({
                                "session_id": current_session_id,
                                "start_time": int(_session_start_time * 1000),
                                "end_time": int(time.time() * 1000),
                                "message_count": exchange_count,
                                "transcript": transcript[:8000],
                            }).encode("utf-8")
                            archive_req = urllib.request.Request(
                                f"{RAG_ENDPOINT}/archive_session",
                                data=archive_payload,
                                headers={"Content-Type": "application/json"},
                                method="POST",
                            )
                            urllib.request.urlopen(archive_req, timeout=10)
                            diag("SESSION_ARCHIVE", f"Archived session {current_session_id} ({exchange_count} exchanges)")
                        except Exception as e:
                            diag("SESSION_ARCHIVE", f"Archival failed (non-fatal): {e}")

                    # Phase 13: Final TriggerLearner pass
                    try:
                        promoted = priority_injector.run_learn_pass()
                        if promoted > 0:
                            diag("TRIGGER_LEARN", f"Final learn pass promoted {promoted} triggers")
                    except Exception as e:
                        diag("TRIGGER_LEARN", f"Final learn pass failed (non-fatal): {e}")

                    await priority_injector.close()
                    return

                msg_type = input_data.get("type", "")
                preview = str(input_data)[:120]
                diag("ORCH_MSG_RECEIVED", f"type={msg_type} preview={preview}")

                if msg_type == "user":
                    # Extract user message content
                    message = input_data.get("message", {})
                    content = message.get("content", "")
                    session_id = input_data.get("session_id", current_session_id)

                    if content:
                        # Phase 5: Feed user message into NapkinDab buffer
                        try:
                            _napkin_buffer.add({"role": "user", "content": content})
                        except Exception:
                            pass

                        # Priority injection
                        exchange_count += 1
                        if _session_start_time is None:
                            _session_start_time = time.time()
                        try:
                            enriched_message = await priority_injector.inject(content, exchange_count)
                        except Exception as e:
                            diag("injection_error", str(e))
                            enriched_message = content  # fail-through: use original message

                        # Cancel any in-flight query before starting a new one
                        if active_query_task and not active_query_task.done():
                            diag("ORCH_QUERY_SUPERSEDED", "Cancelling previous query for new request")
                            active_query_task.cancel()

                        diag("ORCH_QUERY_SENT", f"session={session_id} len={len(enriched_message)} preview={enriched_message[:80]}")

                        # Fire-and-forget: launch query as background task so stdin
                        # loop remains responsive for interrupt/control messages.
                        # Responses flow back through receive_loop() automatically.
                        active_query_task = asyncio.create_task(
                            client.query(enriched_message, session_id=session_id)
                        )
                        active_query_task.add_done_callback(_on_query_done)

                        # Immediate acknowledgment so the UI isn't frozen
                        write_system("query_started", {
                            "preview": content[:80],
                            "session_id": session_id,
                        })
                    else:
                        write_error("Empty message content")

                elif msg_type == "control":
                    action = input_data.get("action", "")
                    if action == "interrupt":
                        # Cancel the background query task if running
                        if active_query_task and not active_query_task.done():
                            active_query_task.cancel()
                            diag("ORCH_INTERRUPT", "Cancelled active query task")
                        await client.interrupt()
                        write_system("interrupted")
                    elif action == "stop":
                        if active_query_task and not active_query_task.done():
                            active_query_task.cancel()
                        receive_task.cancel()
                        alert_task.cancel()
                        health_monitor_task.cancel()
                        return
                    else:
                        write_error(f"Unknown control action: {action}")

                elif msg_type == "resume":
                    write_error("Resume requires orchestrator restart (single-connection mode)")

                else:
                    write_error(f"Unknown message type: {msg_type}")

    except KeyboardInterrupt:
        write_system("shutdown", {"reason": "keyboard_interrupt"})

    except Exception as e:
        error_msg = f"Agent error: {e}"
        diag("ORCH_ERROR", f"{error_msg}")
        sys.stderr.write(f"[orchestrator] {error_msg}\n")
        sys.stderr.write(traceback.format_exc())
        sys.stderr.flush()
        write_error(error_msg, traceback.format_exc())
        sys.exit(1)


# ---------------------------------------------------------------------------
# Process group cleanup
# ---------------------------------------------------------------------------

def kill_process_group():
    """Kill all processes in this process group (including SDK-spawned claude CLIs)."""
    try:
        pgid = os.getpgid(0)
        sys.stderr.write(f"[orchestrator] Killing process group {pgid}\n")
        sys.stderr.flush()
        os.killpg(pgid, signal.SIGTERM)
    except Exception as e:
        sys.stderr.write(f"[orchestrator] Error killing process group: {e}\n")
        sys.stderr.flush()

    # Fallback: kill direct children via pkill
    try:
        import subprocess
        subprocess.run(
            ["pkill", "-P", str(os.getpid())],
            capture_output=True, timeout=5
        )
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    """Entry point -- set up signal handlers and run the async agent."""
    sys.stderr.write(f"Orchestrator v{ORCHESTRATOR_VERSION} starting\n")
    sys.stderr.write("[orchestrator] Starting mK:a orchestrator\n")
    sys.stderr.flush()
    diag("ORCH_STARTED", f"v{ORCHESTRATOR_VERSION} pid={os.getpid()}")

    # Become process group leader so we can kill all descendants on exit (Unix only)
    if hasattr(os, 'setpgrp'):
        try:
            os.setpgrp()
            sys.stderr.write("[orchestrator] Became process group leader\n")
            sys.stderr.flush()
        except OSError as e:
            sys.stderr.write(f"[orchestrator] Warning: Could not become process group leader: {e}\n")
            sys.stderr.flush()
    else:
        sys.stderr.write("[orchestrator] Skipping setpgrp (not available on Windows)\n")
        sys.stderr.flush()

    # Handle graceful shutdown signals
    def handle_signal(signum, frame):
        # Prevent re-entry: ignore signals before calling kill_process_group()
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        sys.stderr.write(f"[orchestrator] Received signal {signum}, shutting down\n")
        sys.stderr.flush()
        write_system("shutdown", {"reason": f"signal_{signum}"})
        kill_process_group()
        sys.exit(0)

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    # Register cleanup handler for normal exit
    import atexit
    atexit.register(kill_process_group)

    # Pre-flight: check MCP service ports before startup
    # Warn only -- the occupant might be a legitimate prior instance
    for port in (DEVICE_API_PORT, RAG_PORT):
        if not check_port_available(port):
            sys.stderr.write(
                f"[orchestrator] WARNING: port {port} already in use "
                f"(prior instance or legitimate service?)\n"
            )
            sys.stderr.flush()

    # Start watchdog thread (the "second lane" -- runs independently of main pipe)
    watchdog = threading.Thread(target=_watchdog_loop, daemon=True, name="watchdog")
    watchdog.start()

    try:
        asyncio.run(run_agent())
    except KeyboardInterrupt:
        pass
    except Exception as e:
        diag("ORCH_ERROR", f"fatal: {e}")
        write_error(f"Fatal error: {e}", traceback.format_exc())
        sys.exit(1)

    sys.stderr.write("[orchestrator] Orchestrator exiting\n")
    sys.stderr.flush()


if __name__ == "__main__":
    main()
