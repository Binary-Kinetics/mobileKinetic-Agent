#!/usr/bin/env python3
"""MCP server providing persistent goals, intentions, and learned behaviors.

The agenda server is the core of agent self-motivation — it stores what the
agent wants to do, when it should act, and what it has learned from past
sessions.  All state is persisted in a local SQLite database.

Uses stdio JSON-RPC 2.0 (MCP protocol). Zero external dependencies.
Port convention: 5567
"""

import json
import os
import sqlite3
import sys
import uuid
from datetime import datetime, timezone

SERVER_NAME = "agenda-mcp"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"

DB_PATH = os.environ.get(
    "AGENDA_DB_PATH",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "agenda.db"),
)


# ---------------------------------------------------------------------------
# SQLite persistence layer
# ---------------------------------------------------------------------------

def _get_db() -> sqlite3.Connection:
    """Return a connection to the agenda database, creating tables if needed."""
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    _ensure_tables(conn)
    return conn


def _ensure_tables(conn: sqlite3.Connection) -> None:
    """Create tables if they don't exist."""
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS goals (
            id TEXT PRIMARY KEY,
            description TEXT NOT NULL,
            priority INTEGER DEFAULT 3 CHECK(priority BETWEEN 1 AND 5),
            status TEXT DEFAULT 'active'
                CHECK(status IN ('active','paused','completed','failed')),
            steps TEXT DEFAULT '[]',
            created_at TEXT DEFAULT (datetime('now')),
            deadline TEXT,
            last_touched TEXT DEFAULT (datetime('now')),
            metadata TEXT DEFAULT '{}'
        );

        CREATE TABLE IF NOT EXISTS intentions (
            id TEXT PRIMARY KEY,
            description TEXT NOT NULL,
            trigger_type TEXT NOT NULL
                CHECK(trigger_type IN ('time','condition','event')),
            trigger_value TEXT NOT NULL,
            status TEXT DEFAULT 'pending'
                CHECK(status IN ('pending','fired','cancelled','expired')),
            fire_count INTEGER DEFAULT 0,
            max_fires INTEGER DEFAULT 1,
            created_at TEXT DEFAULT (datetime('now')),
            goal_id TEXT,
            metadata TEXT DEFAULT '{}',
            FOREIGN KEY (goal_id) REFERENCES goals(id) ON DELETE SET NULL
        );

        CREATE TABLE IF NOT EXISTS learned_behaviors (
            id TEXT PRIMARY KEY,
            pattern TEXT NOT NULL,
            confidence REAL DEFAULT 0.5
                CHECK(confidence BETWEEN 0.0 AND 1.0),
            source_sessions TEXT DEFAULT '[]',
            created_at TEXT DEFAULT (datetime('now')),
            last_confirmed TEXT DEFAULT (datetime('now')),
            times_confirmed INTEGER DEFAULT 1
        );
    """)
    conn.commit()


def _new_id() -> str:
    """Generate a short unique ID."""
    return uuid.uuid4().hex[:12]


def _now_iso() -> str:
    """Return current UTC time as ISO string (no timezone suffix)."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def _row_to_dict(row: sqlite3.Row) -> dict:
    """Convert a sqlite3.Row to a plain dict, parsing JSON fields."""
    d = dict(row)
    for key in ("steps", "metadata", "source_sessions"):
        if key in d and isinstance(d[key], str):
            try:
                d[key] = json.loads(d[key])
            except (json.JSONDecodeError, TypeError):
                pass
    return d


