#!/usr/bin/env python
"""
Device API MCP Server - Tier 2 (Python/Shell)
Port: 5564 | Host: localhost

Runs in Termux on the user's Android device.
Claude on-device can modify this file to add new endpoints.
After modifying, restart: pkill -f device_api_mcp && python ~/device_api_mcp.py &

Pattern for new endpoints:
  @app.get("/my_endpoint")
  async def my_endpoint():
      result = run_command("some_command")
      return {"output": result["stdout"], "success": result["success"]}
"""

import subprocess
import os
import platform
import re
from pathlib import Path
from typing import Optional, List, Dict, Any
from datetime import datetime

from fastapi import FastAPI, HTTPException
from mka_config import MCP_TIER2_PORT
from pydantic import BaseModel

app = FastAPI(title="Device API MCP - Tier 2", version="1.0.0")

# Security
TERMUX_HOME = os.path.expanduser("~")
ALLOWED_PATHS = [TERMUX_HOME, "/sdcard", "/storage/emulated/0", "/proc", "/sys/class", "/tmp"]
BLOCKED_COMMANDS = ["rm -rf /", "mkfs", "dd if=", "reboot", "shutdown", "format", "passwd"]
SENSITIVE_ENV_KEYS = ["API_KEY", "TOKEN", "SECRET", "PASSWORD", "ANTHROPIC", "CREDENTIAL", "PRIVATE"]

def is_path_allowed(path: str) -> bool:
    """Check if path is within allowed directories."""
    try:
        resolved = str(Path(path).resolve())
        return any(resolved.startswith(allowed) for allowed in ALLOWED_PATHS)
    except Exception:
        return False

def is_command_safe(cmd: str) -> bool:
    """Check if command is not in blocked list."""
    return not any(blocked in cmd.lower() for blocked in BLOCKED_COMMANDS)

def run_command(cmd: str, timeout: int = 30, shell: bool = True) -> dict:
    """Execute shell command and return result."""
    try:
        start = datetime.now()
        result = subprocess.run(cmd, shell=shell, capture_output=True, text=True, timeout=timeout)
        elapsed = int((datetime.now() - start).total_seconds() * 1000)
        return {
            "stdout": result.stdout,
            "stderr": result.stderr,
            "return_code": result.returncode,
            "success": result.returncode == 0,
            "execution_time_ms": elapsed
        }
    except subprocess.TimeoutExpired:
        return {
            "stdout": "",
            "stderr": f"Timeout after {timeout}s",
            "return_code": -1,
            "success": False,
            "execution_time_ms": timeout * 1000
        }
    except Exception as e:
        return {
            "stdout": "",
            "stderr": str(e),
            "return_code": -1,
            "success": False,
            "execution_time_ms": 0
        }


# Pydantic Models
class CommandRequest(BaseModel):
    command: str
    timeout: Optional[int] = 30


class FileReadRequest(BaseModel):
    path: str
    lines: Optional[int] = 100


class FileWriteRequest(BaseModel):
    path: str
    content: str


class PackageRequest(BaseModel):
    package: str


# Endpoints

@app.get("/health")
async def health():
    """Health check with system info."""
    uptime_seconds = 0
    try:
        with open("/proc/uptime", "r") as f:
            uptime_seconds = float(f.read().split()[0])
    except Exception:
        pass

    return {
        "status": "healthy",
        "hostname": platform.node(),
        "uptime_seconds": int(uptime_seconds),
        "python_version": platform.python_version(),
        "platform": platform.system(),
        "port": MCP_TIER2_PORT,
        "server": "Device API MCP - Tier 2"
    }


@app.post("/execute")
async def execute(req: CommandRequest):
    """Execute shell command with safety checks."""
    if not is_command_safe(req.command):
        raise HTTPException(status_code=403, detail="Command blocked for safety")

    result = run_command(req.command, timeout=req.timeout)
    return result


