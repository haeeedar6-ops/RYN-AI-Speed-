-- =============================================================================
-- RYN-AI-Speed v2.0 — Local Encrypted Database Schema (SQLite 3 / SQLCipher)
-- Version: 1.0.0
-- Security: Encrypted at rest with SQLCipher AES-256; keys in Keystore / DPAPI
-- =============================================================================

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;

-- 1. Schema Migration Tracking
CREATE TABLE IF NOT EXISTS schema_migrations (
    version         INTEGER PRIMARY KEY,
    name            TEXT    NOT NULL,
    checksum_sha256 TEXT    NOT NULL,
    applied_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 2. Local User Profile Cache (No plaintext passwords or private keys)
CREATE TABLE IF NOT EXISTS users (
    id              TEXT    PRIMARY KEY,
    email           TEXT    NOT NULL UNIQUE,
    display_name    TEXT,
    device_id       TEXT    NOT NULL,
    account_tier    TEXT    NOT NULL DEFAULT 'FREE' CHECK (account_tier IN ('FREE', 'PRO')),
    created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 3. Typed Application Settings
CREATE TABLE IF NOT EXISTS settings (
    key             TEXT    PRIMARY KEY,
    value           TEXT    NOT NULL,
    value_type      TEXT    NOT NULL CHECK (value_type IN ('STRING', 'INTEGER', 'BOOLEAN', 'JSON')),
    updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 4. Active & Queued Downloads
CREATE TABLE IF NOT EXISTS downloads (
    id                  TEXT    PRIMARY KEY,
    url                 TEXT    NOT NULL,
    final_url           TEXT,
    file_name           TEXT    NOT NULL,
    category            TEXT    NOT NULL DEFAULT 'OTHER' CHECK (category IN ('DOCUMENTS', 'VIDEO', 'AUDIO', 'ARCHIVES', 'SOFTWARE', 'IMAGES', 'OTHER')),
    mime_type           TEXT,
    file_size           INTEGER CHECK (file_size IS NULL OR file_size >= 0),
    downloaded_bytes    INTEGER NOT NULL DEFAULT 0 CHECK (downloaded_bytes >= 0),
    status              TEXT    NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'PROBING', 'DOWNLOADING', 'PAUSED', 'RETRYING', 'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELED')),
    priority            INTEGER NOT NULL DEFAULT 1 CHECK (priority IN (0, 1, 2)),
    max_connections     INTEGER NOT NULL DEFAULT 4 CHECK (max_connections BETWEEN 1 AND 16),
    speed_limit_bps     INTEGER NOT NULL DEFAULT 0 CHECK (speed_limit_bps >= 0),
    supports_ranges     INTEGER NOT NULL DEFAULT 0 CHECK (supports_ranges IN (0, 1)),
    etag                TEXT,
    last_modified       TEXT,
    sha256_expected     TEXT,
    sha256_actual       TEXT,
    temp_path           TEXT,
    save_path           TEXT    NOT NULL,
    scheduled_at        TEXT,
    retry_count         INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error_code     TEXT,
    last_error_message  TEXT,
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_downloads_status_priority
    ON downloads (status, priority DESC, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_downloads_url_etag
    ON downloads (url, etag);

-- 5. Download Segments (Work-Stealing Byte Ranges)
CREATE TABLE IF NOT EXISTS download_segments (
    id                  TEXT    PRIMARY KEY,
    download_id         TEXT    NOT NULL,
    segment_index       INTEGER NOT NULL CHECK (segment_index >= 0),
    start_byte          INTEGER NOT NULL CHECK (start_byte >= 0),
    end_byte            INTEGER NOT NULL CHECK (end_byte >= start_byte),
    downloaded_bytes    INTEGER NOT NULL DEFAULT 0 CHECK (downloaded_bytes >= 0),
    status              TEXT    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONNECTING', 'DOWNLOADING', 'COMPLETED', 'FAILED', 'RETRYING')),
    retry_count         INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    last_error          TEXT,
    updated_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (download_id) REFERENCES downloads(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_download_segments_download_id
    ON download_segments (download_id, status);

-- 6. Download History (Completed / Archived Records)
CREATE TABLE IF NOT EXISTS download_history (
    id                  TEXT    PRIMARY KEY,
    download_id         TEXT    NOT NULL,
    url                 TEXT    NOT NULL,
    file_name           TEXT    NOT NULL,
    category            TEXT    NOT NULL,
    file_size           INTEGER,
    sha256_actual       TEXT,
    save_path           TEXT    NOT NULL,
    final_status        TEXT    NOT NULL CHECK (final_status IN ('COMPLETED', 'FAILED', 'CANCELED')),
    avg_speed_bps       INTEGER NOT NULL DEFAULT 0,
    duration_ms         INTEGER NOT NULL DEFAULT 0,
    completed_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_download_history_completed_at
    ON download_history (completed_at DESC);

-- 7. Cached Verified VPN Servers (Public Metadata Only — Zero Private Keys)
CREATE TABLE IF NOT EXISTS vpn_servers (
    server_id           TEXT    PRIMARY KEY,
    server_name         TEXT    NOT NULL,
    country             TEXT    NOT NULL,
    region              TEXT    NOT NULL,
    endpoint            TEXT    NOT NULL,
    public_key          TEXT    NOT NULL,
    protocol            TEXT    NOT NULL DEFAULT 'wireguard' CHECK (protocol IN ('wireguard')),
    server_type         TEXT    NOT NULL CHECK (server_type IN ('PRIVATE', 'COMMUNITY')),
    capacity_status     TEXT    NOT NULL DEFAULT 'UNKNOWN' CHECK (capacity_status IN ('LOW', 'MEDIUM', 'HIGH', 'FULL', 'UNKNOWN')),
    health_status       TEXT    NOT NULL DEFAULT 'UNKNOWN' CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'OFFLINE', 'UNKNOWN')),
    latency_ms          INTEGER CHECK (latency_ms IS NULL OR latency_ms >= 0),
    packet_loss_pct     REAL    CHECK (packet_loss_pct IS NULL OR (packet_loss_pct >= 0.0 AND packet_loss_pct <= 100.0)),
    provider            TEXT    NOT NULL,
    privacy_policy_url  TEXT    NOT NULL,
    last_checked        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_vpn_servers_health_country
    ON vpn_servers (health_status, country, latency_ms ASC);

-- 8. Local VPN Session Telemetry
CREATE TABLE IF NOT EXISTS vpn_sessions (
    id                  TEXT    PRIMARY KEY,
    server_id           TEXT    NOT NULL,
    started_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    ended_at            TEXT,
    disconnect_reason   TEXT,
    bytes_tx            INTEGER NOT NULL DEFAULT 0 CHECK (bytes_tx >= 0),
    bytes_rx            INTEGER NOT NULL DEFAULT 0 CHECK (bytes_rx >= 0),
    kill_switch_active  INTEGER NOT NULL DEFAULT 1 CHECK (kill_switch_active IN (0, 1)),
    dns_leak_passed     INTEGER CHECK (dns_leak_passed IN (0, 1)),
    ipv6_leak_passed    INTEGER CHECK (ipv6_leak_passed IN (0, 1)),
    FOREIGN KEY (server_id) REFERENCES vpn_servers(server_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_vpn_sessions_started_at
    ON vpn_sessions (started_at DESC);

-- 9. Network Diagnostics & Pre/Post-VPN Measurements
CREATE TABLE IF NOT EXISTS network_tests (
    id                  TEXT    PRIMARY KEY,
    network_type        TEXT    NOT NULL CHECK (network_type IN ('WIFI', 'CELLULAR', 'ETHERNET', 'VPN', 'UNKNOWN')),
    vpn_active          INTEGER NOT NULL DEFAULT 0 CHECK (vpn_active IN (0, 1)),
    vpn_server_id       TEXT,
    ping_ms             REAL    CHECK (ping_ms IS NULL OR ping_ms >= 0.0),
    jitter_ms           REAL    CHECK (jitter_ms IS NULL OR jitter_ms >= 0.0),
    packet_loss_pct     REAL    CHECK (packet_loss_pct IS NULL OR (packet_loss_pct >= 0.0 AND packet_loss_pct <= 100.0)),
    dns_resolver        TEXT,
    dns_latency_ms      REAL    CHECK (dns_latency_ms IS NULL OR dns_latency_ms >= 0.0),
    download_mbps       REAL    CHECK (download_mbps IS NULL OR download_mbps >= 0.0),
    upload_mbps         REAL    CHECK (upload_mbps IS NULL OR upload_mbps >= 0.0),
    tested_at           TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_network_tests_tested_at
    ON network_tests (tested_at DESC);

-- 10. AI Assistant Sessions
CREATE TABLE IF NOT EXISTS ai_sessions (
    id                  TEXT    PRIMARY KEY,
    title               TEXT    NOT NULL,
    language            TEXT    NOT NULL DEFAULT 'ar' CHECK (language IN ('ar', 'en')),
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 11. AI Assistant Messages
CREATE TABLE IF NOT EXISTS ai_messages (
    id                  TEXT    PRIMARY KEY,
    session_id          TEXT    NOT NULL,
    role                TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL', 'SYSTEM')),
    content             TEXT    NOT NULL,
    tool_name           TEXT,
    tool_status         TEXT    CHECK (tool_status IS NULL OR tool_status IN ('EXECUTED', 'PENDING_CONFIRMATION', 'DENIED', 'FAILED')),
    provider_used       TEXT,
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    FOREIGN KEY (session_id) REFERENCES ai_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ai_messages_session_created
    ON ai_messages (session_id, created_at ASC);

-- 12. AI User Preferences
CREATE TABLE IF NOT EXISTS ai_preferences (
    key                 TEXT    PRIMARY KEY,
    value               TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 13. Built-in Browser Bookmarks
CREATE TABLE IF NOT EXISTS browser_bookmarks (
    id                  TEXT    PRIMARY KEY,
    title               TEXT    NOT NULL,
    url                 TEXT    NOT NULL UNIQUE,
    folder              TEXT    NOT NULL DEFAULT 'ROOT',
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- 14. Built-in Browser History (Strictly Local, 1-Tap Purgeable)
CREATE TABLE IF NOT EXISTS browser_history (
    id                  TEXT    PRIMARY KEY,
    title               TEXT,
    url                 TEXT    NOT NULL,
    visit_count         INTEGER NOT NULL DEFAULT 1 CHECK (visit_count >= 1),
    last_visited_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_browser_history_last_visited
    ON browser_history (last_visited_at DESC);

-- 15. Application & Security Audit Events
CREATE TABLE IF NOT EXISTS app_events (
    id                  TEXT    PRIMARY KEY,
    module              TEXT    NOT NULL CHECK (module IN ('CORE', 'DOWNLOADS', 'VPN', 'NETWORK', 'BROWSER', 'AI', 'SECURITY', 'UPDATER')),
    severity            TEXT    NOT NULL CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'SECURITY')),
    event_type          TEXT    NOT NULL,
    payload_json        TEXT    NOT NULL DEFAULT '{}',
    created_at          TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_app_events_module_created
    ON app_events (module, severity, created_at DESC);

-- Record initial schema version
INSERT OR IGNORE INTO schema_migrations (version, name, checksum_sha256)
VALUES (1, 'initial_v1_0_0', 'p0_baseline_schema_v1');
