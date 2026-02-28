#!/usr/bin/env python3
"""
Unified Device MCP Server
Consolidates all device APIs (Tier 1 & Tier 2) into a single MCP interface
"""

import asyncio
import json
import logging
import time
from typing import Any, Dict, List, Optional
import httpx
from mcp.server import Server
from mcp.types import Tool, TextContent, ImageContent, CallToolResult
from mcp import types
import os
import sys
from mka_config import DEVICE_API_URL, MCP_TIER2_URL, RAG_URL, VAULT_URL
from rlm_unified_implementation import RLMEngine

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def _register_mcp_identity():
    """Write PID file and set /proc/self/comm for watchdog exemption."""
    home = os.environ.get('HOME', '/data/user/0/com.mobilekinetic.agent/files/home')
    pid_file = os.path.join(home, '.mcp_server.pid')
    try:
        tmp = pid_file + '.tmp'
        with open(tmp, 'w') as f:
            f.write(str(os.getpid()))
        os.replace(tmp, pid_file)
        sys.stderr.write(f"[MCP] Registered PID {os.getpid()} at {pid_file}\n")
    except Exception as e:
        sys.stderr.write(f"[MCP] Failed to write PID file: {e}\n")
    # Set process name so /proc/self/comm reads "mcp_server" instead of "linker64"
    try:
        with open('/proc/self/comm', 'wb') as f:
            f.write(b'mcp_server')
    except OSError:
        pass

# API endpoints
TIER1_BASE = DEVICE_API_URL
TIER2_BASE = MCP_TIER2_URL
RAG_BASE = RAG_URL
HA_BASE = os.environ.get("HA_BASE_URL", "")  # Populated from user_config.json at runtime
VAULT_BASE = VAULT_URL

