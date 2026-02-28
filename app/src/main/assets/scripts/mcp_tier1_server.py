#!/data/user/0/com.mobilekinetic.agent/files/usr/bin/python3
"""
MCP Tier 1 Device API Wrapper for mK:a
================================================
Provides reliable access to Tier 1 Device API (port 5563) via JSON-RPC 2.0 over stdio.
Wraps HTTP requests so sub-agents that cannot use curl can reach device hardware.

Transport: stdio (read JSON-RPC from stdin line-by-line, write JSON to stdout)
Protocol: MCP (Model Context Protocol) v2024-11-05

Tools wrap these Tier 1 endpoints (24 total):
  GET (no params):
  - tier1_device_info: GET /device_info - device model, OS, build info
  - tier1_battery: GET /battery - battery level and charging state
  - tier1_wifi: GET /wifi - WiFi connection info
  - tier1_location: GET /location - GPS coordinates
  - tier1_sensors: GET /sensors - device sensor readings
  - tier1_screen_state: GET /screen/state - screen on/off state
  - tier1_sms_list: GET /sms/list - list SMS messages
  - tier1_contacts: GET /contacts - phone contacts
  - tier1_call_log: GET /call_log - call history
  - tier1_media_playing: GET /media/playing - currently playing media
  - tier1_calendar_list: GET /calendar/list - available calendars
  - tier1_apps_list: GET /apps/list - installed applications
  - tier1_clipboard: GET /clipboard - clipboard contents
  - tier1_volume: GET /volume - current volume levels

  GET (with query params):
  - tier1_calendar_events: GET /calendar/events?calendar_id=X - calendar events

  POST (JSON body):
  - tier1_sms_send: POST /sms/send - send SMS message
  - tier1_media_control: POST /media/control - media playback control
  - tier1_tts: POST /tts - text-to-speech
  - tier1_calendar_create: POST /calendar/create - create calendar event
  - tier1_apps_launch: POST /apps/launch - launch app by package name
  - tier1_brightness: POST /brightness - set screen brightness
  - tier1_torch: POST /torch - toggle flashlight
  - tier1_tasker_run: POST /tasker/run - run a Tasker task
  - tier1_notification: POST /notification - post notification
"""

import json
import sys
import os
import time
from urllib import request as urllib_request
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode

from mka_config import DEVICE_API_PORT, DEVICE_API_URL

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

TIER1_BASE = DEVICE_API_URL
SERVER_NAME = "mcp-tier1-server"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2024-11-05"

DEFAULT_TIMEOUT = 10  # seconds for HTTP requests

DEBUG_LOG_PATH = os.path.expanduser("~/mcp_tier1_debug.log")
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
            f.write(tail)
    except Exception:
        pass


