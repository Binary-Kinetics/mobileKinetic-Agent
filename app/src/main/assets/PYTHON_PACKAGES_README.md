# mK:a Python Packages Installation

## Overview
This directory contains `python_complete.tar.gz` - a pre-built Python package archive containing pydantic-core, claude-agent-sdk, mcp, and all dependencies compiled for Android 15 (16KB page alignment).

## Package Contents (8.3MB)
- **pydantic-core 2.41.5** - Built with 16KB page alignment for Android 15
- **pydantic 2.12.5** - Data validation library
- **claude-agent-sdk 0.1.27** - Claude Agent SDK
- **mcp 1.26.0** - Model Context Protocol
- **Dependencies**: httpx, anyio, starlette, uvicorn, cryptography, and all transitive dependencies

## Why This Exists
Android 15 on Pixel device requires 16KB memory page alignment for native binaries. Standard PyPI wheels fail with SIGSEGV. This archive contains packages built in Termux with proper alignment flags:
```bash
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
```

## Installation into mK:a

### Method 1: ADB (from PC)
```bash
# Push tar to device
adb push python_complete.tar.gz /sdcard/

# Copy into mK:a and extract
adb shell "cat /sdcard/python_complete.tar.gz | run-as com.mobilekinetic.agent sh -c 'cat > /data/data/com.mobilekinetic.agent/files/python_complete.tar.gz'"
adb shell "run-as com.mobilekinetic.agent tar -xzf /data/data/com.mobilekinetic.agent/files/python_complete.tar.gz -C /data/data/com.mobilekinetic.agent/files/usr/lib/python3.12/site-packages"
```

### Method 2: From Device
If tar is on /sdcard:
```bash
# In mK:a terminal
export LD_LIBRARY_PATH=/data/data/com.mobilekinetic.agent/files/usr/lib
cd $PREFIX/lib/python3.12/site-packages
cat /sdcard/python_complete.tar.gz | tar -xzf -
```

## Testing Installation
```bash
adb shell "run-as com.mobilekinetic.agent sh -c 'export LD_LIBRARY_PATH=/data/data/com.mobilekinetic.agent/files/usr/lib && /data/data/com.mobilekinetic.agent/files/usr/bin/python -c \"import pydantic; import claude_agent_sdk; import mcp; print(\\\"SUCCESS\\\")\"'"
```

## Build Source
Built in Termux on Pixel device (Android 15) on 2026-02-05.

Termux build environment:
- Python 3.12.12
- Rust (Termux package)
- binutils-is-llvm
- clang

## Notes
- Both Termux and mK:a run Python 3.12.12 on aarch64
- .so files are binary-compatible between the two environments
- LD_LIBRARY_PATH must be set to /data/data/com.mobilekinetic.agent/files/usr/lib when running Python
- This archive includes both package directories and .dist-info metadata

## Rebuilding (if needed)
See the build instructions below.

Key steps:
1. `pkg install python rust binutils-is-llvm make clang`
2. `export CARGO_BUILD_TARGET=aarch64-linux-android`
3. `export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"`
4. `export CARGO_BUILD_JOBS=1`
5. `pip install pydantic-core --no-binary pydantic-core`
6. `pip install pydantic claude-agent-sdk mcp`
