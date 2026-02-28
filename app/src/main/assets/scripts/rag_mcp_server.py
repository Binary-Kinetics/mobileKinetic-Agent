#!/usr/bin/env python3
"""MCP server wrapping the mK:a on-device RAG API.

Exposes RAG search/add/health as native MCP tools for Claude CLI.
Uses stdio JSON-RPC 2.0 (MCP protocol). Zero external dependencies.

RAG API: http://127.0.0.1:5562
"""

import json
import sys
import urllib.request
import urllib.error
from dataclasses import asdict
from trigger_store import TriggerStore, Trigger

RAG_URL = "http://127.0.0.1:5562"
SERVER_NAME = "rag-mcp"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"

# Phase 4: Semantic trigger store (JSON-backed, loaded once at startup)
trigger_store = TriggerStore()


# ---------------------------------------------------------------------------
# RAG HTTP client (talks to existing RAG server)
# ---------------------------------------------------------------------------

def rag_request(endpoint: str, method: str = "GET", data: dict | None = None) -> dict:
    """Make an HTTP request to the RAG server."""
    url = f"{RAG_URL}{endpoint}"
    try:
        if data is not None:
            body = json.dumps(data).encode("utf-8")
            req = urllib.request.Request(
                url, data=body,
                headers={"Content-Type": "application/json"},
                method=method,
            )
        else:
            req = urllib.request.Request(url, method=method)

        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        return {"error": f"RAG server unreachable: {e}"}
    except Exception as e:
        return {"error": f"RAG request failed: {e}"}


# ---------------------------------------------------------------------------
# MCP tool definitions
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "rag_search",
        "description": (
            "Search your persistent memory (RAG) for relevant context. "
            "Use this BEFORE taking any action to check what you already know."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Semantic search query",
                },
                "top_k": {
                    "type": "integer",
                    "description": "Number of results to return (default 5)",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "rag_context",
        "description": (
            "Get formatted context from RAG memory. Returns a pre-formatted "
            "block of relevant memories for quick orientation."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Context query (e.g. 'endpoints API capabilities')",
                },
                "top_k": {
                    "type": "integer",
                    "description": "Number of memories to include (default 5)",
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "rag_add_memory",
        "description": (
            "Store a new memory in persistent RAG storage. Use after learning "
            "something novel that future sessions should know."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "The memory text to store",
                },
                "category": {
                    "type": "string",
                    "description": "Category (e.g. 'endpoint', 'warning', 'tool_syntax', 'project')",
                },
                "metadata": {
                    "type": "object",
                    "description": "Optional metadata dict",
                    "default": {},
                },
            },
            "required": ["text", "category"],
        },
    },
    {
        "name": "rag_health",
        "description": "Check RAG server health, memory count, and model info.",
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "rag_delete_memory",
        "description": "Delete a memory from the RAG system by its ID",
        "inputSchema": {
            "type": "object",
            "properties": {
                "memory_id": {
                    "type": "string",
                    "description": "The UUID of the memory to delete"
                }
            },
            "required": ["memory_id"]
        }
    },
    # Phase 4: Semantic trigger tools
    {
        "name": "trigger_add",
        "description": (
            "Add a manual semantic trigger. When the user's message contains any of "
            "the keywords, the trigger fires and injects RAG context into the prompt."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "id": {
                    "type": "string",
                    "description": "Unique trigger ID/slug, e.g. 'ssh_credentials'",
                },
                "keywords": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "List of keywords/phrases that activate this trigger",
                },
                "rag_query": {
                    "type": "string",
                    "description": "Query sent to RAG when this trigger fires",
                },
                "rag_category": {
                    "type": "string",
                    "description": "Optional RAG category filter (e.g. 'network_topology')",
                },
                "max_tokens": {
                    "type": "integer",
                    "description": "Token budget for injected RAG result (default 300)",
                    "default": 300,
                },
                "source": {
                    "type": "string",
                    "description": "Creator identifier, e.g. 'user' or 'agent'",
                    "default": "user",
                },
            },
            "required": ["id", "keywords", "rag_query"],
        },
    },
    {
        "name": "trigger_list",
        "description": "List all stored semantic triggers. Optionally filter to enabled-only.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "enabled_only": {
                    "type": "boolean",
                    "description": "If true, return only enabled triggers (default false)",
                    "default": False,
                },
            },
        },
    },
    {
        "name": "trigger_remove",
        "description": "Remove a semantic trigger by its ID.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "id": {
                    "type": "string",
                    "description": "Trigger ID to remove",
                },
            },
            "required": ["id"],
        },
    },
    {
        "name": "trigger_update",
        "description": (
            "Update one or more fields of an existing semantic trigger. "
            "Pass only the fields you want to change."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "id": {
                    "type": "string",
                    "description": "Trigger ID to update",
                },
                "keywords": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "New keyword list",
                },
                "rag_query": {
                    "type": "string",
                    "description": "New RAG query",
                },
                "rag_category": {
                    "type": "string",
                    "description": "New RAG category filter",
                },
                "max_tokens": {
                    "type": "integer",
                    "description": "New token budget",
                },
                "enabled": {
                    "type": "boolean",
                    "description": "Enable or disable the trigger",
                },
            },
            "required": ["id"],
        },
    },
]


