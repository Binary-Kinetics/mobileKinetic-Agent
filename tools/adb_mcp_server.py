"""
ADB MCP Server - FastAPI server for Android Debug Bridge operations
Port: 5565
Host: 127.0.0.1 (localhost only)

Provides REST API endpoints for ADB device management, shell commands,
file operations, and Termux-specific operations.
"""

import subprocess
import os
import sys
import tempfile
from datetime import datetime
from typing import Optional, List, Dict, Any
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import uvicorn


# ============================================================================
# CONFIGURATION
# ============================================================================

ADB_PATH = r"A:\Android-Studio-SDK\platform-tools\adb.exe"
DEVICE_SERIAL: Optional[str] = None  # None = auto-detect single device
HOST = "127.0.0.1"
PORT = 6473  # 55xx range reserved for on-device servers


# ============================================================================
# FASTAPI APP
# ============================================================================

app = FastAPI(
    title="ADB MCP Server",
    description="Model Context Protocol server for Android Debug Bridge operations",
    version="1.0.0"
)

# Enable CORS for browser testing
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============================================================================
# PYDANTIC MODELS
# ============================================================================

class ShellRequest(BaseModel):
    command: str
    timeout: int = 30


class TermuxShellRequest(BaseModel):
    command: str
    timeout: int = 60


class PushRequest(BaseModel):
    local_path: str
    remote_path: str


class PullRequest(BaseModel):
    remote_path: str
    local_path: str


class InstallRequest(BaseModel):
    apk_path: str


class ForwardRequest(BaseModel):
    local_port: int
    remote_port: int


class ForwardRemoveRequest(BaseModel):
    local_port: int


class LogcatRequest(BaseModel):
    filter: str = "*:*"
    lines: int = 100
    timeout: int = 10


class ScreencapRequest(BaseModel):
    save_to: Optional[str] = None


class GrantRequest(BaseModel):
    package: str
    permission: str


class GrantBatchRequest(BaseModel):
    package: str
    permissions: List[str]


class DeviceSelectRequest(BaseModel):
    serial: Optional[str] = None


class Device(BaseModel):
    serial: str
    state: str
    model: Optional[str] = None
    product: Optional[str] = None


# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

def run_adb_command(args: List[str], timeout: int = 30, capture_binary: bool = False) -> Dict[str, Any]:
    """
    Run an ADB command with the given arguments.

    Args:
        args: List of command arguments (without 'adb')
        timeout: Command timeout in seconds
        capture_binary: If True, capture stdout as bytes instead of text

    Returns:
        Dict with stdout, stderr, exit_code, and duration_ms
    """
    start_time = datetime.now()

    # Build command with device serial if set
    cmd = [ADB_PATH]
    if DEVICE_SERIAL:
        cmd.extend(["-s", DEVICE_SERIAL])
    cmd.extend(args)

    try:
        if capture_binary:
            result = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout
            )
            stdout = result.stdout  # Keep as bytes
            stderr = result.stderr.decode('utf-8', errors='replace')
        else:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout,
                encoding='utf-8',
                errors='replace'
            )
            stdout = result.stdout
            stderr = result.stderr

        duration = (datetime.now() - start_time).total_seconds() * 1000

        return {
            "stdout": stdout,
            "stderr": stderr,
            "exit_code": result.returncode,
            "duration_ms": round(duration, 2)
        }

    except subprocess.TimeoutExpired:
        duration = (datetime.now() - start_time).total_seconds() * 1000
        raise HTTPException(
            status_code=408,
            detail=f"Command timed out after {timeout} seconds"
        )
    except FileNotFoundError:
        raise HTTPException(
            status_code=500,
            detail=f"ADB executable not found at: {ADB_PATH}"
        )
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Error executing ADB command: {str(e)}"
        )


def parse_devices_output(output: str) -> List[Device]:
    """Parse 'adb devices -l' output into structured device list."""
    devices = []
    lines = output.strip().split('\n')[1:]  # Skip "List of devices attached" header

    for line in lines:
        line = line.strip()
        if not line:
            continue

        parts = line.split()
        if len(parts) < 2:
            continue

        serial = parts[0]
        state = parts[1]

        # Parse additional properties
        model = None
        product = None

        for part in parts[2:]:
            if part.startswith('model:'):
                model = part.split(':', 1)[1]
            elif part.startswith('product:'):
                product = part.split(':', 1)[1]

        devices.append(Device(
            serial=serial,
            state=state,
            model=model,
            product=product
        ))

    return devices


# ============================================================================
# ENDPOINTS
# ============================================================================