# ---------------------------------------------------------------------------
# MCP tool definitions
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "agenda_create_goal",
        "description": (
            "Create a new goal in the persistent agenda. Goals represent "
            "things the agent wants to accomplish across sessions."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "What the goal is about",
                },
                "priority": {
                    "type": "integer",
                    "description": "Priority 1 (highest) to 5 (lowest), default 3",
                    "default": 3,
                },
                "deadline": {
                    "type": "string",
                    "description": "Optional ISO datetime deadline (e.g. '2026-03-15 09:00:00')",
                },
                "steps": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Optional list of step descriptions to plan out the goal",
                },
            },
            "required": ["description"],
        },
    },
    {
        "name": "agenda_list_goals",
        "description": (
            "List goals with optional filtering by status and priority. "
            "Returns goals sorted by priority (highest first) then last_touched."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "status": {
                    "type": "string",
                    "description": "Filter by status: active|paused|completed|failed|all (default: active)",
                    "default": "active",
                },
                "priority_min": {
                    "type": "integer",
                    "description": "Only return goals with priority <= this value (1=highest)",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results to return (default 20)",
                    "default": 20,
                },
            },
        },
    },
    {
        "name": "agenda_update_goal",
        "description": (
            "Update a goal's status, description, or step progress. "
            "Use step_index + step_status to mark individual steps."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "goal_id": {
                    "type": "string",
                    "description": "ID of the goal to update",
                },
                "status": {
                    "type": "string",
                    "description": "New status: active|paused|completed|failed",
                },
                "description": {
                    "type": "string",
                    "description": "Updated description",
                },
                "step_index": {
                    "type": "integer",
                    "description": "Index of the step to update (0-based)",
                },
                "step_status": {
                    "type": "string",
                    "description": "New step status: pending|done|failed|skipped",
                },
                "step_result": {
                    "type": "string",
                    "description": "Optional result/note for the step",
                },
            },
            "required": ["goal_id"],
        },
    },
    {
        "name": "agenda_delete_goal",
        "description": "Permanently delete a goal by ID.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "goal_id": {
                    "type": "string",
                    "description": "ID of the goal to delete",
                },
            },
            "required": ["goal_id"],
        },
    },
    {
        "name": "agenda_set_intention",
        "description": (
            "Schedule a future action. Time-based intentions fire when their "
            "trigger_value datetime is reached. Condition/event triggers are "
            "checked semantically."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "What should happen when this intention fires",
                },
                "trigger_type": {
                    "type": "string",
                    "description": "Type of trigger: time|condition|event",
                },
                "trigger_value": {
                    "type": "string",
                    "description": (
                        "When to fire: ISO datetime for time triggers, "
                        "description string for condition/event triggers"
                    ),
                },
                "max_fires": {
                    "type": "integer",
                    "description": "How many times to fire (1 for one-shot, -1 for unlimited recurring)",
                    "default": 1,
                },
                "goal_id": {
                    "type": "string",
                    "description": "Optional parent goal ID to link this intention to",
                },
            },
            "required": ["description", "trigger_type", "trigger_value"],
        },
    },
    {
        "name": "agenda_get_due",
        "description": (
            "Get all due/triggered intentions. Checks time-based triggers "
            "against the current time. Returns non-time triggers as-is for "
            "semantic evaluation. Automatically increments fire_count."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
    {
        "name": "agenda_cancel_intention",
        "description": "Cancel a pending intention by ID.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "intention_id": {
                    "type": "string",
                    "description": "ID of the intention to cancel",
                },
            },
            "required": ["intention_id"],
        },
    },
    {
        "name": "agenda_learn",
        "description": (
            "Store a learned behavior or preference. If a very similar pattern "
            "already exists, it reinforces the existing entry instead of "
            "creating a duplicate."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "The behavior/preference pattern observed",
                },
                "confidence": {
                    "type": "number",
                    "description": "Confidence level 0.0 to 1.0 (default 0.5)",
                    "default": 0.5,
                },
                "session_id": {
                    "type": "string",
                    "description": "Optional session ID where this was observed",
                },
            },
            "required": ["pattern"],
        },
    },
    {
        "name": "agenda_get_behaviors",
        "description": "List learned behaviors sorted by confidence (highest first).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "min_confidence": {
                    "type": "number",
                    "description": "Minimum confidence threshold (default 0.3)",
                    "default": 0.3,
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results to return (default 20)",
                    "default": 20,
                },
            },
        },
    },
    {
        "name": "agenda_get_context",
        "description": (
            "Get a full agenda summary formatted for pre-prompt injection. "
            "Includes active goals, due intentions, and top learned behaviors "
            "in a compact markdown block."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {},
        },
    },
]


