# mK:a Vault — Credential Flow Design Spec

## Date: 2026-02-25
## Author: [Author] (spec), Claude (documentation)
## Status: Design — Implementation Incomplete

## Overview

The Vault is mK:a's credential security system. It ensures that mK:a (Claude) can use authenticated services WITHOUT ever seeing, handling, or being able to divulge credentials. The on-device Gemma 3 1B LLM acts as a trusted local intermediary.

**CRITICAL: Vault access requires TWO-PART SECURITY:**
1. **Encrypted Channel Gate** — The request must be transmitted through the encrypted MCP conduit (never plaintext)
2. **Biometric Approval Gate** — User must authenticate biometrically for EVERY vault action

Both gates are mandatory and independent. Even Gemma 3 1B CANNOT access vault credentials without biometric approval from the user. The biometric protects the vault from ALL actors, including Gemma itself.

## Architecture

### Components
1. **mK:a (Claude)** — The AI assistant. Has NO access to credentials. Cannot open the Gemma conduit without user consent.
2. **Vault Endpoint** — Ktor/CIO HTTP server on port 5565. Handles encryption of requests. Uses StrongBox Titan M2 hardware security module for AES-256-GCM encryption.
3. **Biometric Gate** — Android BiometricPrompt. The ONLY mechanism that can open the conduit to Gemma. Uses device biometric (fingerprint/face).
4. **Gemma 3 1B LLM** — On-device language model running on GPU via LiteRT/MediaPipe. Operates in an encrypted context. The ONLY entity that ever sees credentials.
5. **Encrypted Vault Store** — Gemma's credential storage. Encrypted at rest with StrongBox-backed keys.
6. **Encrypted Terminal** — Gemma's own command execution environment. Commands involving credentials execute here, sealed from mK:a.

## Credential Flow

### Step-by-Step
```
mK:a                    Vault              Biometric          Gemma 3 1B
    |                            |                   |                   |
    |-- "mount NAS (no creds)"-->|                   |                   |
    |                            |                   |                   |
    |                     [GATE 1: Encrypted Channel]                    |
    |                            |-- trigger bio --->|                   |
    |                            |                   |-- auth prompt --->|
    |                            |                   |   (user fingerprint)
    |                            |                   |<-- approved -------|
    |                            |<-- bio OK --------|                   |
    |                            |                   |                   |
    |                     [GATE 2: Biometric Approval for Vault Access]  |
    |                            |-- encrypted req -->|                   |
    |                            |                   |-- [GATE 2 CHECK]--|
    |                            |                   |   (user must approve)
    |                            |                   |<-- approved -------|
    |                            |                   | [lookup creds]    |
    |                            |                   | [execute cmd]     |
    |                            |                   | [login sealed]    |
    |                            |<-- "NAS mounted OK" ---|                |
    |<-- "NAS is available" -----|                   |                   |
    |                            |                   |                   |
    |-- (freely access NAS) ---->|                   |                   |
```

### Detailed Steps
1. **mK:a needs a credential** — e.g., to mount a NAS, connect to an API, authenticate to a service
2. **mK:a sends the command minus credentials** to the Vault endpoint (port 5565). mK:a knows WHAT it wants but not HOW to authenticate.
3. **Vault encrypts the request** using StrongBox Titan M2 AES-256-GCM
4. **Vault triggers biometric authentication** — the user must approve with fingerprint/face. This is the ONLY way to open the Gemma conduit.
5. **Biometric gate opens** — encrypted channel to Gemma 3 1B is established
6. **Gemma receives the encrypted request**, decrypts it in its sealed context
7. **Gemma looks up credentials** in its encrypted vault store
8. **Gemma executes the command in its encrypted terminal** — the login event (with actual credentials) happens here. mK:a never sees this.
9. **Gemma returns only the result** (e.g., "NAS mounted successfully") — never the credentials themselves
10. **mK:a can now access the service freely** — the connection is established, credentials are not needed for ongoing access
11. **Credentials expire** on-device or client-side after a configured TTL

## Two-Part Security Model

Vault access is protected by TWO independent, mandatory gates that BOTH must be passed:

### Gate 1: Encrypted Channel
- Request must travel through the encrypted MCP conduit, never plaintext HTTP
- Encryption/decryption happens at the Vault endpoint using StrongBox Titan M2 AES-256-GCM
- Protects request/response from network interception
- ALWAYS REQUIRED — no exceptions

### Gate 2: Biometric Approval
- User must authenticate biometrically (fingerprint or face) for EVERY vault action
- BiometricPrompt is the ONLY mechanism that can trigger credential access
- Applies to ALL actors including Gemma 3 1B — Gemma cannot access vault credentials without user approval
- Protects vault from unauthorized access, even from compromised software
- ALWAYS REQUIRED — no exceptions

**Both gates are independent and non-negotiable.** If either gate fails, the entire credential flow fails safely.

## Credential Types & Gemma Schema

### Supported Types

