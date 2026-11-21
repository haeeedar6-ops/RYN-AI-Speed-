# RYN-AI-Speed — Security Architecture & STRIDE Threat Model

**Classification:** Mandatory Security Baseline  
**Scope:** Android Client, Windows Client, FastAPI Backend, WireGuard VPN Fleet, AI Router & Tool Registry

---

## 1. STRIDE Threat Model & Mitigations

### 1.1 DNS & IPv6 Leaks (Information Disclosure)
- **Threat:** User DNS queries or IPv6 packets bypass the WireGuard tunnel and leak to the local ISP.
- **Mitigation:**
  - Force tunnel-only DNS resolvers inside the WireGuard interface configuration (`1.1.1.1`, `9.9.9.9`, or server-local Unbound).
  - On IPv4-only VPN servers, explicitly route `::/0` into a blackhole / drop rule so IPv6 never falls back to the physical interface.
  - On Windows, disable Smart Multi-Homed Name Resolution (SMHNR) for tunnel interfaces.
  - Automated post-connect verification probe confirms exit IP and DNS resolver IP match the VPN server before displaying `Connected`.

### 1.2 Tunnel Drop & Traffic Exposure (Information Disclosure)
- **Threat:** VPN tunnel drops unexpectedly or app crashes, exposing real IP during active downloads.
- **Mitigation (OS-Level Kill Switch):**
  - **Android:** `VpnService.Builder.setBlocking(true)` + guide/enforce Android Always-on VPN with "Block connections without VPN" (`isLockdownEnabled`).
  - **Windows:** **Windows Filtering Platform (WFP)** sublayer filters with `FWPM_FILTER_FLAG_PERSISTENT` allowing outbound traffic ONLY on the Wintun interface and to the WireGuard endpoint IP/UDP port.
  - **Verification Rule:** Kill Switch is NEVER marked ready by UI toggle alone; P5/P9 require real packet-capture leak tests simulating abrupt tunnel termination.

### 1.3 VPN Key Theft & Server Impersonation (Spoofing / Elevation)
- **Threat:** Attacker steals private WireGuard keys from GitHub, Backend DB, logs, or device storage, or spoofs a VPN server.
- **Mitigation:**
  - Client WireGuard private keys are generated strictly **on-device** (Curve25519) and sealed in **Android Keystore** / **Windows DPAPI**. They are NEVER transmitted to Backend or AI.
  - Server public keys are distributed over TLS 1.3 with signed server registry manifests (`Ed25519` signature verified by the client) to prevent MITM server spoofing.
  - Community Servers undergo mandatory vetting (operator identity, no-logs policy, WireGuard support, verified geolocation, SLA) and are clearly tagged `COMMUNITY` vs `PRIVATE`.

### 1.4 Malicious Download Links, SSRF & Path Traversal (Tampering / RCE)
- **Threat:** Attacker supplies `../../system/evil.dll`, `file:///etc/passwd`, or internal `http://169.254.169.254/` URLs.
- **Mitigation:**
  - Strict scheme allowlist (`http`, `https`).
  - Pre-connect and post-redirect IP validation blocking loopback, link-local, and RFC 1918 ranges by default.
  - Filename sanitization removing path separators, control chars, BiDi overrides, and Windows reserved device names, followed by canonical root-path containment verification.
  - Mark downloaded files on Windows with **Mark-of-the-Web (`Zone.Identifier` ADS)** so Windows Defender / SmartScreen inspects executables safely.

### 1.5 Credential Theft & API Abuse (Spoofing / DoS)
- **Threat:** Brute-force login, stolen JWT replay, credential stuffing, or API flooding.
- **Mitigation:**
  - Passwords hashed with **Argon2id** (`time_cost=3`, `memory_cost=65536 KiB`, `parallelism=4`).
  - Short-lived **Ed25519 (`EdDSA`)** Access Tokens (15 minutes).
  - Opaque **Refresh Tokens** stored as SHA-256 hashes in PostgreSQL with **Rotation & Reuse Detection** (any reuse of a previously rotated token revokes the entire session family immediately).
  - Redis sliding-window Rate Limiting per IP and per User ID on all endpoints (strictest on `/auth/*` and `/ai/*`).
  - `Idempotency-Key` enforcement on sensitive POST endpoints.

### 1.6 AI Prompt Injection & Unauthorized Tool Execution (Elevation of Privilege)
- **Threat:** Malicious web page, filename, or user prompt tricks the AI into executing unauthorized actions or exfiltrating secrets.
- **Mitigation:**
  - **Strict Tool Whitelist:** Only the 12 tools defined in `shared/contracts/ai_tools_schema.json` exist. Zero shell execution, zero arbitrary file reads, zero arbitrary network calls.
  - **3-Tier RBAC Permission Model:**
    - `READ`: Read-only telemetry (`get_network_stats`, `get_dns_info`, `list_vpn_servers`, `get_downloads`, `get_server_health`).
    - `SAFE_ACTION`: Bounded reversible actions (`run_speed_test`, `pause_download`, `resume_download`, `set_download_limit`).
    - `PROTECTED_ACTION`: High-impact actions (`connect_vpn`, `disconnect_vpn`, `cancel_download`) **ALWAYS** require an explicit user confirmation tap generating a single-use short-lived confirmation nonce before execution.
  - **Context Minimization:** Never send raw browsing history, passwords, VPN private keys, or full local file paths to external AI providers. Strip sensitive fields before routing.
  - **Immutable Audit Log:** Every AI tool invocation is recorded in `app_events` with actor, tool name, sanitized args, permission tier, and outcome.

### 1.7 Built-in Browser Isolation
- **Android WebView:** Disable `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, and `allowUniversalAccessFromFileURLs`. Enable Safe Browsing. Isolate cookies/storage per profile with 1-tap wipe.
- **Windows WebView2:** Dedicated isolated `UserDataFolder`, strict permission handler prompting for Camera/Mic/Location (default deny), never log form inputs or passwords.

---

## 2. Cryptographic Standards Summary

- **Transport Security:** TLS 1.3 minimum (TLS 1.2 fallback strictly disabled for Backend API).
- **Local Database Encryption:** SQLCipher (AES-256, PBKDF2-HMAC-SHA512 256,000 iterations).
- **Secret Storage:** Android Keystore (StrongBox/TEE) & Windows DPAPI / CNG.
- **Password Hashing:** Argon2id.
- **Token Signing:** Ed25519 (`EdDSA`).
- **File & Update Integrity:** SHA-256 checksums + signed release artifacts (APK v2/v3 signing, Windows Authenticode MSIX signing).

---

## 3. Log Redaction Policy

Every structured logger (Android, Windows, Backend) passes through a mandatory **Redaction Filter** that masks:
- `Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key` headers
- JWTs (`eyJ...`), WireGuard keys (44-char base64 keys), passwords, and tokens
- Query string parameters matching `token`, `key`, `secret`, `sig`, `password`, `auth`