# ---------------------------------------------------------------------------
# MCP tool handlers
# ---------------------------------------------------------------------------

def handle_create_goal(args: dict) -> str:
    """Create a new goal."""
    description = args.get("description", "").strip()
    if not description:
        return "Error: 'description' is required and cannot be empty"

    priority = args.get("priority", 3)
    if not (1 <= priority <= 5):
        return "Error: 'priority' must be between 1 and 5"

    deadline = args.get("deadline")
    raw_steps = args.get("steps", [])

    # Convert list of step description strings to structured step objects
    steps = [
        {"description": s, "status": "pending", "result": None}
        for s in raw_steps
    ]

    goal_id = _new_id()
    now = _now_iso()

    conn = _get_db()
    try:
        conn.execute(
            """INSERT INTO goals (id, description, priority, status, steps,
                                  created_at, deadline, last_touched, metadata)
               VALUES (?, ?, ?, 'active', ?, ?, ?, ?, '{}')""",
            (goal_id, description, priority, json.dumps(steps), now, deadline, now),
        )
        conn.commit()

        row = conn.execute("SELECT * FROM goals WHERE id = ?", (goal_id,)).fetchone()
        goal = _row_to_dict(row)
        return json.dumps(goal, indent=2)
    finally:
        conn.close()


def handle_list_goals(args: dict) -> str:
    """List goals with filtering."""
    status = args.get("status", "active")
    priority_min = args.get("priority_min")
    limit = args.get("limit", 20)

    conn = _get_db()
    try:
        query = "SELECT * FROM goals"
        conditions = []
        params = []

        if status and status != "all":
            conditions.append("status = ?")
            params.append(status)

        if priority_min is not None:
            conditions.append("priority <= ?")
            params.append(priority_min)

        if conditions:
            query += " WHERE " + " AND ".join(conditions)

        query += " ORDER BY priority ASC, last_touched DESC LIMIT ?"
        params.append(limit)

        rows = conn.execute(query, params).fetchall()
        goals = [_row_to_dict(r) for r in rows]

        if not goals:
            return f"No goals found (status={status})"

        return json.dumps(goals, indent=2)
    finally:
        conn.close()


def handle_update_goal(args: dict) -> str:
    """Update a goal's status, description, or steps."""
    goal_id = args.get("goal_id", "").strip()
    if not goal_id:
        return "Error: 'goal_id' is required"

    conn = _get_db()
    try:
        row = conn.execute("SELECT * FROM goals WHERE id = ?", (goal_id,)).fetchone()
        if not row:
            return f"Error: goal '{goal_id}' not found"

        goal = _row_to_dict(row)
        now = _now_iso()

        # Update status if provided
        new_status = args.get("status")
        if new_status:
            if new_status not in ("active", "paused", "completed", "failed"):
                return f"Error: invalid status '{new_status}'"
            conn.execute(
                "UPDATE goals SET status = ?, last_touched = ? WHERE id = ?",
                (new_status, now, goal_id),
            )

        # Update description if provided
        new_desc = args.get("description")
        if new_desc:
            conn.execute(
                "UPDATE goals SET description = ?, last_touched = ? WHERE id = ?",
                (new_desc, now, goal_id),
            )

        # Update a specific step if step_index provided
        step_index = args.get("step_index")
        if step_index is not None:
            steps = goal["steps"]
            if not isinstance(steps, list):
                steps = json.loads(steps) if isinstance(steps, str) else []

            if step_index < 0 or step_index >= len(steps):
                return f"Error: step_index {step_index} out of range (0-{len(steps) - 1})"

            step_status = args.get("step_status")
            step_result = args.get("step_result")

            if step_status:
                steps[step_index]["status"] = step_status
            if step_result:
                steps[step_index]["result"] = step_result

            conn.execute(
                "UPDATE goals SET steps = ?, last_touched = ? WHERE id = ?",
                (json.dumps(steps), now, goal_id),
            )

        conn.commit()

        # Return updated goal
        row = conn.execute("SELECT * FROM goals WHERE id = ?", (goal_id,)).fetchone()
        return json.dumps(_row_to_dict(row), indent=2)
    finally:
        conn.close()