# ---------------------------------------------------------------------------
# MCP tool handlers
# ---------------------------------------------------------------------------

def handle_rag_search(args: dict) -> str:
    """Execute RAG search and return results."""
    query = args.get("query", "")
    top_k = args.get("top_k", 5)
    result = rag_request("/search", "POST", {"query": query, "top_k": top_k})

    if "error" in result:
        return f"RAG search failed: {result['error']}"

    memories = result.get("memories", result.get("results", []))
    if not memories:
        return f"No results for: {query}"

    lines = [f"Found {len(memories)} results for: {query}\n"]
    for i, mem in enumerate(memories, 1):
        text = mem.get("text", str(mem))
        cat = mem.get("category", "unknown")
        relevance = mem.get("relevance", mem.get("score", 0))
        lines.append(f"[{i}] ({cat}, {relevance:.0%}) {text}\n")

    return "\n".join(lines)


def handle_rag_context(args: dict) -> str:
    """Get formatted context from RAG."""
    query = args.get("query", "")
    top_k = args.get("top_k", 5)
    result = rag_request("/context", "POST", {"query": query, "top_k": top_k})

    if "error" in result:
        return f"RAG context failed: {result['error']}"

    return result.get("context", json.dumps(result, indent=2))


def handle_rag_add_memory(args: dict) -> str:
    """Add a memory to RAG."""
    text = args.get("text", "")
    category = args.get("category", "general")
    metadata = args.get("metadata", {})

    result = rag_request("/memory", "POST", {
        "text": text,
        "category": category,
        "metadata": metadata,
    })

    if "error" in result:
        return f"Failed to add memory: {result['error']}"

    mem_id = result.get("id", "unknown")
    return f"Memory stored (id: {mem_id}, category: {category})"


def handle_rag_health(args: dict) -> str:
    """Check RAG health."""
    result = rag_request("/health", "GET")

    if "error" in result:
        return f"RAG health check failed: {result['error']}"

    return (
        f"Status: {result.get('status', 'unknown')}\n"
        f"Memories: {result.get('memories', '?')}\n"
        f"Model: {result.get('model', '?')}\n"
        f"Engine: {result.get('engine', '?')}"
    )


def handle_rag_delete_memory(args: dict) -> str:
    """Delete a memory from RAG."""
    memory_id = args.get("memory_id", "")
    result = rag_request(f"/memory/{memory_id}", method="DELETE")
    if "error" in result:
        return f"Failed to delete memory: {result['error']}"
    return f"Memory deleted (id: {memory_id})"


# ---------------------------------------------------------------------------
# Phase 4: Semantic trigger handlers
# ---------------------------------------------------------------------------

