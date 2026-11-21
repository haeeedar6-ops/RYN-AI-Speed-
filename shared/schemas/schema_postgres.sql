-- =============================================================================
-- RYN-AI-Speed v2.0 — Central Backend Database Schema (PostgreSQL 16+)
-- Version: 1.0.0
-- Privacy Policy: Zero user browsing logs, zero downloaded files, zero VPN traffic logs
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Users (Argon2id password hashes only)
CREATE TABLE IF NOT EXISTS users (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email               TEXT        NOT NULL UNIQUE,
    password_hash       TEXT        NOT NULL,
    display_name        TEXT,
    account_tier        TEXT        NOT NULL DEFAULT 'FREE' CHECK (account_tier IN ('FREE', 'PRO')),
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Registered User Devices (Holds client WireGuard PUBLIC key only)
CREATE TABLE IF NOT EXISTS user_devices (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_uuid         UUID        NOT NULL,
    platform            TEXT        NOT NULL CHECK (platform IN ('ANDROID', 'WINDOWS')),
    device_name         TEXT        NOT NULL,
    wg_public_key       TEXT,
    app_version         TEXT        NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, device_uuid)
);

CREATE INDEX IF NOT EXISTS idx_user_devices_user_id ON user_devices (user_id);

-- 3. Opaque Refresh Tokens with Rotation & Reuse-Detection Families
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           UUID        NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    token_family_id     UUID        NOT NULL,
    token_hash_sha256   TEXT        NOT NULL UNIQUE,
    is_used             BOOLEAN     NOT NULL DEFAULT FALSE,
    is_revoked          BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON refresh_tokens (token_family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_device ON refresh_tokens (user_id, device_id);

-- 4. Verified VPN Server Registry (Private & Vetted Community Servers)
CREATE TABLE IF NOT EXISTS vpn_servers (
    server_id           TEXT        PRIMARY KEY,
    server_name         TEXT        NOT NULL,
    country             CHAR(2)     NOT NULL,
    region              TEXT        NOT NULL,
    endpoint            TEXT        NOT NULL,
    public_key          TEXT        NOT NULL UNIQUE,
    protocol            TEXT        NOT NULL DEFAULT 'wireguard' CHECK (protocol IN ('wireguard')),
    server_type         TEXT        NOT NULL CHECK (server_type IN ('PRIVATE', 'COMMUNITY')),
    operator_verified   BOOLEAN     NOT NULL DEFAULT FALSE,
    no_logs_verified    BOOLEAN     NOT NULL DEFAULT FALSE,
    capacity_status     TEXT        NOT NULL DEFAULT 'UNKNOWN' CHECK (capacity_status IN ('LOW', 'MEDIUM', 'HIGH', 'FULL', 'UNKNOWN')),
    health_status       TEXT        NOT NULL DEFAULT 'UNKNOWN' CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN')),
    latency_ms          INTEGER     CHECK (latency_ms IS NULL OR latency_ms >= 0),
    packet_loss_pct     REAL        CHECK (packet_loss_pct IS NULL OR (packet_loss_pct >= 0.0 AND packet_loss_pct <= 100.0)),
    provider            TEXT        NOT NULL,
    privacy_policy_url  TEXT        NOT NULL,
    last_checked        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pg_vpn_servers_health ON vpn_servers (health_status, server_type, country);

-- 5. VPN Server Health Probe History (Infrastructure Telemetry Only)
CREATE TABLE IF NOT EXISTS vpn_server_health_logs (
    id                  BIGSERIAL   PRIMARY KEY,
    server_id           TEXT        NOT NULL REFERENCES vpn_servers(server_id) ON DELETE CASCADE,
    health_status       TEXT        NOT NULL CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'OFFLINE')),
    latency_ms          INTEGER,
    packet_loss_pct     REAL,
    active_load_pct     REAL        CHECK (active_load_pct IS NULL OR (active_load_pct >= 0.0 AND active_load_pct <= 100.0)),
    checked_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_vpn_health_logs_server_time
    ON vpn_server_health_logs (server_id, checked_at DESC);

-- 6. AI Usage Quotas & Provider Routing Accounting (No Private User URLs)
CREATE TABLE IF NOT EXISTS ai_usage_logs (
    id                  BIGSERIAL   PRIMARY KEY,
    user_id             UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider            TEXT        NOT NULL,
    model_name          TEXT        NOT NULL,
    prompt_tokens       INTEGER     NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
    completion_tokens   INTEGER     NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
    latency_ms          INTEGER     NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    status              TEXT        NOT NULL CHECK (status IN ('SUCCESS', 'RATE_LIMITED', 'PROVIDER_ERROR', 'BLOCKED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_user_time ON ai_usage_logs (user_id, created_at DESC);

-- 7. App Global Configuration & Policy Versions
CREATE TABLE IF NOT EXISTS app_configs (
    key                 TEXT        PRIMARY KEY,
    value_json          JSONB       NOT NULL,
    policy_version      TEXT        NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 8. Signed App Releases (Android APK & Windows MSIX)
CREATE TABLE IF NOT EXISTS app_releases (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    platform            TEXT        NOT NULL CHECK (platform IN ('ANDROID', 'WINDOWS')),
    version             TEXT        NOT NULL,
    min_supported_ver   TEXT        NOT NULL,
    download_url        TEXT        NOT NULL,
    checksum_sha256     TEXT        NOT NULL,
    signature_ed25519   TEXT        NOT NULL,
    release_notes_md    TEXT        NOT NULL,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    published_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (platform, version)
);
