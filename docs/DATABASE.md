# RYN-AI-Speed — Database Architecture & Storage Policy

**Local Engine:** SQLite 3.45+ with **SQLCipher** (AES-256 encryption at rest, WAL mode, `PRAGMA foreign_keys = ON`)  
**Central Engine:** PostgreSQL 16+ (Backend only)  
**Machine-Readable Schemas:**
- `shared/schemas/schema_sqlite.sql`
- `shared/schemas/schema_postgres.sql`

---

## 1. Storage & Privacy Policy (Local-First)

- **Strictly On-Device (Never Uploaded to Backend):**
  - `downloads`, `download_segments`, `download_history`
  - `browser_bookmarks`, `browser_history`
  - `ai_sessions`, `ai_messages`, `ai_preferences`
  - Local `settings` and `app_events`
  - WireGuard client private keys (stored in **Android Keystore** / **Windows DPAPI**, NEVER in SQLite or PostgreSQL).
- **Central Backend (PostgreSQL 16+):**
  - `users` & `user_devices` (account identity, Argon2id password hash, device public keys)
  - `refresh_tokens` (SHA-256 hashed opaque tokens with token-family tracking for reuse detection)
  - `vpn_servers` & `vpn_server_health_logs` (verified private & community server registry, public keys, latency/packet-loss telemetry — ZERO user browsing/traffic logs)
  - `app_configs` & `app_releases` (signed version manifests, policy versions)
  - `ai_usage_quotas` (rate/token accounting without storing raw user private URLs)

---

## 2. Core Local Tables (14 Tables + Migration Tracker)

1. `schema_migrations` — Tracks applied migration versions and checksums.
2. `users` — Cached local profile metadata (no plaintext passwords).
3. `settings` — Typed key-value configuration (`theme`, `language`, `max_concurrent_downloads`, `kill_switch_enabled`, `ai_auto_manager_enabled`).
4. `downloads` — Active & queued downloads with `etag`, `last_modified`, `sha256_expected`, `sha256_actual`, `supports_ranges`, `priority`, `speed_limit_bps`, `temp_path`, `save_path`, `status`, `retry_count`, `last_error`.
5. `download_segments` — Byte-range segments (`start_byte`, `end_byte`, `downloaded_bytes`, `status`, `retry_count`, `last_error`) with `ON DELETE CASCADE` to `downloads`.
6. `download_history` — Completed/archived download audit log.
7. `vpn_servers` — Local cache of verified servers (`server_id`, `server_name`, `country`, `region`, `endpoint`, `public_key`, `protocol`, `server_type`, `capacity_status`, `health_status`, `latency_ms`, `packet_loss_pct`, `provider`, `privacy_policy_url`).
8. `vpn_sessions` — Local connection history (connect/disconnect timestamps, bytes transferred, exit server).
9. `network_tests` — Real Ping, Jitter, Packet Loss, DNS resolution, Download/Upload Mbps, and `vpn_active` flag (for pre/post-VPN comparison).
10. `ai_sessions` — Conversation threads with title and timestamps.
11. `ai_messages` — User, assistant, and tool-result messages bound to `ai_sessions`.
12. `ai_preferences` — User preferences for AI Auto Manager, confirmation modes, and preferred providers.
13. `browser_bookmarks` — Saved URLs, folders, titles, and favicon hashes.
14. `browser_history` — Local browsing history with 1-click purge support.
15. `app_events` — Structured audit log for security events, AI tool executions, and fault isolation telemetry.

---

## 3. Migration & Backup Strategy

- **Versioned Forward-Only Migrations:**
  - Android: Room `AutoMigration` + explicit `Migration(from, to)` classes tested with `MigrationTestHelper` using exported JSON schemas in CI.
  - Windows: Embedded SQL migration scripts executed inside an exclusive transaction against `schema_migrations`.
  - Backend: **Alembic** migrations verified on ephemeral PostgreSQL containers in CI.
- **Zero-Data-Loss Updates:** Before running any migration during an app update, the updater creates a verified encrypted backup snapshot (`ryn_backup_v<N>.db`). If migration fails, the previous database snapshot is restored atomically.
- **WAL Concurrency Tuning:**
  - `PRAGMA journal_mode = WAL;`
  - `PRAGMA synchronous = NORMAL;` (with `FULL` checkpoint on download completion)
  - `PRAGMA busy_timeout = 5000;`
  - `PRAGMA foreign_keys = ON;`
