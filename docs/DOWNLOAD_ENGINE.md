# RYN-AI-Speed — Download Engine Technical Design (Priority #1)

**Target:** IDM-grade reliability, high throughput, zero data corruption, strict filesystem & network security.  
**Contract Reference:** `shared/contracts/download_contract.json`

---

## 1. Core Architecture & Lifecycle State Machine

Every download task transitions strictly through validated states:

`QUEUED` -> `PROBING` -> `DOWNLOADING` -> `VERIFYING` -> `COMPLETED`  
Interruptible transitions: `DOWNLOADING` <-> `PAUSED` | `RETRYING` | `FAILED` | `CANCELED`

Every state transition is persisted atomically inside an encrypted SQLite (`WAL` mode) transaction before emitting UI events.

---

## 2. Smart Probe Phase (`PROBING`)

When a URL is submitted (manually, via Clipboard, Android Share Intent, or Built-in Browser):

1. **URL Security Validation:**
   - Allow strictly `http://` and `https://` schemes. Reject `file://`, `ftp://`, `javascript:`, `data:`, `content://`.
   - Resolve DNS and block SSRF targets (loopback `127.0.0.0/8`, `::1`, link-local `169.254.0.0/16`, RFC 1918 private ranges) unless the user explicitly enables `allow_local_network_downloads` in settings.
2. **Metadata Probe:**
   - Send `HEAD` request with safe timeout (10s) and up to 5 validated redirects (re-validating SSRF on every redirect hop).
   - If the server rejects `HEAD` (`405 Method Not Allowed` or `403`), fall back to `GET` with `Range: bytes=0-0` and close the stream immediately after reading headers.
3. **Extract Integrity & Capability Headers:**
   - `Content-Length` (or total size from `Content-Range: bytes 0-0/<total>`).
   - `Accept-Ranges: bytes` (confirmed ONLY if `Range: bytes=0-0` returns `206 Partial Content` — never assume support from `HEAD` alone).
   - Strong `ETag` and/or `Last-Modified` for safe resume verification.
   - `Content-Disposition` filename + `Content-Type` MIME classification.
4. **Filename & Path Sanitization:**
   - Never trust server-supplied filenames raw.
   - Strip all directory traversal sequences (`../`, `..\`, `/`, `\`), null bytes (`\x00`), control characters, and Unicode BiDi override characters (`U+202A`–`U+202E`, `U+2066`–`U+2069`).
   - Prefix or rename Windows reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`).
   - Verify canonical resolved target path starts strictly with the configured download root directory.
5. **Duplicate Detection & Disk Space Guard:**
   - Check active/completed DB records for matching `(normalized_url, etag)` or target path collision.
   - Verify `available_free_space >= file_size + 50 MB` safety buffer before allocating.

---

## 3. Adaptive Segmentation & Dynamic Work-Stealing

Instead of forcing 4 static connections on every file:

### 3.1 Initial Connection Count Calculation
- If `supports_ranges == false` OR `file_size == null` OR `file_size < 2 MB`: **1 connection**.
- `2 MB <= file_size < 20 MB`: up to **2 connections**.
- `20 MB <= file_size < 100 MB`: up to **4 connections**.
- `100 MB <= file_size < 500 MB`: up to **6 connections**.
- `file_size >= 500 MB`: up to **8 connections** (configurable cap, default max 8 to avoid triggering server anti-abuse bans).

### 3.2 Dynamic Work-Stealing (Eliminates Tail Latency)
When Worker A finishes its assigned byte range `[start, end]`:
1. The Segment Coordinator scans all active `DOWNLOADING` segments.
2. It finds the segment with the largest remaining un-downloaded bytes `remaining = end_byte - (start_byte + downloaded_bytes)`.
3. If `remaining >= 4 MB` (minimum split threshold):
   - Calculate midpoint `mid = current_pos + (remaining / 2)`.
   - Atomically shrink the active segment's `end_byte` to `mid` in memory and DB.
   - Spawn a new segment `[mid + 1, old_end_byte]` and assign it to Worker A.
