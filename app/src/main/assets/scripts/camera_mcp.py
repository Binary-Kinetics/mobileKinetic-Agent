from mcp.server import Server
from mcp.types import Tool, TextContent
import asyncio
import json
from datetime import datetime

server = Server("camera-server")

# Register handlers
@server.list_tools()
async def list_tools():
    return [
        Tool(
            name="take_photo",
            description="Take a photo using device camera",
            inputSchema={
                "type": "object",
                "properties": {
                    "camera_id": {"type": "string", "default": "0"},
                }
            }
        )
    ]

@server.call_tool()
async def call_tool(name: str, arguments: dict):
    if name == "take_photo":
        # Simulate photo capture
        timestamp = datetime.now().isoformat()
        path = f"/storage/emulated/0/DCIM/photo_{timestamp}.jpg"
        return TextContent(
            type="text",
            text=json.dumps({"success": True, "path": path, "timestamp": timestamp})
        )

async def main():
    async with server:
        await server.run()

if __name__ == "__main__":
    asyncio.run(main())