def handle_delete_goal(args: dict) -> str:
    """Delete a goal."""
    goal_id = args.get("goal_id", "").strip()
    if not goal_id:
        return "Error: 'goal_id' is required"

    conn = _get_db()
    try:
        cursor = conn.execute("DELETE FROM goals WHERE id = ?", (goal_id,))
        conn.commit()
        if cursor.rowcount == 0:
            return f"Error: goal '{goal_id}' not found"
        return f"Goal '{goal_id}' deleted."
    finally:
        conn.close()


def handle_set_intention(args: dict) -> str:
    """Create a new intention (scheduled future action)."""
    description = args.get("description", "").strip()
    trigger_type = args.get("trigger_type", "").strip()
    trigger_value = args.get("trigger_value", "").strip()

    if not description:
        return "Error: 'description' is required"
    if trigger_type not in ("time", "condition", "event"):
        return "Error: 'trigger_type' must be one of: time, condition, event"
    if not trigger_value:
        return "Error: 'trigger_value' is required"

    max_fires = args.get("max_fires", 1)
    goal_id = args.get("goal_id")

    # Validate goal_id exists if provided
    if goal_id:
        conn = _get_db()
        try:
            row = conn.execute("SELECT id FROM goals WHERE id = ?", (goal_id,)).fetchone()
            if not row:
                return f"Error: goal '{goal_id}' not found"
        finally:
            conn.close()

    intention_id = _new_id()
    now = _now_iso()

    conn = _get_db()
    try:
        conn.execute(
            """INSERT INTO intentions
               (id, description, trigger_type, trigger_value, status,
                fire_count, max_fires, created_at, goal_id, metadata)
               VALUES (?, ?, ?, ?, 'pending', 0, ?, ?, ?, '{}')""",
            (intention_id, description, trigger_type, trigger_value,
             max_fires, now, goal_id),
        )
        conn.commit()

        row = conn.execute(
            "SELECT * FROM intentions WHERE id = ?", (intention_id,)
        ).fetchone()
        return json.dumps(_row_to_dict(row), indent=2)
    finally:
        conn.close()


def handle_get_due(args: dict) -> str:
    """Get all due/triggered intentions."""
    now = _now_iso()
    conn = _get_db()
    try:
        # Time-based: trigger_value <= now AND still eligible
        time_rows = conn.execute(
            """SELECT * FROM intentions
               WHERE trigger_type = 'time'
                 AND status = 'pending'
                 AND trigger_value <= ?
                 AND (max_fires = -1 OR fire_count < max_fires)""",
            (now,),
        ).fetchall()

        # Condition/event-based: return all pending (agent evaluates semantically)
        other_rows = conn.execute(
            """SELECT * FROM intentions
               WHERE trigger_type != 'time'
                 AND status = 'pending'
                 AND (max_fires = -1 OR fire_count < max_fires)""",
        ).fetchall()

        due = []

        # Process time-based triggers (auto-fire)
        for row in time_rows:
            intention = _row_to_dict(row)
            new_count = intention["fire_count"] + 1
            new_status = "fired" if (
                intention["max_fires"] != -1 and new_count >= intention["max_fires"]
            ) else "pending"
            conn.execute(
                "UPDATE intentions SET fire_count = ?, status = ? WHERE id = ?",
                (new_count, new_status, intention["id"]),
            )
            intention["fire_count"] = new_count
            intention["status"] = new_status
            intention["_trigger_reason"] = "time_reached"
            due.append(intention)

        # Include condition/event triggers for semantic evaluation
        for row in other_rows:
            intention = _row_to_dict(row)
            intention["_trigger_reason"] = "needs_evaluation"
            due.append(intention)

        conn.commit()

        if not due:
            return "No due intentions."
        return json.dumps(due, indent=2)
    finally:
        conn.close()


