# Review & Fixes (verified vs. unverified)

## Verified by running (Python/SQL/JSON/YAML)
Ran: backend unit tests (19), root security audit + integration suites (10), `validate_contracts.py`, SQLite schema load. All pass.

## Backend / CI
- CI would fail: ruff `E501` on ~55 long lines, `B008` on FastAPI `Depends/Header/Query`, `S105/S106` in tests, `UP017`, `B009` -> config + code fixed.
- CI would fail: `pytest` could not import `app` (no `pythonpath`) -> `pythonpath = ["."]`.
- Auth: login timing no longer reveals unknown emails; JWT header `alg` enforced (EdDSA) and `sub/dev` required; password verify fails closed on corrupt hashes; expired refresh tokens purged.
- Missing `Authorization` header returned 422 instead of 401; email format was not validated.
- Rate limiter / idempotency / nonce stores grew without bound (memory DoS) -> swept and capped. Idempotency keys for AI actions are now per-user.
- AI: confirmation nonce can be bound to exact arguments; context sanitizer now handles lists and key variants (`accessToken`, `apiKey`, `Authorization`...); `provider_used` no longer claims OPENAI/GEMINI when the local rules answered.
- VPN registry: unknown `server_type` bypassed the community-verification rule; endpoint/country now validated.
- Production refuses to start without `JWT_PRIVATE_KEY_PEM`; unhandled errors are now logged (they were swallowed).

## Windows (C#) - NOT compiled here (no .NET SDK in my sandbox); please run CI
- Download engine: GET redirects were not followed during probe; 206 with unknown size reported size 1; weak ETag broke `If-Range`; 404/410 were retried 5x; one failed worker did not cancel siblings; race when a segment end shrinks; existing files were silently overwritten; DNS-rebinding (SSRF) closed via `ConnectCallback`.
- SSRF list now includes CGNAT, reserved, IPv6 unique-local. `FaultIsolation` only rethrows real cancellation.

## Android (Kotlin) - NOT compiled here (no Gradle/Android SDK); please run CI
- Share-intent crash: DNS lookup on the main thread (`NetworkOnMainThreadException`) -> syntax-only check there, full SSRF check on IO thread.
- Download engine rewritten with the same fixes as Windows + status saved as FAILED/CANCELED (was stuck on DOWNLOADING), temp files cleaned, `OkHttp Dns` SSRF guard.
- Keystore passphrase saved with `commit()` (async `apply()` could lose the DB key).
- VPN: the service would have black-holed all traffic (no WireGuard backend) -> now refuses until a backend is attached; `setBlocking` is not a kill switch (docs fixed); config-injection validation; state machine so CONNECTED needs route verification.
- Side effects moved from composition to `LaunchedEffect`; explicit coroutines dependencies.

## Known gaps (not fixed, need design work)
1. FIXED: users and refresh-token families are now stored in SQLite (`app/core/store.py`, WAL, atomic token claim) on a Docker volume `/data`. Still in memory: the VPN server registry and AI nonces (re-populated at start). Single-instance only; Postgres in compose is still unused.
2. No pause/resume/cancel API and no periodic segment checkpoint in either download engine (needed for resume after restart).
3. No real WireGuard integration yet (P5) and no gradle wrapper in `android/`.
4. `/api/v1/version` still returns placeholder checksum/signature; clients must reject them.