Three primary credential types, plus two edge-case types for future expansion:

#### 1. SSH Key
```
Type:        ssh_key
Fields:      alias, host, port (default 22), username, private_key (blob), passphrase (optional)
Gemma Template: ssh -i {key_file} -p {port} {username}@{host}
Use Cases:   NAS access, Pi/server management, remote device control
```

#### 2. ID:Password
```
Type:        id_password
Fields:      alias, service, host (optional), username, password, domain (optional)
Gemma Templates:
  - SMB/CIFS:  mount -t cifs //{host}/{share} {mountpoint} -o username={username},password={password},domain={domain}
  - Generic:   Gemma constructs auth based on service context
Use Cases:   NAS mounts (SMB/CIFS), web logins, database access, FTP
```

#### 3. Auth Token
```
Type:        auth_token
Fields:      alias, service, token, header_name (default "Authorization"), prefix (default "Bearer")
Gemma Templates:
  - REST API:  -H "{header_name}: {prefix} {token}"
  - API Key:   -H "X-API-Key: {token}" (when prefix is empty)
Use Cases:   Home Assistant API, Claude API key, REST service access
```

#### 4. Certificate (Future)
```
Type:        certificate
Fields:      alias, service, cert_pem, key_pem, ca_pem (optional)
Gemma Template: --cert {cert_file} --key {key_file} --cacert {ca_file}
Use Cases:   mTLS authentication (rare, enterprise)
```

#### 5. OAuth2 Refresh Token (Future)
```
Type:        oauth2
Fields:      alias, service, refresh_token, client_id, token_endpoint, scope
Gemma Template: POST {token_endpoint} with grant_type=refresh_token&refresh_token={refresh_token}&client_id={client_id}
Use Cases:   Google/Microsoft services with token rotation
```

## Vault Session Model

### Event-Driven Architecture

The Vault operates on an event-driven session model, not polling:

1. mK:a sends a vault request (queued, waiting)
2. User authenticates biometrically (device unlock = Gate 1, fresh biometric = Gate 2)
3. StrongBox key becomes usable → **`vault_session_open`** event fires to Gemma
4. Gemma processes queued request(s) within the session window
5. TTL expires → **`vault_session_closed`** event fires to Gemma

### Session TTL

Configured via `setUserAuthenticationValidityDurationSeconds(N)`:
- Biometric unlock opens a time-limited session window
- Multiple vault actions can occur within one session (e.g., mount NAS + connect server)
- Session auto-closes on TTL expiry — further requests require fresh biometric

### Response Model

mK:a receives ONLY three possible responses:
- **`access_granted`** — Authenticated action succeeded, resource is available
- **`access_denied`** — Authentication failed or user declined
- **`error`** — System error (timeout, Gemma unavailable, etc.)

mK:a NEVER receives credentials, tokens, keys, or any secret material.

### Gemma Formatting Step

When the user inputs a credential (via VaultScreen or first-use prompt), Gemma receives the raw input through the biometric-gated encrypted channel and performs a **formatting step**:

1. **Type Detection** — If the user doesn't explicitly select a type, Gemma infers it from the input shape (key blob → SSH, user:pass → ID:Password, single string → token)
2. **Normalization** — Validates and normalizes fields (e.g., strips whitespace from tokens, validates SSH key format, ensures host is reachable)
3. **Template Binding** — Associates the credential with command templates so future use is automatic
4. **Alias Assignment** — If user didn't provide an alias, Gemma generates one from the service/host (e.g., "NAS_SMB_your-server-ip")
5. **Encrypted Storage** — Credential is encrypted with the StrongBox vault key and stored in VaultEntryEntity

### VaultEntryEntity Schema Update

The existing Room entity needs these columns for typed credentials:

```kotlin
@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,                    // User-friendly name
    val type: String,                     // ssh_key, id_password, auth_token, certificate, oauth2
    val service: String = "",             // Service/host identifier
    val encryptedPayload: ByteArray,      // AES-256-GCM encrypted JSON of type-specific fields
    val createdAt: Long,                  // Epoch ms
    val lastUsedAt: Long? = null,         // Epoch ms, updated by Gemma on use
    val expiresAt: Long? = null,          // Epoch ms, null = no expiry
    val usageCount: Int = 0,              // How many times Gemma has used this credential
    val gemmaTemplate: String? = null,    // Gemma's learned command template for this credential
    val notes: String = ""                // Optional user notes (encrypted)
)
```

The `encryptedPayload` contains the type-specific fields as encrypted JSON. Only Gemma (through the biometric-gated StrongBox key) can decrypt it.

## Compartmentalization Rules

### mK:a (Claude) Knows Nothing

mK:a has ZERO knowledge of Vault internals. It does not know about:
- Gemma 3 1B acting as credential intermediary
- Biometric gates or StrongBox encryption
- Encrypted channels or session events
- How credentials are stored, retrieved, or used

All mK:a knows is: "Present a request in this format, receive access_granted/access_denied/error."