def _log_debug(tool_name, arguments, result_preview, error="", elapsed=0, status_code=0):
    """Append a structured debug entry to the log file."""
    _rotate_log_if_needed()
    try:
        ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        result_str = str(result_preview)[:500]
        entry = (
            f"[{ts}] tool={tool_name} "
            f"args={json.dumps(arguments, ensure_ascii=False)[:300]} "
            f"status={status_code} elapsed={elapsed:.3f}s "
            f"error={error!r} "
            f"result={result_str}\n"
        )
        with open(DEBUG_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(entry)
    except Exception:
        pass


def _log_stderr(msg):
    """Write a debug message to stderr (visible in launcher logs)."""
    try:
        sys.stderr.write(f"[{SERVER_NAME}] {msg}\n")
        sys.stderr.flush()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# HTTP Client - talks to Tier 1 Device API on port 5563
# ---------------------------------------------------------------------------

def _http_request(endpoint, method="GET", data=None, params=None):
    """Make an HTTP request to the Tier 1 Device API server.

    Args:
        endpoint: URL path (e.g. "/battery")
        method: "GET" or "POST"
        data: dict to send as JSON body (POST only)
        params: dict of query parameters (GET only)

    Returns:
        str: response body text
    """
    url = f"{TIER1_BASE}{endpoint}"
    if params:
        qs = urlencode({k: v for k, v in params.items() if v is not None})
        if qs:
            url = f"{url}?{qs}"

    try:
        if method == "POST" and data is not None:
            body = json.dumps(data).encode("utf-8")
            req = urllib_request.Request(
                url,
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
        else:
            req = urllib_request.Request(url, method=method)

        with urllib_request.urlopen(req, timeout=DEFAULT_TIMEOUT) as resp:
            status = resp.getcode()
            text = resp.read().decode("utf-8")
            return status, text

    except HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8")
        except Exception:
            pass
        return e.code, f"HTTP {e.code}: {e.reason}\n{body}".strip()

    except URLError as e:
        return 0, f"Connection error (is DeviceApiServer running on port {DEVICE_API_PORT}?): {e.reason}"

    except TimeoutError:
        return 0, f"Request timed out after {DEFAULT_TIMEOUT}s: {method} {endpoint}"

    except Exception as e:
        return 0, f"Unexpected error: {type(e).__name__}: {e}"


# ---------------------------------------------------------------------------
# Tool Handlers - each wraps one Tier 1 endpoint
# ---------------------------------------------------------------------------

# --- GET endpoints (no parameters) ---

def tool_tier1_device_info(args):
    """GET /device_info"""
    return _http_request("/device_info")


def tool_tier1_battery(args):
    """GET /battery"""
    return _http_request("/battery")


def tool_tier1_wifi(args):
    """GET /wifi"""
    return _http_request("/wifi")


def tool_tier1_location(args):
    """GET /location"""
    return _http_request("/location")


def tool_tier1_sensors(args):
    """GET /sensors"""
    return _http_request("/sensors")


def tool_tier1_screen_state(args):
    """GET /screen/state"""
    return _http_request("/screen/state")


def tool_tier1_sms_list(args):
    """GET /sms/list"""
    return _http_request("/sms/list")


def tool_tier1_contacts(args):
    """GET /contacts"""
    return _http_request("/contacts")


def tool_tier1_call_log(args):
    """GET /call_log"""
    return _http_request("/call_log")


def tool_tier1_media_playing(args):
    """GET /media/playing"""
    return _http_request("/media/playing")


def tool_tier1_calendar_list(args):
    """GET /calendar/list"""
    return _http_request("/calendar/list")


def tool_tier1_apps_list(args):
    """GET /apps/list"""
    return _http_request("/apps/list")


def tool_tier1_clipboard(args):
    """GET /clipboard"""
    return _http_request("/clipboard")


def tool_tier1_volume(args):
    """GET /volume"""
    return _http_request("/volume")


# --- GET with query parameters ---

def tool_tier1_calendar_events(args):
    """GET /calendar/events with optional calendar_id param."""
    params = {}
    if args.get("calendar_id"):
        params["calendar_id"] = args["calendar_id"]
    return _http_request("/calendar/events", params=params)


# --- POST endpoints (JSON body) ---

def tool_tier1_sms_send(args):
    """POST /sms/send - requires number and message."""
    data = {
        "to": args.get("number", ""),
        "message": args.get("message", ""),
    }
    return _http_request("/sms/send", method="POST", data=data)


def tool_tier1_media_control(args):
    """POST /media/control - action: play/pause/next/prev."""
    data = {"action": args.get("action", "pause")}
    return _http_request("/media/control", method="POST", data=data)


def tool_tier1_tts(args):
    """POST /tts - text to speak."""
    data = {"text": args.get("text", "")}
    return _http_request("/tts", method="POST", data=data)


def tool_tier1_calendar_create(args):
    """POST /calendar/create - create a calendar event."""
    data = {}
    for key in ("title", "description", "start_time", "end_time", "calendar_id"):
        if args.get(key) is not None:
            data[key] = args[key]
    return _http_request("/calendar/create", method="POST", data=data)


def tool_tier1_apps_launch(args):
    """POST /apps/launch - launch app by package name."""
    data = {"package": args.get("package", "")}
    return _http_request("/apps/launch", method="POST", data=data)


def tool_tier1_brightness(args):
    """POST /brightness - set screen brightness (0-255)."""
    data = {"level": args.get("level", 128)}
    return _http_request("/brightness", method="POST", data=data)


def tool_tier1_torch(args):
    """POST /torch - toggle flashlight on/off."""
    data = {"state": args.get("state", "off")}
    return _http_request("/torch", method="POST", data=data)


def tool_tier1_tasker_run(args):
    """POST /tasker/run - run a Tasker task by name."""
    data = {"task": args.get("tasker_name", "")}
    return _http_request("/tasker/run", method="POST", data=data)


def tool_tier1_notification(args):
    """POST /notification - post a notification."""
    data = {
        "title": args.get("title", ""),
        "content": args.get("message", ""),
    }
    return _http_request("/notification", method="POST", data=data)


# ---------------------------------------------------------------------------
# MCP Tool Definitions (JSON Schema)
# ---------------------------------------------------------------------------

TOOLS = [
    # ---- GET endpoints (no parameters) ---- #
    {
        "name": "tier1_device_info",
        "description": "Get device model, Android OS version, and build info.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_battery",
        "description": "Get battery level, charging state, temperature, and voltage.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_wifi",
        "description": "Get WiFi connection info: SSID, signal strength, IP address, frequency.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_location",
        "description": "Get GPS coordinates (latitude, longitude, accuracy).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_sensors",
        "description": "List all device sensor readings (accelerometer, gyroscope, light, proximity, etc.).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_screen_state",
        "description": "Get screen on/off state and current brightness level.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_sms_list",
        "description": "List SMS messages from the device inbox.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_contacts",
        "description": "List phone contacts stored on the device.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_call_log",
        "description": "Get call history (incoming, outgoing, missed calls).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_media_playing",
        "description": "Get metadata for currently playing media (title, artist, album).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_calendar_list",
        "description": "List all available calendars on the device.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_apps_list",
        "description": "List all installed applications with package names.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_clipboard",
        "description": "Get current clipboard text contents.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "tier1_volume",
        "description": "Get current volume levels for all audio streams (media, ring, alarm, etc.).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },

    # ---- GET with query parameters ---- #
    {
        "name": "tier1_calendar_events",
        "description": (
            "Get calendar events. Optionally filter by calendar_id "
            "(use tier1_calendar_list first to find available calendar IDs)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "calendar_id": {
                    "type": "string",
                    "description": "Calendar ID to filter events. Omit to get events from all calendars.",
                },
            },
            "required": [],
        },
    },

    # ---- POST endpoints (JSON body) ---- #
    {
        "name": "tier1_sms_send",
        "description": "Send an SMS message to a phone number.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "number": {
                    "type": "string",
                    "description": "Phone number to send the SMS to.",
                },
                "message": {
                    "type": "string",
                    "description": "Text message content to send.",
                },
            },
            "required": ["number", "message"],
        },
    },
    {
        "name": "tier1_media_control",
        "description": "Control media playback: play, pause, skip to next track, or go to previous track.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Playback action to perform.",
                    "enum": ["play", "pause", "next", "prev"],
                },
            },
            "required": ["action"],
        },
    },
    {
        "name": "tier1_tts",
        "description": "Speak text aloud through the device's text-to-speech engine.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "Text to speak aloud.",
                },
            },
            "required": ["text"],
        },
    },
    {
        "name": "tier1_calendar_create",
        "description": "Create a new calendar event with title, description, start/end times, and optional calendar ID.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Event title.",
                },
                "description": {
                    "type": "string",
                    "description": "Event description or notes.",
                },
                "start_time": {
                    "type": "string",
                    "description": "Event start time (epoch milliseconds as string, or ISO 8601).",
                },
                "end_time": {
                    "type": "string",
                    "description": "Event end time (epoch milliseconds as string, or ISO 8601).",
                },
                "calendar_id": {
                    "type": "string",
                    "description": "Target calendar ID. Use tier1_calendar_list to find valid IDs.",
                },
            },
            "required": [],
        },
    },
    {
        "name": "tier1_apps_launch",
        "description": "Launch an installed application by its package name (e.g. 'com.google.android.gm').",
        "inputSchema": {
            "type": "object",
            "properties": {
                "package": {
                    "type": "string",
                    "description": "Android package name of the app to launch.",
                },
            },
            "required": ["package"],
        },
    },
    {
        "name": "tier1_brightness",
        "description": "Set the screen brightness level (0 = minimum, 255 = maximum).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "level": {
                    "type": "number",
                    "description": "Brightness level from 0 (dimmest) to 255 (brightest).",
                    "minimum": 0,
                    "maximum": 255,
                },
            },
            "required": ["level"],
        },
    },
    {
        "name": "tier1_torch",
        "description": "Toggle the device flashlight (torch) on or off.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "state": {
                    "type": "string",
                    "description": "Desired torch state.",
                    "enum": ["on", "off"],
                },
            },
            "required": ["state"],
        },
    },
    {
        "name": "tier1_tasker_run",
        "description": "Run a Tasker task by name. Use this to trigger Tasker automations.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "tasker_name": {
                    "type": "string",
                    "description": "Name of the Tasker task to run.",
                },
            },
            "required": ["tasker_name"],
        },
    },
    {
        "name": "tier1_notification",
        "description": "Post a notification to the device notification shade.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Notification title.",
                },
                "message": {
                    "type": "string",
                    "description": "Notification body text.",
                },
            },
            "required": [],
        },
    },
]