@app.get("/system/info")
async def system_info():
    """Aggregate system information."""
    info = {
        "hostname": platform.node(),
        "kernel": "",
        "uptime_seconds": 0,
        "load_avg": {"1min": 0.0, "5min": 0.0, "15min": 0.0},
        "memory": {"total_kb": 0, "free_kb": 0, "available_kb": 0},
        "cpu_model": "unknown"
    }

    # Kernel
    uname_result = run_command("uname -a")
    if uname_result["success"]:
        info["kernel"] = uname_result["stdout"].strip()

    # Uptime
    try:
        with open("/proc/uptime", "r") as f:
            info["uptime_seconds"] = int(float(f.read().split()[0]))
    except Exception:
        pass

    # Load average
    try:
        with open("/proc/loadavg", "r") as f:
            parts = f.read().split()
            info["load_avg"] = {
                "1min": float(parts[0]),
                "5min": float(parts[1]),
                "15min": float(parts[2])
            }
    except Exception:
        pass

    # Memory
    try:
        with open("/proc/meminfo", "r") as f:
            meminfo = f.read()
            for line in meminfo.split('\n'):
                if line.startswith("MemTotal:"):
                    info["memory"]["total_kb"] = int(line.split()[1])
                elif line.startswith("MemFree:"):
                    info["memory"]["free_kb"] = int(line.split()[1])
                elif line.startswith("MemAvailable:"):
                    info["memory"]["available_kb"] = int(line.split()[1])
    except Exception:
        pass

    # CPU model
    try:
        with open("/proc/cpuinfo", "r") as f:
            for line in f:
                if line.startswith("model name") or line.startswith("Processor"):
                    info["cpu_model"] = line.split(":", 1)[1].strip()
                    break
    except Exception:
        pass

    return info


@app.get("/ps")
async def ps_list():
    """List running processes."""
    result = run_command("ps -eo pid,user,%cpu,%mem,comm --sort=-%cpu 2>/dev/null || ps -o pid,user,comm")

    if not result["success"]:
        return {"processes": [], "error": result["stderr"]}

    lines = result["stdout"].strip().split('\n')
    if len(lines) < 2:
        return {"processes": []}

    # Parse header
    header = lines[0]
    processes = []

    for line in lines[1:51]:  # Top 50
        parts = line.split(None, 4)
        if len(parts) >= 3:
            proc = {
                "pid": parts[0],
                "user": parts[1] if len(parts) > 1 else "",
                "cpu": parts[2] if len(parts) > 2 else "0",
                "mem": parts[3] if len(parts) > 3 else "0",
                "command": parts[4] if len(parts) > 4 else parts[-1]
            }
            processes.append(proc)

    return {"processes": processes, "count": len(processes)}


@app.get("/df")
async def df_list():
    """List disk usage."""
    result = run_command("df -h")

    if not result["success"]:
        return {"filesystems": [], "error": result["stderr"]}

    lines = result["stdout"].strip().split('\n')
    if len(lines) < 2:
        return {"filesystems": []}

    header = lines[0].split()
    filesystems = []

    for line in lines[1:]:
        parts = line.split()
        if len(parts) >= 6:
            fs = {
                "filesystem": parts[0],
                "size": parts[1],
                "used": parts[2],
                "available": parts[3],
                "use_percent": parts[4],
                "mounted_on": ' '.join(parts[5:])
            }
            filesystems.append(fs)

    return {"filesystems": filesystems, "count": len(filesystems)}


@app.get("/ip")
async def ip_info():
    """Get network interface and routing info."""
    # Try JSON output first
    addr_result = run_command("ip -j addr 2>/dev/null")
    route_result = run_command("ip -j route 2>/dev/null")

    response = {"addresses": [], "routes": []}

    if addr_result["success"] and addr_result["stdout"].strip().startswith('['):
        try:
            import json
            response["addresses"] = json.loads(addr_result["stdout"])
        except Exception:
            # Fallback to text parsing
            addr_text = run_command("ip addr")
            response["addresses"] = addr_text["stdout"]
    else:
        addr_text = run_command("ip addr")
        response["addresses"] = addr_text["stdout"]

    if route_result["success"] and route_result["stdout"].strip().startswith('['):
        try:
            import json
            response["routes"] = json.loads(route_result["stdout"])
        except Exception:
            route_text = run_command("ip route")
            response["routes"] = route_text["stdout"]
    else:
        route_text = run_command("ip route")
        response["routes"] = route_text["stdout"]

    return response