4. This guarantees maximum bandwidth utilization right up to the final megabytes.

---

## 4. Sparse Pre-Allocation & Direct Offset Writes

- **Single `.ryntmp` File:** Upon starting a ranged download of known size, pre-allocate `<target>.ryntmp` using sparse file allocation (`ftruncate` / `SetLength`).
- **Zero-Copy Direct Offset Writing:**
  - Android: `FileChannel.write(ByteBuffer, position)` (thread-safe positional write without shared file pointer lock contention).
  - Windows (.NET 10): `RandomAccess.Write(SafeFileHandle, ReadOnlySpan<byte>, fileOffset)` (lock-free, allocation-free positional write).
- **Checkpoint Persistence:** Every 512 KB (or 500 ms), each worker flushes its `downloaded_bytes` progress to SQLite `download_segments` in a batched WAL transaction.
- **Atomic Finalization:**
  1. Confirm `SUM(segment.downloaded_bytes) == expected_file_size` and all segments are `COMPLETED`.
  2. Call `fsync` / `FlushToDisk`.
  3. Compute streaming **SHA-256** (compare against expected hash if provided).
  4. Atomically move `<target>.ryntmp` -> `<final_filename>` (`ATOMIC_MOVE`).

---

## 5. Error Handling, Backoff & Resume Integrity

- **Never treat HTTP `200 OK` as complete:** Verify `downloaded_bytes == Content-Length` (when known) and clean EOF without socket reset.
- **Strict Resume Validator (`If-Range`):**
  - Every resumed Range request includes `If-Range: <ETag or Last-Modified>`.
  - If the server returns `200 OK` (meaning the remote file changed), the engine **immediately halts** merging, discards stale segments, resets `.ryntmp`, and restarts cleanly from byte 0 so two versions of a file are NEVER corrupted together.
- **HTTP Status Policy:**
  - `401 Unauthorized` / `403 Forbidden`: Stop immediately -> mark `FAILED` with `AUTH_OR_EXPIRED_LINK` (prompt user to refresh link via Built-in Browser).
  - `404 Not Found` / `410 Gone`: Stop immediately -> mark `FAILED` (`RESOURCE_NOT_FOUND`).
  - `429 Too Many Requests` / `503 Service Unavailable`: Respect `Retry-After` header, dynamically halve active connections for that host, apply exponential backoff with full jitter (`min(60s, base * 2^attempt) + random_jitter`).
  - Transient Network Loss (`SocketTimeout`, `UnknownHost`, `IOException`): Pause active workers cleanly, preserve `.ryntmp` and exact byte checkpoints, and auto-resume when OS Connectivity Monitor signals network recovery.

---

## 6. Bandwidth Throttling & Queue Management

- **Token-Bucket Rate Limiter:** Supports both a global speed cap and per-download `speed_limit_bps` cap without blocking worker threads (async delay tokens).
- **Priority Queue & Scheduler:** Supports `HIGH`, `NORMAL`, `LOW` priorities, configurable `max_concurrent_downloads` (default: 3), scheduled start/stop windows, and automatic category folder routing (Documents, Video, Audio, Archives, Software, Other).

---

## 7. Platform-Specific Execution Rules

- **Android:**
  - `WorkManager` with `CoroutineWorker` for persistent queued/scheduled tasks.
  - `ForegroundService` (`foregroundServiceType="dataSync"`) with live progress notification while active downloads run, respecting Android 14/15 foreground timeout and battery optimization rules.
  - Output saved via `MediaStore` / Storage Access Framework (SAF) scoped storage without requesting legacy `MANAGE_EXTERNAL_STORAGE`.
- **Windows:**
  - Dedicated `BackgroundService` / `IHostedService` worker pool using `SocketsHttpHandler` with HTTP/2 + HTTP/3 (QUIC) support and pooled connections.
  - Power-aware execution (`SetThreadExecutionState` optional prevent-sleep toggle while active downloads are running).