# ---------------------------------------------------------------------------
# Tool Dispatch Table
# ---------------------------------------------------------------------------

TOOL_DISPATCH = {
    "tier1_device_info": tool_tier1_device_info,
    "tier1_battery": tool_tier1_battery,
    "tier1_wifi": tool_tier1_wifi,
    "tier1_location": tool_tier1_location,
    "tier1_sensors": tool_tier1_sensors,
    "tier1_screen_state": tool_tier1_screen_state,
    "tier1_sms_list": tool_tier1_sms_list,
    "tier1_contacts": tool_tier1_contacts,
    "tier1_call_log": tool_tier1_call_log,
    "tier1_media_playing": tool_tier1_media_playing,
    "tier1_calendar_list": tool_tier1_calendar_list,
    "tier1_apps_list": tool_tier1_apps_list,
    "tier1_clipboard": tool_tier1_clipboard,
    "tier1_volume": tool_tier1_volume,
    "tier1_calendar_events": tool_tier1_calendar_events,
    "tier1_sms_send": tool_tier1_sms_send,
    "tier1_media_control": tool_tier1_media_control,
    "tier1_tts": tool_tier1_tts,
    "tier1_calendar_create": tool_tier1_calendar_create,
    "tier1_apps_launch": tool_tier1_apps_launch,
    "tier1_brightness": tool_tier1_brightness,
    "tier1_torch": tool_tier1_torch,
    "tier1_tasker_run": tool_tier1_tasker_run,
    "tier1_notification": tool_tier1_notification,
}


