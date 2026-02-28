"""
mK:a Shared Configuration Reader

Reads configuration from environment variables (set by Kotlin ProcessBuilder)
or falls back to ~/user_config.json (written by the app on settings change).

Two-tier fallback for every value:
    1. Environment variable  (MKA_ prefix)
    2. JSON config file      (~/user_config.json)
    3. Hardcoded default     (last resort)

Usage:
    from mka_config import USER_NAME, DEVICE_NAME, TTS_HOST, TTS_PORT
    from mka_config import get  # for dynamic key lookup
"""

import json
import os

# ---------------------------------------------------------------------------
# Config file loader
# ---------------------------------------------------------------------------

def load_config():
    """Load config from ~/user_config.json if it exists."""
    config_path = os.path.join(os.path.expanduser("~"), "user_config.json")
    try:
        with open(config_path) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError, PermissionError):
        return {}


CONFIG = load_config()


def _env(key, default=""):
    """Get from MKA_ env var, then CONFIG, then default."""
    env_key = f"MKA_{key.upper()}"
    return os.getenv(env_key) or CONFIG.get(key, default)


def _env_int(key, default=0):
    """Get an integer config value."""
    raw = _env(key, "")
    if raw == "":
        return default
    try:
        return int(raw)
    except (ValueError, TypeError):
        return default


# ---------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------

USER_NAME = _env("user_name", "User")
DEVICE_NAME = _env("device_name", "Android Device")

# ---------------------------------------------------------------------------
# TTS Server (Kokoro WebSocket)
# ---------------------------------------------------------------------------

TTS_HOST = _env("tts_host", "")
TTS_PORT = _env_int("tts_port", 9199)
TTS_WSS_URL = _env("tts_wss_url", f"wss://{TTS_HOST}:{TTS_PORT}/ws/tts")
TTS_HTTPS_URL = _env("tts_https_url", f"https://{TTS_HOST}:{TTS_PORT}/tts")

# ---------------------------------------------------------------------------
# Local service ports (on-device, localhost)
# ---------------------------------------------------------------------------

DEVICE_API_PORT = _env_int("device_api_port", 5563)       # Tier 1 Kotlin Device API
MCP_TIER2_PORT = _env_int("mcp_tier2_port", 5564)         # Tier 2 Python MCP (FastAPI)
RAG_PORT = _env_int("rag_port", 5562)                     # On-device RAG memory
VAULT_PORT = _env_int("vault_port", 5565)                 # VaultHttpServer (Ktor CIO)

# Derived base URLs (localhost services)
DEVICE_API_URL = _env("device_api_url", f"http://localhost:{DEVICE_API_PORT}")
MCP_TIER2_URL = _env("mcp_tier2_url", f"http://localhost:{MCP_TIER2_PORT}")
RAG_URL = _env("rag_url", f"http://localhost:{RAG_PORT}")
VAULT_URL = _env("vault_url", f"http://127.0.0.1:{VAULT_PORT}")

# ---------------------------------------------------------------------------
# Network services (LAN)
# ---------------------------------------------------------------------------

# Home Assistant
HA_IP = _env("ha_ip", "")
HA_MCP_PORT = _env_int("ha_mcp_port", 5557)
HA_URL = _env("ha_url", f"http://localhost:{HA_MCP_PORT}")

# Network Storage (NAS / P: drive)
NAS_IP = _env("nas_ip", "")

# Switchboard (inter-Claude communication)
SWITCHBOARD_IP = _env("switchboard_ip", "")
SWITCHBOARD_PORT = _env_int("switchboard_port", 5559)
SWITCHBOARD_URL = _env("switchboard_url", f"http://{SWITCHBOARD_IP}:{SWITCHBOARD_PORT}")

# UDM Router
UDM_IP = _env("udm_ip", "")
UDM_MCP_PORT = _env_int("udm_mcp_port", 5556)
UDM_URL = _env("udm_url", f"http://localhost:{UDM_MCP_PORT}")

# Browser MCP
BROWSER_MCP_PORT = _env_int("browser_mcp_port", 5560)
BROWSER_MCP_URL = _env("browser_mcp_url", f"http://localhost:{BROWSER_MCP_PORT}")

# RLM MCP
RLM_MCP_PORT = _env_int("rlm_mcp_port", 6100)
RLM_MCP_URL = _env("rlm_mcp_url", f"http://localhost:{RLM_MCP_PORT}")

# ADB MCP
ADB_MCP_PORT = _env_int("adb_mcp_port", 6473)
ADB_MCP_URL = _env("adb_mcp_url", f"http://localhost:{ADB_MCP_PORT}")

# ---------------------------------------------------------------------------
# Remote network devices (IPs for SSH / direct access)
# ---------------------------------------------------------------------------

ENCODER_PI_IP = _env("encoder_pi_ip", "")
TASKER_PI_IP = _env("tasker_pi_ip", "")
PIHOLE_IP = _env("pihole_ip", "")
PRINTHUB_IP = _env("printhub_ip", "")
MK_IP = _env("mk_ip", "")
CLAUDE_CODE_IP = _env("claude_code_ip", "")

# ---------------------------------------------------------------------------
# Personal domain
# ---------------------------------------------------------------------------

PERSONAL_DOMAIN = _env("domain", "")

# ---------------------------------------------------------------------------
# AI / Model configuration
# ---------------------------------------------------------------------------

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY") or CONFIG.get("anthropic_api_key", "")
CLAUDE_MODEL = _env("claude_model", "claude-sonnet-4-20250514")
LOCAL_MODEL_URL = _env("local_model_url", "")

# ---------------------------------------------------------------------------
# Paths (on-device)
# ---------------------------------------------------------------------------

HOME_DIR = os.path.expanduser("~")
MEMORY_DB_PATH = _env(
    "memory_db_path",
    os.path.join(HOME_DIR, "Memory", "RAG", "memory_index.db"),
)

# ---------------------------------------------------------------------------
# CalDAV
# ---------------------------------------------------------------------------

CALDAV_URL = _env("caldav_url", "")
CALDAV_USERNAME = _env("caldav_username", "")
CALDAV_PASSWORD = _env("caldav_password", "")

# ---------------------------------------------------------------------------
# Dynamic lookup
# ---------------------------------------------------------------------------

def get(key, default=""):
    """Get any config value by key, checking env vars then config file.

    Args:
        key: Config key name (lowercase, underscored).
             Env var lookup uses MKA_ prefix + uppercase.
        default: Fallback if not found anywhere.

    Returns:
        The config value as a string.
    """
    return _env(key, default)


def get_int(key, default=0):
    """Get any config value as an integer."""
    return _env_int(key, default)


def reload():
    """Reload config from disk (e.g., after settings change in the app)."""
    global CONFIG
    CONFIG = load_config()
