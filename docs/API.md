# RYN-AI-Speed — Backend API Contract & Security Specification

**Machine-Readable Specification:** `shared/api-spec/openapi.yaml` (OpenAPI 3.1.0)  
**Transport:** HTTPS ONLY (TLS 1.3 enforced; plain HTTP permanently rejected)  
**Base Path:** `/api/v1`

---

## 1. Authentication & Session Lifecycle

- **Password Hashing:** `Argon2id` (memory-hard, salted).
- **Access Token:** Short-lived JWT signed with **Ed25519 (`EdDSA`)**, TTL = **15 minutes**.
- **Refresh Token:** Cryptographically random 256-bit opaque token, stored as `SHA-256` hash in PostgreSQL, TTL = **30 days**.
- **Rotation & Reuse Detection:**
  - Every `POST /api/v1/auth/refresh` issues a new Access + Refresh pair and marks the old Refresh token as `used`.
  - If a previously `used` Refresh token is ever presented again, the Backend assumes token theft and **immediately revokes the entire token family** for that device.

---

## 2. Core Endpoints

- `POST /api/v1/auth/register` — Register a new user account (strict email/password validation, rate limited: 5 req/min/IP).
- `POST /api/v1/auth/login` — Authenticate user + register/bind device ID; returns Access + Refresh tokens (rate limited: 10 req/min/IP).
- `POST /api/v1/auth/refresh` — Rotate Refresh Token and issue fresh Access Token.
- `POST /api/v1/auth/logout` — Revoke active device session family.
- `GET /api/v1/servers` — Retrieve verified Private & Community WireGuard servers with signed manifest signature (`X-Manifest-Signature`).
- `GET /api/v1/servers/{id}` — Retrieve detailed metadata and WireGuard public endpoint for a specific server.
- `GET /api/v1/servers/health` — Live health, latency, packet loss, and capacity status across active VPN nodes.
- `POST /api/v1/ai/chat` — Route natural-language request through Multi-Provider AI Router (OpenAI / Gemini / Anthropic / OpenAI-compatible) with minimal sanitized context.
- `POST /api/v1/ai/action` — Validate and authorize an AI tool invocation against `shared/contracts/ai_tools_schema.json` and 3-tier RBAC (`READ`, `SAFE_ACTION`, `PROTECTED_ACTION`).
- `GET /api/v1/config` — Retrieve global app configuration, feature flags, and policy versions.
- `GET /api/v1/version` — Retrieve latest signed release metadata (Android APK / Windows MSIX, SHA-256 checksums, minimum required version, release notes).

---

## 3. Security Headers, Rate Limits & Error Taxonomy

- **Mandatory Request Headers:**
  - `Authorization: Bearer <Ed25519_JWT>` (for protected routes)
  - `X-Device-ID: <UUID>`
  - `X-App-Version: <SEMVER>`
  - `Idempotency-Key: <UUID>` (required on `POST /api/v1/ai/action` and `POST /api/v1/auth/register`)
- **Standardized Error Envelope (`application/problem+json`):**
  Every error returns `{ "type", "title", "status", "detail", "error_code", "request_id" }` with zero stack traces or internal SQL leakage.
- **Rate Limit Headers:** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, and `Retry-After` on `429 Too Many Requests`.