class UnifiedDeviceMCP:
    def __init__(self):
        self.server = Server("unified-device")
        limits = httpx.Limits(max_connections=200, max_keepalive_connections=50)
        transport = httpx.AsyncHTTPTransport(retries=2)
        self.client = httpx.AsyncClient(timeout=30.0, limits=limits, transport=transport)
        try:
            self.rlm_engine = RLMEngine(self)
        except Exception:
            self.rlm_engine = None
        self.setup_tools()

    async def _ha_proxy_request(self, method: str, path: str, body: dict = None):
        """Make authenticated HA request through vault proxy.

        Returns a dict with keys: status (int), body (str/dict), headers (dict)
        """
        payload = {
            "credentialName": "ha_token",
            "url": f"{HA_BASE}{path}",
            "method": method,
            "injectAs": "bearer_header",
            "context": "ha_mcp_tool",
        }
        if body is not None:
            payload["body"] = json.dumps(body)
        try:
            resp = await self.client.post(
                f"{VAULT_BASE}/vault/proxy-http", json=payload
            )
            if resp.status_code == 200:
                return resp.json()
            return {"status": resp.status_code, "body": resp.text, "headers": {}}
        except Exception as e:
            return {"status": 0, "body": str(e), "headers": {}}

    def setup_tools(self):
        """Register all MCP tools"""

        # Tool registry - stores functions and Tool definitions
        self._tools = {}

        def _register(name, description, schema):
            """Decorator to register a tool with the MCP server"""
            def decorator(func):
                self._tools[name] = {
                    "func": func,
                    "tool": Tool(name=name, description=description, inputSchema=schema)
                }
                return func
            return decorator

        # SMS Tools
        @_register("device_sms_list", "List SMS messages from inbox or sent", {"type": "object","properties": {"type": {"type": "string"},"limit": {"type": "integer"}}})
        async def device_sms_list(type: str = "inbox", limit: int = 10) -> CallToolResult:
            """List SMS messages from inbox or sent
            Args:
                type: "inbox" or "sent"
                limit: Number of messages to retrieve (max 50)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/sms/list",
                    params={"type": type, "limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing SMS: {str(e)}")]
                )

        @_register("device_sms_send", "Send an SMS message", {"type": "object","properties": {"phone_number": {"type": "string"},"message": {"type": "string"}},"required": ["phone_number","message"]})
        async def device_sms_send(phone_number: str, message: str) -> CallToolResult:
            """Send an SMS message
            Args:
                phone_number: Phone number with country code (e.g. +1234567890)
                message: Message text to send
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/sms/send",
                    json={"phone_number": phone_number, "message": message}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending SMS: {str(e)}")]
                )

        # MMS Tools
        @_register("device_mms_list", "List MMS messages with pagination and box filtering", {"type": "object","properties": {"box": {"type": "string","description": "Message box: inbox, sent, drafts, outbox, all (default: inbox)"},"limit": {"type": "integer","description": "Max messages to return, 1-50 (default: 20)"},"offset": {"type": "integer","description": "Pagination offset (default: 0)"}}})
        async def device_mms_list(box: str = "inbox", limit: int = 20, offset: int = 0) -> CallToolResult:
            """List MMS messages with pagination and box filtering
            Args:
                box: inbox, sent, drafts, outbox, all
                limit: Max messages (1-50)
                offset: Pagination offset
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/mms/list",
                    params={"box": box, "limit": limit, "offset": offset}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing MMS: {str(e)}")],
                    isError=True
                )

        @_register("device_mms_read", "Read a single MMS message with full metadata, addresses, and parts", {"type": "object","properties": {"id": {"type": "integer","description": "MMS message ID (required)"},"include_media": {"type": "boolean","description": "Include base64 image data (default: false)"}},"required": ["id"]})
        async def device_mms_read(id: int, include_media: bool = False) -> CallToolResult:
            """Read a single MMS message by ID
            Args:
                id: MMS message ID
                include_media: If true, include base64-encoded image data
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/mms/read",
                    params={"id": id, "include_media": str(include_media).lower()}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error reading MMS: {str(e)}")],
                    isError=True
                )

        @_register("device_mms_send", "Send an MMS message with text and optional image", {"type": "object","properties": {"recipients": {"type": "array","items": {"type": "string"},"description": "Array of phone numbers"},"message": {"type": "string","description": "Text body of the MMS"},"subject": {"type": "string","description": "Optional subject line"},"image_uri": {"type": "string","description": "Content URI of image to attach"},"image_base64": {"type": "string","description": "Base64-encoded image data (alternative to image_uri)"},"image_content_type": {"type": "string","description": "MIME type of image (default: image/jpeg)"}},"required": ["recipients"]})
        async def device_mms_send(
            recipients: list,
            message: str = "",
            subject: str = "",
            image_uri: str = "",
            image_base64: str = "",
            image_content_type: str = "image/jpeg"
        ) -> CallToolResult:
            """Send an MMS message
            Args:
                recipients: Array of phone numbers (required)
                message: Text body of the MMS
                subject: Optional subject line
                image_uri: Content URI of image to attach
                image_base64: Base64-encoded image data (alternative to image_uri)
                image_content_type: MIME type of image (default: image/jpeg)
            """
            try:
                payload = {"recipients": recipients}
                if message:
                    payload["message"] = message
                if subject:
                    payload["subject"] = subject
                if image_uri:
                    payload["image_uri"] = image_uri
                if image_base64:
                    payload["image_base64"] = image_base64
                if image_content_type and image_content_type != "image/jpeg":
                    payload["image_content_type"] = image_content_type
                resp = await self.client.post(f"{TIER1_BASE}/mms/send", json=payload)
                resp.raise_for_status()
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(resp.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending MMS: {str(e)}")],
                    isError=True
                )

        # JTX Board Task Tools
        @_register("device_tasks_list", "List JTX Board tasks", {"type": "object","properties": {"limit": {"type": "integer"}}})
        async def device_tasks_list(limit: int = 100) -> CallToolResult:
            """List JTX Board tasks
            Args:
                limit: Maximum number of tasks to retrieve
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/tasks/list",
                    params={"limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing tasks: {str(e)}")]
                )

        @_register("device_tasks_create", "Create a new JTX Board task", {"type": "object","properties": {"summary": {"type": "string"},"description": {"type": "string"},"status": {"type": "string"},"priority": {"type": "integer"},"start": {"type": "integer"},"due": {"type": "integer"}},"required": ["summary"]})
        async def device_tasks_create(
            summary: str,
            description: str = "",
            status: str = "NEEDS-ACTION",
            priority: int = 5,
            start: int = 0,
            due: int = 0
        ) -> CallToolResult:
            """Create a new JTX Board task
            Args:
                summary: Task title/summary
                description: Task description (optional)
                status: Task status (NEEDS-ACTION, IN-PROCESS, COMPLETED, CANCELLED)
                priority: Task priority (1-9, 5 is normal)
                start: Start timestamp in milliseconds (0 for none)
                due: Due timestamp in milliseconds (0 for none)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasks/create",
                    json={
                        "summary": summary,
                        "description": description,
                        "status": status,
                        "priority": priority,
                        "start": start,
                        "due": due
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error creating task: {str(e)}")]
                )

        @_register("device_tasks_update", "Update an existing JTX Board task", {"type": "object","properties": {"task_id": {"type": "integer"},"summary": {"type": "string"},"description": {"type": "string"},"status": {"type": "string"},"priority": {"type": "integer"},"percent": {"type": "integer"}},"required": ["task_id"]})
        async def device_tasks_update(
            task_id: int,
            summary: Optional[str] = None,
            description: Optional[str] = None,
            status: Optional[str] = None,
            priority: Optional[int] = None,
            percent: Optional[int] = None
        ) -> CallToolResult:
            """Update an existing JTX Board task
            Args:
                task_id: Task ID to update
                summary: New task title (optional)
                description: New description (optional)
                status: New status (optional)
                priority: New priority (optional)
                percent: Completion percentage (optional)
            """
            try:
                body = {"task_id": task_id}
                if summary is not None: body["summary"] = summary
                if description is not None: body["description"] = description
                if status is not None: body["status"] = status
                if priority is not None: body["priority"] = priority
                if percent is not None: body["percent"] = percent

                response = await self.client.post(
                    f"{TIER1_BASE}/tasks/update",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error updating task: {str(e)}")]
                )

        @_register("device_tasks_delete", "Delete a JTX Board task", {"type": "object","properties": {"task_id": {"type": "integer"}},"required": ["task_id"]})
        async def device_tasks_delete(task_id: int) -> CallToolResult:
            """Delete a JTX Board task
            Args:
                task_id: ID of the task to delete
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/tasks/delete",
                    params={"task_id": task_id}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting task: {str(e)}")]
                )

        # Contact Tools
        @_register("device_contacts_list", "List phone contacts", {"type": "object","properties": {"limit": {"type": "integer"}}})
        async def device_contacts_list(limit: int = 100) -> CallToolResult:
            """List phone contacts
            Args:
                limit: Maximum number of contacts to retrieve
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/contacts",
                    params={"limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing contacts: {str(e)}")]
                )

        # Clipboard Tools
        @_register("device_clipboard_get", "Get current clipboard content", {"type": "object","properties": {},"required": []})
        async def device_clipboard_get() -> CallToolResult:
            """Get current clipboard content"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/clipboard")
                return CallToolResult(
                    content=[TextContent(type="text", text=response.json().get("clipboard", ""))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting clipboard: {str(e)}")]
                )

        @_register("device_clipboard_set", "Set clipboard content", {"type": "object","properties": {"text": {"type": "string"}},"required": ["text"]})
        async def device_clipboard_set(text: str) -> CallToolResult:
            """Set clipboard content
            Args:
                text: Text to copy to clipboard
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/clipboard",
                    json={"text": text}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting clipboard: {str(e)}")]
                )

        # TTS Tool
        @_register("device_tts_speak", "Speak text using Text-to-Speech", {"type": "object","properties": {"text": {"type": "string"}},"required": ["text"]})
        async def device_tts_speak(text: str) -> CallToolResult:
            """Speak text using Text-to-Speech
            Args:
                text: Text to speak
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tts",
                    json={"text": text}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with TTS: {str(e)}")]
                )

        # Hardware Control Tools
        @_register("device_torch_control", "Control the flashlight/torch", {"type": "object","properties": {"enabled": {"type": "boolean"}},"required": ["enabled"]})
        async def device_torch_control(enabled: bool) -> CallToolResult:
            """Control the flashlight/torch
            Args:
                enabled: True to turn on flashlight, False to turn off
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/torch",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error controlling torch: {str(e)}")]
                )

        @_register("device_vibrate", "Advanced vibration control with effects and patterns", {"type": "object","properties": {"duration_ms": {"type": "integer"},"effect": {"type": "string"},"pattern": {"type": "array","items": {"type": "integer"}}}})
        async def device_vibrate(duration_ms: int = 200, effect: str = None, pattern: List[int] = None) -> CallToolResult:
            """Advanced vibration control with effects and patterns
            Args:
                duration_ms: Duration in milliseconds for simple vibration (default: 200)
                effect: Predefined effect - "click", "tick", "double_click", "heavy_click" (optional)
                pattern: Custom pattern as list of [on_ms, off_ms, on_ms, ...] (optional)

            Examples:
                - Simple: device_vibrate(500) - vibrates for 500ms
                - Effect: device_vibrate(effect="heavy_click") - strong haptic feedback
                - Pattern: device_vibrate(pattern=[100, 50, 100, 50, 300]) - morse-like pattern
            """
            try:
                # Build request based on parameters
                if effect:
                    effect_map = {
                        "click": "EFFECT_CLICK",
                        "tick": "EFFECT_TICK",
                        "double_click": "EFFECT_DOUBLE_CLICK",
                        "heavy_click": "EFFECT_HEAVY_CLICK"
                    }
                    response = await self.client.post(
                        f"{TIER1_BASE}/vibrate/effect",
                        json={"effect": effect_map.get(effect, "EFFECT_CLICK")}
                    )
                elif pattern:
                    response = await self.client.post(
                        f"{TIER1_BASE}/vibrate/pattern",
                        json={"pattern": pattern}
                    )
                else:
                    response = await self.client.post(
                        f"{TIER1_BASE}/vibrate",
                        json={"duration_ms": duration_ms}
                    )

                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                if "404" in str(e) or "Not Found" in str(e):
                    try:
                        response = await self.client.post(
                            f"{TIER1_BASE}/vibrate",
                            json={"duration_ms": duration_ms}
                        )
                        result = response.json()
                        result["note"] = "Advanced vibration not yet implemented, used simple vibration"
                        return CallToolResult(
                            content=[TextContent(type="text", text=json.dumps(result, indent=2))]
                        )
                    except:
                        pass

                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with vibration: {str(e)}")]
                )

        @_register("device_alarm_set", "Set a device alarm using AlarmManager", {"type": "object","properties": {"trigger_time_ms": {"type": "integer"},"alarm_type": {"type": "string"},"message": {"type": "string"},"wake_device": {"type": "boolean"}},"required": ["trigger_time_ms"]})
        async def device_alarm_set(
            trigger_time_ms: int,
            alarm_type: str = "exact",
            message: str = "",
            wake_device: bool = True
        ) -> CallToolResult:
            """Set a device alarm using AlarmManager
            Args:
                trigger_time_ms: Trigger time in milliseconds (UTC for RTC, elapsed for ELAPSED)
                alarm_type: Type of alarm - "exact", "inexact", "window", "repeating", "alarm_clock"
                message: Optional message/description for the alarm
                wake_device: Whether to wake the device when alarm triggers
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/alarms/schedule",
                    json={
                        "trigger_time_ms": trigger_time_ms,
                        "alarm_type": alarm_type,
                        "message": message,
                        "wake_device": wake_device
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                if "404" in str(e) or "Not Found" in str(e):
                    try:
                        response = await self.client.post(
                            f"{TIER1_BASE}/alarms/set",
                            json={"time": trigger_time_ms, "message": message}
                        )
                        result = response.json()
                        result["note"] = "Using simple alarm intent, advanced AlarmManager not yet implemented"
                        return CallToolResult(
                            content=[TextContent(type="text", text=json.dumps(result, indent=2))]
                        )
                    except:
                        pass

                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting alarm: {str(e)}")]
                )

        # Network & Connectivity Tools
        @_register("device_network_capabilities", "Get detailed network capabilities and connection status", {"type": "object","properties": {},"required": []})
        async def device_network_capabilities() -> CallToolResult:
            """Get detailed network capabilities and connection status
            Returns information about current network including:
            - Transport types (WiFi, Cellular, Ethernet, Bluetooth)
            - Capabilities (Internet, MMS, Validated, Not Metered)
            - Link properties (bandwidth, latency)
            - Connection quality metrics
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/network/capabilities")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                if "404" in str(e) or "Not Found" in str(e):
                    try:
                        response = await self.client.get(f"{TIER1_BASE}/wifi")
                        result = response.json()
                        result["note"] = "Advanced network capabilities not yet implemented, showing WiFi info"
                        return CallToolResult(
                            content=[TextContent(type="text", text=json.dumps(result, indent=2))]
                        )
                    except:
                        pass
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting network capabilities: {str(e)}")]
                )

        @_register("device_network_request", "Request a specific type of network connection", {"type": "object","properties": {"transport_type": {"type": "string"},"capabilities": {"type": "array","items": {"type": "string"}},"min_bandwidth_kbps": {"type": "integer"}}})
        async def device_network_request(
            transport_type: str = "any",
            capabilities: List[str] = None,
            min_bandwidth_kbps: int = 0
        ) -> CallToolResult:
            """Request a specific type of network connection
            Args:
                transport_type: Type of transport - "wifi", "cellular", "ethernet", "bluetooth", "any"
                capabilities: List of required capabilities - ["internet", "not_metered", "validated", "not_roaming"]
                min_bandwidth_kbps: Minimum bandwidth requirement in kilobits per second
            """
            try:
                if capabilities is None:
                    capabilities = ["internet"]

                response = await self.client.post(
                    f"{TIER1_BASE}/network/request",
                    json={
                        "transport_type": transport_type,
                        "capabilities": capabilities,
                        "min_bandwidth_kbps": min_bandwidth_kbps
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error requesting network: {str(e)}")]
                )

        @_register("device_data_usage", "Get data usage statistics", {"type": "object","properties": {"days": {"type": "integer"}}})
        async def device_data_usage(days: int = 30) -> CallToolResult:
            """Get data usage statistics
            Args:
                days: Number of days to get statistics for (default: 30)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/network/data_usage",
                    params={"days": days}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting data usage: {str(e)}")]
                )

        @_register("device_bluetooth_scan", "Scan for nearby Bluetooth devices", {"type": "object","properties": {"duration_seconds": {"type": "integer"}}})
        async def device_bluetooth_scan(duration_seconds: int = 10) -> CallToolResult:
            """Scan for nearby Bluetooth devices
            Args:
                duration_seconds: How long to scan for devices (max 30 seconds)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/bluetooth/scan",
                    json={"duration_seconds": min(duration_seconds, 30)}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error scanning Bluetooth: {str(e)}")]
                )

        @_register("device_bluetooth_headset_status",
                   "Get Bluetooth HFP headset status - connected devices, audio state, NR/VR capabilities",
                   {"type": "object", "properties": {}, "required": []})
        async def device_bluetooth_headset_status() -> CallToolResult:
            """Get Bluetooth HFP headset status including connected devices and capabilities"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/bluetooth/headset/status")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting headset status: {str(e)}")],
                    isError=True
                )

        @_register("device_bluetooth_headset_voice_recognition",
                   "Start or stop voice recognition on a connected Bluetooth HFP headset",
                   {
                       "type": "object",
                       "properties": {
                           "action": {"type": "string", "description": "Either 'start' or 'stop'"},
                           "device_address": {"type": "string", "description": "Bluetooth MAC address of target headset (optional, defaults to first connected)"}
                       },
                       "required": ["action"]
                   })
        async def device_bluetooth_headset_voice_recognition(action: str, device_address: str = None) -> CallToolResult:
            """Start or stop voice recognition on a connected HFP headset
            Args:
                action: 'start' or 'stop'
                device_address: Optional MAC address of target headset
            """
            try:
                payload = {"action": action}
                if device_address:
                    payload["device_address"] = device_address
                response = await self.client.post(
                    f"{TIER1_BASE}/bluetooth/headset/voice_recognition",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with headset voice recognition: {str(e)}")],
                    isError=True
                )

        @_register("device_network_active", "Get the current active network connection info including type, IP addresses, DNS, and internet validation status", {"type": "object","properties": {},"required": []})
        async def device_network_active() -> CallToolResult:
            """Get the current active network connection info
            Returns details about the currently active network including:
            - connected: whether a network is active
            - type: wifi, cellular, ethernet, vpn, or unknown
            - hasInternet: whether the network has internet capability
            - hasValidated: whether internet connectivity is validated
            - isMetered: whether the network is metered
            - linkAddresses: list of IP addresses and prefix lengths
            - dnsServers: list of DNS server IPs
            - routes: list of routing entries
            - interfaceName: network interface name
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/network/active")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting active network: {str(e)}")]
                )

        @_register("device_network_all", "Get all available networks on the device including inactive ones, with type, speed, and link info", {"type": "object","properties": {},"required": []})
        async def device_network_all() -> CallToolResult:
            """Get all available networks on the device
            Returns a list of all registered networks (active and inactive) including:
            - count: total number of networks
            - networks[]: each with networkHandle, type, isActive, hasInternet, hasValidated,
              isMetered, interfaceName, linkAddresses, dnsServers, downloadSpeed (Kbps), uploadSpeed (Kbps)
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/network/all")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting all networks: {str(e)}")]
                )

        @_register("device_wifi_scan", "Scan for available WiFi networks", {"type": "object","properties": {},"required": []})
        async def device_wifi_scan() -> CallToolResult:
            """Scan for available WiFi networks"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/wifi/scan")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error scanning WiFi: {str(e)}")]
                )

        # System Info Tools (Tier 1)
        @_register("device_battery_info", "Get battery status and information", {"type": "object","properties": {},"required": []})
        async def device_battery_info() -> CallToolResult:
            """Get battery status and information"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/battery")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting battery info: {str(e)}")]
                )

        @_register("device_wifi_info", "Get WiFi connection information", {"type": "object","properties": {},"required": []})
        async def device_wifi_info() -> CallToolResult:
            """Get WiFi connection information"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/wifi")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting WiFi info: {str(e)}")]
                )

        @_register("device_location_get", "Get current device location", {"type": "object","properties": {},"required": []})
        async def device_location_get() -> CallToolResult:
            """Get current device location"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/location")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting location: {str(e)}")]
                )

        @_register("device_gnss_status", "Get GNSS satellite status including fix state, satellite count, per-satellite CNR/azimuth/elevation", {"type": "object","properties": {},"required": []})
        async def device_gnss_status() -> CallToolResult:
            """Get GNSS satellite status — fix state, satellite count, per-satellite details"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/gnss/status", timeout=20.0)
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting GNSS status: {str(e)}")]
                )

        # Sensor Tools
        @_register("device_sensors_list", "List all available device sensors with their properties", {"type": "object","properties": {},"required": []})
        async def device_sensors_list() -> CallToolResult:
            """List all available device sensors with their properties"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/sensors")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing sensors: {str(e)}")]
                )

        @_register("device_sensor_read", "Read current values from specific device sensors with configurable sampling rate", {
            "type": "object",
            "properties": {
                "sensor_types": {"type": "array", "items": {"type": "string"}, "description": "Sensor types to read: accelerometer, gyroscope, magnetic_field, light, pressure, proximity, gravity, linear_acceleration, rotation_vector, relative_humidity, ambient_temperature, hinge_angle"},
                "sampling_rate": {"type": "string", "description": "Sampling rate: 'fastest', 'game', 'ui', 'normal' (default), or microsecond integer as string"}
            },
            "required": ["sensor_types"]
        })
        async def device_sensor_read(sensor_types: List[str], sampling_rate: str = "normal") -> CallToolResult:
            """Read current values from specific device sensors
            Args:
                sensor_types: List of sensor types to read (e.g. ["accelerometer", "gyroscope", "light"])
                sampling_rate: Sampling delay - 'fastest', 'game', 'ui', 'normal', or microsecond int
            """
            try:
                payload = {"sensor_types": sensor_types}
                if sampling_rate != "normal":
                    payload["sampling_rate"] = sampling_rate
                response = await self.client.post(
                    f"{TIER1_BASE}/sensors/read",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error reading sensors: {str(e)}")],
                    isError=True
                )

        @_register("device_sensors_orientation", "Get device orientation (azimuth/pitch/roll) from accelerometer and magnetometer", {
            "type": "object",
            "properties": {},
            "required": []
        })
        async def device_sensors_orientation() -> CallToolResult:
            """Get device orientation (azimuth, pitch, roll) in degrees
            Uses accelerometer + magnetometer with getRotationMatrix/getOrientation
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/sensors/orientation")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting orientation: {str(e)}")],
                    isError=True
                )

        @_register("device_sensors_trigger", "Wait for significant motion detection (one-shot trigger sensor)", {
            "type": "object",
            "properties": {
                "timeout_ms": {"type": "integer", "description": "Max wait time in ms (100-30000, default 5000)"}
            },
            "required": []
        })
        async def device_sensors_trigger(timeout_ms: int = 5000) -> CallToolResult:
            """Wait for significant motion detection
            Args:
                timeout_ms: Maximum time to wait for motion event in milliseconds (100-30000)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/sensors/trigger",
                    json={"timeout_ms": timeout_ms}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with trigger sensor: {str(e)}")],
                    isError=True
                )

        @_register("device_vibrate_effect", "Trigger a predefined vibration effect", {"type": "object","properties": {"effect": {"type": "string"}},"required": ["effect"]})
        async def device_vibrate_effect(effect: str) -> CallToolResult:
            """Trigger a predefined vibration effect
            Args:
                effect: Effect name - "EFFECT_CLICK", "EFFECT_TICK", "EFFECT_DOUBLE_CLICK", "EFFECT_HEAVY_CLICK"
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/vibrate/effect",
                    json={"effect": effect}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with vibration effect: {str(e)}")]
                )

        @_register("device_vibrate_pattern", "Vibrate with a custom pattern", {"type": "object","properties": {"pattern": {"type": "array","items": {"type": "integer"}},"repeat": {"type": "integer"}},"required": ["pattern"]})
        async def device_vibrate_pattern(pattern: List[int], repeat: int = -1) -> CallToolResult:
            """Vibrate with a custom pattern
            Args:
                pattern: List of durations in milliseconds [on_ms, off_ms, on_ms, off_ms, ...]
                repeat: Index to repeat from, or -1 for no repeat
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/vibrate/pattern",
                    json={"pattern": pattern, "repeat": repeat}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with vibration pattern: {str(e)}")]
                )

        @_register("device_vibrate_cancel", "Cancel any ongoing vibration", {"type": "object","properties": {},"required": []})
        async def device_vibrate_cancel() -> CallToolResult:
            """Cancel any ongoing vibration"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/vibrate/cancel",
                    json={}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error canceling vibration: {str(e)}")]
                )

        @_register("device_alarm_schedule", "Schedule a device alarm", {"type": "object","properties": {"trigger_time_ms": {"type": "integer"},"alarm_type": {"type": "string"},"message": {"type": "string"},"repeating_interval_ms": {"type": "integer"}},"required": ["trigger_time_ms"]})
        async def device_alarm_schedule(
            trigger_time_ms: int,
            alarm_type: str = "exact",
            message: str = "",
            repeating_interval_ms: int = 0
        ) -> CallToolResult:
            """Schedule a device alarm
            Args:
                trigger_time_ms: Trigger time in milliseconds since epoch
                alarm_type: Type of alarm - "exact", "inexact", "window", "repeating", "alarm_clock"
                message: Optional alarm message
                repeating_interval_ms: Interval for repeating alarms in milliseconds (0 for one-time)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/alarms/schedule",
                    json={
                        "trigger_time_ms": trigger_time_ms,
                        "alarm_type": alarm_type,
                        "message": message,
                        "repeating_interval_ms": repeating_interval_ms
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error scheduling alarm: {str(e)}")]
                )

        @_register("device_alarm_cancel", "Cancel a scheduled alarm", {"type": "object","properties": {"alarm_id": {"type": "string"}},"required": ["alarm_id"]})
        async def device_alarm_cancel(alarm_id: str) -> CallToolResult:
            """Cancel a scheduled alarm
            Args:
                alarm_id: Identifier of the alarm to cancel
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/alarms/cancel",
                    json={"alarm_id": alarm_id}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error canceling alarm: {str(e)}")]
                )

        # =====================================================================
        # Tasker Tools
        # =====================================================================

        @_register("device_tasker_run", "Run a Tasker task by name", {"type": "object","properties": {"task_name": {"type": "string"}},"required": ["task_name"]})
        async def device_tasker_run(task_name: str) -> CallToolResult:
            """Run a Tasker task by name
            Args:
                task_name: Name of the Tasker task to execute
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": task_name}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error running Tasker task: {str(e)}")]
                )

        @_register("device_tasker_tasks", "List available Tasker tasks (limited by Tasker API availability)", {"type": "object","properties": {},"required": []})
        async def device_tasker_tasks() -> CallToolResult:
            """List available Tasker tasks (limited by Tasker API availability)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/tasker/tasks")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing Tasker tasks: {str(e)}")]
                )

        @_register("device_tasker_variable", "Set a Tasker variable", {"type": "object","properties": {"name": {"type": "string"},"value": {"type": "string"}},"required": ["name","value"]})
        async def device_tasker_variable(name: str, value: str) -> CallToolResult:
            """Set a Tasker variable
            Args:
                name: Variable name (e.g. "%MyVar")
                value: Value to set
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/variable",
                    json={"name": name, "value": value}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting Tasker variable: {str(e)}")]
                )

        @_register("device_tasker_profile", "Enable or disable a Tasker profile", {"type": "object","properties": {"name": {"type": "string"},"enabled": {"type": "boolean"}},"required": ["name"]})
        async def device_tasker_profile(name: str, enabled: bool = True) -> CallToolResult:
            """Enable or disable a Tasker profile
            Args:
                name: Profile name
                enabled: True to enable, False to disable
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/profile",
                    json={"profile_name": name, "enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error controlling Tasker profile: {str(e)}")]
                )

        # =====================================================================
        # Calendar Tools
        # =====================================================================

        @_register("device_calendar_list", "List all calendars on the device with their IDs, names, and accounts", {"type": "object","properties": {},"required": []})
        async def device_calendar_list() -> CallToolResult:
            """List all calendars on the device with their IDs, names, and accounts"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/calendar/list")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing calendars: {str(e)}")]
                )

        @_register("device_calendar_events", "Get calendar events for the next N days", {"type": "object","properties": {"calendar_id": {"type": "integer"},"days": {"type": "integer"}}})
        async def device_calendar_events(calendar_id: Optional[int] = None, days: int = 30) -> CallToolResult:
            """Get calendar events for the next N days
            Args:
                calendar_id: Optional calendar ID to filter (None for all calendars)
                days: Number of days ahead to retrieve events (default: 30)
            """
            try:
                now_ms = int(time.time() * 1000)
                end_ms = now_ms + (days * 24 * 60 * 60 * 1000)
                params = {"start_time": now_ms, "end_time": end_ms}

                response = await self.client.get(
                    f"{TIER1_BASE}/calendar/events",
                    params=params
                )
                result = response.json()

                # Client-side filter by calendar_id if specified
                if calendar_id is not None and "events" in result:
                    result["events"] = [
                        e for e in result["events"]
                        if e.get("calendar_id") == calendar_id
                    ]
                    result["count"] = len(result["events"])
                    result["filtered_by_calendar_id"] = calendar_id

                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(result, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting calendar events: {str(e)}")]
                )

        @_register("device_calendar_create", "Create a new calendar event", {"type": "object","properties": {"calendar_id": {"type": "integer"},"title": {"type": "string"},"start_time": {"type": "integer"},"end_time": {"type": "integer"},"description": {"type": "string"},"location": {"type": "string"},"all_day": {"type": "boolean"}},"required": ["calendar_id","title","start_time","end_time"]})
        async def device_calendar_create(
            calendar_id: int,
            title: str,
            start_time: int,
            end_time: int,
            description: str = "",
            location: str = "",
            all_day: bool = False
        ) -> CallToolResult:
            """Create a new calendar event
            Args:
                calendar_id: Calendar ID to create event in (get from device_calendar_list)
                title: Event title
                start_time: Start time in milliseconds since epoch
                end_time: End time in milliseconds since epoch
                description: Event description (optional)
                location: Event location (optional)
                all_day: Whether this is an all-day event (optional)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/calendar/create",
                    json={
                        "calendar_id": calendar_id,
                        "title": title,
                        "start_time": start_time,
                        "end_time": end_time,
                        "description": description,
                        "location": location,
                        "all_day": all_day
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error creating calendar event: {str(e)}")]
                )

        @_register("device_calendar_update", "Update an existing calendar event", {"type": "object","properties": {"event_id": {"type": "integer"},"title": {"type": "string"},"start_time": {"type": "integer"},"end_time": {"type": "integer"},"description": {"type": "string"},"location": {"type": "string"},"all_day": {"type": "boolean"}},"required": ["event_id"]})
        async def device_calendar_update(
            event_id: int,
            title: Optional[str] = None,
            start_time: Optional[int] = None,
            end_time: Optional[int] = None,
            description: Optional[str] = None,
            location: Optional[str] = None,
            all_day: Optional[bool] = None
        ) -> CallToolResult:
            """Update an existing calendar event
            Args:
                event_id: Event ID to update
                title: New event title (optional)
                start_time: New start time in ms since epoch (optional)
                end_time: New end time in ms since epoch (optional)
                description: New description (optional)
                location: New location (optional)
                all_day: Whether all-day event (optional)
            """
            try:
                body = {"event_id": event_id}
                if title is not None: body["title"] = title
                if start_time is not None: body["start_time"] = start_time
                if end_time is not None: body["end_time"] = end_time
                if description is not None: body["description"] = description
                if location is not None: body["location"] = location
                if all_day is not None: body["all_day"] = all_day

                response = await self.client.post(
                    f"{TIER1_BASE}/calendar/update",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error updating calendar event: {str(e)}")]
                )

        @_register("device_calendar_delete", "Delete a calendar event by its ID", {"type": "object","properties": {"event_id": {"type": "integer","description": "Event ID to delete (get from device_calendar_events)"}},"required": ["event_id"]})
        async def device_calendar_delete(event_id: int) -> CallToolResult:
            """Delete a calendar event by its ID
            Args:
                event_id: The numeric ID of the event to delete (get from device_calendar_events)
            Returns success with rows_deleted count, or error if event not found
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/calendar/delete",
                    params={"event_id": event_id}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting calendar event: {str(e)}")]
                )

        # =====================================================================
        # Media Tools
        # =====================================================================

        @_register("device_media_playing", "Get current media playback status (whether music is active, audio mode)", {"type": "object","properties": {},"required": []})
        async def device_media_playing() -> CallToolResult:
            """Get current media playback status (whether music is active, audio mode)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/media/playing")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting media status: {str(e)}")]
                )

        @_register("device_media_control", "Control media playback", {"type": "object","properties": {"action": {"type": "string"}},"required": ["action"]})
        async def device_media_control(action: str) -> CallToolResult:
            """Control media playback
            Args:
                action: Media action - "play", "pause", "play_pause", "next", "previous", "stop"
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/media/control",
                    json={"action": action}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error controlling media: {str(e)}")]
                )

        # =====================================================================
        # Communication Tools
        # =====================================================================

        @_register("device_call_log", "Get recent call log entries", {"type": "object","properties": {"limit": {"type": "integer"}}})
        async def device_call_log(limit: int = 50) -> CallToolResult:
            """Get recent call log entries
            Args:
                limit: Maximum number of call log entries to retrieve (default: 50)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/call_log",
                    params={"limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting call log: {str(e)}")]
                )

        @_register("device_share", "Share content via Android share sheet (text, HTML, streams, email)", {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Plain text content to share"},
                "htmlText": {"type": "string", "description": "HTML-formatted text content"},
                "type": {"type": "string", "description": "MIME type (default: text/plain)"},
                "streamUri": {"type": "string", "description": "Single content:// or file:// URI to share"},
                "streamUris": {"type": "array", "items": {"type": "string"}, "description": "Multiple stream URIs to share"},
                "subject": {"type": "string", "description": "Subject line (for email clients)"},
                "emailTo": {"type": "array", "items": {"type": "string"}, "description": "To email addresses"},
                "emailCc": {"type": "array", "items": {"type": "string"}, "description": "CC email addresses"},
                "emailBcc": {"type": "array", "items": {"type": "string"}, "description": "BCC email addresses"},
                "chooserTitle": {"type": "string", "description": "Chooser dialog title (default: Share via)"}
            }
        })
        async def device_share(
            text: str = "",
            htmlText: str = "",
            type: str = "text/plain",
            subject: str = "",
            streamUri: str = "",
            streamUris: list = None,
            emailTo: list = None,
            emailCc: list = None,
            emailBcc: list = None,
            chooserTitle: str = "Share via"
        ) -> CallToolResult:
            """Share content via Android share sheet with full ShareCompat support
            Args:
                text: Plain text content to share
                htmlText: HTML-formatted text content
                type: MIME type (default: text/plain)
                subject: Subject line (for email clients)
                streamUri: Single content:// or file:// URI to share
                streamUris: Multiple stream URIs to share
                emailTo: To email addresses
                emailCc: CC email addresses
                emailBcc: BCC email addresses
                chooserTitle: Chooser dialog title (default: Share via)
            """
            try:
                payload = {}
                if text:
                    payload["text"] = text
                if htmlText:
                    payload["htmlText"] = htmlText
                if type:
                    payload["type"] = type
                if subject:
                    payload["subject"] = subject
                if streamUri:
                    payload["streamUri"] = streamUri
                if streamUris:
                    payload["streamUris"] = streamUris
                if emailTo:
                    payload["emailTo"] = emailTo
                if emailCc:
                    payload["emailCc"] = emailCc
                if emailBcc:
                    payload["emailBcc"] = emailBcc
                if chooserTitle and chooserTitle != "Share via":
                    payload["chooserTitle"] = chooserTitle
                response = await self.client.post(
                    f"{TIER1_BASE}/share",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sharing: {str(e)}")],
                    isError=True
                )

        @_register("device_share_received", "Get the most recent incoming share received by mK:a", {
            "type": "object",
            "properties": {}
        })
        async def device_share_received() -> CallToolResult:
            """Get the most recent incoming share intent received by mK:a.
            Returns parsed share data including text, HTML, streams, email fields, and caller info.
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/share/received"
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting received share: {str(e)}")],
                    isError=True
                )

        @_register("device_notification_send",
                   "Send a rich notification to the device with optional action buttons, deep links, styles (big_text, big_picture, messaging), priority, grouping, and more",
                   {
                       "type": "object",
                       "properties": {
                           "title": {
                               "type": "string",
                               "description": "Notification title"
                           },
                           "text": {
                               "type": "string",
                               "description": "Notification message body"
                           },
                           "id": {
                               "type": "integer",
                               "description": "Notification ID (for updating existing). Auto-generated if omitted."
                           },
                           "priority": {
                               "type": "string",
                               "enum": ["min", "low", "default", "high", "max"],
                               "description": "Notification priority level"
                           },
                           "category": {
                               "type": "string",
                               "enum": ["msg", "email", "call", "alarm", "reminder", "event", "promo", "social", "err", "transport", "sys", "service", "progress", "status"],
                               "description": "Android notification category hint"
                           },
                           "group": {
                               "type": "string",
                               "description": "Group key for bundling related notifications"
                           },
                           "group_summary": {
                               "type": "boolean",
                               "description": "Set true if this is the group summary notification"
                           },
                           "ongoing": {
                               "type": "boolean",
                               "description": "If true, notification cannot be dismissed by user"
                           },
                           "auto_cancel": {
                               "type": "boolean",
                               "description": "Auto-dismiss on tap (default true)"
                           },
                           "silent": {
                               "type": "boolean",
                               "description": "Show without sound or vibration"
                           },
                           "sub_text": {
                               "type": "string",
                               "description": "Smaller text displayed below the main content"
                           },
                           "ticker": {
                               "type": "string",
                               "description": "Text announced by accessibility services"
                           },
                           "timestamp": {
                               "type": "integer",
                               "description": "Custom timestamp in epoch milliseconds"
                           },
                           "show_timestamp": {
                               "type": "boolean",
                               "description": "Whether to display the timestamp"
                           },
                           "timeout_after": {
                               "type": "integer",
                               "description": "Auto-cancel after N milliseconds"
                           },
                           "color": {
                               "type": "string",
                               "description": "Accent color as hex string, e.g. '#FF5722'"
                           },
                           "large_icon_url": {
                               "type": "string",
                               "description": "URL for large icon image"
                           },
                           "deep_link": {
                               "type": "string",
                               "description": "URI to open on notification tap (e.g. 'https://...', 'myapp://screen')"
                           },
                           "actions": {
                               "type": "array",
                               "description": "Up to 3 action buttons",
                               "items": {
                                   "type": "object",
                                   "properties": {
                                       "title": {"type": "string", "description": "Button label"},
                                       "deep_link": {"type": "string", "description": "URI to open when tapped"},
                                       "icon": {"type": "string", "description": "android.R.drawable icon name (default: ic_menu_send)"}
                                   },
                                   "required": ["title", "deep_link"]
                               }
                           },
                           "style": {
                               "type": "string",
                               "enum": ["big_text", "big_picture", "messaging"],
                               "description": "Notification style"
                           },
                           "style_data": {
                               "type": "object",
                               "description": "Style-specific data. For big_text: {big_text, big_content_title?, summary_text?}. For big_picture: {picture_url, big_content_title?, summary_text?, show_when_collapsed?}. For messaging: {user_name, conversation_title?, is_group?, messages: [{text, timestamp, sender?}]}"
                           }
                       },
                       "required": ["title", "text"]
                   })
        async def device_notification_send(title: str, text: str, id: Optional[int] = None, **kwargs) -> CallToolResult:
            """Send a rich notification to the device
            Args:
                title: Notification title
                text: Notification message body
                id: Optional notification ID (for updating existing notifications)
                **kwargs: Optional params - priority, category, group, group_summary, ongoing,
                         auto_cancel, silent, sub_text, ticker, timestamp, show_timestamp,
                         timeout_after, color, large_icon_url, deep_link, actions, style, style_data
            """
            try:
                body = {"title": title, "message": text}
                if id is not None:
                    body["id"] = id

                # Pass through all optional parameters
                optional_keys = [
                    "priority", "category", "group", "group_summary", "ongoing",
                    "auto_cancel", "silent", "sub_text", "ticker", "timestamp",
                    "show_timestamp", "timeout_after", "color", "large_icon_url",
                    "deep_link", "actions", "style", "style_data"
                ]
                for key in optional_keys:
                    if key in kwargs and kwargs[key] is not None:
                        body[key] = kwargs[key]

                response = await self.client.post(
                    f"{TIER1_BASE}/notification/send",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending notification: {str(e)}")],
                    isError=True
                )

        @_register("device_toast", "Show a toast message on the device screen", {"type": "object","properties": {"text": {"type": "string"},"duration": {"type": "string"}},"required": ["text"]})
        async def device_toast(text: str, duration: str = "short") -> CallToolResult:
            """Show a toast message on the device screen
            Args:
                text: Message to display
                duration: "short" or "long"
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/toast",
                    json={"message": text, "duration": duration}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error showing toast: {str(e)}")]
                )

        # =====================================================================
        # Device Control Tools
        # =====================================================================

        @_register("device_brightness", "Set screen brightness", {"type": "object","properties": {"level": {"type": "integer"}},"required": ["level"]})
        async def device_brightness(level: int) -> CallToolResult:
            """Set screen brightness
            Args:
                level: Brightness level (0-255)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/brightness",
                    json={"brightness": level}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting brightness: {str(e)}")]
                )

        @_register("device_volume_get", "Get current volume levels for all audio streams (ring, media, alarm, notification)", {"type": "object","properties": {},"required": []})
        async def device_volume_get() -> CallToolResult:
            """Get current volume levels for all audio streams (ring, media, alarm, notification)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/volume")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting volume: {str(e)}")]
                )

        @_register("device_volume_set", "Set volume level for a specific audio stream", {"type": "object","properties": {"stream": {"type": "string"},"level": {"type": "integer"}}})
        async def device_volume_set(stream: str = "media", level: int = 5) -> CallToolResult:
            """Set volume level for a specific audio stream
            Args:
                stream: Audio stream - "ring", "media", "alarm", "notification"
                level: Volume level (0 to stream max)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/volume",
                    json={"stream": stream, "level": level}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting volume: {str(e)}")]
                )

        @_register("device_dnd", "Set Do Not Disturb mode", {"type": "object","properties": {"mode": {"type": "string"}},"required": ["mode"]})
        async def device_dnd(mode: str) -> CallToolResult:
            """Set Do Not Disturb mode
            Args:
                mode: DND mode - "none" (total silence), "priority" (priority only), "alarms" (alarms only), "normal" (DND off)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/dnd",
                    json={"mode": mode}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting DND mode: {str(e)}")]
                )

        @_register("device_screen_state", "Get screen state (on/off) and current brightness level", {"type": "object","properties": {},"required": []})
        async def device_screen_state() -> CallToolResult:
            """Get screen state (on/off) and current brightness level"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/screen/state")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting screen state: {str(e)}")]
                )

        @_register("device_screenshot", "Capture a screenshot of the current screen via AccessibilityService (API 30+). Returns base64 JPEG and saves to ~/Screenshots/.", {"type": "object","properties": {"quality": {"type": "integer","description": "JPEG quality 1-100 (default 60)"},"max_width": {"type": "integer","description": "Max image width in pixels (default 1080)"}}})
        async def device_screenshot(quality: int = 60, max_width: int = 1080) -> CallToolResult:
            """Capture a screenshot via the AccessibilityService API. Also saves to ~/Screenshots/."""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/accessibility/screenshot",
                    params={"quality": quality, "max_width": max_width},
                    timeout=10.0
                )
                data = response.json()
                if data.get("success"):
                    # Save to ~/Screenshots/ with timestamp
                    import base64
                    from datetime import datetime
                    home = os.environ.get('HOME', '/data/user/0/com.mobilekinetic.agent/files/home')
                    screenshots_dir = os.path.join(home, 'Screenshots')
                    os.makedirs(screenshots_dir, exist_ok=True)
                    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
                    filepath = os.path.join(screenshots_dir, f'screenshot_{timestamp}.jpg')
                    with open(filepath, 'wb') as f:
                        f.write(base64.b64decode(data["data"]))
                    return CallToolResult(
                        content=[
                            ImageContent(
                                type="image",
                                data=data["data"],
                                mimeType="image/jpeg"
                            ),
                            TextContent(type="text", text=f"Screenshot saved to {filepath}")
                        ]
                    )
                else:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Screenshot failed: {data.get('error', 'unknown error')}")]
                    )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error capturing screenshot: {str(e)}")]
                )

        # =====================================================================
        # File / Content Tools
        # =====================================================================

        @_register("device_photos_recent", "Get metadata for recent photos on the device", {"type": "object","properties": {"limit": {"type": "integer"}}})
        async def device_photos_recent(limit: int = 20) -> CallToolResult:
            """Get metadata for recent photos on the device
            Args:
                limit: Maximum number of photos to retrieve (default: 20)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/photos/recent",
                    params={"limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting recent photos: {str(e)}")]
                )

        @_register("device_downloads", "List recent downloads on the device", {"type": "object","properties": {"limit": {"type": "integer"}}})
        async def device_downloads(limit: int = 50) -> CallToolResult:
            """List recent downloads on the device
            Args:
                limit: Maximum number of downloads to retrieve (default: 50)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/downloads",
                    params={"limit": limit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting downloads: {str(e)}")]
                )

        # =====================================================================
        # System Tools (Tier 2)
        # =====================================================================

        @_register("device_system_info", "Get comprehensive system information", {"type": "object","properties": {},"required": []})
        async def device_system_info() -> CallToolResult:
            """Get comprehensive system information"""
            try:
                response = await self.client.get(f"{TIER2_BASE}/system/info")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting system info: {str(e)}")]
                )

        @_register("device_process_list", "List running processes", {"type": "object","properties": {},"required": []})
        async def device_process_list() -> CallToolResult:
            """List running processes"""
            try:
                response = await self.client.get(f"{TIER2_BASE}/system/processes")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing processes: {str(e)}")]
                )

        @_register("device_network_info", "Get network interfaces and routing information", {"type": "object","properties": {},"required": []})
        async def device_network_info() -> CallToolResult:
            """Get network interfaces and routing information"""
            try:
                response = await self.client.get(f"{TIER2_BASE}/network/interfaces")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting network info: {str(e)}")]
                )

        @_register("device_shell_execute", "Execute a shell command (Tier 2)", {"type": "object","properties": {"command": {"type": "string"},"timeout": {"type": "integer"}},"required": ["command"]})
        async def device_shell_execute(command: str, timeout: int = 30) -> CallToolResult:
            """Execute a shell command (Tier 2)
            Args:
                command: Shell command to execute
                timeout: Command timeout in seconds
            """
            try:
                response = await self.client.post(
                    f"{TIER2_BASE}/shell/execute",
                    json={"command": command, "timeout": timeout}
                )
                result = response.json()
                output = f"Exit code: {result.get('exit_code', 'N/A')}\n"
                output += f"STDOUT:\n{result.get('stdout', '')}\n"
                if result.get('stderr'):
                    output += f"STDERR:\n{result.get('stderr', '')}"
                return CallToolResult(
                    content=[TextContent(type="text", text=output)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error executing command: {str(e)}")]
                )

        # File Operation Tools (to avoid hanging file operations)
        @_register("device_file_read", "Read a file safely without hanging", {"type": "object","properties": {"path": {"type": "string"},"lines": {"type": "integer"}},"required": ["path"]})
        async def device_file_read(path: str, lines: Optional[int] = None) -> CallToolResult:
            """Read a file safely without hanging
            Args:
                path: File path to read
                lines: Number of lines to read (None for all)
            """
            try:
                import os
                path = os.path.expanduser(path)
                with open(path, 'r') as f:
                    if lines:
                        content = ''.join(f.readlines()[:lines])
                    else:
                        content = f.read()
                return CallToolResult(
                    content=[TextContent(type="text", text=content)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error reading file: {str(e)}")]
                )

        @_register("device_file_write", "Write to a file safely", {"type": "object","properties": {"path": {"type": "string"},"content": {"type": "string"}},"required": ["path","content"]})
        async def device_file_write(path: str, content: str) -> CallToolResult:
            """Write to a file safely
            Args:
                path: File path to write
                content: Content to write
            """
            try:
                import os
                path = os.path.expanduser(path)
                with open(path, 'w') as f:
                    f.write(content)
                return CallToolResult(
                    content=[TextContent(type="text", text=f"File written successfully: {path}")]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error writing file: {str(e)}")]
                )

        @_register("device_file_list", "List files in directory (replaces problematic ls/find commands)", {"type": "object","properties": {"path": {"type": "string"},"pattern": {"type": "string"}}})
        async def device_file_list(path: str = ".", pattern: Optional[str] = None) -> CallToolResult:
            """List files in directory (replaces problematic ls/find commands)
            Args:
                path: Directory path
                pattern: Optional glob pattern to filter files
            """
            try:
                import os
                import glob
                path = os.path.expanduser(path)

                if pattern:
                    files = glob.glob(os.path.join(path, pattern))
                else:
                    files = os.listdir(path)
                    files = [os.path.join(path, f) for f in files]

                # Get file info
                file_info = []
                for f in sorted(files):
                    try:
                        stat = os.stat(f)
                        file_info.append({
                            "path": f,
                            "size": stat.st_size,
                            "is_dir": os.path.isdir(f)
                        })
                    except:
                        pass

                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(file_info, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing files: {str(e)}")]
                )

        @_register("device_grep", "Search for pattern in files (replaces hanging grep)", {"type": "object","properties": {"pattern": {"type": "string"},"path": {"type": "string"},"file_pattern": {"type": "string"}},"required": ["pattern"]})
        async def device_grep(pattern: str, path: str = ".", file_pattern: str = "*") -> CallToolResult:
            """Search for pattern in files (replaces hanging grep)
            Args:
                pattern: Regex pattern to search for
                path: Directory to search in
                file_pattern: File pattern to search (e.g., "*.py")
            """
            try:
                import os
                import re
                import glob

                path = os.path.expanduser(path)
                matches = []

                # Find files to search
                if os.path.isfile(path):
                    files = [path]
                else:
                    files = glob.glob(os.path.join(path, "**", file_pattern), recursive=True)

                regex = re.compile(pattern)

                for filepath in files[:100]:  # Limit to 100 files
                    if os.path.isfile(filepath):
                        try:
                            with open(filepath, 'r') as f:
                                for i, line in enumerate(f, 1):
                                    if regex.search(line):
                                        matches.append({
                                            "file": filepath,
                                            "line": i,
                                            "content": line.strip()
                                        })
                                        if len(matches) >= 50:  # Limit results
                                            break
                        except:
                            pass
                    if len(matches) >= 50:
                        break

                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps({"matches": matches[:50]}, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error searching files: {str(e)}")]
                )

        @_register("device_command", "Execute a command safely with timeout (replaces hanging Bash)", {"type": "object","properties": {"command": {"type": "string"},"timeout": {"type": "integer"}},"required": ["command"]})
        async def device_command(command: str, timeout: int = 30) -> CallToolResult:
            """Execute a command safely with timeout (replaces hanging Bash)
            Args:
                command: Command to execute
                timeout: Timeout in seconds (max 600)
            """
            try:
                import subprocess
                import os

                # Expand home directory
                command = command.replace("~", os.path.expanduser("~"))
                timeout = min(timeout, 600)  # Cap at 10 minutes

                # Avoid known hanging commands
                forbidden = ['/sdcard', '/storage/emulated', 'termux-', 'pkg install', 'apt install']
                if any(f in command.lower() for f in forbidden):
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Command blocked: Contains forbidden path/command that hangs on this device")]
                    )

                result = subprocess.run(
                    command,
                    shell=True,
                    capture_output=True,
                    text=True,
                    timeout=timeout,
                    cwd=os.path.expanduser("~")
                )

                output = f"Exit code: {result.returncode}\n"
                if result.stdout:
                    output += f"STDOUT:\n{result.stdout}\n"
                if result.stderr:
                    output += f"STDERR:\n{result.stderr}"

                return CallToolResult(
                    content=[TextContent(type="text", text=output)]
                )
            except subprocess.TimeoutExpired:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Command timed out after {timeout} seconds")]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error executing command: {str(e)}")]
                )

        # =====================================================================
        # Camera Tools (Overnight)
        # =====================================================================

        @_register("device_camera_list", "List available cameras on the device with their IDs, facing direction, and capabilities", {"type": "object","properties": {},"required": []})
        async def device_camera_list() -> CallToolResult:
            """List available cameras on the device with their IDs, facing direction, and capabilities"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/camera/list")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing cameras: {str(e)}")]
                )

        @_register("device_camera_photo", "Take a photo using the device camera", {"type": "object","properties": {"camera_id": {"type": "string"},"flash": {"type": "string"},"quality": {"type": "integer"}}})
        async def device_camera_photo(
            camera_id: str = "0",
            flash: str = "auto",
            quality: int = 90
        ) -> CallToolResult:
            """Take a photo using the device camera
            Args:
                camera_id: Camera ID to use (default "0" for rear camera, "1" for front)
                flash: Flash mode - "auto", "on", "off", "torch"
                quality: JPEG quality 1-100 (default 90)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/camera/photo",
                    json={
                        "camera_id": camera_id,
                        "flash": flash,
                        "quality": quality
                    },
                    timeout=15.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error taking photo: {str(e)}")]
                )

        @_register("device_camera_video_start", "Start recording video with the device camera", {"type": "object","properties": {"camera_id": {"type": "string"},"max_duration_seconds": {"type": "integer"},"quality": {"type": "string"}}})
        async def device_camera_video_start(
            camera_id: str = "0",
            max_duration_seconds: int = 60,
            quality: str = "high"
        ) -> CallToolResult:
            """Start recording video with the device camera
            Args:
                camera_id: Camera ID to use (default "0" for rear camera)
                max_duration_seconds: Maximum recording duration in seconds (default 60, max 300)
                quality: Video quality - "low", "medium", "high" (default "high")
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/camera/video",
                    json={
                        "action": "start",
                        "camera_id": camera_id,
                        "max_duration_seconds": min(max_duration_seconds, 300),
                        "quality": quality
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error starting video recording: {str(e)}")]
                )

        @_register("device_camera_video_stop", "Stop the current video recording and save the file", {"type": "object","properties": {},"required": []})
        async def device_camera_video_stop() -> CallToolResult:
            """Stop the current video recording and save the file"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/camera/video",
                    json={"action": "stop"}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error stopping video recording: {str(e)}")]
                )

        @_register("device_camera_status", "Get current camera status (whether recording, which camera is active, etc.)", {"type": "object","properties": {},"required": []})
        async def device_camera_status() -> CallToolResult:
            """Get current camera status (whether recording, which camera is active, etc.)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/camera/status")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting camera status: {str(e)}")]
                )

        @_register("device_camera_analyze", "Analyze live camera frames using CameraX ImageAnalysis. Returns frame metadata (resolution, format, FPS, per-frame dimensions and plane info) without saving any image to disk.", {
            "type": "object",
            "properties": {
                "camera_id": {
                    "type": "string",
                    "description": "Camera ID to use: \"0\" for rear, \"1\" for front, or a raw camera2 ID (default \"0\")"
                },
                "duration_ms": {
                    "type": "integer",
                    "description": "How long to collect frames in milliseconds, 100–30000 (default 3000)"
                },
                "analyzer_mode": {
                    "type": "string",
                    "description": "Backpressure strategy: \"latest\" (drop old frames, default) or \"block\" (process every frame)"
                },
                "max_frames": {
                    "type": "integer",
                    "description": "Maximum number of frames to capture, 1–100 (default 10)"
                }
            }
        })
        async def device_camera_analyze(
            camera_id: str = "0",
            duration_ms: int = 3000,
            analyzer_mode: str = "latest",
            max_frames: int = 10
        ) -> CallToolResult:
            """Analyze live camera frames using CameraX ImageAnalysis.
            Args:
                camera_id: Camera to use ("0"=rear, "1"=front, or raw camera2 ID)
                duration_ms: Collection window in milliseconds (100-30000, default 3000)
                analyzer_mode: "latest" drops stale frames (default); "block" processes every frame
                max_frames: Maximum frames to capture (1-100, default 10)
            Returns frame metadata: resolution, format, FPS, per-frame plane info, throughput stats.
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/camera/analyze",
                    json={
                        "camera_id": camera_id,
                        "duration_ms": duration_ms,
                        "analyzer_mode": analyzer_mode,
                        "max_frames": max_frames
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error analyzing camera frames: {str(e)}")]
                )

        # =====================================================================
        # Audio Recording Tools (Overnight)
        # =====================================================================

        @_register("device_audio_record_start", "Start recording audio from the device microphone", {"type": "object","properties": {"max_duration_seconds": {"type": "integer"},"output_format": {"type": "string"},"source": {"type": "string"}}})
        async def device_audio_record_start(
            max_duration_seconds: int = 60,
            output_format: str = "m4a",
            source: str = "mic"
        ) -> CallToolResult:
            """Start recording audio from the device microphone
            Args:
                max_duration_seconds: Maximum recording duration in seconds (default 60, max 600)
                output_format: Audio format - "m4a", "amr", "wav", "3gp" (default "m4a")
                source: Audio source - "mic", "camcorder", "voice_recognition", "voice_communication" (default "mic")
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/audio/record/start",
                    json={
                        "max_duration_seconds": min(max_duration_seconds, 600),
                        "output_format": output_format,
                        "source": source
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error starting audio recording: {str(e)}")]
                )

        @_register("device_audio_record_stop", "Stop the current audio recording and save the file", {"type": "object","properties": {},"required": []})
        async def device_audio_record_stop() -> CallToolResult:
            """Stop the current audio recording and save the file"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/audio/record/stop",
                    json={}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error stopping audio recording: {str(e)}")]
                )

        @_register("device_audio_status", "Get current audio recording status (whether recording, duration, file path)", {"type": "object","properties": {},"required": []})
        async def device_audio_status() -> CallToolResult:
            """Get current audio recording status (whether recording, duration, file path)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/audio/status")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting audio status: {str(e)}")]
                )

        # =====================================================================
        # Phone Call Tools (Overnight)
        # =====================================================================

        @_register("device_phone_call", "Initiate a phone call to the given number", {"type": "object","properties": {"phone_number": {"type": "string"}},"required": ["phone_number"]})
        async def device_phone_call(phone_number: str) -> CallToolResult:
            """Initiate a phone call to the given number
            Args:
                phone_number: Phone number to call (e.g. "+1234567890")
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/phone/call",
                    json={"phone_number": phone_number}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error initiating phone call: {str(e)}")]
                )

        @_register("device_phone_hangup", "End the current active phone call", {"type": "object","properties": {},"required": []})
        async def device_phone_hangup() -> CallToolResult:
            """End the current active phone call"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/phone/hangup",
                    json={}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error hanging up phone call: {str(e)}")]
                )

        @_register("device_phone_state", "Get current phone/telephony state (idle, ringing, in-call, number, etc.)", {"type": "object","properties": {},"required": []})
        async def device_phone_state() -> CallToolResult:
            """Get current phone/telephony state (idle, ringing, in-call, number, etc.)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/phone/state")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting phone state: {str(e)}")]
                )

        # =====================================================================
        # WorkManager Tools (Overnight)
        # =====================================================================

        @_register("device_work_schedule", "Schedule a background work task via WorkManager", {"type": "object","properties": {"task_name": {"type": "string","description": "Unique name/tag for this work task (used as WorkManager tag)"},"work_type": {"type": "string","description": "one_time or periodic (default: one_time)"},"interval_minutes": {"type": "integer","description": "Repeat interval in minutes (min 15 for periodic)"},"delay_minutes": {"type": "integer","description": "Delay before first execution in minutes (default 0)"},"constraints": {"type": "object","description": "Optional constraints: network, charging, idle, storage_not_low, battery_not_low (all boolean)"}},"required": ["task_name"]})
        async def device_work_schedule(
            task_name: str,
            work_type: str = "one_time",
            interval_minutes: int = 15,
            delay_minutes: int = 0,
            constraints: Optional[Dict[str, Any]] = None
        ) -> CallToolResult:
            """Schedule a background work task via WorkManager
            Args:
                task_name: Unique name/tag for this work task (used as WorkManager tag)
                work_type: 'one_time' or 'periodic' (default: one_time)
                interval_minutes: Repeat interval in minutes (min 15 for periodic, default 15)
                delay_minutes: Delay before first execution in minutes (default 0)
                constraints: Optional constraints dict - {"network": true|false, "charging": true|false, "idle": true|false, "storage_not_low": true|false, "battery_not_low": true|false}
            """
            try:
                body = {
                    "task_name": task_name,
                    "work_type": work_type,
                    "interval_minutes": interval_minutes,
                    "delay_minutes": delay_minutes
                }
                if constraints is not None:
                    body["constraints"] = constraints

                response = await self.client.post(
                    f"{TIER1_BASE}/work/schedule",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error scheduling work: {str(e)}")]
                )

        @_register("device_work_status", "Get status of scheduled WorkManager tasks", {"type": "object","properties": {"tag": {"type": "string","description": "Tag of specific work to query"}},"required": ["tag"]})
        async def device_work_status(tag: str) -> CallToolResult:
            """Get status of scheduled WorkManager tasks
            Args:
                tag: Tag of specific work to query (required by Kotlin backend)
            """
            try:
                params = {"tag": tag}

                response = await self.client.get(
                    f"{TIER1_BASE}/work/status",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting work status: {str(e)}")]
                )

        @_register("device_work_cancel", "Cancel a scheduled WorkManager task by tag", {"type": "object","properties": {"tag": {"type": "string","description": "Tag of the work request to cancel"}},"required": ["tag"]})
        async def device_work_cancel(tag: str) -> CallToolResult:
            """Cancel a scheduled WorkManager task by tag
            Args:
                tag: Tag of the work request to cancel
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/work/cancel",
                    json={"tag": tag}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error canceling work: {str(e)}")]
                )

        # =====================================================================
        # Intent Tools (Overnight)
        # =====================================================================

        @_register("device_intent_broadcast",
                   "Send an Android broadcast intent with optional BroadcastOptions (API 34+)",
                   {
                       "type": "object",
                       "properties": {
                           "action": {
                               "type": "string",
                               "description": "Intent action string (e.g. 'com.example.MY_ACTION')"
                           },
                           "package": {
                               "type": "string",
                               "description": "Target package to restrict broadcast to"
                           },
                           "permission": {
                               "type": "string",
                               "description": "Permission the receiver must hold"
                           },
                           "extras": {
                               "type": "object",
                               "description": "Key-value pairs for intent extras (String, Int, Long, Double, Boolean)"
                           },
                           "broadcastOptions": {
                               "type": "object",
                               "description": "BroadcastOptions (API 34+ only, ignored on older). Keys: deferralPolicy (int: -1=default, 0=none, 1=until_active), deliveryGroupPolicy (int: 0=all, 1=most_recent), deliveryGroupMatchingNamespace (string), deliveryGroupMatchingKey (string), shareIdentity (boolean)",
                               "properties": {
                                   "deferralPolicy": {
                                       "type": "integer",
                                       "description": "-1=default, 0=none (immediate), 1=until_active (defer)"
                                   },
                                   "deliveryGroupPolicy": {
                                       "type": "integer",
                                       "description": "0=all, 1=most_recent"
                                   },
                                   "deliveryGroupMatchingNamespace": {
                                       "type": "string",
                                       "description": "Namespace for delivery group"
                                   },
                                   "deliveryGroupMatchingKey": {
                                       "type": "string",
                                       "description": "Key for delivery group"
                                   },
                                   "shareIdentity": {
                                       "type": "boolean",
                                       "description": "Share sender identity with receivers"
                                   }
                               }
                           }
                       },
                       "required": ["action"]
                   })
        async def device_intent_broadcast(
            action: str,
            package: str = "",
            permission: str = "",
            extras: dict = None,
            broadcastOptions: dict = None
        ) -> CallToolResult:
            """Send an Android broadcast intent with optional BroadcastOptions (API 34+)
            Args:
                action: Intent action string
                package: Target package to restrict broadcast to
                permission: Permission the receiver must hold
                extras: Key-value pairs for intent extras
                broadcastOptions: BroadcastOptions (API 34+ only)
            """
            try:
                payload = {"action": action}
                if package:
                    payload["package"] = package
                if permission:
                    payload["permission"] = permission
                if extras:
                    payload["extras"] = extras
                if broadcastOptions:
                    payload["broadcastOptions"] = broadcastOptions
                response = await self.client.post(
                    f"{TIER1_BASE}/intent/broadcast",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending broadcast intent: {str(e)}")],
                    isError=True
                )

        @_register("device_intent_activity",
                   "Start an Android activity via intent with resolve-first check and category support",
                   {
                       "type": "object",
                       "properties": {
                           "action": {
                               "type": "string",
                               "description": "Intent action (e.g. 'android.intent.action.VIEW', 'android.intent.action.SEND')"
                           },
                           "data": {
                               "type": "string",
                               "description": "URI string for intent data (e.g. 'https://google.com', 'tel:5551234')"
                           },
                           "package": {
                               "type": "string",
                               "description": "Target package to restrict to a specific app"
                           },
                           "mimeType": {
                               "type": "string",
                               "description": "MIME type (e.g. 'text/plain', 'image/*'). Combined with data uses setDataAndType()"
                           },
                           "categories": {
                               "type": "array",
                               "items": {"type": "string"},
                               "description": "Intent category strings (e.g. ['android.intent.category.BROWSABLE'])"
                           },
                           "extras": {
                               "type": "object",
                               "description": "Key-value pairs for intent extras"
                           },
                           "flags": {
                               "type": "integer",
                               "description": "Additional intent flags bitmask (OR'd with FLAG_ACTIVITY_NEW_TASK)"
                           },
                           "resolveFirst": {
                               "type": "boolean",
                               "description": "If true, verify an activity can handle the intent before launching. Returns resolved=false if none found."
                           }
                       },
                       "required": ["action"]
                   })
        async def device_intent_activity(
            action: str,
            data: str = "",
            package: str = "",
            mimeType: str = "",
            categories: list = None,
            extras: dict = None,
            flags: int = 0,
            resolveFirst: bool = False
        ) -> CallToolResult:
            """Start an Android activity via intent
            Args:
                action: Intent action string
                data: URI string for intent data
                package: Target package
                mimeType: MIME type
                categories: Intent category strings
                extras: Key-value pairs for intent extras
                flags: Additional intent flags bitmask
                resolveFirst: Verify activity can handle intent before launching
            """
            try:
                payload = {"action": action}
                if data:
                    payload["data"] = data
                if package:
                    payload["package"] = package
                if mimeType:
                    payload["mimeType"] = mimeType
                if categories:
                    payload["categories"] = categories
                if extras:
                    payload["extras"] = extras
                if flags:
                    payload["flags"] = flags
                if resolveFirst:
                    payload["resolveFirst"] = resolveFirst
                response = await self.client.post(
                    f"{TIER1_BASE}/intent/activity",
                    json=payload
                )
                result = response.json()
                # Surface resolve failure clearly
                if result.get("resolved") is False:
                    return CallToolResult(
                        content=[TextContent(
                            type="text",
                            text=f"No activity found to handle intent:\n{json.dumps(result, indent=2)}"
                        )]
                    )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(result, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error starting activity intent: {str(e)}")],
                    isError=True
                )

        # =====================================================================
        # Geofence Tools (Overnight)
        # =====================================================================

        @_register("device_geofence_add", "Add a geofence to monitor", {"type": "object","properties": {"geofence_id": {"type": "string"},"latitude": {"type": "number"},"longitude": {"type": "number"},"radius_meters": {"type": "number"},"expiration_ms": {"type": "integer"},"transition_types": {"type": "array","items": {"type": "string"}},"loitering_delay_ms": {"type": "integer"}},"required": ["geofence_id","latitude","longitude"]})
        async def device_geofence_add(
            geofence_id: str,
            latitude: float,
            longitude: float,
            radius_meters: float = 100.0,
            expiration_ms: int = -1,
            transition_types: Optional[List[str]] = None,
            loitering_delay_ms: int = 30000
        ) -> CallToolResult:
            """Add a geofence to monitor
            Args:
                geofence_id: Unique identifier for this geofence
                latitude: Center latitude of the geofence
                longitude: Center longitude of the geofence
                radius_meters: Radius in meters (default 100, min 50)
                expiration_ms: Expiration time in milliseconds (-1 for never expire)
                transition_types: List of transitions to monitor - ["enter", "exit", "dwell"] (default ["enter", "exit"])
                loitering_delay_ms: Time in ms before DWELL transition triggers (default 30000)
            """
            try:
                if transition_types is None:
                    transition_types = ["enter", "exit"]

                response = await self.client.post(
                    f"{TIER1_BASE}/geofence/add",
                    json={
                        "geofence_id": geofence_id,
                        "latitude": latitude,
                        "longitude": longitude,
                        "radius_meters": max(radius_meters, 50.0),
                        "expiration_ms": expiration_ms,
                        "transition_types": transition_types,
                        "loitering_delay_ms": loitering_delay_ms
                    }
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error adding geofence: {str(e)}")]
                )

        @_register("device_geofence_remove", "Remove a geofence by ID", {"type": "object","properties": {"geofence_id": {"type": "string"}},"required": ["geofence_id"]})
        async def device_geofence_remove(geofence_id: str) -> CallToolResult:
            """Remove a geofence by ID
            Args:
                geofence_id: Identifier of the geofence to remove
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/geofence/remove",
                    json={"geofence_id": geofence_id}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error removing geofence: {str(e)}")]
                )

        @_register("device_geofence_list", "List all active geofences with their parameters and status", {"type": "object","properties": {},"required": []})
        async def device_geofence_list() -> CallToolResult:
            """List all active geofences with their parameters and status"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/geofence/list")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing geofences: {str(e)}")]
                )

        @_register("device_input_keyevent", "Send an input key event to the device (e.g. power=26, home=3, back=4, volume_up=24, volume_down=25, camera=27)", {"type": "object","properties": {"keycode": {"type": "integer","description": "Android keycode integer (e.g. 26=POWER, 3=HOME, 4=BACK, 24=VOLUME_UP, 25=VOLUME_DOWN)"}},"required": ["keycode"]})
        async def device_input_keyevent(keycode: int) -> CallToolResult:
            """Send an input key event to the device
            Args:
                keycode: Android keycode integer (e.g. 26=POWER, 3=HOME, 4=BACK, 24=VOLUME_UP, 25=VOLUME_DOWN, 27=CAMERA)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/input/keyevent",
                    json={"keycode": keycode}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending key event: {str(e)}")]
                )

        # =====================================================================
        # Download Manager Tools (Overnight)
        # =====================================================================

        @_register("device_download_enqueue", "Enqueue a file download via Android DownloadManager", {"type": "object","properties": {"url": {"type": "string"},"filename": {"type": "string"},"title": {"type": "string"},"description": {"type": "string"},"destination": {"type": "string"},"wifi_only": {"type": "boolean"}},"required": ["url"]})
        async def device_download_enqueue(
            url: str,
            filename: str = "",
            title: str = "",
            description: str = "",
            destination: str = "downloads",
            wifi_only: bool = False
        ) -> CallToolResult:
            """Enqueue a file download via Android DownloadManager
            Args:
                url: URL of the file to download
                filename: Desired filename (auto-detected from URL if empty)
                title: Notification title for the download
                description: Notification description
                destination: Download destination - "downloads", "documents", "pictures", "music" (default "downloads")
                wifi_only: Only download on WiFi (default False)
            """
            try:
                body = {"url": url, "destination": destination, "wifi_only": wifi_only}
                if filename:
                    body["filename"] = filename
                if title:
                    body["title"] = title
                if description:
                    body["description"] = description

                response = await self.client.post(
                    f"{TIER1_BASE}/download/enqueue",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error enqueuing download: {str(e)}")]
                )

        @_register("device_download_status", "Get download status from DownloadManager", {"type": "object","properties": {"id": {"type": "integer","description": "Download ID to query"}},"required": ["id"]})
        async def device_download_status(id: int) -> CallToolResult:
            """Get download status from DownloadManager
            Args:
                id: Specific download ID to query (required by Kotlin backend)
            """
            try:
                params = {"id": id}

                response = await self.client.get(
                    f"{TIER1_BASE}/download/status",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting download status: {str(e)}")]
                )

        # =====================================================================
        # Biometric Tools (Overnight)
        # =====================================================================

        @_register("device_biometric_status", "Get biometric authentication capabilities and enrollment status", {"type": "object","properties": {},"required": []})
        async def device_biometric_status() -> CallToolResult:
            """Get biometric authentication capabilities and enrollment status
            Returns info about:
            - Available biometric types (fingerprint, face, iris)
            - Enrollment status
            - Hardware availability
            - Security level (strong, weak, device credential)
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/biometric/status")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting biometric status: {str(e)}")]
                )

        # =====================================================================
        # Health Check Tools
        # =====================================================================

        # =====================================================================
        # PackageManager Tools (zero-permission, Tier 1)
        # =====================================================================

        @_register("device_apps_list", "List all installed apps on the device (user apps by default)", {"type": "object","properties": {"show_system": {"type": "boolean","description": "Include system apps (default false)"}}})
        async def device_apps_list(show_system: bool = False) -> CallToolResult:
            """List installed apps"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/apps/list")
                data = response.json()
                if not show_system:
                    data["apps"] = [a for a in data.get("apps", []) if not a.get("system_app", False)]
                    data["count"] = len(data["apps"])
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(data, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing apps: {str(e)}")]
                )

        @_register("device_apps_launch", "Launch an installed app by its package name", {"type": "object","properties": {"package": {"type": "string","description": "Package name of the app to launch (e.g. com.example.app)"}},"required": ["package"]})
        async def device_apps_launch(package: str) -> CallToolResult:
            """Launch an installed app by its package name
            Args:
                package: Package name of the app to launch (e.g. com.android.settings)
            Returns success with app name and version, or error if no launch intent found
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/apps/launch",
                    json={"package": package}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error launching app: {str(e)}")]
                )

        @_register("device_apps_info", "Get detailed info about a specific app including permissions, installer, SDK targets", {"type": "object","properties": {"package": {"type": "string","description": "Package name (e.g. com.example.app)"}},"required": ["package"]})
        async def device_apps_info(package: str) -> CallToolResult:
            """Get detailed app info"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/apps/info",
                    params={"package": package}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting app info: {str(e)}")]
                )

        @_register("device_apps_resolve", "Find which apps can handle a given intent action", {"type": "object","properties": {"action": {"type": "string","description": "Intent action (e.g. android.intent.action.VIEW)"},"data": {"type": "string","description": "Optional data URI (e.g. https://example.com)"},"mimeType": {"type": "string","description": "Optional MIME type (e.g. image/png)"}},"required": ["action"]})
        async def device_apps_resolve(action: str, data: str = None, mimeType: str = None) -> CallToolResult:
            """Resolve intent to matching activities"""
            try:
                params = {"action": action}
                if data:
                    params["data"] = data
                if mimeType:
                    params["mimeType"] = mimeType
                response = await self.client.get(
                    f"{TIER1_BASE}/apps/resolve",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error resolving intent: {str(e)}")]
                )

        @_register("device_apps_find_by_permission", "Find all apps that have been granted a specific permission", {"type": "object","properties": {"permission": {"type": "string","description": "Android permission (e.g. android.permission.CAMERA)"}},"required": ["permission"]})
        async def device_apps_find_by_permission(permission: str) -> CallToolResult:
            """Find apps holding a given permission"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/apps/find-by-permission",
                    params={"permission": permission}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error finding apps by permission: {str(e)}")]
                )

        @_register("device_apps_changes", "Get packages that changed since a sequence number (for tracking installs/updates/uninstalls)", {"type": "object","properties": {"sequenceNumber": {"type": "integer","description": "Sequence number from previous call (0 for first call)"}}})
        async def device_apps_changes(sequenceNumber: int = 0) -> CallToolResult:
            """Get changed packages since sequence number"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/apps/changes",
                    params={"sequenceNumber": sequenceNumber}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting changed packages: {str(e)}")]
                )

        @_register("device_apps_components", "List all activities, services, and receivers for a given app", {"type": "object","properties": {"package": {"type": "string","description": "Package name (e.g. com.example.app)"}},"required": ["package"]})
        async def device_apps_components(package: str) -> CallToolResult:
            """Get app components (activities, services, receivers)"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/apps/components",
                    params={"package": package}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting components: {str(e)}")]
                )

        @_register("device_apps_defaults", "Get default app handlers (browser, SMS, dialer, launcher, email, camera, maps)", {"type": "object","properties": {},"required": []})
        async def device_apps_defaults() -> CallToolResult:
            """Get default app handlers for common intents"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/apps/defaults")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting default apps: {str(e)}")]
                )

        @_register("device_features_list", "List all hardware/software features available on the device (NFC, camera, sensors, etc.)", {"type": "object","properties": {},"required": []})
        async def device_features_list() -> CallToolResult:
            """List all device system features"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/device/features")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting device features: {str(e)}")]
                )

        @_register("device_modules_list", "List installed Android modules (API 29+, e.g. ADBD, ExtServices, Networking)", {"type": "object","properties": {},"required": []})
        async def device_modules_list() -> CallToolResult:
            """List installed device modules"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/device/modules")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting device modules: {str(e)}")]
                )

        @_register("device_health_check", "Check health of all device APIs", {"type": "object","properties": {},"required": []})
        async def device_health_check() -> CallToolResult:
            """Check health of all device APIs"""
            results = {}

            # Check Tier 1
            try:
                response = await self.client.get(f"{TIER1_BASE}/health", timeout=5.0)
                results["tier1"] = {"status": "healthy", "response": response.json()}
            except Exception as e:
                results["tier1"] = {"status": "unhealthy", "error": str(e)}

            # Check Tier 2
            try:
                response = await self.client.get(f"{TIER2_BASE}/health", timeout=5.0)
                results["tier2"] = {"status": "healthy", "response": response.json()}
            except Exception as e:
                results["tier2"] = {"status": "unhealthy", "error": str(e)}

            return CallToolResult(
                content=[TextContent(type="text", text=json.dumps(results, indent=2))]
            )

        @_register("device_api_list", "List all available device API endpoints", {"type": "object","properties": {},"required": []})
        async def device_api_list() -> CallToolResult:
            """List all available device API endpoints"""
            endpoints = {
                "tier1": {
                    "base_url": TIER1_BASE,
                    "sms": ["/sms/list", "/sms/send"],
                    "tasks": ["/tasks/list", "/tasks/create", "/tasks/update", "/tasks/delete"],
                    "contacts": ["/contacts"],
                    "clipboard": ["/clipboard (GET/POST)"],
                    "tts": ["/tts"],
                    "system": ["/battery", "/wifi", "/location", "/screen/state"],
                    "sensors": ["/sensors", "/sensors/read"],
                    "apps": ["/apps/list", "/apps/launch", "/apps/info", "/apps/resolve", "/apps/find-by-permission", "/apps/changes", "/apps/components", "/apps/defaults"],
                    "device_meta": ["/device/features", "/device/modules"],
                    "media": ["/media/playing", "/media/control"],
                    "volume": ["/volume (GET/POST)"],
                    "brightness": ["/brightness (POST)"],
                    "dnd": ["/dnd (POST)"],
                    "torch": ["/torch (POST)"],
                    "vibration": ["/vibrate", "/vibrate/effect", "/vibrate/pattern", "/vibrate/cancel"],
                    "alarms": ["/alarms/set", "/alarms/schedule", "/alarms/cancel"],
                    "calendar": ["/calendar/list", "/calendar/events", "/calendar/create", "/calendar/update", "/calendar/delete"],
                    "communication": ["/call_log", "/share", "/notification", "/toast"],
                    "tasker": ["/tasker/run", "/tasker/tasks", "/tasker/variable", "/tasker/profile"],
                    "photos": ["/photos/recent"],
                    "downloads": ["/downloads"],
                    "network": ["/network/capabilities", "/network/request", "/network/data_usage", "/network/active", "/network/all"],
                    "bluetooth": ["/bluetooth", "/bluetooth/scan"],
                    "wifi": ["/wifi", "/wifi/scan"],
                    "camera": ["/camera/list", "/camera/photo", "/camera/video", "/camera/analyze", "/camera/status"],
                    "audio_recording": ["/audio/record/start", "/audio/record/stop", "/audio/status"],
                    "phone": ["/phone/call", "/phone/hangup", "/phone/state"],
                    "work_manager": ["/work/schedule", "/work/status", "/work/cancel"],
                    "intent": ["/intent/broadcast", "/intent/activity"],
                    "geofence": ["/geofence/add", "/geofence/remove", "/geofence/list"],
                    "download_manager": ["/download/enqueue", "/download/status"],
                    "biometric": ["/biometric/status"]
                },
                "tier2": {
                    "base_url": TIER2_BASE,
                    "shell": ["/shell/execute"],
                    "system": ["/system/info", "/system/processes", "/system/disk"],
                    "network": ["/network/interfaces"],
                    "files": ["/files/read", "/files/write", "/files/list"],
                    "package": ["/package/installed", "/package/pip"]
                },
                "rag": {
                    "base_url": RAG_BASE,
                    "memory": ["/search (POST)", "/context (POST)", "/memory (POST/DELETE)", "/health (GET)"]
                },
                "tasker_shortcuts": {
                    "note": "Convenience wrappers around /tasker/run",
                    "tools": ["tasker_print", "tasker_lamp_on", "tasker_lamp_off",
                              "tasker_play_music", "tasker_browse_url", "tasker_screenshot"]
                }
            }
            return CallToolResult(
                content=[TextContent(type="text", text=json.dumps(endpoints, indent=2))]
            )



        # =====================================================================
        # RAG Memory Tools (http://127.0.0.1:5562)
        # =====================================================================

        @_register("rag_search", "Search RAG memory for relevant memories", {"type": "object","properties": {"query": {"type": "string","description": "Search query"},"top_k": {"type": "integer","description": "Number of results to return (default 5)"}},"required": ["query"]})
        async def rag_search(query: str, top_k: int = 5) -> CallToolResult:
            """Search RAG memory for relevant memories
            Args:
                query: Search query string
                top_k: Number of results to return (default 5)
            """
            try:
                response = await self.client.post(
                    f"{RAG_BASE}/search",
                    json={"query": query, "top_k": top_k}
                )
                data = response.json()
                # Format results for readability
                results = data.get("memories", data.get("results", []))
                formatted = []
                for i, mem in enumerate(results, 1):
                    text = mem.get("text", "")
                    category = mem.get("category", "unknown")
                    distance = mem.get("distance", "N/A")
                    formatted.append(f"{i}. [{category}] (dist: {distance})\n   {text}")
                output = f"Found {len(results)} results for '{query}':\n\n" + "\n\n".join(formatted)
                return CallToolResult(
                    content=[TextContent(type="text", text=output)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error searching RAG: {str(e)}")]
                )

        @_register("rag_context", "Get formatted context from RAG memory for a query", {"type": "object","properties": {"query": {"type": "string","description": "Context query"},"top_k": {"type": "integer","description": "Number of memories to include (default 5)"}},"required": ["query"]})
        async def rag_context(query: str, top_k: int = 5) -> CallToolResult:
            """Get formatted context from RAG memory
            Args:
                query: Context query string
                top_k: Number of memories to include (default 5)
            """
            try:
                response = await self.client.post(
                    f"{RAG_BASE}/context",
                    json={"query": query, "top_k": top_k}
                )
                data = response.json()
                context_text = data.get("context", json.dumps(data, indent=2))
                return CallToolResult(
                    content=[TextContent(type="text", text=context_text)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting RAG context: {str(e)}")]
                )

        @_register("rag_add_memory", "Add a new memory to RAG storage", {"type": "object","properties": {"text": {"type": "string","description": "Memory text to store"},"category": {"type": "string","description": "Category for the memory"},"metadata": {"type": "object","description": "Additional metadata dict"}},"required": ["text","category"]})
        async def rag_add_memory(text: str, category: str, metadata: dict = None) -> CallToolResult:
            """Add a new memory to RAG storage
            Args:
                text: Memory text to store
                category: Category for the memory (e.g. 'project', 'tool', 'warning')
                metadata: Additional metadata dictionary
            """
            try:
                payload = {"text": text, "category": category, "metadata": metadata or {}}
                response = await self.client.post(
                    f"{RAG_BASE}/memory",
                    json=payload
                )
                data = response.json()
                memory_id = data.get("id", data.get("memory_id", "unknown"))
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Memory added successfully. ID: {memory_id}")]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error adding RAG memory: {str(e)}")]
                )

        @_register("rag_health", "Check RAG memory service health status", {"type": "object","properties": {}})
        async def rag_health() -> CallToolResult:
            """Check RAG memory service health status"""
            try:
                response = await self.client.get(f"{RAG_BASE}/health")
                data = response.json()
                status = data.get("status", "unknown")
                memories = data.get("memories", "N/A")
                model = data.get("embedding_model", "N/A")
                output = f"RAG Health: {status}\nMemories: {memories}\nModel: {model}"
                return CallToolResult(
                    content=[TextContent(type="text", text=output)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"RAG service unreachable: {str(e)}")]
                )

        @_register("rag_delete_memory", "Delete a specific memory from RAG storage by ID", {"type": "object","properties": {"memory_id": {"type": "string","description": "ID of the memory to delete"}},"required": ["memory_id"]})
        async def rag_delete_memory(memory_id: str) -> CallToolResult:
            """Delete a memory from RAG storage
            Args:
                memory_id: ID of the memory to delete
            """
            try:
                response = await self.client.delete(f"{RAG_BASE}/memory/{memory_id}")
                if response.status_code == 200:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Memory {memory_id} deleted successfully.")]
                    )
                else:
                    data = response.json()
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Delete failed ({response.status_code}): {json.dumps(data)}")]
                    )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting RAG memory: {str(e)}")]
                )

        # =====================================================================
        # Tasker Unique Tools (via TIER1_BASE /tasker/run)
        # =====================================================================

        @_register("tasker_print", "Print a document via Tasker MCP Print task", {"type": "object","properties": {"text": {"type": "string","description": "Text content to print"}},"required": ["text"]})
        async def tasker_print(text: str) -> CallToolResult:
            """Print a document via Tasker
            Args:
                text: Text content to print
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Print", "text": text}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error running Tasker print: {str(e)}")]
                )

        @_register("tasker_lamp_on", "Turn bedroom lamp ON via Tasker", {"type": "object","properties": {}})
        async def tasker_lamp_on() -> CallToolResult:
            """Turn bedroom lamp ON via Tasker"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Lamp ON"}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error turning lamp on: {str(e)}")]
                )

        @_register("tasker_lamp_off", "Turn bedroom lamp OFF via Tasker", {"type": "object","properties": {}})
        async def tasker_lamp_off() -> CallToolResult:
            """Turn bedroom lamp OFF via Tasker"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Lamp OFF"}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error turning lamp off: {str(e)}")]
                )

        @_register("tasker_play_music", "Play music on YouTube Music via Tasker", {"type": "object","properties": {"query": {"type": "string","description": "Music search query"}},"required": ["query"]})
        async def tasker_play_music(query: str) -> CallToolResult:
            """Play music on YouTube Music via Tasker
            Args:
                query: Music search query (song name, artist, etc.)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Play Music", "query": query}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error playing music: {str(e)}")]
                )

        @_register("tasker_browse_url", "Open a URL in the browser via Tasker", {"type": "object","properties": {"url": {"type": "string","description": "URL to open"}},"required": ["url"]})
        async def tasker_browse_url(url: str) -> CallToolResult:
            """Open a URL in the browser via Tasker
            Args:
                url: URL to open in the browser
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Browse URL", "url": url}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error browsing URL: {str(e)}")]
                )

        @_register("tasker_screenshot", "Take a screenshot via Tasker", {"type": "object","properties": {}})
        async def tasker_screenshot() -> CallToolResult:
            """Take a screenshot via Tasker"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tasker/run",
                    json={"task_name": "MCP Screenshot"}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error taking screenshot: {str(e)}")]
                )


        # ===== Notification Reading Tools =====

        @_register("device_notifications_active",
                   "Get all active notifications on the device. Returns enriched data: identity (key, id, package, op_pkg, tag, uid), grouping (group_key, is_group, is_app_group, override_group_key), state (is_ongoing, is_clearable), text (title, text, sub_text, big_text, info_text, summary_text, ticker_text), metadata (category, group, channel_id, when, color, visibility, number, flags), intents (has_content_intent, has_delete_intent, has_full_screen_intent), and actions with semantic_action and is_contextual.",
                   {
                       "type": "object",
                       "properties": {
                           "package_filter": {
                               "type": "string",
                               "description": "Optional: filter results to a specific package name"
                           }
                       }
                   })
        async def device_notifications_active(package_filter: Optional[str] = None) -> CallToolResult:
            """Get all currently active notifications on the device
            Args:
                package_filter: Optional package name to filter results (client-side filter)
            """
            try:
                response = await self.client.get(f"{TIER1_BASE}/notifications/active")
                data = response.json()

                # Client-side package filter
                if package_filter and data.get("success") and "notifications" in data:
                    filtered = [n for n in data["notifications"] if n.get("package") == package_filter]
                    data["notifications"] = filtered
                    data["count"] = len(filtered)
                    data["filtered_by"] = package_filter

                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(data, indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting notifications: {str(e)}")],
                    isError=True
                )

        @_register("device_notifications_status", "Check if the notification listener service is running and has permission", {"type": "object","properties": {}})
        async def device_notifications_status() -> CallToolResult:
            """Check notification listener status"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notifications/status")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking notification status: {str(e)}")]
                )

        @_register("device_notifications_dismiss", "Dismiss a specific notification by its key", {"type": "object","properties": {"key": {"type": "string","description": "The notification key from device_notifications_active"}},"required": ["key"]})
        async def device_notifications_dismiss(key: str) -> CallToolResult:
            """Dismiss a notification by key
            Args:
                key: Notification key from the active notifications list
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/notifications/dismiss",
                    json={"key": key}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error dismissing notification: {str(e)}")]
                )

        @_register("device_notifications_dismiss_all", "Dismiss all clearable notifications", {"type": "object","properties": {}})
        async def device_notifications_dismiss_all() -> CallToolResult:
            """Dismiss all clearable notifications"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/notifications/dismiss_all")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error dismissing notifications: {str(e)}")]
                )

        @_register("device_notifications_reply", "Reply to a messaging notification (WhatsApp, SMS, etc.)", {"type": "object","properties": {"key": {"type": "string","description": "Notification key"},"reply_text": {"type": "string","description": "Text to reply with"},"action_index": {"type": "integer","description": "Action index for reply (optional, auto-detected if omitted)"}},"required": ["key","reply_text"]})
        async def device_notifications_reply(key: str, reply_text: str, action_index: Optional[int] = None) -> CallToolResult:
            """Reply to a messaging notification
            Args:
                key: Notification key
                reply_text: Text to send as reply
                action_index: Optional action index (auto-detected if omitted)
            """
            try:
                body = {"key": key, "reply_text": reply_text}
                if action_index is not None:
                    body["action_index"] = action_index
                response = await self.client.post(
                    f"{TIER1_BASE}/notifications/reply",
                    json=body
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error replying to notification: {str(e)}")]
                )

        # ===== Home Assistant Tools =====

        @_register("ha_get_state", "Get the state of a Home Assistant entity (light, sensor, switch, climate, lock, etc.)", {"type": "object","properties": {"entity_id": {"type": "string","description": "Entity ID, e.g. light.living_room, sensor.temperature, climate.thermostat"}},"required": ["entity_id"]})
        async def ha_get_state(entity_id: str) -> CallToolResult:
            """Get state of a single HA entity
            Args:
                entity_id: The entity_id (e.g. light.living_room)
            """
            try:
                result = await self._ha_proxy_request("GET", f"/api/states/{entity_id}")
                if result.get("status") == 404:
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Entity '{entity_id}' not found")]
                    )
                body = result.get("body", "")
                data = json.loads(body) if isinstance(body, str) else body
                summary = f"Entity: {data.get('entity_id')}\nState: {data.get('state')}\nAttributes: {json.dumps(data.get('attributes', {}), indent=2)}\nLast changed: {data.get('last_changed')}"
                return CallToolResult(
                    content=[TextContent(type="text", text=summary)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting HA state: {str(e)}")]
                )

        @_register("ha_get_states", "Get states of all Home Assistant entities, optionally filtered by domain (light, sensor, switch, climate, etc.)", {"type": "object","properties": {"domain": {"type": "string","description": "Optional domain filter, e.g. 'light', 'sensor', 'switch', 'climate', 'lock'"}}})
        async def ha_get_states(domain: Optional[str] = None) -> CallToolResult:
            """Get all HA entity states, optionally filtered by domain
            Args:
                domain: Optional domain prefix to filter (e.g. 'light', 'sensor')
            """
            try:
                proxy_result = await self._ha_proxy_request("GET", "/api/states")
                body = proxy_result.get("body", "")
                entities = json.loads(body) if isinstance(body, str) else body
                if domain:
                    entities = [e for e in entities if e.get("entity_id", "").startswith(f"{domain}.")]
                summary_lines = []
                for e in entities:
                    attrs = e.get("attributes", {})
                    name = attrs.get("friendly_name", e.get("entity_id"))
                    summary_lines.append(f"{e.get('entity_id')}: {e.get('state')} ({name})")
                result = f"Found {len(entities)} entities"
                if domain:
                    result += f" in domain '{domain}'"
                result += ":\n" + "\n".join(summary_lines[:100])
                if len(entities) > 100:
                    result += f"\n... and {len(entities) - 100} more"
                return CallToolResult(
                    content=[TextContent(type="text", text=result)]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting HA states: {str(e)}")]
                )

        @_register("ha_call_service", "Call a Home Assistant service (turn on/off lights, set temperature, lock/unlock, trigger automations, etc.)", {"type": "object","properties": {"domain": {"type": "string","description": "Service domain: light, switch, climate, lock, script, automation, input_boolean, etc."},"service": {"type": "string","description": "Service name: turn_on, turn_off, toggle, set_temperature, lock, unlock, trigger, etc."},"entity_id": {"type": "string","description": "Target entity_id (e.g. light.living_room)"},"data": {"type": "object","description": "Optional extra service data (brightness, temperature, rgb_color, etc.)"}},"required": ["domain","service"]})
        async def ha_call_service(domain: str, service: str, entity_id: Optional[str] = None, data: Optional[dict] = None) -> CallToolResult:
            """Call a Home Assistant service
            Args:
                domain: Service domain (light, switch, climate, etc.)
                service: Service name (turn_on, turn_off, set_temperature, etc.)
                entity_id: Target entity (optional for some services)
                data: Additional service data (optional)
            """
            try:
                svc_body = data or {}
                if entity_id:
                    svc_body["entity_id"] = entity_id
                proxy_result = await self._ha_proxy_request(
                    "POST", f"/api/services/{domain}/{service}", svc_body
                )
                body = proxy_result.get("body", "")
                changed = json.loads(body) if isinstance(body, str) else body
                if isinstance(changed, list) and len(changed) > 0:
                    summary_lines = []
                    for e in changed:
                        summary_lines.append(f"{e.get('entity_id')}: {e.get('state')}")
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"Service {domain}.{service} called. Changed entities:\n" + "\n".join(summary_lines))]
                    )
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Service {domain}.{service} called successfully")]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error calling HA service: {str(e)}")]
                )

        @_register("ha_get_services", "List all available Home Assistant service domains and their services", {"type": "object","properties": {"domain": {"type": "string","description": "Optional: filter to a specific domain to see its available services"}}})
        async def ha_get_services(domain: Optional[str] = None) -> CallToolResult:
            """List available HA services
            Args:
                domain: Optional domain to filter (e.g. 'light' to see light services only)
            """
            try:
                proxy_result = await self._ha_proxy_request("GET", "/api/services")
                body = proxy_result.get("body", "")
                services = json.loads(body) if isinstance(body, str) else body
                if domain:
                    services = [s for s in services if s.get("domain") == domain]
                summary_lines = []
                for svc in services:
                    d = svc.get("domain", "?")
                    svc_names = list(svc.get("services", {}).keys())
                    summary_lines.append(f"{d}: {', '.join(svc_names)}")
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Available services ({len(services)} domains):\n" + "\n".join(summary_lines))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting HA services: {str(e)}")]
                )

        @_register("ha_render_template", "Render a Home Assistant Jinja2 template (useful for computed values, complex queries)", {"type": "object","properties": {"template": {"type": "string","description": "Jinja2 template string, e.g. {{ states('sensor.temperature') }} or {{ states.light | selectattr('state','eq','on') | list | count }}"}},"required": ["template"]})
        async def ha_render_template(template: str) -> CallToolResult:
            """Render an HA Jinja2 template
            Args:
                template: Jinja2 template string
            """
            try:
                proxy_result = await self._ha_proxy_request(
                    "POST", "/api/template", {"template": template}
                )
                body = proxy_result.get("body", "")
                return CallToolResult(
                    content=[TextContent(type="text", text=body if isinstance(body, str) else json.dumps(body))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error rendering HA template: {str(e)}")]
                )

        # Accounts + Logcat Tools
        @_register("device_accounts_list", "List all accounts registered on the device (type and name pairs, no auth tokens)", {"type": "object","properties": {},"required": []})
        async def device_accounts_list() -> CallToolResult:
            """List all accounts registered on the device"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/accounts")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing accounts: {str(e)}")]
                )

        @_register("device_logcat", "Retrieve recent logcat output from the device with optional filtering by tag and priority level", {"type": "object","properties": {"lines": {"type": "integer","description": "Number of lines to retrieve (default 100, max 5000)"},"tag": {"type": "string","description": "Filter by log tag (e.g. 'ActivityManager', 'mK:a')"},"priority": {"type": "string","description": "Minimum priority level: V(erbose), D(ebug), I(nfo), W(arning), E(rror), F(atal)"}}})
        async def device_logcat(lines: int = 100, tag: str = None, priority: str = None) -> CallToolResult:
            """Retrieve recent logcat output
            Args:
                lines: Number of lines (default 100)
                tag: Optional tag filter
                priority: Optional priority filter (V/D/I/W/E/F)
            """
            try:
                params = {"lines": lines}
                if tag:
                    params["tag"] = tag
                if priority:
                    params["priority"] = priority
                response = await self.client.get(
                    f"{TIER1_BASE}/logcat",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error reading logcat: {str(e)}")]
                )

        # Chrome Custom Tabs Browser Tools
        @_register("device_browser_open",
                   "Open a URL in Chrome Custom Tabs with customizable appearance",
                   {
                       "type": "object",
                       "properties": {
                           "url": {"type": "string", "description": "URL to open (http:// or https://)"},
                           "toolbarColor": {"type": "string", "description": "Hex color for toolbar (default: '#FCB45B')"},
                           "showTitle": {"type": "boolean", "description": "Show page title in toolbar (default: true)"},
                           "shareState": {"type": "string", "description": "Share button: 'on', 'off', or 'default' (default: 'on')"}
                       },
                       "required": ["url"]
                   })
        async def device_browser_open(url: str, toolbarColor: str = "#FCB45B", showTitle: bool = True, shareState: str = "on") -> CallToolResult:
            """Open a URL in Chrome Custom Tabs
            Args:
                url: URL to open (must be http:// or https://)
                toolbarColor: Hex color for toolbar (default: '#FCB45B')
                showTitle: Show page title in toolbar (default: true)
                shareState: Share button visibility: 'on', 'off', or 'default'
            """
            try:
                payload = {"url": url, "toolbarColor": toolbarColor, "showTitle": showTitle, "shareState": shareState}
                response = await self.client.post(
                    f"{TIER1_BASE}/browser/open",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error opening browser: {str(e)}")],
                    isError=True
                )

        @_register("device_browser_prefetch",
                   "Pre-warm browser and speculatively load a URL for instant Custom Tabs launch",
                   {
                       "type": "object",
                       "properties": {
                           "url": {"type": "string", "description": "URL to prefetch (http:// or https://)"}
                       },
                       "required": ["url"]
                   })
        async def device_browser_prefetch(url: str) -> CallToolResult:
            """Pre-warm browser and prefetch a URL for near-instant loading
            Args:
                url: URL to prefetch (must be http:// or https://)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/browser/prefetch",
                    json={"url": url}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error prefetching URL: {str(e)}")],
                    isError=True
                )

        # ---- RLM (Recursive Language Model) ----
        @_register("rlm_analyze",
                   "Run recursive analysis using Claude agents. Handles large contexts (100x model limit), improves reasoning 28-114%. Modes: rag (search knowledge), text (analyze text), file (analyze file), codebase (analyze directory), hybrid (combine)",
                   {
                       "type": "object",
                       "properties": {
                           "query": {"type": "string", "description": "The question to answer or task to perform"},
                           "context_text": {"type": "string", "description": "Direct text to analyze (text mode)"},
                           "context_file": {"type": "string", "description": "Path to file to analyze (file mode)"},
                           "directory": {"type": "string", "description": "Directory for codebase analysis (codebase mode)"},
                           "mode": {"type": "string", "description": "Analysis mode", "enum": ["rag", "text", "file", "codebase", "hybrid"]},
                           "model": {"type": "string", "description": "Claude model", "enum": ["haiku", "sonnet", "opus"]},
                           "max_iterations": {"type": "integer", "description": "Max REPL iterations (1-15)", "minimum": 1, "maximum": 15},
                           "timeout_ms": {"type": "integer", "description": "Total timeout in milliseconds"},
                           "extensions": {"type": "array", "items": {"type": "string"}, "description": "File extensions for codebase mode"},
                           "categories": {"type": "array", "items": {"type": "string"}, "description": "RAG categories to search"},
                           "include_session_memory": {"type": "boolean", "description": "Include session memory in context"},
                           "use_task_tool": {"type": "boolean", "description": "Use Task tool vs direct CLI"}
                       },
                       "required": ["query"]
                   })
        async def rlm_analyze(**kwargs) -> CallToolResult:
            try:
                if not self.rlm_engine:
                    return CallToolResult(content=[TextContent(type="text", text="RLM engine not initialized")])
                result = await self.rlm_engine.analyze(**kwargs)
                output = f"RLM Analysis Complete\n{'='*50}\n\n"
                output += f"Query: {kwargs.get('query')}\n"
                output += f"Mode: {kwargs.get('mode', 'rag')}\n"
                output += f"Model: {result.get('model', 'haiku')}\n"
                output += f"Iterations: {result.get('iterations')}\n"
                output += f"Time: {result.get('elapsed_ms')}ms\n"
                if result.get('tools_used'):
                    output += f"Tools: {', '.join(result['tools_used'])}\n"
                output += f"\n{'='*50}\nANSWER:\n{result.get('result', 'No answer found')}\n"
                return CallToolResult(content=[TextContent(type="text", text=output)])
            except Exception as e:
                logger.error(f"RLM analysis failed: {e}", exc_info=True)
                return CallToolResult(content=[TextContent(type="text", text=f"RLM Error: {str(e)}")])

        # ---- Notification Tools ----
        @_register("notification_channels_list", "List all notification channels for this app",
                   {"type": "object", "properties": {}})
        async def notification_channels_list() -> CallToolResult:
            """List all notification channels for this app."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/channels")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing notification channels: {str(e)}")]
                )

        @_register("notification_channel_get", "Get details of a specific notification channel",
                   {"type": "object", "properties": {"channelId": {"type": "string", "description": "The channel ID to look up"}}, "required": ["channelId"]})
        async def notification_channel_get(channelId: str) -> CallToolResult:
            """Get details of a specific notification channel.
            Args:
                channelId: The channel ID to look up
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/notification/channel",
                    params={"channelId": channelId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting notification channel: {str(e)}")]
                )

        @_register("notification_channel_create", "Create or update a notification channel",
                   {"type": "object", "properties": {
                       "channelId": {"type": "string", "description": "Unique channel ID"},
                       "name": {"type": "string", "description": "User-visible channel name"},
                       "importance": {"type": "integer", "description": "Importance level (0-5)"},
                       "description": {"type": "string", "description": "Channel description"},
                       "groupId": {"type": "string", "description": "Channel group ID"},
                       "vibration": {"type": "boolean", "description": "Enable vibration"},
                       "lights": {"type": "boolean", "description": "Enable notification LED"},
                       "soundUri": {"type": "string", "description": "Sound URI"},
                       "lightColor": {"type": "integer", "description": "LED color as ARGB int"},
                       "lockscreenVisibility": {"type": "integer", "description": "Lockscreen visibility (-1=secret, 0=private, 1=public)"},
                       "bypassDnd": {"type": "boolean", "description": "Allow bypassing DND"},
                       "showBadge": {"type": "boolean", "description": "Show badge on app icon"},
                       "allowBubbles": {"type": "boolean", "description": "Allow bubble notifications"}
                   }, "required": ["channelId", "name"]})
        async def notification_channel_create(
            channelId: str,
            name: str,
            importance: int = 3,
            description: str = "",
            groupId: str = "",
            vibration: bool = True,
            lights: bool = False,
            soundUri: str = "",
            lightColor: int = 0,
            lockscreenVisibility: int = 0,
            bypassDnd: bool = False,
            showBadge: bool = True,
            allowBubbles: bool = False
        ) -> CallToolResult:
            """Create or update a notification channel.
            Args:
                channelId: Unique channel ID
                name: User-visible channel name
                importance: Importance level (0-5, default 3)
                description: Channel description
                groupId: Channel group ID
                vibration: Enable vibration
                lights: Enable notification LED
                soundUri: Sound URI
                lightColor: LED color as ARGB int
                lockscreenVisibility: Lockscreen visibility (-1=secret, 0=private, 1=public)
                bypassDnd: Allow bypassing DND
                showBadge: Show badge on app icon
                allowBubbles: Allow bubble notifications
            """
            try:
                payload = {
                    "channelId": channelId, "name": name, "importance": importance,
                    "description": description, "groupId": groupId, "vibration": vibration,
                    "lights": lights, "soundUri": soundUri, "lightColor": lightColor,
                    "lockscreenVisibility": lockscreenVisibility, "bypassDnd": bypassDnd,
                    "showBadge": showBadge, "allowBubbles": allowBubbles
                }
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/channel",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error creating notification channel: {str(e)}")]
                )

        @_register("notification_channel_delete", "Delete a notification channel",
                   {"type": "object", "properties": {"channelId": {"type": "string", "description": "Channel ID to delete"}}, "required": ["channelId"]})
        async def notification_channel_delete(channelId: str) -> CallToolResult:
            """Delete a notification channel.
            Args:
                channelId: Channel ID to delete
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/notification/channel",
                    params={"channelId": channelId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting notification channel: {str(e)}")]
                )

        @_register("notification_channel_groups_list", "List all notification channel groups",
                   {"type": "object", "properties": {}})
        async def notification_channel_groups_list() -> CallToolResult:
            """List all notification channel groups."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/channel_groups")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing channel groups: {str(e)}")]
                )

        @_register("notification_channel_group_create", "Create a notification channel group",
                   {"type": "object", "properties": {
                       "groupId": {"type": "string", "description": "Unique group ID"},
                       "name": {"type": "string", "description": "User-visible group name"},
                       "description": {"type": "string", "description": "Group description"}
                   }, "required": ["groupId", "name"]})
        async def notification_channel_group_create(groupId: str, name: str, description: str = "") -> CallToolResult:
            """Create a notification channel group.
            Args:
                groupId: Unique group ID
                name: User-visible group name
                description: Group description
            """
            try:
                payload = {"groupId": groupId, "name": name, "description": description}
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/channel_group",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error creating channel group: {str(e)}")]
                )

        @_register("notification_channel_group_delete", "Delete a notification channel group",
                   {"type": "object", "properties": {"groupId": {"type": "string", "description": "Group ID to delete"}}, "required": ["groupId"]})
        async def notification_channel_group_delete(groupId: str) -> CallToolResult:
            """Delete a notification channel group.
            Args:
                groupId: Group ID to delete
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/notification/channel_group",
                    params={"groupId": groupId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting channel group: {str(e)}")]
                )

        @_register("notification_cancel", "Cancel a specific notification by ID",
                   {"type": "object", "properties": {
                       "id": {"type": "integer", "description": "Notification ID to cancel"},
                       "tag": {"type": "string", "description": "Notification tag"}
                   }, "required": ["id"]})
        async def notification_cancel(id: int, tag: str = "") -> CallToolResult:
            """Cancel a specific notification by ID.
            Args:
                id: Notification ID to cancel
                tag: Notification tag
            """
            try:
                payload = {"id": id}
                if tag:
                    payload["tag"] = tag
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/cancel",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error cancelling notification: {str(e)}")]
                )

        @_register("notification_cancel_all", "Cancel all notifications from this app",
                   {"type": "object", "properties": {}})
        async def notification_cancel_all() -> CallToolResult:
            """Cancel all notifications from this app."""
            try:
                response = await self.client.post(f"{TIER1_BASE}/notification/cancel_all", json={})
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error cancelling all notifications: {str(e)}")]
                )

        @_register("notification_active_list", "List all currently active notifications",
                   {"type": "object", "properties": {}})
        async def notification_active_list() -> CallToolResult:
            """List all currently active notifications."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/active")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing active notifications: {str(e)}")]
                )

        @_register("notification_policy_get", "Get current DND notification policy details",
                   {"type": "object", "properties": {}})
        async def notification_policy_get() -> CallToolResult:
            """Get current DND notification policy details."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/policy")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting notification policy: {str(e)}")]
                )

        @_register("notification_policy_set", "Set DND notification policy",
                   {"type": "object", "properties": {
                       "allowAlarms": {"type": "boolean", "description": "Allow alarm sounds"},
                       "allowMedia": {"type": "boolean", "description": "Allow media sounds"},
                       "allowSystem": {"type": "boolean", "description": "Allow system sounds"},
                       "allowCalls": {"type": "boolean", "description": "Allow calls"},
                       "allowMessages": {"type": "boolean", "description": "Allow messages"},
                       "allowRepeatCallers": {"type": "boolean", "description": "Allow repeat callers"},
                       "allowConversations": {"type": "boolean", "description": "Allow conversations"},
                       "allowCallsFrom": {"type": "integer", "description": "Calls sender filter (0=anyone,1=contacts,2=starred,3=none)"},
                       "allowMessagesFrom": {"type": "integer", "description": "Messages sender filter"},
                       "allowConversationsFrom": {"type": "integer", "description": "Conversations filter (0=all,1=important,2=none)"},
                       "allowEvents": {"type": "boolean", "description": "Allow calendar events"},
                       "allowReminders": {"type": "boolean", "description": "Allow reminders"},
                       "suppressedVisualEffects": {"type": "integer", "description": "Bitmask of suppressed visual effects"}
                   }})
        async def notification_policy_set(
            allowAlarms: bool = None,
            allowMedia: bool = None,
            allowSystem: bool = None,
            allowCalls: bool = None,
            allowMessages: bool = None,
            allowRepeatCallers: bool = None,
            allowConversations: bool = None,
            allowCallsFrom: int = None,
            allowMessagesFrom: int = None,
            allowConversationsFrom: int = None,
            allowEvents: bool = None,
            allowReminders: bool = None,
            suppressedVisualEffects: int = None
        ) -> CallToolResult:
            """Set DND notification policy.
            Args:
                allowAlarms: Allow alarm sounds
                allowMedia: Allow media sounds
                allowSystem: Allow system sounds
                allowCalls: Allow calls
                allowMessages: Allow messages
                allowRepeatCallers: Allow repeat callers
                allowConversations: Allow conversations
                allowCallsFrom: Calls sender filter (0=anyone,1=contacts,2=starred,3=none)
                allowMessagesFrom: Messages sender filter
                allowConversationsFrom: Conversations filter (0=all,1=important,2=none)
                allowEvents: Allow calendar events
                allowReminders: Allow reminders
                suppressedVisualEffects: Bitmask of suppressed visual effects
            """
            try:
                payload = {}
                for key, val in [
                    ("allowAlarms", allowAlarms), ("allowMedia", allowMedia),
                    ("allowSystem", allowSystem), ("allowCalls", allowCalls),
                    ("allowMessages", allowMessages), ("allowRepeatCallers", allowRepeatCallers),
                    ("allowConversations", allowConversations), ("allowCallsFrom", allowCallsFrom),
                    ("allowMessagesFrom", allowMessagesFrom), ("allowConversationsFrom", allowConversationsFrom),
                    ("allowEvents", allowEvents), ("allowReminders", allowReminders),
                    ("suppressedVisualEffects", suppressedVisualEffects)
                ]:
                    if val is not None:
                        payload[key] = val
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/policy",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting notification policy: {str(e)}")]
                )

        @_register("notification_interruption_filter_get", "Get current interruption filter level",
                   {"type": "object", "properties": {}})
        async def notification_interruption_filter_get() -> CallToolResult:
            """Get current interruption filter level."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/interruption_filter")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting interruption filter: {str(e)}")]
                )

        @_register("notification_interruption_filter_set", "Set interruption filter level",
                   {"type": "object", "properties": {
                       "filter": {"type": "integer", "description": "Filter: 1=all, 2=priority, 3=none, 4=alarms"}
                   }, "required": ["filter"]})
        async def notification_interruption_filter_set(filter: int) -> CallToolResult:
            """Set interruption filter level.
            Args:
                filter: Filter level (1=all, 2=priority, 3=none, 4=alarms)
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/interruption_filter",
                    json={"filter": filter}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting interruption filter: {str(e)}")]
                )

        @_register("notification_snooze", "Snooze a notification",
                   {"type": "object", "properties": {
                       "key": {"type": "string", "description": "Notification key"},
                       "durationMs": {"type": "integer", "description": "Snooze duration in milliseconds"}
                   }, "required": ["key", "durationMs"]})
        async def notification_snooze(key: str, durationMs: int) -> CallToolResult:
            """Snooze a notification.
            Args:
                key: Notification key
                durationMs: Snooze duration in milliseconds
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/snooze",
                    json={"key": key, "durationMs": durationMs}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error snoozing notification: {str(e)}")]
                )

        @_register("notification_unsnooze", "Unsnooze a notification",
                   {"type": "object", "properties": {
                       "key": {"type": "string", "description": "Notification key"}
                   }, "required": ["key"]})
        async def notification_unsnooze(key: str) -> CallToolResult:
            """Unsnooze a notification.
            Args:
                key: Notification key
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/notification/unsnooze",
                    json={"key": key}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error unsnoozing notification: {str(e)}")]
                )

        @_register("notification_snoozed_list", "List snoozed notifications",
                   {"type": "object", "properties": {}})
        async def notification_snoozed_list() -> CallToolResult:
            """List snoozed notifications."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/notification/snoozed")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing snoozed notifications: {str(e)}")]
                )

        # ---- Zen / DND Rules ----
        @_register("zen_rule_list", "List all automatic DND zen rules",
                   {"type": "object", "properties": {}})
        async def zen_rule_list() -> CallToolResult:
            """List all automatic DND zen rules."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/zen/rules")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing zen rules: {str(e)}")]
                )

        @_register("zen_rule_get", "Get a specific zen rule",
                   {"type": "object", "properties": {
                       "ruleId": {"type": "string", "description": "Zen rule ID"}
                   }, "required": ["ruleId"]})
        async def zen_rule_get(ruleId: str) -> CallToolResult:
            """Get a specific zen rule.
            Args:
                ruleId: Zen rule ID
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/zen/rule",
                    params={"ruleId": ruleId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting zen rule: {str(e)}")]
                )

        @_register("zen_rule_create", "Create a new automatic zen rule",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Rule name"},
                       "interruptionFilter": {"type": "integer", "description": "Filter level"},
                       "conditionId": {"type": "string", "description": "Condition URI"},
                       "enabled": {"type": "boolean", "description": "Start enabled"},
                       "triggerDescription": {"type": "string", "description": "Trigger description"},
                       "type": {"type": "integer", "description": "Rule type"}
                   }, "required": ["name", "interruptionFilter"]})
        async def zen_rule_create(
            name: str,
            interruptionFilter: int,
            conditionId: str = "",
            enabled: bool = True,
            triggerDescription: str = "",
            type: int = 0
        ) -> CallToolResult:
            """Create a new automatic zen rule.
            Args:
                name: Rule name
                interruptionFilter: Filter level
                conditionId: Condition URI
                enabled: Start enabled
                triggerDescription: Trigger description
                type: Rule type
            """
            try:
                payload = {
                    "name": name, "interruptionFilter": interruptionFilter,
                    "enabled": enabled
                }
                if conditionId:
                    payload["conditionId"] = conditionId
                if triggerDescription:
                    payload["triggerDescription"] = triggerDescription
                if type != 0:
                    payload["type"] = type
                response = await self.client.post(
                    f"{TIER1_BASE}/zen/rule",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error creating zen rule: {str(e)}")]
                )

        @_register("zen_rule_update", "Update an existing zen rule",
                   {"type": "object", "properties": {
                       "ruleId": {"type": "string", "description": "Rule ID"},
                       "name": {"type": "string", "description": "New name"},
                       "interruptionFilter": {"type": "integer", "description": "New filter level"},
                       "enabled": {"type": "boolean", "description": "Enable/disable"},
                       "triggerDescription": {"type": "string", "description": "New trigger description"}
                   }, "required": ["ruleId"]})
        async def zen_rule_update(
            ruleId: str,
            name: str = None,
            interruptionFilter: int = None,
            enabled: bool = None,
            triggerDescription: str = None
        ) -> CallToolResult:
            """Update an existing zen rule.
            Args:
                ruleId: Rule ID
                name: New name
                interruptionFilter: New filter level
                enabled: Enable/disable
                triggerDescription: New trigger description
            """
            try:
                payload = {"ruleId": ruleId}
                if name is not None:
                    payload["name"] = name
                if interruptionFilter is not None:
                    payload["interruptionFilter"] = interruptionFilter
                if enabled is not None:
                    payload["enabled"] = enabled
                if triggerDescription is not None:
                    payload["triggerDescription"] = triggerDescription
                response = await self.client.post(
                    f"{TIER1_BASE}/zen/rule/update",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error updating zen rule: {str(e)}")]
                )

        @_register("zen_rule_delete", "Delete a zen rule",
                   {"type": "object", "properties": {
                       "ruleId": {"type": "string", "description": "Rule ID"}
                   }, "required": ["ruleId"]})
        async def zen_rule_delete(ruleId: str) -> CallToolResult:
            """Delete a zen rule.
            Args:
                ruleId: Rule ID to delete
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/zen/rule",
                    params={"ruleId": ruleId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting zen rule: {str(e)}")]
                )

        @_register("zen_rule_set_state", "Set condition state of a zen rule",
                   {"type": "object", "properties": {
                       "ruleId": {"type": "string", "description": "Rule ID"},
                       "state": {"type": "integer", "description": "State: 0=false, 1=true, 2=unknown"},
                       "summary": {"type": "string", "description": "Condition summary"}
                   }, "required": ["ruleId", "state"]})
        async def zen_rule_set_state(ruleId: str, state: int, summary: str = "") -> CallToolResult:
            """Set condition state of a zen rule.
            Args:
                ruleId: Rule ID
                state: State (0=false, 1=true, 2=unknown)
                summary: Condition summary
            """
            try:
                payload = {"ruleId": ruleId, "state": state}
                if summary:
                    payload["summary"] = summary
                response = await self.client.post(
                    f"{TIER1_BASE}/zen/rule/state",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting zen rule state: {str(e)}")]
                )

        @_register("zen_policy_get", "Get consolidated zen policy",
                   {"type": "object", "properties": {}})
        async def zen_policy_get() -> CallToolResult:
            """Get consolidated zen policy."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/zen/policy")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting zen policy: {str(e)}")]
                )

        @_register("zen_device_effects_get", "Get zen device effects for a DND rule",
                   {"type": "object", "properties": {
                       "ruleId": {"type": "string", "description": "Zen rule ID"}
                   }, "required": ["ruleId"]})
        async def zen_device_effects_get(ruleId: str) -> CallToolResult:
            """Get zen device effects for a DND rule.
            Args:
                ruleId: Zen rule ID
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/zen/device_effects",
                    params={"ruleId": ruleId}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting zen device effects: {str(e)}")]
                )

        # ---- Clipboard Tools ----
        @_register("clipboard_clear", "Clear the system clipboard",
                   {"type": "object", "properties": {}})
        async def clipboard_clear() -> CallToolResult:
            """Clear the system clipboard."""
            try:
                response = await self.client.post(f"{TIER1_BASE}/clipboard/clear", json={})
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error clearing clipboard: {str(e)}")]
                )

        @_register("clipboard_has_clip", "Check if clipboard has content",
                   {"type": "object", "properties": {}})
        async def clipboard_has_clip() -> CallToolResult:
            """Check if clipboard has content."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/clipboard/has_clip")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking clipboard: {str(e)}")]
                )

        @_register("clipboard_get_rich", "Get clipboard with full type info",
                   {"type": "object", "properties": {}})
        async def clipboard_get_rich() -> CallToolResult:
            """Get clipboard with full type info."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/clipboard/rich")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting rich clipboard: {str(e)}")]
                )

        @_register("clipboard_set_html", "Set clipboard to HTML content",
                   {"type": "object", "properties": {
                       "label": {"type": "string", "description": "Clip label"},
                       "text": {"type": "string", "description": "Plain text fallback"},
                       "htmlText": {"type": "string", "description": "HTML content"}
                   }, "required": ["label", "text", "htmlText"]})
        async def clipboard_set_html(label: str, text: str, htmlText: str) -> CallToolResult:
            """Set clipboard to HTML content.
            Args:
                label: Clip label
                text: Plain text fallback
                htmlText: HTML content
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/clipboard/html",
                    json={"label": label, "text": text, "htmlText": htmlText}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting HTML clipboard: {str(e)}")]
                )

        @_register("clipboard_set_uri", "Set clipboard to a content URI",
                   {"type": "object", "properties": {
                       "label": {"type": "string", "description": "Clip label"},
                       "uri": {"type": "string", "description": "Content URI"}
                   }, "required": ["label", "uri"]})
        async def clipboard_set_uri(label: str, uri: str) -> CallToolResult:
            """Set clipboard to a content URI.
            Args:
                label: Clip label
                uri: Content URI
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/clipboard/uri",
                    json={"label": label, "uri": uri}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting URI clipboard: {str(e)}")]
                )

        # ---- SharedPreferences Tools ----
        @_register("preferences_get_all", "Get all key-value pairs from SharedPreferences",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"}
                   }, "required": ["name"]})
        async def preferences_get_all(name: str) -> CallToolResult:
            """Get all key-value pairs from SharedPreferences.
            Args:
                name: Prefs file name
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/preferences/all",
                    params={"name": name}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting preferences: {str(e)}")]
                )

        @_register("preferences_get", "Get a single preference value",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"},
                       "key": {"type": "string", "description": "Preference key"},
                       "type": {"type": "string", "description": "Type: string,int,long,float,boolean,string_set"}
                   }, "required": ["name", "key"]})
        async def preferences_get(name: str, key: str, type: str = "string") -> CallToolResult:
            """Get a single preference value.
            Args:
                name: Prefs file name
                key: Preference key
                type: Type hint (string,int,long,float,boolean,string_set)
            """
            try:
                params = {"name": name, "key": key}
                if type != "string":
                    params["type"] = type
                response = await self.client.get(
                    f"{TIER1_BASE}/preferences/get",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting preference: {str(e)}")]
                )

        @_register("preferences_set", "Set a preference value",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"},
                       "key": {"type": "string", "description": "Preference key"},
                       "value": {"type": "string", "description": "Value to set"},
                       "type": {"type": "string", "description": "Type: string,int,long,float,boolean,string_set"}
                   }, "required": ["name", "key", "value"]})
        async def preferences_set(name: str, key: str, value: str, type: str = "string") -> CallToolResult:
            """Set a preference value.
            Args:
                name: Prefs file name
                key: Preference key
                value: Value to set
                type: Type hint (string,int,long,float,boolean,string_set)
            """
            try:
                payload = {"name": name, "key": key, "value": value}
                if type != "string":
                    payload["type"] = type
                response = await self.client.post(
                    f"{TIER1_BASE}/preferences/set",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting preference: {str(e)}")]
                )

        @_register("preferences_remove", "Remove a preference key",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"},
                       "key": {"type": "string", "description": "Key to remove"}
                   }, "required": ["name", "key"]})
        async def preferences_remove(name: str, key: str) -> CallToolResult:
            """Remove a preference key.
            Args:
                name: Prefs file name
                key: Key to remove
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/preferences/remove",
                    json={"name": name, "key": key}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error removing preference: {str(e)}")]
                )

        @_register("preferences_clear", "Clear all preferences in a file",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"}
                   }, "required": ["name"]})
        async def preferences_clear(name: str) -> CallToolResult:
            """Clear all preferences in a file.
            Args:
                name: Prefs file name
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/preferences/clear",
                    json={"name": name}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error clearing preferences: {str(e)}")]
                )

        @_register("preferences_contains", "Check if a preference key exists",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"},
                       "key": {"type": "string", "description": "Key to check"}
                   }, "required": ["name", "key"]})
        async def preferences_contains(name: str, key: str) -> CallToolResult:
            """Check if a preference key exists.
            Args:
                name: Prefs file name
                key: Key to check
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/preferences/contains",
                    params={"name": name, "key": key}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking preference: {str(e)}")]
                )

        @_register("preferences_delete_file", "Delete a SharedPreferences file",
                   {"type": "object", "properties": {
                       "name": {"type": "string", "description": "Prefs file name"}
                   }, "required": ["name"]})
        async def preferences_delete_file(name: str) -> CallToolResult:
            """Delete a SharedPreferences file.
            Args:
                name: Prefs file name
            """
            try:
                response = await self.client.delete(
                    f"{TIER1_BASE}/preferences/file",
                    params={"name": name}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting preferences file: {str(e)}")]
                )

        # ---- Package Manager Tools ----
        @_register("package_info_get", "Get detailed installed package info",
                   {"type": "object", "properties": {
                       "packageName": {"type": "string", "description": "Package name"},
                       "includePermissions": {"type": "boolean", "description": "Include permissions list"}
                   }, "required": ["packageName"]})
        async def package_info_get(packageName: str, includePermissions: bool = False) -> CallToolResult:
            """Get detailed installed package info.
            Args:
                packageName: Package name
                includePermissions: Include permissions list
            """
            try:
                params = {"packageName": packageName}
                if includePermissions:
                    params["includePermissions"] = "true"
                response = await self.client.get(
                    f"{TIER1_BASE}/package/info",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting package info: {str(e)}")]
                )

        @_register("package_permissions_check", "Check if permission granted to package",
                   {"type": "object", "properties": {
                       "permission": {"type": "string", "description": "Permission name"},
                       "packageName": {"type": "string", "description": "Package (defaults to this app)"}
                   }, "required": ["permission"]})
        async def package_permissions_check(permission: str, packageName: str = "") -> CallToolResult:
            """Check if permission granted to package.
            Args:
                permission: Permission name
                packageName: Package name (defaults to this app)
            """
            try:
                params = {"permission": permission}
                if packageName:
                    params["packageName"] = packageName
                response = await self.client.get(
                    f"{TIER1_BASE}/package/permission",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking permission: {str(e)}")]
                )

        @_register("package_system_feature", "Check if device has a system feature",
                   {"type": "object", "properties": {
                       "featureName": {"type": "string", "description": "Feature name"}
                   }, "required": ["featureName"]})
        async def package_system_feature(featureName: str) -> CallToolResult:
            """Check if device has a system feature.
            Args:
                featureName: Feature name (e.g. android.hardware.camera)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/package/feature",
                    params={"featureName": featureName}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking system feature: {str(e)}")]
                )

        @_register("package_component_enabled", "Get or set enabled state of app component",
                   {"type": "object", "properties": {
                       "componentName": {"type": "string", "description": "Fully qualified component name"},
                       "newState": {"type": "integer", "description": "New state: 0=default,1=enabled,2=disabled"},
                       "flags": {"type": "integer", "description": "Flags (1=DONT_KILL_APP)"}
                   }, "required": ["componentName"]})
        async def package_component_enabled(componentName: str, newState: int = None, flags: int = None) -> CallToolResult:
            """Get or set enabled state of app component.
            Args:
                componentName: Fully qualified component name
                newState: New state (0=default,1=enabled,2=disabled)
                flags: Flags (1=DONT_KILL_APP)
            """
            try:
                payload = {"componentName": componentName}
                if newState is not None:
                    payload["newState"] = newState
                if flags is not None:
                    payload["flags"] = flags
                response = await self.client.post(
                    f"{TIER1_BASE}/package/component",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with component enabled state: {str(e)}")]
                )

        @_register("package_signatures_check", "Check if two packages share signing certs",
                   {"type": "object", "properties": {
                       "package1": {"type": "string", "description": "First package"},
                       "package2": {"type": "string", "description": "Second package"}
                   }, "required": ["package1", "package2"]})
        async def package_signatures_check(package1: str, package2: str) -> CallToolResult:
            """Check if two packages share signing certs.
            Args:
                package1: First package name
                package2: Second package name
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/package/signatures",
                    params={"package1": package1, "package2": package2}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking signatures: {str(e)}")]
                )

        # ---- Content Provider Tools ----
        @_register("content_query", "Query a content provider URI",
                   {"type": "object", "properties": {
                       "uri": {"type": "string", "description": "Content URI"},
                       "projection": {"type": "string", "description": "Comma-separated columns"},
                       "selection": {"type": "string", "description": "WHERE clause"},
                       "selectionArgs": {"type": "string", "description": "Comma-separated args for ? placeholders"},
                       "sortOrder": {"type": "string", "description": "ORDER BY clause"},
                       "limit": {"type": "integer", "description": "Max rows (default 100)"}
                   }, "required": ["uri"]})
        async def content_query(
            uri: str,
            projection: str = "",
            selection: str = "",
            selectionArgs: str = "",
            sortOrder: str = "",
            limit: int = 100
        ) -> CallToolResult:
            """Query a content provider URI.
            Args:
                uri: Content URI
                projection: Comma-separated columns
                selection: WHERE clause
                selectionArgs: Comma-separated args for ? placeholders
                sortOrder: ORDER BY clause
                limit: Max rows (default 100)
            """
            try:
                payload = {"uri": uri, "limit": limit}
                if projection:
                    payload["projection"] = projection
                if selection:
                    payload["selection"] = selection
                if selectionArgs:
                    payload["selectionArgs"] = selectionArgs
                if sortOrder:
                    payload["sortOrder"] = sortOrder
                response = await self.client.post(
                    f"{TIER1_BASE}/content/query",
                    json=payload,
                    timeout=30.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error querying content provider: {str(e)}")]
                )

        @_register("content_insert", "Insert row into content provider",
                   {"type": "object", "properties": {
                       "uri": {"type": "string", "description": "Content URI"},
                       "values": {"type": "string", "description": "JSON object of column:value pairs"}
                   }, "required": ["uri", "values"]})
        async def content_insert(uri: str, values: str) -> CallToolResult:
            """Insert row into content provider.
            Args:
                uri: Content URI
                values: JSON object of column:value pairs
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/content/insert",
                    json={"uri": uri, "values": values}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error inserting content: {str(e)}")]
                )

        @_register("content_update", "Update rows in content provider",
                   {"type": "object", "properties": {
                       "uri": {"type": "string", "description": "Content URI"},
                       "values": {"type": "string", "description": "JSON object of column:value pairs"},
                       "selection": {"type": "string", "description": "WHERE clause"},
                       "selectionArgs": {"type": "string", "description": "Args for ? placeholders"}
                   }, "required": ["uri", "values"]})
        async def content_update(uri: str, values: str, selection: str = "", selectionArgs: str = "") -> CallToolResult:
            """Update rows in content provider.
            Args:
                uri: Content URI
                values: JSON object of column:value pairs
                selection: WHERE clause
                selectionArgs: Args for ? placeholders
            """
            try:
                payload = {"uri": uri, "values": values}
                if selection:
                    payload["selection"] = selection
                if selectionArgs:
                    payload["selectionArgs"] = selectionArgs
                response = await self.client.post(
                    f"{TIER1_BASE}/content/update",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error updating content: {str(e)}")]
                )

        @_register("content_delete", "Delete rows from content provider",
                   {"type": "object", "properties": {
                       "uri": {"type": "string", "description": "Content URI"},
                       "selection": {"type": "string", "description": "WHERE clause"},
                       "selectionArgs": {"type": "string", "description": "Args for ? placeholders"}
                   }, "required": ["uri"]})
        async def content_delete(uri: str, selection: str = "", selectionArgs: str = "") -> CallToolResult:
            """Delete rows from content provider.
            Args:
                uri: Content URI
                selection: WHERE clause
                selectionArgs: Args for ? placeholders
            """
            try:
                params = {"uri": uri}
                if selection:
                    params["selection"] = selection
                if selectionArgs:
                    params["selectionArgs"] = selectionArgs
                response = await self.client.delete(
                    f"{TIER1_BASE}/content/delete",
                    params=params
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error deleting content: {str(e)}")]
                )

        # ---- Intent Tools ----
        @_register("intent_send", "Send an intent (activity, service, or broadcast)",
                   {"type": "object", "properties": {
                       "action": {"type": "string", "description": "Intent action"},
                       "data": {"type": "string", "description": "Data URI"},
                       "type": {"type": "string", "description": "MIME type"},
                       "packageName": {"type": "string", "description": "Target package"},
                       "component": {"type": "string", "description": "Explicit component"},
                       "category": {"type": "string", "description": "Intent category"},
                       "extras": {"type": "string", "description": "JSON extras"},
                       "flags": {"type": "integer", "description": "Intent flags"},
                       "target": {"type": "string", "description": "activity|service|broadcast"}
                   }, "required": ["action"]})
        async def intent_send(
            action: str,
            data: str = "",
            type: str = "",
            packageName: str = "",
            component: str = "",
            category: str = "",
            extras: str = "",
            flags: int = 0,
            target: str = "activity"
        ) -> CallToolResult:
            """Send an intent (activity, service, or broadcast).
            Args:
                action: Intent action
                data: Data URI
                type: MIME type
                packageName: Target package
                component: Explicit component
                category: Intent category
                extras: JSON extras
                flags: Intent flags
                target: activity|service|broadcast
            """
            try:
                payload = {"action": action, "target": target}
                if data:
                    payload["data"] = data
                if type:
                    payload["type"] = type
                if packageName:
                    payload["packageName"] = packageName
                if component:
                    payload["component"] = component
                if category:
                    payload["category"] = category
                if extras:
                    payload["extras"] = extras
                if flags:
                    payload["flags"] = flags
                response = await self.client.post(
                    f"{TIER1_BASE}/intent/send",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending intent: {str(e)}")]
                )

        @_register("intent_resolve", "Resolve which components handle an intent",
                   {"type": "object", "properties": {
                       "action": {"type": "string", "description": "Intent action"},
                       "data": {"type": "string", "description": "Data URI"},
                       "type": {"type": "string", "description": "MIME type"},
                       "category": {"type": "string", "description": "Intent category"},
                       "target": {"type": "string", "description": "activity|service|broadcast"}
                   }, "required": ["action"]})
        async def intent_resolve(
            action: str,
            data: str = "",
            type: str = "",
            category: str = "",
            target: str = "activity"
        ) -> CallToolResult:
            """Resolve which components handle an intent.
            Args:
                action: Intent action
                data: Data URI
                type: MIME type
                category: Intent category
                target: activity|service|broadcast
            """
            try:
                payload = {"action": action, "target": target}
                if data:
                    payload["data"] = data
                if type:
                    payload["type"] = type
                if category:
                    payload["category"] = category
                response = await self.client.post(
                    f"{TIER1_BASE}/intent/resolve",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error resolving intent: {str(e)}")]
                )

        # ---- File & Database Tools ----
        @_register("file_list_private", "List private files in app storage",
                   {"type": "object", "properties": {}})
        async def file_list_private() -> CallToolResult:
            """List private files in app storage."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/files/private")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing private files: {str(e)}")]
                )

        @_register("database_list", "List private databases in app storage",
                   {"type": "object", "properties": {}})
        async def database_list() -> CallToolResult:
            """List private databases in app storage."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/databases/list")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing databases: {str(e)}")]
                )

        # ---- Wallpaper Tools ----
        @_register("wallpaper_set", "Set device wallpaper",
                   {"type": "object", "properties": {
                       "imagePath": {"type": "string", "description": "Image file path or content URI"}
                   }, "required": ["imagePath"]})
        async def wallpaper_set(imagePath: str) -> CallToolResult:
            """Set device wallpaper.
            Args:
                imagePath: Image file path or content URI
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/wallpaper/set",
                    json={"imagePath": imagePath}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting wallpaper: {str(e)}")]
                )

        @_register("wallpaper_clear", "Reset wallpaper to default",
                   {"type": "object", "properties": {}})
        async def wallpaper_clear() -> CallToolResult:
            """Reset wallpaper to default."""
            try:
                response = await self.client.post(f"{TIER1_BASE}/wallpaper/clear", json={})
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error clearing wallpaper: {str(e)}")]
                )

        # ---- Sync Adapter Tools ----
        @_register("sync_adapters_list", "List installed sync adapter types",
                   {"type": "object", "properties": {}})
        async def sync_adapters_list() -> CallToolResult:
            """List installed sync adapter types."""
            try:
                response = await self.client.get(f"{TIER1_BASE}/sync/adapters")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing sync adapters: {str(e)}")]
                )

        @_register("sync_request", "Request sync for account and authority",
                   {"type": "object", "properties": {
                       "accountName": {"type": "string", "description": "Account name"},
                       "accountType": {"type": "string", "description": "Account type"},
                       "authority": {"type": "string", "description": "Content authority"},
                       "extras": {"type": "string", "description": "JSON sync extras"}
                   }, "required": ["accountName", "accountType", "authority"]})
        async def sync_request(accountName: str, accountType: str, authority: str, extras: str = "") -> CallToolResult:
            """Request sync for account and authority.
            Args:
                accountName: Account name
                accountType: Account type
                authority: Content authority
                extras: JSON sync extras
            """
            try:
                payload = {
                    "accountName": accountName,
                    "accountType": accountType,
                    "authority": authority
                }
                if extras:
                    payload["extras"] = extras
                response = await self.client.post(
                    f"{TIER1_BASE}/sync/request",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error requesting sync: {str(e)}")]
                )

        # ---- URI Permission Tools ----
        @_register("uri_permission_grant", "Grant URI permission to package",
                   {"type": "object", "properties": {
                       "packageName": {"type": "string", "description": "Target package"},
                       "uri": {"type": "string", "description": "Content URI"},
                       "modeFlags": {"type": "integer", "description": "1=read, 2=write, 3=both"}
                   }, "required": ["packageName", "uri", "modeFlags"]})
        async def uri_permission_grant(packageName: str, uri: str, modeFlags: int) -> CallToolResult:
            """Grant URI permission to package.
            Args:
                packageName: Target package
                uri: Content URI
                modeFlags: 1=read, 2=write, 3=both
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/permission/uri/grant",
                    json={"packageName": packageName, "uri": uri, "modeFlags": modeFlags}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error granting URI permission: {str(e)}")]
                )

        @_register("uri_permission_revoke", "Revoke URI permission",
                   {"type": "object", "properties": {
                       "uri": {"type": "string", "description": "Content URI"},
                       "modeFlags": {"type": "integer", "description": "1=read, 2=write, 3=both"}
                   }, "required": ["uri", "modeFlags"]})
        async def uri_permission_revoke(uri: str, modeFlags: int) -> CallToolResult:
            """Revoke URI permission.
            Args:
                uri: Content URI
                modeFlags: 1=read, 2=write, 3=both
            """
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/permission/uri/revoke",
                    json={"uri": uri, "modeFlags": modeFlags}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error revoking URI permission: {str(e)}")]
                )

        @_register("permission_check_self", "Check if this app holds a permission",
                   {"type": "object", "properties": {
                       "permission": {"type": "string", "description": "Permission name"}
                   }, "required": ["permission"]})
        async def permission_check_self(permission: str) -> CallToolResult:
            """Check if this app holds a permission.
            Args:
                permission: Permission name (e.g. android.permission.CAMERA)
            """
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/permission/self",
                    params={"permission": permission}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking self permission: {str(e)}")]
                )

        # ====================================================================
        # Settings Launcher Tools (Batch 1)
        # ====================================================================
        @_register("device_settings_open", "Open an Android settings screen by name (e.g. wifi, bluetooth, display, sound). Use device_settings_list to see all valid screen names.", {
            "type": "object",
            "properties": {
                "screen": {
                    "type": "string",
                    "description": "Settings screen name (e.g. wifi, bluetooth, display, sound, location, security, nfc, vpn, accessibility, notification, manage_apps, development, battery_saver, default_apps, device_info). Use device_settings_list to see all valid names."
                }
            },
            "required": ["screen"]
        })
        async def device_settings_open(screen: str) -> CallToolResult:
            """Open an Android settings screen by friendly name"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/settings/open",
                    json={"screen": screen}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error opening settings screen: {str(e)}")]
                )

        @_register("device_settings_list", "List all available Android settings screen names that can be opened with device_settings_open", {"type": "object","properties": {},"required": []})
        async def device_settings_list() -> CallToolResult:
            """List all available settings screen names"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/settings/list")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing settings screens: {str(e)}")]
                )

        # ====================================================================
        # Volume Stream Extension Tools (Batch 2)
        # ====================================================================
        @_register("device_volume_get_stream", "Get volume for a specific audio stream", {"type": "object","properties": {"stream": {"type": "string","description": "Audio stream: music, ring, notification, alarm, system, call, dtmf, accessibility","enum": ["music","ring","notification","alarm","system","call","dtmf","accessibility"]}},"required": ["stream"]})
        async def device_volume_get_stream(stream: str) -> CallToolResult:
            """Get volume for a specific audio stream"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/volume/get_stream",
                    params={"stream": stream}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting stream volume: {str(e)}")]
                )

        @_register("device_volume_get_all", "Get volume levels for ALL audio streams (music, ring, notification, alarm, system, call, dtmf, accessibility) plus ringer mode", {"type": "object","properties": {},"required": []})
        async def device_volume_get_all() -> CallToolResult:
            """Get volume levels for ALL audio streams plus ringer mode"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/volume/get_all")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting all volumes: {str(e)}")]
                )

        @_register("device_volume_set_stream", "Set volume for a specific audio stream with optional flags", {"type": "object","properties": {"stream": {"type": "string","description": "Audio stream: music, ring, notification, alarm, system, call, dtmf, accessibility","enum": ["music","ring","notification","alarm","system","call","dtmf","accessibility"]},"level": {"type": "integer","description": "Volume level (0 to stream max)"},"flags": {"type": "integer","description": "Optional flags: 0=silent, 1=show UI, 4=play sound (default 0)"}},"required": ["stream","level"]})
        async def device_volume_set_stream(stream: str, level: int, flags: int = 0) -> CallToolResult:
            """Set volume for a specific audio stream with optional flags"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/volume/set_stream",
                    json={"stream": stream, "level": level, "flags": flags}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting stream volume: {str(e)}")]
                )

        @_register("device_volume_adjust", "Adjust volume up/down/same for a specific audio stream", {"type": "object","properties": {"stream": {"type": "string","description": "Audio stream: music, ring, notification, alarm, system, call, dtmf, accessibility","enum": ["music","ring","notification","alarm","system","call","dtmf","accessibility"]},"direction": {"type": "string","description": "Adjustment direction","enum": ["raise","lower","same"]},"flags": {"type": "integer","description": "Optional flags: 0=silent, 1=show UI, 4=play sound (default 0)"}},"required": ["stream","direction"]})
        async def device_volume_adjust(stream: str, direction: str, flags: int = 0) -> CallToolResult:
            """Adjust volume up/down/same for a specific audio stream"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/volume/adjust",
                    json={"stream": stream, "direction": direction, "flags": flags}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error adjusting volume: {str(e)}")]
                )

        # ====================================================================
        # Audio Mode Control Tools (Batch 3)
        # ====================================================================
        @_register("device_audio_ringer_mode_set", "Set the device ringer mode (normal, silent, vibrate)", {"type": "object","properties": {"mode": {"type": "string","description": "Ringer mode","enum": ["normal","silent","vibrate"]}},"required": ["mode"]})
        async def device_audio_ringer_mode_set(mode: str) -> CallToolResult:
            """Set the device ringer mode"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/audio/ringer_mode",
                    json={"mode": mode}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting ringer mode: {str(e)}")]
                )

        @_register("device_audio_ringer_mode_get", "Get the current device ringer mode", {"type": "object","properties": {},"required": []})
        async def device_audio_ringer_mode_get() -> CallToolResult:
            """Get the current device ringer mode (normal, silent, vibrate)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/audio/ringer_mode")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting ringer mode: {str(e)}")]
                )

        @_register("device_audio_mic_mute_set", "Mute or unmute the device microphone", {"type": "object","properties": {"muted": {"type": "boolean","description": "true to mute, false to unmute"}},"required": ["muted"]})
        async def device_audio_mic_mute_set(muted: bool) -> CallToolResult:
            """Mute or unmute the device microphone"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/audio/mic_mute",
                    json={"muted": muted}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting mic mute: {str(e)}")]
                )

        @_register("device_audio_mic_mute_get", "Get the current microphone mute state", {"type": "object","properties": {},"required": []})
        async def device_audio_mic_mute_get() -> CallToolResult:
            """Get the current microphone mute state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/audio/mic_mute")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting mic mute state: {str(e)}")]
                )

        @_register("device_audio_speakerphone_set", "Enable or disable speakerphone", {"type": "object","properties": {"enabled": {"type": "boolean","description": "true to enable speakerphone, false to disable"}},"required": ["enabled"]})
        async def device_audio_speakerphone_set(enabled: bool) -> CallToolResult:
            """Enable or disable speakerphone"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/audio/speakerphone",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting speakerphone: {str(e)}")]
                )

        @_register("device_audio_speakerphone_get", "Get the current speakerphone state", {"type": "object","properties": {},"required": []})
        async def device_audio_speakerphone_get() -> CallToolResult:
            """Get the current speakerphone state (on/off)"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/audio/speakerphone")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting speakerphone state: {str(e)}")]
                )

        @_register("device_audio_mode_get", "Get the current audio mode and comprehensive audio state", {"type": "object","properties": {},"required": []})
        async def device_audio_mode_get() -> CallToolResult:
            """Get the current audio mode and audio state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/audio/mode")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting audio mode: {str(e)}")]
                )

        # ====================================================================
        # Display Settings Tools (Batch 4)
        # ====================================================================
        @_register("get_screen_timeout", "Get the current screen timeout (auto-off) duration in milliseconds", {"type": "object","properties": {},"required": []})
        async def get_screen_timeout() -> CallToolResult:
            """Get the current screen timeout duration"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/display/timeout")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting screen timeout: {str(e)}")]
                )

        @_register("set_screen_timeout", "Set the screen timeout (auto-off) duration. Common values: 15000 (15s), 30000 (30s), 60000 (1min), 120000 (2min), 300000 (5min), 600000 (10min), 1800000 (30min)", {"type": "object","properties": {"timeout_ms": {"type": "integer","description": "Timeout in milliseconds (minimum 1000)"}},"required": ["timeout_ms"]})
        async def set_screen_timeout(timeout_ms: int = 30000) -> CallToolResult:
            """Set the screen timeout duration"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/display/timeout",
                    json={"timeout_ms": timeout_ms}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting screen timeout: {str(e)}")]
                )

        @_register("get_auto_brightness", "Get whether automatic brightness adjustment is enabled or disabled", {"type": "object","properties": {},"required": []})
        async def get_auto_brightness() -> CallToolResult:
            """Get auto brightness state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/display/auto_brightness")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting auto brightness: {str(e)}")]
                )

        @_register("set_auto_brightness", "Enable or disable automatic brightness adjustment", {"type": "object","properties": {"enabled": {"type": "boolean","description": "true to enable auto brightness, false to disable"}},"required": ["enabled"]})
        async def set_auto_brightness(enabled: bool = True) -> CallToolResult:
            """Enable or disable auto brightness"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/display/auto_brightness",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting auto brightness: {str(e)}")]
                )

        @_register("get_auto_rotate", "Get whether automatic screen rotation is enabled or disabled", {"type": "object","properties": {},"required": []})
        async def get_auto_rotate() -> CallToolResult:
            """Get auto rotate state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/display/auto_rotate")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting auto rotate: {str(e)}")]
                )

        @_register("set_auto_rotate", "Enable or disable automatic screen rotation", {"type": "object","properties": {"enabled": {"type": "boolean","description": "true to enable auto rotate, false to disable (locks current orientation)"}},"required": ["enabled"]})
        async def set_auto_rotate(enabled: bool = True) -> CallToolResult:
            """Enable or disable auto rotate"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/display/auto_rotate",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting auto rotate: {str(e)}")]
                )

        @_register("get_stay_on", "Get the screen stay-on mode (whether screen stays on while charging)", {"type": "object","properties": {},"required": []})
        async def get_stay_on() -> CallToolResult:
            """Get screen stay-on mode"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/display/stay_on")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting stay-on mode: {str(e)}")]
                )

        @_register("set_stay_on", "Set screen stay-on behavior while charging. Mode: 0=never stay on, 1=AC charger only, 2=USB only, 3=AC+USB, 7=all power sources", {"type": "object","properties": {"mode": {"type": "integer","description": "Stay-on mode: 0=never, 1=AC, 2=USB, 3=AC+USB, 7=all"}},"required": ["mode"]})
        async def set_stay_on(mode: int = 0) -> CallToolResult:
            """Set screen stay-on behavior"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/display/stay_on",
                    json={"mode": mode}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting stay-on mode: {str(e)}")]
                )

        @_register("get_font_scale", "Get the current system font scale factor (1.0 = default, >1.0 = larger, <1.0 = smaller)", {"type": "object","properties": {},"required": []})
        async def get_font_scale() -> CallToolResult:
            """Get current font scale"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/display/font_scale")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting font scale: {str(e)}")]
                )

        @_register("set_font_scale", "Set the system font scale factor. 0.85=small, 1.0=default, 1.15=large, 1.3=largest. Range: 0.5 to 3.0", {"type": "object","properties": {"scale": {"type": "number","description": "Font scale factor (0.5 to 3.0, default is 1.0)"}},"required": ["scale"]})
        async def set_font_scale(scale: float = 1.0) -> CallToolResult:
            """Set system font scale"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/display/font_scale",
                    json={"scale": scale}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting font scale: {str(e)}")]
                )

        # ====================================================================
        # Call Management Tools (Batch 5)
        # ====================================================================
        @_register("make_call", "Make a phone call to the specified number. Uses Intent.ACTION_CALL which dials immediately", {"type": "object","properties": {"number": {"type": "string","description": "Phone number to call (e.g. '+1234567890', '555-1234')"}},"required": ["number"]})
        async def make_call(number: str) -> CallToolResult:
            """Make a phone call"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/call/make",
                    json={"number": number}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error making call: {str(e)}")]
                )

        @_register("end_call", "End the current active phone call. Requires API 28+ (Android 9 Pie)", {"type": "object","properties": {},"required": []})
        async def end_call() -> CallToolResult:
            """End the current active phone call"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/call/end")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error ending call: {str(e)}")]
                )

        @_register("answer_call", "Answer an incoming ringing phone call. Requires API 26+ (Android 8 Oreo)", {"type": "object","properties": {},"required": []})
        async def answer_call() -> CallToolResult:
            """Answer an incoming ringing phone call"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/call/answer")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error answering call: {str(e)}")]
                )

        @_register("silence_ringer", "Silence the ringer for an incoming call without rejecting it. The call continues to ring silently", {"type": "object","properties": {},"required": []})
        async def silence_ringer() -> CallToolResult:
            """Silence the ringer for an incoming call"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/call/silence")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error silencing ringer: {str(e)}")]
                )

        @_register("get_call_state", "Get the current phone call state: idle (no call), ringing (incoming), or offhook (active call in progress)", {"type": "object","properties": {},"required": []})
        async def get_call_state() -> CallToolResult:
            """Get the current phone call state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/call/state")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting call state: {str(e)}")]
                )

        @_register("reject_call", "Reject an incoming ringing call. Optionally send an SMS reply to the caller", {"type": "object","properties": {"sms_text": {"type": "string","description": "Optional SMS text to send to the caller after rejecting"}}})
        async def reject_call(sms_text: str = None) -> CallToolResult:
            """Reject an incoming ringing call"""
            try:
                payload = {}
                if sms_text:
                    payload["sms_text"] = sms_text
                response = await self.client.post(
                    f"{TIER1_BASE}/call/reject",
                    json=payload
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error rejecting call: {str(e)}")]
                )

        @_register("set_speakerphone", "Toggle speakerphone on or off during an active call", {"type": "object","properties": {"enabled": {"type": "boolean","description": "true to turn speakerphone on, false to turn off"}},"required": ["enabled"]})
        async def set_speakerphone(enabled: bool = True) -> CallToolResult:
            """Toggle speakerphone during a call"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/call/speaker",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting speakerphone: {str(e)}")]
                )

        @_register("hold_call", "Toggle hold state for the current call", {"type": "object","properties": {"hold": {"type": "boolean","description": "true to place call on hold, false to resume (default: true)"}}})
        async def hold_call(hold: bool = True) -> CallToolResult:
            """Toggle hold state for the current call"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/call/hold",
                    json={"hold": hold}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error holding call: {str(e)}")]
                )

        @_register("send_dtmf", "Send a DTMF tone during an active phone call (touch-tone digit). Also plays the tone audibly", {"type": "object","properties": {"digit": {"type": "string","description": "Single DTMF digit to send: 0-9, *, or #"}},"required": ["digit"]})
        async def send_dtmf(digit: str) -> CallToolResult:
            """Send a DTMF tone during an active call"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/call/dtmf",
                    json={"digit": digit}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error sending DTMF: {str(e)}")]
                )

        # ====================================================================
        # TTS Extension Tools (Batch 6)
        # ====================================================================
        @_register("tts_speak", "Speak text aloud with full control over locale, pitch, speed, audio stream, and queue mode", {"type": "object","properties": {"text": {"type": "string","description": "Text to speak aloud"},"locale": {"type": "string","description": "Locale/language tag (e.g. 'en-US', 'es-ES', 'ja-JP')"},"pitch": {"type": "number","description": "Speech pitch multiplier (0.1 to 4.0, default 1.0)"},"speed": {"type": "number","description": "Speech speed/rate multiplier (0.1 to 4.0, default 1.0)"},"stream": {"type": "string","description": "Audio stream to use for playback","enum": ["music","notification","alarm","ring","system","voice_call"]},"queue": {"type": "string","description": "Queue mode: 'flush' replaces current speech, 'add' queues after current","enum": ["flush","add"]}},"required": ["text"]})
        async def tts_speak(text: str, locale: str = "en-US", pitch: float = 1.0, speed: float = 1.0, stream: str = "music", queue: str = "flush") -> CallToolResult:
            """Speak text aloud with full control"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tts/speak",
                    json={"text": text, "locale": locale, "pitch": pitch, "speed": speed, "stream": stream, "queue": queue},
                    timeout=30.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with TTS speak: {str(e)}")]
                )

        @_register("tts_stop", "Stop all ongoing TTS (text-to-speech) playback immediately", {"type": "object","properties": {},"required": []})
        async def tts_stop() -> CallToolResult:
            """Stop all ongoing TTS playback"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/tts/stop")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error stopping TTS: {str(e)}")]
                )

        @_register("tts_engines", "List all available TTS engines installed on the device and the currently active default engine", {"type": "object","properties": {},"required": []})
        async def tts_engines() -> CallToolResult:
            """List available TTS engines"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/tts/engines")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing TTS engines: {str(e)}")]
                )

        @_register("tts_set_engine", "Switch the active TTS engine to a different installed engine (e.g. 'com.google.android.tts')", {"type": "object","properties": {"engine": {"type": "string","description": "Package name of the TTS engine (e.g. 'com.google.android.tts', 'com.samsung.SMT')"}},"required": ["engine"]})
        async def tts_set_engine(engine: str) -> CallToolResult:
            """Switch the active TTS engine"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tts/engine",
                    json={"engine": engine}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error setting TTS engine: {str(e)}")]
                )

        @_register("tts_voices", "List all available voices for the current TTS engine, including locale, quality, and network requirements", {"type": "object","properties": {},"required": []})
        async def tts_voices() -> CallToolResult:
            """List available TTS voices"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/tts/voices")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing TTS voices: {str(e)}")]
                )

        @_register("tts_synthesize", "Synthesize speech from text and save it as an audio file (WAV) on the device", {"type": "object","properties": {"text": {"type": "string","description": "Text to synthesize into audio"},"output_path": {"type": "string","description": "Absolute path on device to save the audio file (e.g. '/data/local/tmp/speech.wav')"},"locale": {"type": "string","description": "Locale/language tag for synthesis (e.g. 'en-US')"},"pitch": {"type": "number","description": "Speech pitch multiplier (0.1 to 4.0)"},"speed": {"type": "number","description": "Speech speed multiplier (0.1 to 4.0)"}},"required": ["text","output_path"]})
        async def tts_synthesize(text: str, output_path: str, locale: str = "en-US", pitch: float = 1.0, speed: float = 1.0) -> CallToolResult:
            """Synthesize speech to audio file"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/tts/synthesize",
                    json={"text": text, "output_path": output_path, "locale": locale, "pitch": pitch, "speed": speed},
                    timeout=60.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error synthesizing speech: {str(e)}")]
                )

        # ====================================================================
        # Connectivity Toggle Tools (Batch 7)
        # ====================================================================
        @_register("connectivity_wifi_toggle", "Toggle WiFi on or off. On Android 10+ may require shell access or will open Settings panel.", {"type": "object","properties": {"enabled": {"type": "boolean","description": "True to enable WiFi, False to disable"}},"required": ["enabled"]})
        async def connectivity_wifi_toggle(enabled: bool) -> CallToolResult:
            """Toggle WiFi on or off"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/connectivity/wifi",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error toggling WiFi: {str(e)}")]
                )

        @_register("connectivity_wifi_state", "Get current WiFi enabled/disabled state (compact -- use device_wifi_info for full details)", {"type": "object","properties": {},"required": []})
        async def connectivity_wifi_state() -> CallToolResult:
            """Get WiFi enabled state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/wifi")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting WiFi state: {str(e)}")]
                )

        @_register("connectivity_bluetooth_toggle", "Toggle Bluetooth on or off. On Android 13+ may require shell access or will open Settings panel.", {"type": "object","properties": {"enabled": {"type": "boolean","description": "True to enable Bluetooth, False to disable"}},"required": ["enabled"]})
        async def connectivity_bluetooth_toggle(enabled: bool) -> CallToolResult:
            """Toggle Bluetooth on or off"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/connectivity/bluetooth",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error toggling Bluetooth: {str(e)}")]
                )

        @_register("connectivity_bluetooth_state", "Get current Bluetooth enabled/disabled state with connected audio profiles (compact -- use get_bluetooth_info for paired devices)", {"type": "object","properties": {},"required": []})
        async def connectivity_bluetooth_state() -> CallToolResult:
            """Get Bluetooth enabled state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/bluetooth")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting Bluetooth state: {str(e)}")]
                )

        @_register("connectivity_airplane_toggle", "Toggle airplane mode on or off. Requires WRITE_SECURE_SETTINGS permission (grant via adb).", {"type": "object","properties": {"enabled": {"type": "boolean","description": "True to enable airplane mode, False to disable"}},"required": ["enabled"]})
        async def connectivity_airplane_toggle(enabled: bool) -> CallToolResult:
            """Toggle airplane mode"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/connectivity/airplane",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error toggling airplane mode: {str(e)}")]
                )

        @_register("connectivity_airplane_state", "Get current airplane mode state", {"type": "object","properties": {},"required": []})
        async def connectivity_airplane_state() -> CallToolResult:
            """Get airplane mode state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/airplane")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting airplane mode state: {str(e)}")]
                )

        @_register("connectivity_mobile_data", "Get mobile data state including network type (LTE/5G), SIM state, data connection status, and carrier name", {"type": "object","properties": {},"required": []})
        async def connectivity_mobile_data() -> CallToolResult:
            """Get mobile data state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/mobile_data")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting mobile data state: {str(e)}")]
                )

        @_register("connectivity_nfc_toggle", "Toggle NFC on or off. NFC cannot be toggled programmatically -- opens NFC Settings screen as fallback.", {"type": "object","properties": {"enabled": {"type": "boolean","description": "True to enable NFC, False to disable"}},"required": ["enabled"]})
        async def connectivity_nfc_toggle(enabled: bool) -> CallToolResult:
            """Toggle NFC"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/connectivity/nfc",
                    json={"enabled": enabled}
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error toggling NFC: {str(e)}")]
                )

        @_register("connectivity_nfc_state", "Get current NFC availability and enabled state", {"type": "object","properties": {},"required": []})
        async def connectivity_nfc_state() -> CallToolResult:
            """Get NFC state"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/nfc")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting NFC state: {str(e)}")]
                )

        @_register("connectivity_all", "Get all connectivity states at once: WiFi, Bluetooth, airplane mode, mobile data, NFC, and active network transport", {"type": "object","properties": {},"required": []})
        async def connectivity_all() -> CallToolResult:
            """Get all connectivity states"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/connectivity/all")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting connectivity states: {str(e)}")]
                )

        # ====================================================================
        # Media Playback Extension Tools (Batch 8)
        # ====================================================================
        @_register("media_play_pause", "Toggle media play/pause -- pauses if playing, plays if paused. Uses MediaSession or AudioManager key event fallback.", {"type": "object","properties": {},"required": []})
        async def media_play_pause() -> CallToolResult:
            """Toggle media play/pause"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/play_pause")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error toggling play/pause: {str(e)}")]
                )

        @_register("media_next", "Skip to the next media track in the active media session", {"type": "object","properties": {},"required": []})
        async def media_next() -> CallToolResult:
            """Skip to next track"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/next")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error skipping to next: {str(e)}")]
                )

        @_register("media_previous", "Go to the previous media track in the active media session", {"type": "object","properties": {},"required": []})
        async def media_previous() -> CallToolResult:
            """Go to previous track"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/previous")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error going to previous: {str(e)}")]
                )

        @_register("media_stop", "Stop media playback completely in the active media session", {"type": "object","properties": {},"required": []})
        async def media_stop() -> CallToolResult:
            """Stop media playback"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/stop")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error stopping media: {str(e)}")]
                )

        @_register("media_fast_forward", "Fast forward in the active media session", {"type": "object","properties": {},"required": []})
        async def media_fast_forward() -> CallToolResult:
            """Fast forward media"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/fast_forward")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error fast forwarding: {str(e)}")]
                )

        @_register("media_rewind", "Rewind in the active media session", {"type": "object","properties": {},"required": []})
        async def media_rewind() -> CallToolResult:
            """Rewind media"""
            try:
                response = await self.client.post(f"{TIER1_BASE}/media/rewind")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error rewinding: {str(e)}")]
                )

        @_register("media_now_playing", "Get enhanced now-playing info for ALL active media sessions -- includes metadata (title, artist, album, genre, track/disc number, year), playback state, position, speed, and available transport actions", {"type": "object","properties": {},"required": []})
        async def media_now_playing() -> CallToolResult:
            """Get enhanced now-playing info"""
            try:
                response = await self.client.get(f"{TIER1_BASE}/media/now_playing")
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error getting now playing: {str(e)}")]
                )

        @_register("media_play_file", "Play a local audio file on the device using Android's MediaPlayer", {"type": "object","properties": {"path": {"type": "string","description": "Absolute path to the audio file on the device (e.g. '/sdcard/Music/song.mp3')"},"loop": {"type": "boolean","description": "Whether to loop the audio file (default false)"}},"required": ["path"]})
        async def media_play_file(path: str, loop: bool = False) -> CallToolResult:
            """Play a local audio file"""
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/media/play_file",
                    json={"path": path, "loop": loop},
                    timeout=15.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error playing file: {str(e)}")]
                )

        # ====================================================================
        # LuxTTS (PC-based TTS Server) Tools (Batch 9)
        # ====================================================================
        @_register("luxtts_speak", "Speak text using LuxTTS PC TTS server (high-quality, 17 voices). Returns audio data. Minimum 20 characters.", {"type": "object", "properties": {"text": {"type": "string", "description": "Text to speak (minimum 20 characters)"}, "voice": {"type": "string", "description": "Voice ID (default: af_heart). Options: bf_eleanor, bf_jane, bf_kelly, bf_penny, bm_charles, bm_crofty, bm_james, bm_russell, af_cinnamon, af_erin, af_jessica, af_riley, af_susanna, am_brawny, am_guy, am_hank, af_heart"}, "speed": {"type": "number", "description": "Speech speed multiplier (default: 1.0)"}}, "required": ["text"]})
        async def luxtts_speak(text: str, voice: str = "af_heart", speed: float = 1.0) -> CallToolResult:
            """Speak text using LuxTTS PC TTS server"""
            if len(text) < 20:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error: Text too short ({len(text)} chars). Minimum 20 characters required.")]
                )
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/luxtts/speak",
                    json={"text": text, "voice": voice, "speed": speed},
                    timeout=120.0
                )
                if response.status_code == 200:
                    content_type = response.headers.get("content-type", "")
                    if "audio" in content_type:
                        return CallToolResult(
                            content=[TextContent(type="text", text=json.dumps({
                                "success": True,
                                "message": f"LuxTTS speaking with voice '{voice}' at speed {speed}",
                                "audio_size_bytes": len(response.content),
                                "content_type": content_type,
                                "text_length": len(text)
                            }, indent=2))]
                        )
                    else:
                        return CallToolResult(
                            content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                        )
                else:
                    error_text = response.text
                    return CallToolResult(
                        content=[TextContent(type="text", text=f"LuxTTS speak error (HTTP {response.status_code}): {error_text}")]
                    )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error calling LuxTTS speak: {str(e)}")]
                )

        @_register("luxtts_voices", "List available voices on the LuxTTS PC TTS server", {"type": "object", "properties": {}})
        async def luxtts_voices() -> CallToolResult:
            """List available LuxTTS voices"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/luxtts/voices",
                    timeout=10.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error listing LuxTTS voices: {str(e)}")]
                )

        @_register("luxtts_health", "Check health status of the LuxTTS PC TTS server", {"type": "object", "properties": {}})
        async def luxtts_health() -> CallToolResult:
            """Check LuxTTS server health status"""
            try:
                response = await self.client.get(
                    f"{TIER1_BASE}/luxtts/health",
                    timeout=10.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error checking LuxTTS health: {str(e)}")]
                )

        @_register("luxtts_speak_stream", "Speak text via LuxTTS WebSocket streaming (external WSS). Audio plays on device via ExoPlayer.", {"type": "object", "properties": {"text": {"type": "string", "description": "Text to speak (minimum 20 characters)"}, "voice": {"type": "string", "description": "Voice ID (default: af_heart)"}, "speed": {"type": "number", "description": "Speech speed multiplier (default: 1.0)"}}, "required": ["text"]})
        async def luxtts_speak_stream(text: str, voice: str = "af_heart", speed: float = 1.0) -> CallToolResult:
            """Speak text via LuxTTS WebSocket streaming"""
            if len(text) < 20:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error: Text too short ({len(text)} chars). Minimum 20 characters required.")]
                )
            try:
                response = await self.client.post(
                    f"{TIER1_BASE}/luxtts/speak_stream",
                    json={"text": text, "voice": voice, "speed": speed},
                    timeout=120.0
                )
                return CallToolResult(
                    content=[TextContent(type="text", text=json.dumps(response.json(), indent=2))]
                )
            except Exception as e:
                return CallToolResult(
                    content=[TextContent(type="text", text=f"Error with LuxTTS streaming: {str(e)}")]
                )

        @self.server.list_tools()
        async def handle_list_tools() -> list[Tool]:
            return [entry["tool"] for entry in self._tools.values()]

        @self.server.call_tool()
        async def handle_call_tool(name: str, arguments: dict) -> list[TextContent]:
            entry = self._tools.get(name)
            if not entry:
                raise ValueError(f"Unknown tool: {name}")
            try:
                result = await entry["func"](**arguments)
                # result is a CallToolResult, extract content
                return result.content
            except Exception as e:
                logger.error(f"Tool {name} failed: {e}")
                return [TextContent(type="text", text=f"Error in {name}: {str(e)}")]

    async def run(self):
        """Run the MCP server"""
        from mcp.server.stdio import stdio_server

        async with stdio_server() as (read_stream, write_stream):
            logger.info("Unified Device MCP Server started with %d tools", len(self._tools))
            await self.server.run(read_stream, write_stream, self.server.create_initialization_options())

    async def cleanup(self):
        """Clean up resources"""
        await self.client.aclose()

async def main():
    """Main entry point"""
    mcp = UnifiedDeviceMCP()
    try:
        await mcp.run()
    finally:
        await mcp.cleanup()

if __name__ == "__main__":
    _register_mcp_identity()
    asyncio.run(main())