# ---------------------------------------------------------------------------
# MCP JSON-RPC Protocol Helpers
# ---------------------------------------------------------------------------

def _make_response(req_id, result):
    """Build a JSON-RPC 2.0 success response."""
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


def _make_error(req_id, code, message):
    """Build a JSON-RPC 2.0 error response."""
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": code, "message": message},
    }


def _write_response(response):
    """Write a JSON-RPC response to stdout, one line, flush immediately."""
    try:
        sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


# ---------------------------------------------------------------------------
# MCP Request Handler
# ---------------------------------------------------------------------------

def handle_request(request):
    """Process a single MCP JSON-RPC request and return a response (or None)."""
    req_id = request.get("id")
    method = request.get("method", "")
    params = request.get("params", {})

    # --- Initialize ---
    if method == "initialize":
        _log_stderr("initialize received")
        return _make_response(req_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {
                "name": SERVER_NAME,
                "version": SERVER_VERSION,
            },
        })

    # --- Notifications (no response needed) ---
    if method.startswith("notifications/"):
        _log_stderr(f"notification: {method}")
        return None

    # --- List Tools ---
    if method == "tools/list":
        _log_stderr(f"tools/list: returning {len(TOOLS)} tools")
        return _make_response(req_id, {"tools": TOOLS})

    # --- Call Tool ---
    if method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})

        handler = TOOL_DISPATCH.get(tool_name)
        if handler is None:
            _log_stderr(f"unknown tool: {tool_name}")
            return _make_error(req_id, -32602, f"Unknown tool: {tool_name}")

        _log_stderr(f"calling tool: {tool_name}")
        t0 = time.time()

        try:
            status_code, result_text = handler(arguments)
        except Exception as exc:
            elapsed = time.time() - t0
            error_msg = f"{type(exc).__name__}: {exc}"
            _log_debug(tool_name, arguments, "(exception during execution)",
                       error=error_msg, elapsed=elapsed)
            return _make_response(req_id, {
                "content": [{"type": "text", "text": f"Error: {error_msg}"}],
                "isError": True,
            })

        elapsed = time.time() - t0
        is_error = status_code != 0 and status_code != 200

        _log_debug(tool_name, arguments, result_text,
                   error="" if not is_error else result_text,
                   elapsed=elapsed, status_code=status_code)

        return _make_response(req_id, {
            "content": [{"type": "text", "text": result_text}],
            "isError": is_error,
        })

    # --- Unknown method ---
    _log_stderr(f"unknown method: {method}")
    return _make_error(req_id, -32601, f"Unknown method: {method}")


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

def main():
    """Read JSON-RPC requests from stdin, dispatch, write responses to stdout."""
    _log_stderr(f"{SERVER_NAME} v{SERVER_VERSION} starting (protocol {PROTOCOL_VERSION})")
    _log_stderr(f"Tier 1 Device API base: {TIER1_BASE}")
    _log_stderr(f"Debug log: {DEBUG_LOG_PATH}")

    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue

            try:
                request = json.loads(line)
            except json.JSONDecodeError as exc:
                _log_stderr(f"invalid JSON: {exc}")
                # Send parse error per JSON-RPC spec
                try:
                    _write_response(_make_error(None, -32700, f"Parse error: {exc}"))
                except Exception:
                    pass
                continue

            try:
                response = handle_request(request)
            except Exception as exc:
                _log_stderr(f"handler exception: {exc}")
                response = _make_error(
                    request.get("id"), -32603, f"Internal error: {exc}"
                )

            if response is not None:
                _write_response(response)

    except KeyboardInterrupt:
        _log_stderr("shutting down (KeyboardInterrupt)")
    except Exception as exc:
        _log_stderr(f"fatal error: {exc}")
    finally:
        _log_stderr(f"{SERVER_NAME} stopped")


if __name__ == "__main__":
    main()
