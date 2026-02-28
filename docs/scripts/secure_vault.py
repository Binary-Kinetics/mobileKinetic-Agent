#!/data/user/0/com.mobilekinetic.agent/files/usr/bin/python3
"""
Secure Vault - Encrypted credential storage
============================================
Stores sensitive data (passwords, API keys, tokens) encrypted at rest.

Features:
- AES-256 encryption via Fernet (symmetric encryption)
- Password-based key derivation (PBKDF2)
- Individual file encryption per secret
- Only decrypts when explicitly requested

Usage:
    python3 secure_vault.py store <name> <value> [password]
    python3 secure_vault.py get <name> [password]
    python3 secure_vault.py list
    python3 secure_vault.py delete <name>
"""

import sys
import os
import json
import base64
import hashlib
from pathlib import Path
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

VAULT_DIR = Path.home() / ".secure" / "vault"
SALT_FILE = Path.home() / ".secure" / ".salt"

def get_salt():
    """Get or create salt for key derivation."""
    if SALT_FILE.exists():
        return SALT_FILE.read_bytes()
    else:
        salt = os.urandom(16)
        SALT_FILE.parent.mkdir(parents=True, exist_ok=True)
        SALT_FILE.write_bytes(salt)
        os.chmod(SALT_FILE, 0o600)
        return salt

def derive_key(password: str) -> bytes:
    """Derive encryption key from password."""
    salt = get_salt()
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=100000,
    )
    key = base64.urlsafe_b64encode(kdf.derive(password.encode()))
    return key

def encrypt_value(value: str, password: str) -> bytes:
    """Encrypt a value with password."""
    key = derive_key(password)
    f = Fernet(key)
    return f.encrypt(value.encode())

def decrypt_value(encrypted: bytes, password: str) -> str:
    """Decrypt a value with password."""
    key = derive_key(password)
    f = Fernet(key)
    try:
        return f.decrypt(encrypted).decode()
    except Exception:
        raise ValueError("Decryption failed - incorrect password or corrupted data")

def store_secret(name: str, value: str, password: str):
    """Store an encrypted secret."""
    VAULT_DIR.mkdir(parents=True, exist_ok=True)

    encrypted = encrypt_value(value, password)

    secret_file = VAULT_DIR / f"{name}.enc"
    secret_file.write_bytes(encrypted)
    os.chmod(secret_file, 0o600)

    # Store metadata (not encrypted, just for listing)
    meta_file = VAULT_DIR / f"{name}.meta"
    meta = {
        "name": name,
        "created": str(Path(secret_file).stat().st_mtime),
        "size": len(encrypted)
    }
    meta_file.write_text(json.dumps(meta, indent=2))
    os.chmod(meta_file, 0o600)

    print(f"✓ Secret '{name}' stored securely")
    print(f"  Location: {secret_file}")
    print(f"  Size: {len(encrypted)} bytes (encrypted)")

def get_secret(name: str, password: str) -> str:
    """Retrieve and decrypt a secret."""
    secret_file = VAULT_DIR / f"{name}.enc"

    if not secret_file.exists():
        raise FileNotFoundError(f"Secret '{name}' not found")

    encrypted = secret_file.read_bytes()
    decrypted = decrypt_value(encrypted, password)

    return decrypted

def list_secrets():
    """List all stored secrets (names only)."""
    if not VAULT_DIR.exists():
        print("No secrets stored yet")
        return

    secrets = []
    for meta_file in VAULT_DIR.glob("*.meta"):
        try:
            meta = json.loads(meta_file.read_text())
            secrets.append(meta)
        except Exception:
            pass

    if not secrets:
        print("No secrets stored yet")
        return

    print(f"Stored secrets ({len(secrets)}):")
    print("-" * 60)
    for meta in sorted(secrets, key=lambda x: x['name']):
        print(f"  {meta['name']:<30} ({meta['size']} bytes)")

def delete_secret(name: str):
    """Delete a secret."""
    secret_file = VAULT_DIR / f"{name}.enc"
    meta_file = VAULT_DIR / f"{name}.meta"

    if not secret_file.exists():
        raise FileNotFoundError(f"Secret '{name}' not found")

    secret_file.unlink()
    if meta_file.exists():
        meta_file.unlink()

    print(f"✓ Secret '{name}' deleted")

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    command = sys.argv[1].lower()

    try:
        if command == "store":
            if len(sys.argv) < 4:
                print("Usage: secure_vault.py store <name> <value> [password]")
                sys.exit(1)

            name = sys.argv[2]
            value = sys.argv[3]
            password = sys.argv[4] if len(sys.argv) > 4 else os.environ.get("VAULT_PASSWORD", "")

            if not password:
                print("Error: Password required (provide as argument or set VAULT_PASSWORD env var)")
                sys.exit(1)

            store_secret(name, value, password)

        elif command == "get":
            if len(sys.argv) < 3:
                print("Usage: secure_vault.py get <name> [password]")
                sys.exit(1)

            name = sys.argv[2]
            password = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("VAULT_PASSWORD", "")

            if not password:
                print("Error: Password required (provide as argument or set VAULT_PASSWORD env var)")
                sys.exit(1)

            value = get_secret(name, password)
            print(value)

        elif command == "list":
            list_secrets()

        elif command == "delete":
            if len(sys.argv) < 3:
                print("Usage: secure_vault.py delete <name>")
                sys.exit(1)

            name = sys.argv[2]
            delete_secret(name)

        else:
            print(f"Unknown command: {command}")
            print(__doc__)
            sys.exit(1)

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
