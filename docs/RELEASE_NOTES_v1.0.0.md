# RYN-AI-Speed v1.0.0 — Release Notes

**Release Version:** `1.0.0` (SemVer)  
**Platforms:** Android (Kotlin + Jetpack Compose) & Windows (C# + .NET 10 LTS + WinUI 3) + Central Backend (FastAPI + PostgreSQL 16)  
**Theme:** Dark Mode / Emerald Green — Arabic (RTL) & English (LTR)

---

## What's Included in v1.0.0

- **Advanced Download Engine (Priority #1):** SSRF-safe probing with redirect re-validation, Dynamic Work-Stealing segmentation, sparse `.ryntmp` pre-allocation with lock-free direct offset writes (`FileChannel` / `RandomAccess.Write`), strict `ETag`/`If-Range` resume integrity, Token-Bucket throttling, SHA-256 atomic finalization, and Windows Mark-of-the-Web (`Zone.Identifier`).
- **Built-in Browser & External Integration:** Hardened Android `WebView` and isolated Windows `WebView2` with default-deny permissions, zero password logging, download interception routed directly to `RynDownloadEngine`, Android Share Intent receiver, and CSRF-protected Windows extension bridge.
- **Real Network Optimizer:** Live TCP Ping, Jitter, Packet Loss, multi-resolver DNS benchmark, real download/upload speed test, Pre/Post-VPN delta analytics, and live connectivity transition monitoring.
- **Hardened WireGuard VPN Engine:** On-device Curve25519 keys, weighted server auto-selector rejecting unverified community nodes, DNS & IPv6 (`::/0`) leak protection, and OS-level Kill Switch (`VpnService.setBlocking(true)` & Windows WFP + SMHNR guard).
- **Central Backend & Server Registry:** Argon2id password hashing, Ed25519 JWT (15-min TTL), Refresh Token Rotation with Reuse-Detection family revocation, Ed25519-signed VPN server manifests (`X-Manifest-Signature`), sliding-window Rate Limiting, and Idempotency-Key replay guard.
- **AI Assistant & AI Auto Manager:** Multi-Provider AI Router (OpenAI, Gemini, Anthropic, Local Offline Rules) supporting Arabic & English, context sanitizer, strict 12-tool whitelist with 3-tier RBAC and single-use confirmation Nonces for `PROTECTED_ACTION`, and an instant Master Kill Switch for all automation.
- **Security, Privacy & Automated CI/CD:** Encrypted SQLCipher AES-256 local storage sealed in Android Keystore / Windows DPAPI, transparent Privacy Policy, and full automated Contract, Security, Integration, Performance, and Signed Release GitHub Actions pipelines.