@app.get("/proc/read")
async def proc_read_alias(path: str):
    """Alias: GET /proc/read?path=X -> read_proc(path)
    Used by mcp_tier2_server.py which calls /proc/read?path=meminfo"""
    return await read_proc(path)


@app.get("/proc/{path:path}")
async def read_proc(path: str):
    """Read whitelisted /proc files."""
    allowed = ["meminfo", "cpuinfo", "loadavg", "uptime", "version", "stat", "net/dev"]

    if path not in allowed:
        raise HTTPException(status_code=403, detail=f"Path /proc/{path} not whitelisted")

    full_path = f"/proc/{path}"

    try:
        with open(full_path, "r") as f:
            content = f.read()
        return {"path": full_path, "content": content}
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File {full_path} not found")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/sys/read")
async def sys_read_alias(path: str):
    """Alias: GET /sys/read?path=X -> read_sys(path)
    Used by mcp_tier2_server.py which calls /sys/read?path=..."""
    return await read_sys(path)


@app.get("/sys/{path:path}")
async def read_sys(path: str):
    """Read whitelisted /sys files."""
    allowed_patterns = ["power_supply/", "net/", "thermal/", "display/", "class/power_supply/", "class/net/", "class/thermal/"]

    if not any(path.startswith(pattern) for pattern in allowed_patterns):
        raise HTTPException(status_code=403, detail=f"Path /sys/{path} not whitelisted")

    full_path = f"/sys/{path}"

    try:
        with open(full_path, "r") as f:
            content = f.read().strip()
        return {"path": full_path, "content": content}
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail=f"File {full_path} not found")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/files/list")
async def list_files(path: str = "~"):
    """List directory contents."""
    expanded_path = os.path.expanduser(path)

    if not is_path_allowed(expanded_path):
        raise HTTPException(status_code=403, detail="Path not allowed")

    if not os.path.exists(expanded_path):
        raise HTTPException(status_code=404, detail="Path not found")

    if not os.path.isdir(expanded_path):
        raise HTTPException(status_code=400, detail="Path is not a directory")

    try:
        entries = []
        for entry in os.listdir(expanded_path):
            entry_path = os.path.join(expanded_path, entry)
            try:
                stat = os.stat(entry_path)
                entries.append({
                    "name": entry,
                    "size": stat.st_size,
                    "is_dir": os.path.isdir(entry_path),
                    "modified": datetime.fromtimestamp(stat.st_mtime).isoformat()
                })
            except Exception:
                # Skip entries we can't stat
                continue

        return {"path": expanded_path, "entries": entries, "count": len(entries)}
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/files/read")
async def read_file(req: FileReadRequest):
    """Read file content with line limit."""
    expanded_path = os.path.expanduser(req.path)

    if not is_path_allowed(expanded_path):
        raise HTTPException(status_code=403, detail="Path not allowed")

    if not os.path.exists(expanded_path):
        raise HTTPException(status_code=404, detail="File not found")

    if not os.path.isfile(expanded_path):
        raise HTTPException(status_code=400, detail="Path is not a file")

    try:
        with open(expanded_path, "r") as f:
            lines = []
            for i, line in enumerate(f):
                if i >= req.lines:
                    break
                lines.append(line.rstrip('\n\r'))

            content = '\n'.join(lines)
            truncated = len([1 for _ in f]) > 0  # Check if more lines exist

        return {
            "path": expanded_path,
            "content": content,
            "lines_read": len(lines),
            "truncated": truncated
        }
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except UnicodeDecodeError:
        raise HTTPException(status_code=400, detail="File is not text")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/files/write")
async def write_file(req: FileWriteRequest):
    """Write content to file (must be under TERMUX_HOME)."""
    expanded_path = os.path.expanduser(req.path)

    if not expanded_path.startswith(TERMUX_HOME):
        raise HTTPException(status_code=403, detail="Can only write to files under TERMUX_HOME")

    try:
        # Ensure parent directory exists
        parent = os.path.dirname(expanded_path)
        if parent:
            os.makedirs(parent, exist_ok=True)

        with open(expanded_path, "w") as f:
            f.write(req.content)

        return {
            "path": expanded_path,
            "bytes_written": len(req.content),
            "success": True
        }
    except PermissionError:
        raise HTTPException(status_code=403, detail="Permission denied")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/packages")
