# RYN-AI-Speed v2.0 — Phase-by-Phase Implementation Tickets

**Execution Rule:** Phases run strictly sequentially (`P0` -> `P11`). No phase may be marked complete until 100% of its acceptance criteria pass real verification.

---

## Phase P0: Requirements, Hardened Architecture & Contracts (CURRENT — COMPLETE)
- **T-P0-01:** Repository assessment & security-hardened `.gitignore`.
- **T-P0-02:** Final Architecture Document & Module Dependency Diagrams (`docs/ARCHITECTURE.md`).
- **T-P0-03:** Encrypted SQLite (SQLCipher) & PostgreSQL 16 schemas (`docs/DATABASE.md`, `shared/schemas/`).
- **T-P0-04:** OpenAPI 3.1.0 Backend Contract (`docs/API.md`, `shared/api-spec/openapi.yaml`).
- **T-P0-05:** Download Engine Technical Design & State Contract (`docs/DOWNLOAD_ENGINE.md`, `shared/contracts/download_contract.json`).
- **T-P0-06:** STRIDE Security Threat Model & AI Tool JSON Schemas (`docs/SECURITY.md`, `shared/contracts/ai_tools_schema.json`).
- **T-P0-07:** Android & Windows Build/CI Strategy (`docs/BUILD_STRATEGY.md`).
- **Acceptance Gate:** All SQL, YAML, and JSON contracts pass automated syntax, schema, and relational integrity validation with zero errors.

---

## Phase P1: Repository & Platform Foundation
- **T-P1-01:** Scaffold Android Gradle multi-module project (`android/`), `libs.versions.toml`, Hilt DI, Dark/Emerald theme tokens, RTL/LTR localization (`ar`, `en`), and Room + SQLCipher base DB.
- **T-P1-02:** Scaffold Windows `.NET 10 LTS` + WinUI 3 solution (`windows/RynSpeed.sln`), `Directory.Packages.props`, DI container, Dark/Emerald theme, RTL/LTR localization, and SQLite + SQLCipher base DB.
- **T-P1-03:** Scaffold FastAPI backend skeleton (`backend/`) with health endpoint, Pydantic settings, and Alembic initial migration.
- **T-P1-04:** Add GitHub Actions CI workflows (`.github/workflows/ci-contracts.yml`, `ci-android.yml`, `ci-windows.yml`, `ci-backend.yml`, `security-scan.yml`).
- **Acceptance Gate:** Both Android and Windows apps compile cleanly, run unit tests, initialize encrypted SQLCipher DB, and pass CI green.

---

## Phase P2: Download Engine (Priority #1)
- **T-P2-01:** Implement SSRF-safe URL validator, filename sanitizer, and canonical path containment guard (`core/security`).
- **T-P2-02:** Implement Smart Probe (`HEAD` + `Range: bytes=0-0` fallback), `ETag`/`Last-Modified` extraction, and disk-space check.
- **T-P2-03:** Implement Sparse `.ryntmp` Pre-Allocation + Direct Offset Writes (`FileChannel` on Android, `RandomAccess.Write` on Windows) + Dynamic Work-Stealing Segment Coordinator.
- **T-P2-04:** Implement `If-Range` safe resume, exponential backoff with jitter (`429`/`503`), Token-Bucket speed limiter, priority queue, scheduler, and SHA-256 atomic finalization.
- **T-P2-05:** Build Download Manager & Download Details UI (Dark/Emerald) with real-time per-segment visualization.
- **Acceptance Gate:** Automated tests pass for small/large files, forced mid-download disconnect + resume, file change on server (`If-Range` reset), non-range fallback, `403`/`429` handling, and SHA-256 verification on both Android & Windows.

---

## Phase P3: Built-in Browser & External Browser Integration
- **T-P3-01:** Android hardened `WebView` (multi-tab, bookmarks, history, privacy wipe, zero file-access leaks) + Share Intent & clipboard link capture.
- **T-P3-02:** Windows hardened `WebView2` (multi-tab, isolated `UserDataFolder`, permission prompts, download event interception) + clipboard capture + optional browser extension messaging bridge.
- **T-P3-03:** Download dialog handoff to Download Engine with fallback to native browser download for unsupported streams.
- **Acceptance Gate:** Clicking downloadable links inside the built-in browser or sharing links from external apps hands off cleanly to the Download Engine with full progress visibility.

---