def handle_cancel_intention(args: dict) -> str:
    """Cancel a pending intention."""
    intention_id = args.get("intention_id", "").strip()
    if not intention_id:
        return "Error: 'intention_id' is required"

    conn = _get_db()
    try:
        cursor = conn.execute(
            "UPDATE intentions SET status = 'cancelled' WHERE id = ? AND status = 'pending'",
            (intention_id,),
        )
        conn.commit()
        if cursor.rowcount == 0:
            # Check if it exists at all
            row = conn.execute(
                "SELECT status FROM intentions WHERE id = ?", (intention_id,)
            ).fetchone()
            if not row:
                return f"Error: intention '{intention_id}' not found"
            return f"Error: intention '{intention_id}' is already '{row['status']}'"
        return f"Intention '{intention_id}' cancelled."
    finally:
        conn.close()


def handle_learn(args: dict) -> str:
    """Store or reinforce a learned behavior."""
    pattern = args.get("pattern", "").strip()
    if not pattern:
        return "Error: 'pattern' is required"

    confidence = args.get("confidence", 0.5)
    if not (0.0 <= confidence <= 1.0):
        return "Error: 'confidence' must be between 0.0 and 1.0"

    session_id = args.get("session_id")
    now = _now_iso()

    conn = _get_db()
    try:
        # Check for existing similar pattern (exact match for now; semantic
        # matching can be added later via RAG embedding comparison)
        existing = conn.execute(
            "SELECT * FROM learned_behaviors WHERE LOWER(pattern) = LOWER(?)",
            (pattern,),
        ).fetchone()

        if existing:
            # Reinforce existing behavior
            behavior = _row_to_dict(existing)
            new_count = behavior["times_confirmed"] + 1
            # Weighted average confidence, trending upward with confirmation
            new_confidence = min(
                1.0,
                (behavior["confidence"] * behavior["times_confirmed"] + confidence)
                / new_count,
            )

            sessions = behavior["source_sessions"]
            if not isinstance(sessions, list):
                sessions = []
            if session_id and session_id not in sessions:
                sessions.append(session_id)

            conn.execute(
                """UPDATE learned_behaviors
                   SET confidence = ?, times_confirmed = ?,
                       last_confirmed = ?, source_sessions = ?
                   WHERE id = ?""",
                (new_confidence, new_count, now, json.dumps(sessions), behavior["id"]),
            )
            conn.commit()

            row = conn.execute(
                "SELECT * FROM learned_behaviors WHERE id = ?", (behavior["id"],)
            ).fetchone()
            result = _row_to_dict(row)
            result["_action"] = "reinforced"
            return json.dumps(result, indent=2)
        else:
            # Create new behavior
            behavior_id = _new_id()
            sessions = [session_id] if session_id else []

            conn.execute(
                """INSERT INTO learned_behaviors
                   (id, pattern, confidence, source_sessions,
                    created_at, last_confirmed, times_confirmed)
                   VALUES (?, ?, ?, ?, ?, ?, 1)""",
                (behavior_id, pattern, confidence, json.dumps(sessions), now, now),
            )
            conn.commit()

            row = conn.execute(
                "SELECT * FROM learned_behaviors WHERE id = ?", (behavior_id,)
            ).fetchone()
            result = _row_to_dict(row)
            result["_action"] = "created"
            return json.dumps(result, indent=2)
    finally:
        conn.close()


def handle_get_behaviors(args: dict) -> str:
    """List learned behaviors sorted by confidence."""
    min_confidence = args.get("min_confidence", 0.3)
    limit = args.get("limit", 20)

    conn = _get_db()
    try:
        rows = conn.execute(
            """SELECT * FROM learned_behaviors
               WHERE confidence >= ?
               ORDER BY confidence DESC, times_confirmed DESC
               LIMIT ?""",
            (min_confidence, limit),
        ).fetchall()

        behaviors = [_row_to_dict(r) for r in rows]
        if not behaviors:
            return "No learned behaviors found."
        return json.dumps(behaviors, indent=2)
    finally:
        conn.close()


