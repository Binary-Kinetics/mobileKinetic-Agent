#!/data/user/0/com.mobilekinetic.agent/files/usr/bin/python3
"""
MCP Shell Server for mK:a (Android/Termux)
==================================================
Provides reliable shell access to Claude sub-agents via JSON-RPC 2.0 over stdio.

Each command runs in a FRESH bash subprocess with .bashrc sourced, bypassing
broken shell snapshots that lose function definitions.

Transport: stdio (read JSON-RPC from stdin line-by-line, write JSON to stdout)
Protocol: MCP (Model Context Protocol) v2024-11-05

Tools:
  - shell_exec: Execute shell commands with .bashrc sourced
  - grep_search: Search files using grep (no ripgrep on ARM64 Android)
  - find_files: Find files by name pattern
  - list_directory: List directory contents
  - read_file: Read file contents with line numbers

Debug log: ~/mcp_shell_debug.log (auto-rotated at 500KB)
"""

import json
import socket
import sys
import subprocess
import os
import time
from datetime import datetime, timezone

from mka_config import DEVICE_API_PORT, MCP_TIER2_PORT

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SERVER_NAME = "mcp-shell-server"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"

DEBUG_LOG_PATH = os.path.expanduser("~/mcp_shell_debug.log")
DEBUG_LOG_MAX_BYTES = 500 * 1024       # 500 KB
DEBUG_LOG_KEEP_BYTES = 250 * 1024      # keep last 250 KB on rotation

BASHRC_PATH = os.path.expanduser("~/.bashrc")

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

