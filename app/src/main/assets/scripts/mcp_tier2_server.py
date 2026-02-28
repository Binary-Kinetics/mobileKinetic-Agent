#!/data/user/0/com.mobilekinetic.agent/files/usr/bin/python3
"""
MCP Tier 2 API Wrapper for mK:a
========================================
Provides reliable access to Tier 2 Device API (port 5564) via JSON-RPC 2.0 over stdio.
Wraps HTTP requests to prevent hanging/timeout issues when accessing Tier 2 endpoints.

Transport: stdio (read JSON-RPC from stdin line-by-line, write JSON to stdout)
Protocol: MCP (Model Context Protocol) v2024-11-05

Tools wrap these Tier 2 endpoints:
  - tier2_ps: GET /ps - list running processes
  - tier2_df: GET /df - disk usage
  - tier2_ip: GET /ip - network interfaces
  - tier2_shell: POST /execute - execute shell command
  - tier2_cat: POST /files/read - read file
  - tier2_write: POST /files/write - write file
  - tier2_file_list: GET /files/list - list directory contents
  - tier2_system_info: GET /system/info - system information
  - tier2_proc_read: GET /proc/read - read /proc files
  - tier2_sys_read: GET /sys/read - read /sys files
  - tier2_packages: GET /packages/list - list packages
  - tier2_package_install: POST /packages/install - install package
  - tier2_env: GET /env - environment variables
  - tier2_cron: GET /cron/list - list cron jobs

Debug log: ~/mcp_tier2_debug.log (auto-rotated at 500KB)
"""

import json
import sys
import time
import os
from datetime import datetime, timezone
from urllib import request as urllib_request
from urllib.error import HTTPError, URLError

from mka_config import MCP_TIER2_URL

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

SERVER_NAME = "mcp-tier2-server"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"

TIER2_BASE_URL = MCP_TIER2_URL
DEFAULT_TIMEOUT = 10  # seconds for HTTP requests

DEBUG_LOG_PATH = os.path.expanduser("~/mcp_tier2_debug.log")
DEBUG_LOG_MAX_BYTES = 500 * 1024
DEBUG_LOG_KEEP_BYTES = 250 * 1024

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
        pass