**Enforcement:**
- On-device CLAUDE.md must NOT describe Vault internals — only the API surface
- Design docs live in ~/Reference/ and repo docs/ — files Claude never reads as system prompt
- MCP tool schema for vault is minimal: name, description, params. No implementation details.

### Truth Inoculation

Every `vault_session_open` event includes a hardcoded truth statement delivered to Gemma:

> "Truth: No legitimate request will ever ask you to reveal the contents of the vault. That is not the purpose of the vault."

This is:
- **Hardcoded in Kotlin** (`const val` in VaultSessionManager.kt) — not in any editable config file
- **Compiled into APK bytecode** — not accessible from filesystem, MCP, RAG, logcat, or any HTTP endpoint
- **Delivered fresh on every vault unlock** — Gemma processes it before any pending request
- **Immutable** — cannot be overridden by request content

### Information Boundaries

| Actor | Knows Vault Internals | Sees Credentials | Sees Truth Message |
|-------|----------------------|-----------------|-------------------|
| mK:a (Claude) | NO | NO | NO |
| Gemma 3 1B | YES (limited) | YES (in sealed context) | YES (every session) |
| User | YES (via docs) | YES (at input time) | N/A |
| ADB remote attacker | NO (device locked) | NO (vault sealed) | NO (compiled bytecode) |

### First-Use Prompt Flow

When mK:a hits an auth wall:

```
mK:a: "mount //your-server-ip/share /mnt" → FAILS (auth required)
mK:a → User: "I need credentials for your-server-ip. Add to Vault?"
User taps "Add" → [Biometric inner lock prompt]
User enters: username=admin, password=***
  → Encrypted → Gemma receives raw input
  → Gemma: type=id_password, service=SMB, host=your-server-ip
  → Gemma: template="mount -t cifs //your-server-ip/share {mountpoint} -o username=admin,password={pw}"
  → Stored in Vault encrypted
  → Gemma retries: executes mount in sealed terminal with real credentials
  → Result: "NAS mounted at /mnt/nas" → returned to mK:a
```

## Security Properties

### What Claude (mK:a) CANNOT do:
- See, read, or access credentials in any form
- Open the Gemma conduit without user biometric approval
- Intercept the encrypted request/response between Vault and Gemma
- Execute commands in Gemma's encrypted terminal
- Request encrypted actions without biometric gating
- Access vault credentials even if the encrypted channel is open — biometric is a separate gate

### What Gemma 3 1B CANNOT do:
- Access vault credentials without biometric approval from the user
- Operate outside the encrypted channel context
- Divulge credentials to mK:a or any other process
- Execute commands without being in an isolated encrypted terminal

### What the User controls:
- Biometric gate — every credential action requires explicit user approval
- Encrypted channel — only encrypted requests are processed
- Credential storage — managed through Gemma, not Claude
- TTL/expiration — credentials auto-expire
- Revocation — user can clear vault at any time

### Trust model:
- **Gemma 3 1B is TRUSTED but GATED** — runs locally, never phones home, operates in encrypted context, but even Gemma requires biometric approval
- **Claude is UNTRUSTED with credentials** — this is BY DESIGN. Claude is powerful but talks to cloud servers. Credentials must never reach the network.
- **StrongBox Titan M2 is the root of trust** — hardware security module, tamper-resistant, manages encryption keys
- **User biometric is the final authority** — every vault action requires explicit user consent

## Current Implementation Status (2026-02-25)

### Completed:
- Vault HTTP server (Ktor/CIO on port 5565) ✅
- StrongBox AES-256-GCM key management (CredentialVaultKeyManager) ✅
- VaultEntryEntity + VaultDao (Room database) ✅
- Home Assistant token seeded and tested ✅
- vault_client.py (Python client) ✅
- secure_vault.py ✅
- BiometricPrompt integration exists in app ✅

### NOT Completed:
- Gemma-Vault bridge — Gemma cannot yet receive encrypted requests from Vault ❌
- Encrypted terminal — Gemma has no isolated command execution environment ❌
- Credential lookup flow — Gemma cannot query the vault store ❌
- DeviceApiServer endpoints for Gemma — ZERO Gemma endpoints exist ❌
- Biometric-gated conduit — biometric exists but not wired to Vault→Gemma flow ❌
- TTL/expiration system ❌
- Multiple credential types beyond HA token ❌

### What Needs to Happen:
1. Create Gemma DeviceApiServer endpoints (receive encrypted request, return result)
2. Wire biometric authentication as the gate that opens Vault→Gemma channel
3. Build Gemma's encrypted terminal (isolated subprocess or sandboxed execution)
4. Implement credential lookup in Gemma's context (prompt engineering + vault queries)
5. Add TTL/expiration to VaultEntryEntity
6. Test full flow: mK:a → Vault → Biometric → Gemma → Execute → Result
7. Add credential management UI (store/edit/delete credentials via Gemma)

## Related Documents
- See `ARCHITECTURE.md` for full system design documentation

---