## Phase P4: Network Optimizer
- **T-P4-01:** Implement real ICMP/TCP Ping, Jitter, Packet Loss, and multi-resolver DNS benchmark engine (Cloudflare, Quad9, Google, ISP).
- **T-P4-02:** Implement real Download/Upload speed test and continuous Connection Stability Monitor (Wi-Fi <-> Mobile Data / Ethernet handoff detection).
- **T-P4-03:** Implement pre-VPN vs post-VPN comparison analytics persisted in `network_tests`.
- **Acceptance Gate:** Reproducible real measurements verified on live connections with zero fabricated numbers.

---

## Phase P5: VPN Engine (WireGuard)
- **T-P5-01:** Implement on-device Curve25519 key generation sealed in Android Keystore / Windows DPAPI.
- **T-P5-02:** Android `VpnService` + `wireguard-android` tunnel + Lockdown Kill Switch + IPv6 blackhole route.
- **T-P5-03:** Windows `Wintun`/WireGuard service + Windows Filtering Platform (WFP) persistent Kill Switch + SMHNR DNS leak mitigation.
- **T-P5-04:** Weighted Server Auto-Selector (Ping + Jitter + Loss + Capacity + Historical Stability) and post-connect exit-IP/DNS verification.
- **Acceptance Gate:** Real WireGuard tunnel connects/disconnects/switches cleanly on both platforms; DNS leak, IPv6 leak, and abrupt-drop Kill Switch packet tests pass 100%.

---

## Phase P6: Backend & Server Management
- **T-P6-01:** Implement Argon2id Auth, Ed25519 JWT, Refresh Token Rotation with Reuse Detection, and Device Management.
- **T-P6-02:** Implement Verified VPN Server Registry (Private + Vetted Community), Ed25519-signed server manifests, and background health probe worker.
- **T-P6-03:** Implement Redis sliding-window Rate Limiter, Idempotency-Key guard, structured log redactor, and `/config` + `/version` endpoints.
- **Acceptance Gate:** Full pytest suite passes against live PostgreSQL 16 & Redis; client apps authenticate and sync signed server lists cleanly.

---

## Phase P7: AI Assistant & Multi-Provider Router
- **T-P7-01:** Implement Backend Multi-Provider AI Router (OpenAI, Gemini, Anthropic, OpenAI-compatible) with automatic provider failover and quota tracking.
- **T-P7-02:** Implement Client Local Rules Engine (offline Ping/DNS/error diagnosis & safe connection tuning) + 3-layer local memory (`Session`, `Preferences`, `Operational History`).
- **T-P7-03:** Implement Strict AI Tool Executor enforcing `ai_tools_schema.json`, 3-tier RBAC (`READ`, `SAFE_ACTION`, `PROTECTED_ACTION` UI confirmation nonce), and immutable audit logging.
- **Acceptance Gate:** Arabic & English commands execute real tools safely; unauthorized or unconfirmed `PROTECTED_ACTION` calls are strictly blocked and logged.

---

## Phase P8: AI Auto Manager
- **T-P8-01:** Background network quality & download failure monitor with battery-aware intervals.
- **T-P8-02:** Automated safe recovery (download retry, VPN switch suggestions, actionable alerts) with a global master kill-switch toggle to disable all automation instantly.
- **Acceptance Gate:** Automated monitoring acts strictly within user-granted permissions and halts immediately when master toggle is switched off.

---

## Phase P9: Security & Privacy Hardening Audit
- **T-P9-01:** Execute full security test suite (path traversal, SSRF, secret leakage in logs/APK/MSIX, auth token reuse, rate limiting, WebView/WebView2 injection, AI prompt-injection/permission bypass).
- **T-P9-02:** Publish in-app transparent Privacy Policy & 1-tap Account/Data Deletion flow.
- **Acceptance Gate:** Zero Critical or High security findings remain open.

---

## Phase P10: Integration & Performance Testing
- **T-P10-01:** End-to-end integration tests across all 6 engines simultaneously (VPN active + multi-connection download + browser + AI assistant).
- **T-P10-02:** Profile and optimize RAM, CPU, battery drain, cold startup time, VPN connect latency, and download recovery speed.
- **Acceptance Gate:** All performance budgets and long-duration soak tests pass without memory leaks or crashes.

---

## Phase P11: Release & Deployment (v1.0.0)
- **T-P11-01:** Build signed Android APK (v2/v3) & signed Windows MSIX (`x64`/`arm64`) via GitHub Actions.
- **T-P11-02:** Verify update check, signature verification, DB migration backup/restore, and rollback recovery.
- **T-P11-03:** Publish `v1.0.0` release with SHA-256 checksums and verified Release Notes.
- **Acceptance Gate:** Signed installers install, upgrade, and run cleanly on fresh Android and Windows machines.