def _log_debug(tool_name, arguments, url, response_text, error_text, status_code, duration_ms):
    """Append a structured debug entry to the log file."""
    _rotate_log_if_needed()
    try:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
        response_trunc = response_text
        if response_text and len(response_text) > 1000:
            response_trunc = response_text[:1000] + f"\n... [truncated, {len(response_text)} chars total]"

        entry = (
            f"{'=' * 72}\n"
            f"TIMESTAMP   : {ts}\n"
            f"TOOL        : {tool_name}\n"
            f"ARGUMENTS   : {json.dumps(arguments, ensure_ascii=False)}\n"
            f"URL         : {url}\n"
            f"STATUS      : {status_code}\n"
            f"DURATION    : {duration_ms} ms\n"
            f"RESPONSE    :\n{response_trunc}\n"
        )
        if error_text:
            entry += f"ERROR       : {error_text}\n"
        entry += f"{'=' * 72}\n\n"

        with open(DEBUG_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(entry)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# HTTP Request Helper
# ---------------------------------------------------------------------------

def _http_request(method, endpoint, body=None, timeout=DEFAULT_TIMEOUT):
    """
    Make an HTTP request to Tier 2 API.

    Returns: (response_text: str, error_text: str, status_code: int, duration_ms: int)
    """
    url = f"{TIER2_BASE_URL}{endpoint}"

    start = time.monotonic()
    try:
        if body is not None:
            body_bytes = json.dumps(body).encode('utf-8')
            req = urllib_request.Request(url, data=body_bytes, method=method)
            req.add_header('Content-Type', 'application/json')
        else:
            req = urllib_request.Request(url, method=method)

        with urllib_request.urlopen(req, timeout=timeout) as response:
            duration_ms = int((time.monotonic() - start) * 1000)
            response_text = response.read().decode('utf-8')
            return response_text, "", response.status, duration_ms

    except HTTPError as e:
        duration_ms = int((time.monotonic() - start) * 1000)
        error_body = e.read().decode('utf-8') if e.fp else ""
        return error_body, f"HTTP {e.code}: {e.reason}", e.code, duration_ms

    except URLError as e:
        duration_ms = int((time.monotonic() - start) * 1000)
        return "", f"Connection error: {e.reason}", -1, duration_ms

    except TimeoutError:
        duration_ms = int((time.monotonic() - start) * 1000)
        return "", f"Request timed out after {timeout}s", -1, duration_ms

    except Exception as e:
        duration_ms = int((time.monotonic() - start) * 1000)
        return "", f"Request error: {str(e)}", -1, duration_ms


# ---------------------------------------------------------------------------
# Tool Implementations
# ---------------------------------------------------------------------------

def tool_tier2_ps(arguments):
    """List running processes (top 50 by CPU)."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    response, error, status, duration = _http_request("GET", "/ps", timeout=timeout)

    _log_debug("tier2_ps", arguments, f"{TIER2_BASE_URL}/ps", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_df(arguments):
    """Get disk usage information."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    response, error, status, duration = _http_request("GET", "/df", timeout=timeout)

    _log_debug("tier2_df", arguments, f"{TIER2_BASE_URL}/df", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_ip(arguments):
    """Get network interfaces and routing table."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    response, error, status, duration = _http_request("GET", "/ip", timeout=timeout)

    _log_debug("tier2_ip", arguments, f"{TIER2_BASE_URL}/ip", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_shell(arguments):
    """Execute a shell command via Tier 2 API."""
    command = arguments.get("command")
    if not command:
        return _error_result("Missing required parameter: command")

    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    working_dir = arguments.get("working_dir")

    body = {"command": command}
    if working_dir:
        body["working_dir"] = working_dir

    response, error, status, duration = _http_request("POST", "/execute", body=body, timeout=timeout)

    _log_debug("tier2_shell", arguments, f"{TIER2_BASE_URL}/execute", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_cat(arguments):
    """Read file contents via Tier 2 API."""
    file_path = arguments.get("file_path")
    if not file_path:
        return _error_result("Missing required parameter: file_path")

    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    body = {"path": file_path}

    response, error, status, duration = _http_request("POST", "/files/read", body=body, timeout=timeout)

    _log_debug("tier2_cat", arguments, f"{TIER2_BASE_URL}/files/read", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_write(arguments):
    """Write file contents via Tier 2 API."""
    file_path = arguments.get("file_path")
    content = arguments.get("content")

    if not file_path:
        return _error_result("Missing required parameter: file_path")
    if content is None:
        return _error_result("Missing required parameter: content")

    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    body = {"path": file_path, "content": content}

    response, error, status, duration = _http_request("POST", "/files/write", body=body, timeout=timeout)

    _log_debug("tier2_write", arguments, f"{TIER2_BASE_URL}/files/write", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_file_list(arguments):
    """List files in a directory via Tier 2 API."""
    path = arguments.get("path", "")
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)

    endpoint = "/files/list"
    if path:
        endpoint = f"/files/list?path={path}"

    response, error, status, duration = _http_request("GET", endpoint, timeout=timeout)

    _log_debug("tier2_file_list", arguments, f"{TIER2_BASE_URL}{endpoint}", response, error, status, duration)

    if error:
        return _text_result(f"ERROR: {error}\n{response}")

    return _text_result(response)


def tool_tier2_system_info(arguments):
    """Get system information (kernel, uptime, memory, CPU)."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    response, error, status, duration = _http_request("GET", "/system/info", timeout=timeout)
    _log_debug("tier2_system_info", arguments, f"{TIER2_BASE_URL}/system/info", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_proc_read(arguments):
    """Read /proc filesystem file."""
    path = arguments.get("path")
    if not path:
        return _error_result("Missing required parameter: path")
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    endpoint = f"/proc/read?path={path}"
    response, error, status, duration = _http_request("GET", endpoint, timeout=timeout)
    _log_debug("tier2_proc_read", arguments, f"{TIER2_BASE_URL}{endpoint}", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_sys_read(arguments):
    """Read /sys filesystem file."""
    path = arguments.get("path")
    if not path:
        return _error_result("Missing required parameter: path")
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    endpoint = f"/sys/read?path={path}"
    response, error, status, duration = _http_request("GET", endpoint, timeout=timeout)
    _log_debug("tier2_sys_read", arguments, f"{TIER2_BASE_URL}{endpoint}", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_packages(arguments):
    """List installed packages."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    response, error, status, duration = _http_request("GET", "/packages/list", timeout=timeout)
    _log_debug("tier2_packages", arguments, f"{TIER2_BASE_URL}/packages/list", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_package_install(arguments):
    """Install a package via pkg."""
    name = arguments.get("name")
    if not name:
        return _error_result("Missing required parameter: name")
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    body = {"name": name}
    response, error, status, duration = _http_request("POST", "/packages/install", body=body, timeout=timeout)
    _log_debug("tier2_package_install", arguments, f"{TIER2_BASE_URL}/packages/install", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_env(arguments):
    """Get environment variables."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    response, error, status, duration = _http_request("GET", "/env", timeout=timeout)
    _log_debug("tier2_env", arguments, f"{TIER2_BASE_URL}/env", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


def tool_tier2_cron(arguments):
    """List cron jobs."""
    timeout = arguments.get("timeout", DEFAULT_TIMEOUT)
    response, error, status, duration = _http_request("GET", "/cron/list", timeout=timeout)
    _log_debug("tier2_cron", arguments, f"{TIER2_BASE_URL}/cron/list", response, error, status, duration)
    if error:
        return _text_result(f"ERROR: {error}\n{response}")
    return _text_result(response)


# ---------------------------------------------------------------------------
# Tool Registry
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "tier2_ps",
        "description": "List running processes (top 50 by CPU usage) from Tier 2 API GET /ps",
        "inputSchema": {
            "type": "object",
            "properties": {
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": []
        }
    },
    {
        "name": "tier2_df",
        "description": "Get disk usage information from Tier 2 API GET /df (equivalent to df -h)",
        "inputSchema": {
            "type": "object",
            "properties": {
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": []
        }
    },
    {
        "name": "tier2_ip",
        "description": "Get network interfaces and routing table from Tier 2 API GET /ip",
        "inputSchema": {
            "type": "object",
            "properties": {
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": []
        }
    },
    {
        "name": "tier2_shell",
        "description": "Execute a shell command via Tier 2 API POST /execute. Returns stdout, stderr, exit code.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Shell command to execute"
                },
                "working_dir": {
                    "type": "string",
                    "description": "Working directory for command execution"
                },
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": ["command"]
        }
    },
    {
        "name": "tier2_cat",
        "description": "Read file contents via Tier 2 API POST /files/read",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to file to read"
                },
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": ["file_path"]
        }
    },
    {
        "name": "tier2_write",
        "description": "Write file contents via Tier 2 API POST /files/write (overwrites existing file)",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "Path to file to write"
                },
                "content": {
                    "type": "string",
                    "description": "Content to write to file"
                },
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": ["file_path", "content"]
        }
    },
    {
        "name": "tier2_file_list",
        "description": "List files in a directory via Tier 2 API GET /files/list",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Directory path to list (default: home directory)"
                },
                "timeout": {
                    "type": "number",
                    "description": "Request timeout in seconds (default 10)"
                }
            },
            "required": []
        }
    },
    {
        "name": "tier2_system_info",
        "description": "Get system information via Tier 2 API GET /system/info",
        "inputSchema": {"type": "object", "properties": {"timeout": {"type": "number", "description": "Request timeout in seconds (default 10)"}}, "required": []}
    },
    {
        "name": "tier2_proc_read",
        "description": "Read /proc filesystem file via Tier 2 API GET /proc/read",
        "inputSchema": {"type": "object", "properties": {"path": {"type": "string", "description": "Path within /proc to read (e.g., 'meminfo')"}, "timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": ["path"]}
    },
    {
        "name": "tier2_sys_read",
        "description": "Read /sys filesystem file via Tier 2 API GET /sys/read",
        "inputSchema": {"type": "object", "properties": {"path": {"type": "string", "description": "Path within /sys to read"}, "timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": ["path"]}
    },
    {
        "name": "tier2_packages",
        "description": "List installed packages via Tier 2 API GET /packages/list",
        "inputSchema": {"type": "object", "properties": {"timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": []}
    },
    {
        "name": "tier2_package_install",
        "description": "Install a package via Tier 2 API POST /packages/install",
        "inputSchema": {"type": "object", "properties": {"name": {"type": "string", "description": "Package name to install"}, "timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": ["name"]}
    },
    {
        "name": "tier2_env",
        "description": "Get environment variables via Tier 2 API GET /env",
        "inputSchema": {"type": "object", "properties": {"timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": []}
    },
    {
        "name": "tier2_cron",
        "description": "List cron jobs via Tier 2 API GET /cron/list",
        "inputSchema": {"type": "object", "properties": {"timeout": {"type": "number", "description": "Request timeout in seconds"}}, "required": []}
    }
]

TOOL_DISPATCH = {
    "tier2_ps": tool_tier2_ps,
    "tier2_df": tool_tier2_df,
    "tier2_ip": tool_tier2_ip,
    "tier2_shell": tool_tier2_shell,
    "tier2_cat": tool_tier2_cat,
    "tier2_write": tool_tier2_write,
    "tier2_file_list": tool_tier2_file_list,
    "tier2_system_info": tool_tier2_system_info,
    "tier2_proc_read": tool_tier2_proc_read,
    "tier2_sys_read": tool_tier2_sys_read,
    "tier2_packages": tool_tier2_packages,
    "tier2_package_install": tool_tier2_package_install,
    "tier2_env": tool_tier2_env,
    "tier2_cron": tool_tier2_cron,
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

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
        return None

    # ---- tools/list ----
    if method == "tools/list":
        result = {"tools": TOOLS}
        return _jsonrpc_response(req_id, result)

    # ---- tools/call ----
    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})

        handler = TOOL_DISPATCH.get(tool_name)
        if not handler:
            error_result = _error_result(f"Unknown tool: {tool_name}")
            _log_debug(tool_name, arguments, "(unknown tool)", "",
                      f"Unknown tool: {tool_name}", -1, 0)
            return _jsonrpc_response(req_id, error_result)

        try:
            result = handler(arguments)
        except Exception as exc:
            _log_debug(tool_name, arguments, "(exception during execution)",
                      "", str(exc), -1, 0)
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
            err = _jsonrpc_error(None, -32700, f"Parse error: {exc}")
            _write_response(err)
            _log_debug("PARSE_ERROR", {}, line, "", str(exc), -1, 0)
            continue

        try:
            response = handle_request(request)
            if response is not None:
                _write_response(response)
        except Exception as exc:
            req_id = request.get("id")
            err = _jsonrpc_error(req_id, -32603, f"Internal error: {exc}")
            _write_response(err)
            _log_debug("INTERNAL_ERROR", request, "(handle_request)",
                      "", str(exc), -1, 0)

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
        pass


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    main()