async def list_packages():
    """List installed packages."""
    # Try pkg first (Termux), fallback to dpkg
    result = run_command("pkg list-installed 2>/dev/null || dpkg -l 2>/dev/null || echo 'No package manager found'")

    packages = []

    if "No package manager found" in result["stdout"]:
        return {"packages": [], "error": "No package manager found"}

    lines = result["stdout"].strip().split('\n')

    for line in lines:
        # Parse pkg output (name/version format) or dpkg output
        if '/' in line:
            # pkg format: package/stable,now version arch [installed]
            parts = line.split('/', 1)
            name = parts[0].strip()
            if len(parts) > 1:
                version_part = parts[1].split()[0] if parts[1].split() else "unknown"
                packages.append({"name": name, "version": version_part})
        elif line.startswith('ii '):
            # dpkg format: ii  package  version  arch  description
            parts = line.split(None, 4)
            if len(parts) >= 3:
                packages.append({"name": parts[1], "version": parts[2]})

    return {"packages": packages, "count": len(packages)}


@app.post("/packages/install")
async def install_package(req: PackageRequest):
    """Install package using pkg."""
    # Sanitize package name
    if not re.match(r'^[a-zA-Z0-9._-]+$', req.package):
        raise HTTPException(status_code=400, detail="Invalid package name")

    result = run_command(f"pkg install -y {req.package}", timeout=300)

    return {
        "package": req.package,
        "success": result["success"],
        "output": result["stdout"],
        "error": result["stderr"],
        "execution_time_ms": result["execution_time_ms"]
    }


@app.get("/env")
async def get_env():
    """Get environment variables (filtered for sensitive data)."""
    env = {}

    for key, value in os.environ.items():
        # Filter out sensitive keys
        if not any(sensitive.lower() in key.lower() for sensitive in SENSITIVE_ENV_KEYS):
            env[key] = value
        else:
            env[key] = "***REDACTED***"

    return {"env": env, "count": len(env)}


@app.get("/cron")
async def get_cron():
    """Get crontab entries."""
    result = run_command("crontab -l 2>/dev/null")

    if result["return_code"] != 0:
        return {"crontab": "no crontab", "entries": []}

    entries = []
    for line in result["stdout"].strip().split('\n'):
        line = line.strip()
        if line and not line.startswith('#'):
            entries.append(line)

    return {
        "crontab": result["stdout"].strip() if result["stdout"].strip() else "no crontab",
        "entries": entries,
        "count": len(entries)
    }


# ---------------------------------------------------------------------------
# Route Aliases
# The unified MCP (unified_device_mcp.py) uses structured paths like
# /shell/execute, /system/processes, /network/interfaces.
# These aliases let both old short paths and new structured paths work.
# ---------------------------------------------------------------------------

@app.post("/shell/execute")
async def shell_execute_alias(req: CommandRequest):
    """Alias: POST /shell/execute -> POST /execute"""
    return await execute(req)


@app.get("/system/processes")
async def system_processes_alias():
    """Alias: GET /system/processes -> GET /ps"""
    return await ps_list()


@app.get("/network/interfaces")
async def network_interfaces_alias():
    """Alias: GET /network/interfaces -> GET /ip"""
    return await ip_info()


@app.get("/system/disk")
async def system_disk_alias():
    """Alias: GET /system/disk -> GET /df"""
    return await df_list()


@app.get("/packages/list")
async def packages_list_alias():
    """Alias: GET /packages/list -> GET /packages"""
    return await list_packages()


@app.get("/cron/list")
async def cron_list_alias():
    """Alias: GET /cron/list -> GET /cron"""
    return await get_cron()


# Startup
if __name__ == "__main__":
    import uvicorn
    print("Device API MCP - Tier 2 (Python/Shell)")
    print(f"Listening on http://127.0.0.1:{MCP_TIER2_PORT}")
    uvicorn.run(app, host="127.0.0.1", port=MCP_TIER2_PORT, log_level="warning")
