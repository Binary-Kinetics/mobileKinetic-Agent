import sys
try:
    import claude_agent_sdk
    print("claude_agent_sdk OK")
except Exception as e:
    print(f"claude_agent_sdk FAILED: {e}")

try:
    import mcp
    print("mcp OK")
except Exception as e:
    print(f"mcp FAILED: {e}")

try:
    import pydantic
    print(f"pydantic OK: {pydantic.__version__}")
except Exception as e:
    print(f"pydantic FAILED: {e}")

try:
    import rpds
    print("rpds OK")
except Exception as e:
    print(f"rpds FAILED: {e}")

try:
    import jsonschema
    print("jsonschema OK")
except Exception as e:
    print(f"jsonschema FAILED: {e}")
