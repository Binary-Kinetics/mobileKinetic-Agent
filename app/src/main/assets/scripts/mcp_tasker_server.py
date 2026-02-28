#!/usr/bin/env python3
"""MCP server wrapping 23 Tasker tool definitions for mK:a.

Loads tool schemas from config/toolDescriptions.json and exposes them as
MCP tools over stdio JSON-RPC 2.0. Each tool/call routes to the on-device
Tasker HTTP endpoint at http://127.0.0.1:5563/tasker/run.

Uses only Python stdlib - zero external dependencies.
"""

import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

TASKER_URL = "http://127.0.0.1:5563/tasker/run"
SERVER_NAME = "tasker-mcp"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"
HTTP_TIMEOUT = 30  # seconds


# ---------------------------------------------------------------------------
# Load tool definitions from toolDescriptions.json
# ---------------------------------------------------------------------------

def load_tool_definitions() -> list[dict]:
    """Load tool definitions from config/toolDescriptions.json relative to this script."""
    script_dir = Path(__file__).resolve().parent
    config_path = script_dir / "config" / "toolDescriptions.json"

    if not config_path.exists():
        log(f"FATAL: Tool definitions not found at {config_path}")
        sys.exit(1)

    try:
        with open(config_path, "r", encoding="utf-8") as f:
            tools_raw = json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        log(f"FATAL: Failed to load tool definitions: {e}")
        sys.exit(1)

    if not isinstance(tools_raw, list):
        log("FATAL: toolDescriptions.json must be a JSON array")
        sys.exit(1)

    log(f"Loaded {len(tools_raw)} tool definitions from {config_path}")
    return tools_raw


def build_mcp_tools(tools_raw: list[dict]) -> tuple[list[dict], dict[str, str]]:
    """Build MCP tool list and name-to-tasker_name mapping from raw definitions.

    Returns:
        (mcp_tools, name_to_tasker_name) where mcp_tools is the list for
        tools/list and name_to_tasker_name maps MCP tool name -> Tasker task name.
    """
    mcp_tools = []
    name_map = {}

    for entry in tools_raw:
        name = entry.get("name")
        tasker_name = entry.get("tasker_name")
        description = entry.get("description", "")
        input_schema = entry.get("inputSchema", {"type": "object", "properties": {}})

        if not name or not tasker_name:
            log(f"WARNING: Skipping tool with missing name or tasker_name: {entry}")
            continue

        mcp_tools.append({
            "name": name,
            "description": description,
            "inputSchema": input_schema,
        })
        name_map[name] = tasker_name

    log(f"Built {len(mcp_tools)} MCP tools, {len(name_map)} name mappings")
    return mcp_tools, name_map


# ---------------------------------------------------------------------------
# Logging (stderr only - stdout is reserved for JSON-RPC)
# ---------------------------------------------------------------------------

def log(msg: str) -> None:
    """Write a debug message to stderr."""
    sys.stderr.write(f"[{SERVER_NAME}] {msg}\n")
    sys.stderr.flush()


# ---------------------------------------------------------------------------
# Tasker HTTP client
# ---------------------------------------------------------------------------

def tasker_request(tasker_name: str, params: dict) -> dict:
    """POST to the Tasker endpoint and return the parsed JSON response.

    Sends: {"tasker_name": "<tasker_name>", ...params}
    """
    payload = {"tasker_name": tasker_name}
    payload.update(params)

    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        TASKER_URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            status = resp.status
            raw = resp.read().decode("utf-8")
            log(f"Tasker response ({status}): {raw[:200]}")
            try:
                return {"status": status, "body": json.loads(raw)}
            except json.JSONDecodeError:
                return {"status": status, "body": raw}

    except urllib.error.HTTPError as e:
        error_body = ""
        try:
            error_body = e.read().decode("utf-8")
        except Exception:
            pass
        log(f"Tasker HTTP error {e.code}: {error_body[:200]}")
        return {"error": f"HTTP {e.code}: {error_body or e.reason}"}

    except urllib.error.URLError as e:
        log(f"Tasker connection error: {e.reason}")
        return {"error": f"Connection failed: {e.reason}"}

    except TimeoutError:
        log("Tasker request timed out")
        return {"error": f"Request timed out after {HTTP_TIMEOUT}s"}

    except Exception as e:
        log(f"Tasker request failed: {e}")
        return {"error": f"Request failed: {e}"}


# ---------------------------------------------------------------------------
# MCP JSON-RPC protocol helpers
# ---------------------------------------------------------------------------

def make_response(req_id, result: dict) -> dict:
    """Build a JSON-RPC 2.0 success response."""
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def make_error(req_id, code: int, message: str) -> dict:
    """Build a JSON-RPC 2.0 error response."""
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}


# ---------------------------------------------------------------------------
# MCP request handler
# ---------------------------------------------------------------------------

def handle_request(request: dict, mcp_tools: list[dict], name_map: dict[str, str]) -> dict | None:
    """Handle a single JSON-RPC request and return the response (or None for notifications)."""
    method = request.get("method", "")
    req_id = request.get("id")
    params = request.get("params", {})

    # --- Initialize ---
    if method == "initialize":
        return make_response(req_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        })

    # --- Notifications (no response needed) ---
    if method.startswith("notifications/"):
        return None

    # --- List tools ---
    if method == "tools/list":
        return make_response(req_id, {"tools": mcp_tools})

    # --- Call tool ---
    if method == "tools/call":
        tool_name = params.get("name", "")
        tool_args = params.get("arguments", {})

        tasker_name = name_map.get(tool_name)
        if not tasker_name:
            return make_response(req_id, {
                "content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}],
                "isError": True,
            })

        log(f"Calling tool '{tool_name}' -> Tasker '{tasker_name}' with args: {tool_args}")

        result = tasker_request(tasker_name, tool_args)

        if "error" in result:
            return make_response(req_id, {
                "content": [{"type": "text", "text": f"Tasker error: {result['error']}"}],
                "isError": True,
            })

        # Format the successful response
        body = result.get("body", "")
        if isinstance(body, dict):
            result_text = json.dumps(body, indent=2, ensure_ascii=False)
        else:
            result_text = str(body)

        return make_response(req_id, {
            "content": [{"type": "text", "text": result_text}],
        })

    # --- Unknown method ---
    if req_id is not None:
        return make_error(req_id, -32601, f"Method not found: {method}")

    return None


# ---------------------------------------------------------------------------
# Main stdio loop
# ---------------------------------------------------------------------------

def main():
    """Read JSON-RPC requests from stdin, write responses to stdout."""
    # Load tool definitions at startup
    tools_raw = load_tool_definitions()
    mcp_tools, name_map = build_mcp_tools(tools_raw)

    log(f"Started ({len(mcp_tools)} tools, Tasker at {TASKER_URL})")

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            log(f"Invalid JSON: {e}")
            continue

        log(f"Request: {request.get('method', '?')} (id={request.get('id')})")

        try:
            response = handle_request(request, mcp_tools, name_map)
        except Exception as e:
            log(f"Unhandled error processing request: {e}")
            req_id = request.get("id")
            if req_id is not None:
                response = make_error(req_id, -32603, f"Internal error: {e}")
            else:
                response = None

        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
