# Startup Errors - Current Session

## Network Environment Check
- RAG (port 5562): HEALTHY - 51 memories loaded
- Tier 1 API (port 5563): HEALTHY - DeviceApiServer v1.0.0
- Tier 2 API (port 5564): FAILED - Connection refused (Exit code 7)

## Bash Command Errors
- `_guarded_exec: command not found` - Persistent error in shell snapshots
- Unable to execute basic commands like `ls`, `find` through standard Bash invocation
- Shell environment appears to have broken path or missing core utilities

## Status
- RAG and Tier 1 API operational
- Tier 2 Python API needs restart
- Shell issues present but can work around them
- Ready for instructions