@app.get("/health")
async def health_check():
    """
    Check ADB server status and list connected devices.

    Returns:
        Status dict with ADB health, device list, and configuration
    """
    try:
        result = run_adb_command(["devices", "-l"], timeout=5)

        if result["exit_code"] != 0:
            return {
                "status": "unhealthy",
                "adb_path": ADB_PATH,
                "devices": [],
                "selected_device": DEVICE_SERIAL,
                "port": PORT,
                "error": result["stderr"]
            }

        devices = parse_devices_output(result["stdout"])

        return {
            "status": "healthy",
            "adb_path": ADB_PATH,
            "devices": [d.dict() for d in devices],
            "selected_device": DEVICE_SERIAL,
            "port": PORT
        }

    except Exception as e:
        return {
            "status": "unhealthy",
            "adb_path": ADB_PATH,
            "devices": [],
            "selected_device": DEVICE_SERIAL,
            "port": PORT,
            "error": str(e)
        }


@app.get("/devices")
async def list_devices():
    """
    List all connected ADB devices with details.

    Returns:
        List of connected devices with serial, state, model, and product info
    """
    result = run_adb_command(["devices", "-l"], timeout=5)

    if result["exit_code"] != 0:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to list devices: {result['stderr']}"
        )

    devices = parse_devices_output(result["stdout"])

    return {
        "devices": [d.dict() for d in devices],
        "count": len(devices),
        "selected_device": DEVICE_SERIAL
    }


@app.post("/device/select")
async def select_device(request: DeviceSelectRequest):
    """
    Select a specific device by serial number, or set to None for auto-detect.

    Args:
        request: Device serial or None for auto-detect

    Returns:
        Confirmation of selected device
    """
    global DEVICE_SERIAL

    DEVICE_SERIAL = request.serial

    return {
        "success": True,
        "selected_device": DEVICE_SERIAL,
        "message": "Auto-detect (single device)" if DEVICE_SERIAL is None else f"Selected device: {DEVICE_SERIAL}"
    }


@app.post("/shell")
async def shell_command(request: ShellRequest):
    """
    Execute a shell command on the device.

    Args:
        request: Shell command and optional timeout

    Returns:
        Command output with stdout, stderr, exit code, and duration
    """
    result = run_adb_command(
        ["shell", request.command],
        timeout=request.timeout
    )

    return result


@app.post("/shell/termux")
async def termux_shell_command(request: TermuxShellRequest):
    """
    Execute a command inside Termux environment with proper PATH and HOME.

    Args:
        request: Command to run in Termux and optional timeout

    Returns:
        Command output with stdout, stderr, exit code, and duration
    """
    # Properly escape the command for shell execution
    command_escaped = request.command.replace("'", "'\\''")

    # Run as mK:a (embedded Termux) user with proper environment
    ba = "/data/data/com.mobilekinetic.agent/files"
    termux_cmd = (
        f"run-as com.mobilekinetic.agent "
        f"env LD_LIBRARY_PATH={ba}/usr/lib "
        f"HOME={ba}/home "
        f"PATH={ba}/usr/bin:{ba}/usr/bin/applets "
        f"PREFIX={ba}/usr "
        f"TMPDIR={ba}/usr/tmp "
        f"CURL_CA_BUNDLE={ba}/usr/etc/tls/cert.pem "
        f"SSL_CERT_FILE={ba}/usr/etc/tls/cert.pem "
        f"files/usr/bin/bash -c '{command_escaped}'"
    )

    result = run_adb_command(
        ["shell", termux_cmd],
        timeout=request.timeout
    )

    return result