def handle_trigger_add(args: dict) -> str:
    """Add a new semantic trigger."""
    trigger_id = args.get("id", "")
    keywords = args.get("keywords", [])
    rag_query = args.get("rag_query", "")

    if not trigger_id:
        return "Error: 'id' is required"
    if not keywords:
        return "Error: 'keywords' must be a non-empty list"
    if not rag_query:
        return "Error: 'rag_query' is required"

    t = trigger_store.add(
        trigger_id=trigger_id,
        keywords=keywords,
        rag_query=rag_query,
        rag_category=args.get("rag_category"),
        max_tokens=args.get("max_tokens", 300),
        enabled=args.get("enabled", True),
        source=args.get("source", "user"),
    )
    return f"Trigger added: {json.dumps(asdict(t), indent=2)}"


def handle_trigger_list(args: dict) -> str:
    """List stored semantic triggers."""
    enabled_only = args.get("enabled_only", False)
    if enabled_only:
        triggers = trigger_store.list_enabled()
    else:
        triggers = trigger_store.list_all()
    if not triggers:
        return "No triggers stored."
    lines = [f"Triggers ({len(triggers)} total):"]
    for t in triggers:
        status = "ON" if t.enabled else "OFF"
        lines.append(
            f"  [{status}] {t.id} | keywords={t.keywords} | rag_query={t.rag_query!r}"
        )
    return "\n".join(lines)


def handle_trigger_remove(args: dict) -> str:
    """Remove a semantic trigger by ID."""
    trigger_id = args.get("id", "")
    if not trigger_id:
        return "Error: 'id' is required"
    removed = trigger_store.remove(trigger_id)
    if removed:
        return f"Trigger '{trigger_id}' removed."
    return f"Trigger '{trigger_id}' not found."


def handle_trigger_update(args: dict) -> str:
    """Update fields of an existing semantic trigger."""
    trigger_id = args.get("id", "")
    if not trigger_id:
        return "Error: 'id' is required"
    update_fields = {k: v for k, v in args.items() if k != "id"}
    if not update_fields:
        return "Error: no fields to update"
    updated = trigger_store.update(trigger_id, **update_fields)
    if updated is None:
        return f"Trigger '{trigger_id}' not found."
    return f"Trigger updated: {json.dumps(asdict(updated), indent=2)}"


TOOL_HANDLERS = {
    "rag_search": handle_rag_search,
    "rag_context": handle_rag_context,
    "rag_add_memory": handle_rag_add_memory,
    "rag_health": handle_rag_health,
    "rag_delete_memory": handle_rag_delete_memory,
    # Phase 4: Semantic trigger handlers
    "trigger_add": handle_trigger_add,
    "trigger_list": handle_trigger_list,
    "trigger_remove": handle_trigger_remove,
    "trigger_update": handle_trigger_update,
}


# ---------------------------------------------------------------------------
# MCP JSON-RPC protocol handler
# ---------------------------------------------------------------------------

def make_response(req_id, result):
    """Build a JSON-RPC success response."""
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def make_error(req_id, code, message):
    """Build a JSON-RPC error response."""
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}


def handle_request(request: dict) -> dict | None:
    """Handle a single JSON-RPC request."""
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
        return make_response(req_id, {"tools": TOOLS})

    # --- Call tool ---
    if method == "tools/call":
        tool_name = params.get("name", "")
        tool_args = params.get("arguments", {})

        handler = TOOL_HANDLERS.get(tool_name)
        if not handler:
            return make_response(req_id, {
                "content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}],
                "isError": True,
            })

        try:
            result_text = handler(tool_args)
            return make_response(req_id, {
                "content": [{"type": "text", "text": result_text}],
            })
        except Exception as e:
            return make_response(req_id, {
                "content": [{"type": "text", "text": f"Tool error: {e}"}],
                "isError": True,
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
    sys.stderr.write(f"[{SERVER_NAME}] Started (RAG at {RAG_URL})\n")
    sys.stderr.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            request = json.loads(line)
        except json.JSONDecodeError as e:
            sys.stderr.write(f"[{SERVER_NAME}] Invalid JSON: {e}\n")
            sys.stderr.flush()
            continue

        response = handle_request(request)
        if response is not None:
            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
