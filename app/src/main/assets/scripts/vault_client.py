#!/usr/bin/env python3
"""
vault_client.py — Phase 9: Credential Bridge

Lightweight async client for the VaultHttpServer running at 127.0.0.1:5565
(Ktor CIO server in mK:a, integrated with CredentialGatekeeper).

Implemented with stdlib urllib.request to avoid third-party dependencies in
the Termux Python environment. Async methods run synchronous HTTP calls in a
thread executor so they are safe to await from async contexts.

Vault endpoints consumed:
  GET  /vault/health
  GET  /vault/list
  GET  /vault/get?name=<name>&context=<context>
  POST /vault/store
  DELETE /vault/delete
  POST /vault/authorize
  GET  /vault/policies

Usage (async context):
    client = VaultClient()
    value = await client.get_credential("ANTHROPIC_API_KEY", "claude conversation")
    await client.close()

Usage (sync/convenience):
    value = fetch_credential("ANTHROPIC_API_KEY", "claude conversation")
    ok    = store_credential("MY_KEY", "my_secret_value")
    names = list_credentials()
"""

import asyncio
import json
import logging
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

VAULT_URL = "http://127.0.0.1:5565"
_REQUEST_TIMEOUT = 5  # seconds

logger = logging.getLogger(__name__)
if not logger.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("[vault_client] %(levelname)s %(message)s"))
    logger.addHandler(_handler)
    logger.setLevel(logging.WARNING)


# ---------------------------------------------------------------------------
# Low-level sync HTTP helpers (urllib only — no third-party deps)
# ---------------------------------------------------------------------------

def _http_get(url: str, timeout: int = _REQUEST_TIMEOUT) -> Optional[dict]:
    """Perform a GET request and return parsed JSON, or None on failure."""
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        logger.warning("GET %s -> HTTP %d: %s", url, e.code, e.reason)
        return None
    except urllib.error.URLError as e:
        logger.warning("GET %s -> URLError: %s", url, e.reason)
        return None
    except Exception as e:
        logger.warning("GET %s -> error: %s", url, e)
        return None


def _http_post(url: str, payload: dict, timeout: int = _REQUEST_TIMEOUT) -> Optional[dict]:
    """Perform a POST request with JSON body and return parsed JSON, or None on failure."""
    try:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        logger.warning("POST %s -> HTTP %d: %s", url, e.code, e.reason)
        return None
    except urllib.error.URLError as e:
        logger.warning("POST %s -> URLError: %s", url, e.reason)
        return None
    except Exception as e:
        logger.warning("POST %s -> error: %s", url, e)
        return None


def _http_delete(url: str, timeout: int = _REQUEST_TIMEOUT) -> Optional[dict]:
    """Perform a DELETE request and return parsed JSON, or None on failure."""
    try:
        req = urllib.request.Request(url, method="DELETE")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        logger.warning("DELETE %s -> HTTP %d: %s", url, e.code, e.reason)
        return None
    except urllib.error.URLError as e:
        logger.warning("DELETE %s -> URLError: %s", url, e.reason)
        return None
    except Exception as e:
        logger.warning("DELETE %s -> error: %s", url, e)
        return None


# ---------------------------------------------------------------------------
# VaultClient — async interface
# ---------------------------------------------------------------------------