@app.post("/push")
async def push_file(request: PushRequest):
    """
    Push a file from local system to device.

    Args:
        request: Local and remote file paths

    Returns:
        Success status and operation details
    """
    # Verify local file exists
    if not os.path.exists(request.local_path):
        raise HTTPException(
            status_code=400,
            detail=f"Local file not found: {request.local_path}"
        )

    result = run_adb_command(
        ["push", "--no-streaming", request.local_path, request.remote_path],
        timeout=60
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "message": result["stdout"] if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.post("/pull")
async def pull_file(request: PullRequest):
    """
    Pull a file from device to local system.

    Args:
        request: Remote and local file paths

    Returns:
        Success status and operation details
    """
    result = run_adb_command(
        ["pull", request.remote_path, request.local_path],
        timeout=60
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "message": result["stdout"] if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.post("/install")
async def install_apk(request: InstallRequest):
    """
    Install an APK on the device (with -r flag to reinstall/replace).

    Args:
        request: Path to APK file

    Returns:
        Success status and installation details
    """
    # Verify APK exists
    if not os.path.exists(request.apk_path):
        raise HTTPException(
            status_code=400,
            detail=f"APK file not found: {request.apk_path}"
        )

    result = run_adb_command(
        ["install", "--no-streaming", "-r", request.apk_path],
        timeout=120
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "message": result["stdout"] if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.post("/forward")
async def forward_port(request: ForwardRequest):
    """
    Forward a local port to a remote port on the device.

    Args:
        request: Local and remote port numbers

    Returns:
        Success status and forwarding details
    """
    result = run_adb_command(
        ["forward", f"tcp:{request.local_port}", f"tcp:{request.remote_port}"],
        timeout=5
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "local_port": request.local_port,
        "remote_port": request.remote_port,
        "message": "Port forwarding established" if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.post("/forward/remove")
async def remove_forward(request: ForwardRemoveRequest):
    """
    Remove a port forwarding rule.

    Args:
        request: Local port to stop forwarding

    Returns:
        Success status
    """
    result = run_adb_command(
        ["forward", "--remove", f"tcp:{request.local_port}"],
        timeout=5
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "local_port": request.local_port,
        "message": "Port forwarding removed" if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.get("/forward/list")
async def list_forwards():
    """
    List all active port forwarding rules.

    Returns:
        List of active port forwards
    """
    result = run_adb_command(["forward", "--list"], timeout=5)

    if result["exit_code"] != 0:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to list forwards: {result['stderr']}"
        )

    # Parse forward list output
    forwards = []
    for line in result["stdout"].strip().split('\n'):
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 3:
            forwards.append({
                "serial": parts[0],
                "local": parts[1],
                "remote": parts[2]
            })

    return {
        "forwards": forwards,
        "count": len(forwards)
    }


@app.post("/logcat")
async def get_logcat(request: LogcatRequest):
    """
    Retrieve device logcat output with optional filtering.

    Args:
        request: Filter expression, line count, and timeout

    Returns:
        Logcat lines and metadata
    """
    args = ["logcat", "-d", "-t", str(request.lines)]

    # Add filter if not default
    if request.filter != "*:*":
        args.append(request.filter)

    result = run_adb_command(args, timeout=request.timeout)

    if result["exit_code"] != 0:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get logcat: {result['stderr']}"
        )

    lines = result["stdout"].strip().split('\n')

    return {
        "lines": lines,
        "count": len(lines),
        "filter": request.filter,
        "duration_ms": result["duration_ms"]
    }


@app.post("/screencap")
async def capture_screen(request: ScreencapRequest):
    """
    Capture a screenshot from the device.

    Args:
        request: Optional local path to save screenshot

    Returns:
        Path to saved screenshot and success status
    """
    # Determine output path
    if request.save_to:
        output_path = request.save_to
    else:
        # Create temp file
        temp_file = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        output_path = temp_file.name
        temp_file.close()

    try:
        # Use exec-out for binary output
        result = run_adb_command(
            ["exec-out", "screencap", "-p"],
            timeout=10,
            capture_binary=True
        )

        if result["exit_code"] != 0:
            raise HTTPException(
                status_code=500,
                detail=f"Failed to capture screen: {result['stderr']}"
            )

        # Write binary data to file
        with open(output_path, 'wb') as f:
            f.write(result["stdout"])

        return {
            "success": True,
            "path": output_path,
            "size_bytes": len(result["stdout"]),
            "duration_ms": result["duration_ms"]
        }

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Error saving screenshot: {str(e)}"
        )


@app.post("/pm/grant")
async def grant_permission(request: GrantRequest):
    """
    Grant a permission to a package.

    Args:
        request: Package name and permission to grant

    Returns:
        Success status and grant details
    """
    result = run_adb_command(
        ["shell", "pm", "grant", request.package, request.permission],
        timeout=10
    )

    success = result["exit_code"] == 0

    return {
        "success": success,
        "package": request.package,
        "permission": request.permission,
        "message": "Permission granted" if success else result["stderr"],
        "duration_ms": result["duration_ms"]
    }


@app.post("/pm/grant_batch")
async def grant_permissions_batch(request: GrantBatchRequest):
    """
    Grant multiple permissions to a package.

    Args:
        request: Package name and list of permissions to grant

    Returns:
        Results for each permission grant operation
    """
    results = []
    total_duration = 0.0

    for permission in request.permissions:
        result = run_adb_command(
            ["shell", "pm", "grant", request.package, permission],
            timeout=10
        )

        total_duration += result["duration_ms"]

        results.append({
            "permission": permission,
            "success": result["exit_code"] == 0,
            "message": "Granted" if result["exit_code"] == 0 else result["stderr"]
        })

    all_success = all(r["success"] for r in results)

    return {
        "success": all_success,
        "package": request.package,
        "results": results,
        "total_granted": sum(1 for r in results if r["success"]),
        "total_failed": sum(1 for r in results if not r["success"]),
        "duration_ms": round(total_duration, 2)
    }


# ============================================================================
# STARTUP
# ============================================================================

def print_startup_banner():
    """Print server startup information."""
    print("=" * 70)
    print("ADB MCP SERVER")
    print("=" * 70)
    print(f"Host: {HOST}")
    print(f"Port: {PORT}")
    print(f"ADB Path: {ADB_PATH}")
    print(f"Device Selection: {'Auto-detect' if DEVICE_SERIAL is None else DEVICE_SERIAL}")
    print("=" * 70)
    print(f"Health Check: http://{HOST}:{PORT}/health")
    print(f"API Docs: http://{HOST}:{PORT}/docs")
    print("=" * 70)


if __name__ == "__main__":
    print_startup_banner()
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