def handle_get_context(args: dict) -> str:
    """Build a full agenda summary for pre-prompt injection."""
    conn = _get_db()
    try:
        lines = []

        # --- Active Goals ---
        goals = conn.execute(
            """SELECT * FROM goals WHERE status = 'active'
               ORDER BY priority ASC, last_touched DESC LIMIT 10""",
        ).fetchall()

        if goals:
            lines.append("## Active Goals")
            for g in goals:
                goal = _row_to_dict(g)
                steps = goal.get("steps", [])
                done_count = sum(
                    1 for s in steps if isinstance(s, dict) and s.get("status") == "done"
                )
                total_steps = len(steps)

                parts = [f"[P{goal['priority']}] {goal['description']}"]
                if total_steps > 0:
                    parts.append(f"({done_count}/{total_steps} steps done)")
                if goal.get("deadline"):
                    parts.append(f"(due: {goal['deadline']})")

                lines.append(f"- {' '.join(parts)}")
            lines.append("")

        # --- Due Intentions ---
        now = _now_iso()
        time_due = conn.execute(
            """SELECT * FROM intentions
               WHERE trigger_type = 'time'
                 AND status = 'pending'
                 AND trigger_value <= ?
                 AND (max_fires = -1 OR fire_count < max_fires)""",
            (now,),
        ).fetchall()

        other_pending = conn.execute(
            """SELECT * FROM intentions
               WHERE trigger_type != 'time'
                 AND status = 'pending'
                 AND (max_fires = -1 OR fire_count < max_fires)
               LIMIT 10""",
        ).fetchall()

        all_due = list(time_due) + list(other_pending)
        if all_due:
            lines.append("## Due Intentions")
            for row in all_due:
                intent = _row_to_dict(row)
                fires_str = (
                    f"{intent['fire_count']}/inf"
                    if intent["max_fires"] == -1
                    else f"{intent['fire_count']}/{intent['max_fires']}"
                )
                trigger_label = f"{intent['trigger_type']} trigger"
                if intent["trigger_type"] == "time":
                    trigger_label = "time trigger, due now"

                parts = [intent["description"]]
                parts.append(f"({trigger_label}, {fires_str} fires)")

                lines.append(f"- {' '.join(parts)}")
            lines.append("")

        # --- Top Learned Behaviors ---
        behaviors = conn.execute(
            """SELECT * FROM learned_behaviors
               WHERE confidence >= 0.3
               ORDER BY confidence DESC LIMIT 10""",
        ).fetchall()

        if behaviors:
            lines.append("## Learned Behaviors")
            for b in behaviors:
                beh = _row_to_dict(b)
                lines.append(
                    f"- {beh['pattern']} (confidence: {beh['confidence']:.2f})"
                )
            lines.append("")

        if not lines:
            return "Agenda is empty. No goals, intentions, or learned behaviors."

        return "\n".join(lines)
    finally:
        conn.close()


# ---------------------------------------------------------------------------
# Handler dispatch table
# ---------------------------------------------------------------------------

TOOL_HANDLERS = {
    "agenda_create_goal":      handle_create_goal,
    "agenda_list_goals":       handle_list_goals,
    "agenda_update_goal":      handle_update_goal,
    "agenda_delete_goal":      handle_delete_goal,
    "agenda_set_intention":    handle_set_intention,
    "agenda_get_due":          handle_get_due,
    "agenda_cancel_intention": handle_cancel_intention,
    "agenda_learn":            handle_learn,
    "agenda_get_behaviors":    handle_get_behaviors,
    "agenda_get_context":      handle_get_context,
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
    # Ensure DB is created on startup
    conn = _get_db()
    conn.close()
    sys.stderr.write(f"[{SERVER_NAME}] Started (db: {DB_PATH})\n")
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
