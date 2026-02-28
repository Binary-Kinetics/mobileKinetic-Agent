#!/bin/bash
# Start Unified Device MCP Server

echo "Starting Unified Device MCP Server..."

# Kill any existing instance
pkill -f unified_device_mcp.py 2>/dev/null

# Start the server in background
nohup python3 ~/unified_device_mcp.py > ~/unified_mcp.log 2>&1 &

echo "MCP Server started with PID: $!"
echo "Logs: ~/unified_mcp.log"

# Give it a moment to start
sleep 2

# Check if it's running
if pgrep -f unified_device_mcp.py > /dev/null; then
    echo "Unified Device MCP Server is running"
else
    echo "Failed to start MCP Server"
    echo "Check logs: tail -f ~/unified_mcp.log"
    exit 1
fi
