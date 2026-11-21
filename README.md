# RYN-AI-Speed v2.0

**AI-Powered Internet Management Platform (Android + Windows)**

RYN-AI-Speed is a modular, security-first internet management platform uniting six independent core engines under a shared architectural contract:

1. **Download Engine** (Priority #1 — IDM-grade multi-connection segmented downloader with work-stealing, sparse pre-allocation, strict `ETag`/`If-Range` integrity, and atomic completion)
2. **VPN Engine** (WireGuard protocol, on-device key generation, Private + Verified Community Servers, OS-level Kill Switch, DNS/IPv6 leak protection)
3. **Network Optimizer** (Real Ping, Jitter, Packet Loss, DNS benchmarking, and pre/post-VPN telemetry)
4. **AI Assistant & AI Auto Manager** (Multi-provider AI Router with strict JSON Schema tool registry, 3-tier RBAC permissions, and offline local rules fallback)
5. **Built-in Browser & External Browser Integration** (Hardened Android WebView & Windows WebView2 integrated directly with the Download Engine)
6. **Central Backend** (Python FastAPI + PostgreSQL for Auth, Verified Server Registry, Health Monitoring, AI Routing, and Signed Release Config)

---

## Visual Identity
- **Default Theme:** Dark Mode (`#0B1114` / `#111A1E`)
- **Primary Accent:** Emerald Green (`#10B981` / `#059669`)
- **Languages:** Arabic (RTL) & English (LTR)

---

## Current Status: Phase P0 Complete (Architecture, Contracts & Threat Model)

This repository package contains the complete, validated **Phase P0** engineering foundation:

- `docs/ARCHITECTURE.md` — Repository assessment, hardened system architecture, architectural upgrades over v2.0 baseline, and module dependency diagrams
- `docs/DATABASE.md` — Local (SQLCipher/SQLite) & Central (PostgreSQL) storage policy, migration strategy, and privacy rules
- `docs/API.md` — REST API design, Ed25519 JWT + Refresh Token Rotation, rate limiting, and error taxonomy
- `docs/DOWNLOAD_ENGINE.md` — Technical design of the IDM-grade Download Engine (work-stealing, sparse pre-allocation, SSRF/path-traversal defense)
- `docs/SECURITY.md` — STRIDE threat model, cryptography standards, OS-level Kill Switch design, and AI permission boundaries
- `docs/BUILD_STRATEGY.md` — Android (Kotlin/Compose/Gradle Catalog) & Windows (C#/.NET 10 LTS/WinUI 3/MSIX) build, signing, and CI/CD blueprint
- `docs/ROADMAP_TICKETS.md` — Phase-by-phase implementation tickets (`P0` through `P11`) with strict acceptance criteria
- `shared/schemas/schema_sqlite.sql` — Validated SQLite / SQLCipher DDL (v1.0.0)
- `shared/schemas/schema_postgres.sql` — Validated PostgreSQL 16+ DDL (v1.0.0)
- `shared/api-spec/openapi.yaml` — OpenAPI 3.1.0 machine-readable API contract
- `shared/contracts/ai_tools_schema.json` — Strict JSON Schema definitions for all 12 AI tools
- `shared/contracts/download_contract.json` — Cross-platform Download Engine state & segment contract

---

## Core Engineering Rules
- **Fault Isolation:** Failure in any single module must never crash the host application.
- **No Fake Functionality:** All speeds, pings, VPN states, and download metrics must come from real engines.
- **Zero Secrets in Git:** Private keys, signing certificates, API keys, and `.env` files are strictly excluded.
- **Phase Gating:** No phase begins until the previous phase passes its acceptance criteria.
