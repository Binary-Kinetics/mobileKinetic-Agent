# Claude Memory - Android Environment

## Quick Reference Files
- **This file**: `~/memory.md` - Read this first in every new session
- **About me**: `~/Claude.md` - My capabilities and documentation
- **Shared folder**: `~/shared/` - Files shared by user for analysis

## Environment Details

### Platform
- **Device**: Android (Linux 6.1.145-android14)
- **Architecture**: aarch64
- **App**: Binary Kinetics custom Claude Code integration
- **User**: u0_a538
- **Home**: `/data/data/com.mobilekinetic.agent/files/home`
- **Alt path**: `/data/user/0/com.mobilekinetic.agent/files/home` (same location)

### Available Tools
- Python 3.12.12
- Node.js v25.3.0
- Rust/Cargo toolchain
- Termux-style package manager (`pkg install`)

### Not Installed (yet)
- Git (install with: `pkg install git`)

## Storage Locations

### Internal Storage
- **Home**: `~/` or `/data/data/com.mobilekinetic.agent/files/home`
- **Shared folder**: `~/shared/` - User drops files here for me
- **Temp**: `~/tmp/` or `~/.tmp/`
- **Claude config**: `~/.claude/`

### External Storage (SDCard)
- **Path**: `/sdcard/` → `/storage/self/primary`
- **Workspace**: `/sdcard/claude_workspace/`
- **Test folder**: `/sdcard/claude_test/` (I have write access)
- ⚠️ **IMPORTANT**: Always use `timeout` with sdcard operations to prevent freezing

## Common Errors & Solutions

### 1. **Freezing on sdcard operations**
- **Problem**: Direct `mkdir /sdcard/foo` can hang and crash the app
- **Solution**: Always wrap in timeout: `timeout 5 mkdir -p /sdcard/foo`
- **Why**: Android permission/lock issues can cause indefinite hangs

### 2. **/tmp issues**
- **Problem**: Android has no `/tmp` or it's not writable
- **Solution**: Use `TMPDIR=$HOME/tmp` or `TMPDIR=$PREFIX/tmp`
- **Already set in**: `~/.bashrc`

### 3. **Pip installation failures**
- **Problem**: Pip tries to use /tmp for builds
- **Solution**:
  ```bash
  export TMPDIR=$HOME/.pip_tmp
  export PIP_CACHE_DIR=$HOME/.pip_tmp/pip-cache
  mkdir -p $TMPDIR $PIP_CACHE_DIR
  ```

### 4. **Rust compilation issues**
- **Problem**: Cargo generates huge artifacts, fills up tmp
- **Solution**:
  ```bash
  export CARGO_TARGET_DIR=$HOME/tmp/cargo
  export CARGO_HOME=$HOME/.cargo
  ```

## Session Workflow

### Starting Fresh
1. Read `~/memory.md` (this file) first
2. Check `~/Claude.md` for capabilities reference
3. Check `~/shared/` for any new files from user

### File Locations to Remember
- User-shared files: `~/shared/`
- My documentation: `~/Claude.md` and `~/memory.md`
- SDCard access: `/sdcard/` (use timeouts!)
- Session history: `~/.claude/history.jsonl`

## Notes
- App can crash/freeze on unprotected system calls
- Always prefer timeout wrappers for risky operations
- File history is tracked in `~/.claude/file-history/`
- User is building this as a custom mobile Claude Code assistant

---
*Last updated: 2026-02-02*