class VaultClient:
    """
    Async client for the mK:a VaultHttpServer (Ktor CIO, :5565).

    Authorization is enforced server-side by CredentialGatekeeper; callers
    only need to supply the credential name and a natural-language context
    string describing why the credential is needed.

    All methods catch exceptions and return None/False/[] on failure so that
    the orchestrator can degrade gracefully when the Vault is unavailable.
    """

    def __init__(self, vault_url: str = VAULT_URL):
        self._base = vault_url.rstrip("/")
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    # ------------------------------------------------------------------
    # Internal helper: run sync HTTP call in thread executor
    # ------------------------------------------------------------------

    async def _run(self, fn, *args):
        """Run a synchronous HTTP function in the default thread executor."""
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, fn, *args)

    # ------------------------------------------------------------------
    # Async public methods
    # ------------------------------------------------------------------

    async def health(self) -> dict:
        """GET /vault/health — returns health dict or empty dict on failure."""
        result = await self._run(_http_get, f"{self._base}/vault/health")
        return result if isinstance(result, dict) else {}

    async def list_credentials(self) -> list:
        """GET /vault/list — returns list of credential names or [] on failure."""
        result = await self._run(_http_get, f"{self._base}/vault/list")
        if isinstance(result, list):
            return result
        if isinstance(result, dict):
            # Server may return {"credentials": [...]} or similar
            return result.get("credentials", result.get("names", []))
        return []

    async def get_catalog(self) -> list:
        """GET /vault/catalog — returns credential metadata (never values).

        Returns list of dicts with keys: name, description, injectAs, service, contexts, hint.
        Returns empty list on failure or if vault is locked.
        """
        result = await self._run(_http_get, f"{self._base}/vault/catalog")
        if isinstance(result, dict):
            return result.get("credentials", [])
        return []

    async def get_credential(self, name: str, context: str = "unknown") -> Optional[str]:
        """DEPRECATED: Credential values can no longer be read directly.
        Use proxy_request() instead."""
        logger.warning(f"get_credential() is deprecated — use proxy_request() instead")
        return None

    async def proxy_request(
        self,
        credential_name: str,
        url: str,
        method: str = "GET",
        inject_as: str = "bearer_header",
        context: str = "vault_proxy",
        headers: dict = None,
        body: str = None,
    ) -> Optional[dict]:
        """Make an authenticated request through the vault proxy.

        The vault injects the credential server-side. The credential value
        is never returned to the caller.

        Returns dict with keys: status (int), body (str), headers (dict)
        """
        try:
            payload = {
                "credentialName": credential_name,
                "url": url,
                "method": method,
                "injectAs": inject_as,
                "context": context,
            }
            if headers:
                payload["headers"] = headers
            if body:
                payload["body"] = body
            result = await self._run(
                _http_post, f"{self._base}/vault/proxy-http", payload
            )
            if isinstance(result, dict):
                return result
            return {"status": 0, "body": str(result), "headers": {}}
        except Exception as e:
            logger.warning(f"Vault proxy request failed: {e}")
            return None

    async def store_credential(
        self,
        name: str,
        value: str,
        metadata: Optional[dict] = None,
    ) -> bool:
        """
        POST /vault/store — store a credential in the Vault.

        Returns True on success, False on failure.
        """
        payload: dict = {"name": name, "value": value}
        if metadata:
            payload["metadata"] = metadata
        result = await self._run(_http_post, f"{self._base}/vault/store", payload)
        if result is None:
            return False
        if isinstance(result, dict):
            return result.get("success", result.get("ok", True))
        return bool(result)

    async def delete_credential(self, name: str) -> bool:
        """
        DELETE /vault/delete?name=<name> — remove a credential from the Vault.

        Returns True on success, False on failure.
        """
        params = urllib.parse.urlencode({"name": name})
        url = f"{self._base}/vault/delete?{params}"
        result = await self._run(_http_delete, url)
        if result is None:
            return False
        if isinstance(result, dict):
            return result.get("success", result.get("ok", True))
        return bool(result)

    async def authorize(self, name: str, context: str) -> dict:
        """
        POST /vault/authorize — ask CredentialGatekeeper whether access is
        permitted for the given credential and context string.

        Returns the authorization decision dict, or {} on failure.
        """
        payload = {"name": name, "context": context}
        result = await self._run(_http_post, f"{self._base}/vault/authorize", payload)
        return result if isinstance(result, dict) else {}

    async def get_policies(self) -> list:
        """GET /vault/policies — returns list of policy objects or [] on failure."""
        result = await self._run(_http_get, f"{self._base}/vault/policies")
        if isinstance(result, list):
            return result
        if isinstance(result, dict):
            return result.get("policies", [])
        return []

    async def close(self) -> None:
        """No persistent connection to close; included for API symmetry."""
        pass

    def __repr__(self) -> str:
        return f"VaultClient(base={self._base!r})"


# ---------------------------------------------------------------------------
# Module-level sync convenience functions (for non-async callers)
# ---------------------------------------------------------------------------

def fetch_credential(name: str, context: str = "unknown") -> Optional[str]:
    """DEPRECATED: Use proxy_request() instead."""
    logger.warning("fetch_credential() is deprecated — use proxy_request() instead")
    return None


def proxy_request(credential_name: str, url: str, method: str = "GET",
                  inject_as: str = "bearer_header", context: str = "vault_proxy") -> Optional[dict]:
    """Synchronous wrapper for vault proxy requests."""
    client = VaultClient()
    return asyncio.get_event_loop().run_until_complete(
        client.proxy_request(credential_name, url, method, inject_as, context)
    )


def store_credential(name: str, value: str, metadata: Optional[dict] = None) -> bool:
    """
    Synchronous wrapper: store a credential in the Vault.

    Args:
        name:     Credential name.
        value:    Plaintext secret value.
        metadata: Optional dict of additional metadata to store alongside.

    Returns:
        True on success, False on failure.
    """
    payload: dict = {"name": name, "value": value}
    if metadata:
        payload["metadata"] = metadata
    result = _http_post(f"{VAULT_URL}/vault/store", payload)
    if result is None:
        return False
    if isinstance(result, dict):
        return result.get("success", result.get("ok", True))
    return bool(result)


def list_credentials() -> list:
    """
    Synchronous wrapper: list all credential names stored in the Vault.

    Returns:
        List of credential name strings, or [] on failure.
    """
    result = _http_get(f"{VAULT_URL}/vault/list")
    if isinstance(result, list):
        return result
    if isinstance(result, dict):
        return result.get("credentials", result.get("names", []))
    return []
