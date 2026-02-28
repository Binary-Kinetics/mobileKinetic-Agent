import claude_agent_sdk
print("Version:", claude_agent_sdk.__version__ if hasattr(claude_agent_sdk, '__version__') else 'unknown')
print("Exports:", [x for x in dir(claude_agent_sdk) if not x.startswith('_')])
print()
print("Has StreamEvent:", hasattr(claude_agent_sdk, 'StreamEvent'))

# Check submodules
import pkgutil
for importer, modname, ispkg in pkgutil.walk_packages(claude_agent_sdk.__path__, claude_agent_sdk.__name__ + '.'):
    print(f"  submodule: {modname}")