def check_port_available(port):
    """Check if a port is available; if occupied, try to kill the old process.

    Uses socket bind test to check availability. If the port is in use,
    attempts to find and kill the occupying process via lsof or fuser.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.bind(("127.0.0.1", port))
        sock.close()
        diag("PORT_CHECK", f"port {port} is available")
        return True
    except OSError:
        sock.close()
        diag("PORT_CHECK", f"port {port} is in use, attempting to free it")

    # Try lsof first, then fuser as fallback
    pid = None
    for cmd in [f"lsof -ti :{port}", f"fuser {port}/tcp 2>/dev/null"]:
        try:
            result = subprocess.run(
                ["bash", "-c", cmd],
                capture_output=True, text=True, timeout=5
            )
            output = result.stdout.strip()
            if output:
                # lsof/fuser may return multiple PIDs; take the first
                pid = int(output.split()[0])
                break
        except (subprocess.TimeoutExpired, ValueError, Exception):
            continue

    if pid is None:
        diag("PORT_CHECK", f"port {port} occupied but could not identify PID")
        return False

    diag("PORT_CHECK", f"port {port} occupied by PID {pid}, sending SIGTERM")
    try:
        os.kill(pid, 15)  # SIGTERM
        time.sleep(1)
        # Check if it's still alive
        try:
            os.kill(pid, 0)
            diag("PORT_CHECK", f"PID {pid} still alive, sending SIGKILL")
            os.kill(pid, 9)  # SIGKILL
            time.sleep(0.5)
        except ProcessLookupError:
            pass  # Already dead
        diag("PORT_CHECK", f"killed old process {pid} on port {port}")
        return True
    except ProcessLookupError:
        diag("PORT_CHECK", f"PID {pid} already exited")
        return True
    except PermissionError:
        diag("PORT_CHECK", f"no permission to kill PID {pid} on port {port}")
        return False


DEFAULT_TIMEOUT = 30
DEFAULT_READ_LIMIT = 200
DEFAULT_HEAD_LIMIT_GREP = 100
DEFAULT_HEAD_LIMIT_FIND = 50

# ---------------------------------------------------------------------------
# Debug Logging
# ---------------------------------------------------------------------------

def _rotate_log_if_needed():
    """If the debug log exceeds 500KB, truncate to the last 250KB."""
    try:
        if not os.path.exists(DEBUG_LOG_PATH):
            return
        size = os.path.getsize(DEBUG_LOG_PATH)
        if size <= DEBUG_LOG_MAX_BYTES:
            return
        with open(DEBUG_LOG_PATH, "rb") as f:
            f.seek(-DEBUG_LOG_KEEP_BYTES, 2)
            tail = f.read()
        with open(DEBUG_LOG_PATH, "wb") as f:
            f.write(b"--- LOG ROTATED ---\n")
            f.write(tail)
    except Exception:
        pass  # never crash on log rotation


def _log_debug(tool_name, arguments, command_built, stdout_text, stderr_text,
               exit_code, duration_ms, exception=None):
    """Append a structured debug entry to the log file."""
    _rotate_log_if_needed()
    try:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        stdout_trunc = stdout_text
        if stdout_text and len(stdout_text) > 500:
            stdout_trunc = stdout_text[:500] + f"\n... [truncated, {len(stdout_text)} chars total]"

        entry = (
            f"{'=' * 72}\n"
            f"TIMESTAMP   : {ts}\n"
            f"TOOL        : {tool_name}\n"
            f"ARGUMENTS   : {json.dumps(arguments, ensure_ascii=False)}\n"
            f"COMMAND     : {command_built}\n"
            f"EXIT CODE   : {exit_code}\n"
            f"DURATION    : {duration_ms} ms\n"
            f"STDOUT      :\n{stdout_trunc}\n"
            f"STDERR      :\n{stderr_text}\n"
        )
        if exception:
            entry += f"EXCEPTION   : {exception}\n"
        entry += f"{'=' * 72}\n\n"

        with open(DEBUG_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(entry)
    except Exception:
        pass  # never crash on logging


# ---------------------------------------------------------------------------
# Subprocess Execution Helper
# ---------------------------------------------------------------------------

def _run_command(command, timeout=DEFAULT_TIMEOUT, working_dir=None):
    """
    Run a command in a fresh bash subprocess with .bashrc sourced.

    Returns: (stdout: str, stderr: str, exit_code: int, duration_ms: int)
    """
    # Wrap the command so .bashrc is sourced first
    wrapped = f"source ~/.bashrc 2>/dev/null; {command}"

    env = os.environ.copy()
    env["BASH_ENV"] = BASHRC_PATH

    cwd = working_dir if working_dir else None

    diag("SHELL_EXEC", f"cmd={command[:120]} timeout={timeout}s cwd={cwd}")

    start = time.monotonic()
    try:
        proc = subprocess.run(
            ["bash", "-c", wrapped],
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
            env=env,
        )
        duration_ms = int((time.monotonic() - start) * 1000)
        diag("SHELL_RESULT", f"exit={proc.returncode} stdout={len(proc.stdout)}B stderr={len(proc.stderr)}B dur={duration_ms}ms")
        return proc.stdout, proc.stderr, proc.returncode, duration_ms

    except subprocess.TimeoutExpired:
        duration_ms = int((time.monotonic() - start) * 1000)
        diag("SHELL_TIMEOUT", f"cmd={command[:80]} timeout={timeout}s dur={duration_ms}ms")
        return "", f"Command timed out after {timeout}s", -1, duration_ms

    except Exception as exc:
        duration_ms = int((time.monotonic() - start) * 1000)
        diag("SHELL_ERROR", f"cmd={command[:80]} error={exc}")
        return "", f"Subprocess error: {exc}", -1, duration_ms


def _guarded_exec(command, timeout=DEFAULT_TIMEOUT, working_dir=None):
    """
    Convenience wrapper that also returns the built command string for logging.
    Routes through _run_command (bash -c with .bashrc).
    """
    return command, *_run_command(command, timeout=timeout, working_dir=working_dir)


# ---------------------------------------------------------------------------
# Tool Implementations
# ---------------------------------------------------------------------------

def tool_shell_exec(arguments):
    """Execute a shell command with .bashrc properly sourced."""
    command = arguments.get("command")
    if not command:
        return _error_result("Missing required parameter: command")

    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    working_dir = arguments.get("working_dir")

    cmd_built, stdout, stderr, exit_code, duration_ms = _guarded_exec(
        command, timeout=timeout, working_dir=working_dir
    )

    _log_debug("shell_exec", arguments, cmd_built, stdout, stderr,
               exit_code, duration_ms)

    result_text = (
        f"EXIT_CODE: {exit_code}\n"
        f"STDOUT:\n{stdout}\n"
        f"STDERR:\n{stderr}"
    )
    return _text_result(result_text)


def tool_grep_search(arguments):
    """Search files using grep."""
    pattern = arguments.get("pattern")
    if not pattern:
        return _error_result("Missing required parameter: pattern")

    path = arguments.get("path", ".")
    glob_filter = arguments.get("glob")
    case_insensitive = arguments.get("case_insensitive", False)
    show_line_numbers = arguments.get("show_line_numbers", True)
    context_lines = arguments.get("context_lines")
    output_mode = arguments.get("output_mode", "files_with_matches")
    head_limit = arguments.get("head_limit", DEFAULT_HEAD_LIMIT_GREP)

    # Build grep command
    parts = ["grep", "-r"]

    if output_mode == "files_with_matches":
        parts.append("-l")
    elif output_mode == "count":
        parts.append("-c")
    # "content" mode: just show matching lines

    if case_insensitive:
        parts.append("-i")

    if show_line_numbers and output_mode == "content":
        parts.append("-n")

    if context_lines is not None and output_mode == "content":
        parts.append(f"-C {int(context_lines)}")

    if glob_filter:
        parts.append(f"--include={_shell_quote(glob_filter)}")

    parts.append("--")
    parts.append(_shell_quote(pattern))
    parts.append(_shell_quote(path))

    cmd = " ".join(parts)
    if head_limit and head_limit > 0:
        cmd += f" | head -n {int(head_limit)}"

    cmd_built, stdout, stderr, exit_code, duration_ms = _guarded_exec(cmd)

    _log_debug("grep_search", arguments, cmd_built, stdout, stderr,
               exit_code, duration_ms)

    # grep returns 1 for no matches - that's not an error
    if exit_code == 1 and not stderr.strip():
        return _text_result("No matches found.")

    if exit_code not in (0, 1) and stderr.strip():
        return _text_result(f"grep error (exit {exit_code}):\n{stderr}\n{stdout}")

    return _text_result(stdout if stdout.strip() else "No matches found.")


def tool_find_files(arguments):
    """Find files by name pattern."""
    pattern = arguments.get("pattern")
    if not pattern:
        return _error_result("Missing required parameter: pattern")

    path = arguments.get("path", ".")
    file_type = arguments.get("type")
    max_depth = arguments.get("max_depth")
    head_limit = arguments.get("head_limit", DEFAULT_HEAD_LIMIT_FIND)

    parts = ["find", _shell_quote(path)]

    if max_depth is not None:
        parts.append(f"-maxdepth {int(max_depth)}")

    if file_type:
        parts.append(f"-type {_shell_quote(file_type)}")

    parts.append(f"-name {_shell_quote(pattern)}")

    cmd = " ".join(parts)
    if head_limit and head_limit > 0:
        cmd += f" 2>/dev/null | head -n {int(head_limit)}"

    cmd_built, stdout, stderr, exit_code, duration_ms = _guarded_exec(cmd)

    _log_debug("find_files", arguments, cmd_built, stdout, stderr,
               exit_code, duration_ms)

    return _text_result(stdout if stdout.strip() else "No files found.")


def tool_list_directory(arguments):
    """
    List directory contents.
    Uses 'command ls' directly to avoid sdcard timeout issues.
    Does NOT route through _guarded_exec.
    """
    path = arguments.get("path", ".")
    show_all = arguments.get("all", False)
    long_format = arguments.get("long", False)

    flags = []
    if show_all:
        flags.append("-a")
    if long_format:
        flags.append("-l")

    flag_str = " ".join(flags)
    cmd = f"command ls {flag_str} {_shell_quote(path)}".strip()

    diag("SHELL_EXEC", f"cmd={cmd[:120]} timeout={DEFAULT_TIMEOUT}s cwd=None (list_directory)")

    # Run directly via subprocess without .bashrc sourcing to avoid
    # sdcard timeout issues. Still use bash for flag parsing.
    env = os.environ.copy()
    start = time.monotonic()
    try:
        proc = subprocess.run(
            ["bash", "-c", cmd],
            capture_output=True,
            text=True,
            timeout=DEFAULT_TIMEOUT,
            env=env,
        )
        duration_ms = int((time.monotonic() - start) * 1000)
        stdout, stderr, exit_code = proc.stdout, proc.stderr, proc.returncode
        diag("SHELL_RESULT", f"exit={exit_code} stdout={len(stdout)}B stderr={len(stderr)}B dur={duration_ms}ms")
    except subprocess.TimeoutExpired:
        duration_ms = int((time.monotonic() - start) * 1000)
        stdout, stderr, exit_code = "", f"ls timed out after {DEFAULT_TIMEOUT}s", -1
        diag("SHELL_TIMEOUT", f"cmd=ls path={path} timeout={DEFAULT_TIMEOUT}s dur={duration_ms}ms")
    except Exception as exc:
        duration_ms = int((time.monotonic() - start) * 1000)
        stdout, stderr, exit_code = "", f"ls error: {exc}", -1
        diag("SHELL_ERROR", f"cmd=ls path={path} error={exc}")

    _log_debug("list_directory", arguments, cmd, stdout, stderr,
               exit_code, duration_ms)

    if exit_code != 0 and stderr.strip():
        return _text_result(f"ls error (exit {exit_code}):\n{stderr}")

    return _text_result(stdout if stdout.strip() else "(empty directory)")


def tool_read_file(arguments):
    """Read file contents safely with line numbers."""
    file_path = arguments.get("file_path")
    if not file_path:
        return _error_result("Missing required parameter: file_path")

    offset = arguments.get("offset", 1)
    if offset < 1:
        offset = 1
    limit = arguments.get("limit", DEFAULT_READ_LIMIT)

    end_line = offset + limit - 1

    # Use sed to extract the line range, then nl for line numbers
    # sed -n '<start>,<end>p' gives us the range
    # We use cat -n style numbering via awk
    cmd = (
        f"sed -n '{int(offset)},{int(end_line)}p' {_shell_quote(file_path)} "
        f"| awk -v start={int(offset)} '{{printf \"%6d\\t%s\\n\", NR + start - 1, $0}}'"
    )

    cmd_built, stdout, stderr, exit_code, duration_ms = _guarded_exec(cmd)

    _log_debug("read_file", arguments, cmd_built, stdout, stderr,
               exit_code, duration_ms)

    if exit_code != 0 and stderr.strip():
        return _text_result(f"read_file error (exit {exit_code}):\n{stderr}")

    if not stdout.strip():
        # Check if file exists
        check_cmd = f"test -f {_shell_quote(file_path)} && echo EXISTS || echo MISSING"
        check_out, _, _, _ = _run_command(check_cmd)
        if "MISSING" in check_out:
            return _text_result(f"File not found: {file_path}")
        else:
            return _text_result(f"(no content in lines {offset}-{end_line})")

    # Add a header showing what was read
    total_cmd = f"wc -l < {_shell_quote(file_path)}"
    total_out, _, _, _ = _run_command(total_cmd)
    total_lines = total_out.strip()

    header = f"File: {file_path} | Lines {offset}-{end_line} (total: {total_lines})\n"
    return _text_result(header + stdout)


# ---------------------------------------------------------------------------
# Tool Registry
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "shell_exec",
        "description": (
            "Execute a shell command in a fresh bash subprocess with ~/.bashrc sourced. "
            "Returns stdout, stderr, and exit_code."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute"
                },
                "timeout": {
                    "type": "number",
                    "description": "Timeout in seconds (default 30)"
                },
                "working_dir": {
                    "type": "string",
                    "description": "Working directory for the command"
                }
            },
            "required": ["command"]
        }
    },
    {
        "name": "grep_search",
        "description": (
            "Search file contents using grep -r. Works on ARM64 Android without ripgrep. "
            "Supports glob filtering, case sensitivity, context lines, and output modes."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Regex pattern to search for"
                },
                "path": {
                    "type": "string",
                    "description": "Directory or file to search in (default '.')"
                },
                "glob": {
                    "type": "string",
                    "description": "File filter pattern, e.g. '*.py'"
                },
                "case_insensitive": {
                    "type": "boolean",
                    "description": "Case insensitive search (default false)"
                },
                "show_line_numbers": {
                    "type": "boolean",
                    "description": "Show line numbers in content mode (default true)"
                },
                "context_lines": {
                    "type": "number",
                    "description": "Lines of context before and after each match"
                },
                "output_mode": {
                    "type": "string",
                    "description": "Output format: 'content', 'files_with_matches', or 'count' (default 'files_with_matches')",
                    "enum": ["content", "files_with_matches", "count"]
                },
                "head_limit": {
                    "type": "number",
                    "description": "Max output lines (default 100)"
                }
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "find_files",
        "description": (
            "Find files by name pattern using the find command. "
            "Supports type filtering and depth limits."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "File name pattern, e.g. '*.py'"
                },
                "path": {
                    "type": "string",
                    "description": "Directory to search in (default '.')"
                },
                "type": {
                    "type": "string",
                    "description": "'f' for files, 'd' for directories",
                    "enum": ["f", "d"]
                },
                "max_depth": {
                    "type": "number",
                    "description": "Maximum search depth"
                },
                "head_limit": {
                    "type": "number",
                    "description": "Max results (default 50)"
                }
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "list_directory",
        "description": (
            "List directory contents using ls. "
            "Runs 'command ls' directly to avoid sdcard timeout issues."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path (default '.')"
                },
                "all": {
                    "type": "boolean",
                    "description": "Show hidden files (default false)"
                },
                "long": {
                    "type": "boolean",
                    "description": "Long/detailed listing (default false)"
                }
            },
            "required": []
        }
    },
    {
        "name": "read_file",
        "description": (
            "Read file contents with line numbers. "
            "Supports offset and limit for reading specific sections of large files."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Absolute or relative path to the file"
                },
                "offset": {
                    "type": "number",
                    "description": "Starting line number (default 1)"
                },
                "limit": {
                    "type": "number",
                    "description": "Maximum lines to read (default 200)"
                }
            },
            "required": ["file_path"]
        }
    }
]

TOOL_DISPATCH = {
    "shell_exec": tool_shell_exec,
    "grep_search": tool_grep_search,
    "find_files": tool_find_files,
    "list_directory": tool_list_directory,
    "read_file": tool_read_file,
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _shell_quote(s):
    """Shell-quote a string safely."""
    if s is None:
        return "''"
    # Use single quotes, escaping any embedded single quotes
    return "'" + str(s).replace("'", "'\\''") + "'"


def _text_result(text):
    """Build a successful tool result."""
    return {"content": [{"type": "text", "text": str(text)}]}


def _error_result(message):
    """Build an error tool result."""
    return {"content": [{"type": "text", "text": f"ERROR: {message}"}], "isError": True}


# ---------------------------------------------------------------------------
# JSON-RPC 2.0 Handler
# ---------------------------------------------------------------------------

def handle_request(request):
    """
    Process a single JSON-RPC 2.0 request and return the response dict.
    Returns None for notifications (no id).
    """
    req_id = request.get("id")
    method = request.get("method", "")
    params = request.get("params", {})

    # ---- initialize ----
    if method == "initialize":
        result = {
            "protocolVersion": PROTOCOL_VERSION,
            "serverInfo": {
                "name": SERVER_NAME,
                "version": SERVER_VERSION,
            },
            "capabilities": {
                "tools": {}
            }
        }
        return _jsonrpc_response(req_id, result)

    # ---- notifications/initialized ----
    if method == "notifications/initialized":
        # This is a notification, no response required
        return None

    # ---- tools/list ----
    if method == "tools/list":
        result = {"tools": TOOLS}
        return _jsonrpc_response(req_id, result)

    # ---- tools/call ----
    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})

        args_preview = str(arguments)[:120]
        diag("MCP_TOOL_CALL", f"tool={tool_name} args={args_preview}")

        handler = TOOL_DISPATCH.get(tool_name)
        if not handler:
            error_result = _error_result(f"Unknown tool: {tool_name}")
            _log_debug(tool_name, arguments, "(unknown tool)", "", "",
                       -1, 0, exception=f"Unknown tool: {tool_name}")
            return _jsonrpc_response(req_id, error_result)

        try:
            result = handler(arguments)
        except Exception as exc:
            diag("SHELL_ERROR", f"tool={tool_name} exception={exc}")
            _log_debug(tool_name, arguments, "(exception during execution)",
                       "", str(exc), -1, 0, exception=str(exc))
            result = _error_result(f"Tool execution error: {exc}")

        return _jsonrpc_response(req_id, result)

    # ---- unknown method ----
    return _jsonrpc_error(req_id, -32601, f"Method not found: {method}")


def _jsonrpc_response(req_id, result):
    """Build a JSON-RPC 2.0 success response."""
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "result": result,
    }


def _jsonrpc_error(req_id, code, message):
    """Build a JSON-RPC 2.0 error response."""
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {
            "code": code,
            "message": message,
        }
    }


# ---------------------------------------------------------------------------
# Main Loop - stdio transport
# ---------------------------------------------------------------------------

def main():
    """
    Read JSON-RPC 2.0 requests from stdin line-by-line.
    Write JSON-RPC 2.0 responses to stdout, one per line, flushed immediately.
    """
    # Pre-flight: check that service ports are available before starting
    # The MCP shell server itself uses stdio, but the device API ports
    # (5563 Tier 1, 5564 Tier 2) may have stale processes from a prior run.
    for port in (DEVICE_API_PORT, MCP_TIER2_PORT):
        check_port_available(port)

    # Log server startup
    _log_debug("SERVER", {"event": "startup"}, "main()",
               f"Server {SERVER_NAME} v{SERVER_VERSION} starting",
               "", 0, 0)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError as exc:
            # Invalid JSON - send parse error
            err = _jsonrpc_error(None, -32700, f"Parse error: {exc}")
            _write_response(err)
            _log_debug("PARSE_ERROR", {}, line, "", str(exc), -1, 0,
                       exception=str(exc))
            continue

        try:
            response = handle_request(request)
            if response is not None:
                _write_response(response)
        except Exception as exc:
            # Catch-all: never crash the server
            req_id = request.get("id")
            err = _jsonrpc_error(req_id, -32603, f"Internal error: {exc}")
            _write_response(err)
            _log_debug("INTERNAL_ERROR", request, "(handle_request)",
                       "", str(exc), -1, 0, exception=str(exc))

    # Log server shutdown
    _log_debug("SERVER", {"event": "shutdown"}, "main()",
               f"Server {SERVER_NAME} v{SERVER_VERSION} shutting down (stdin closed)",
               "", 0, 0)


def _write_response(response):
    """Write a JSON-RPC response to stdout, one line, flush immediately."""
    try:
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        pass  # stdout broken - nothing we can do


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    main()
