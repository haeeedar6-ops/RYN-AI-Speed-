# RYN-AI-Speed — Testing & Performance Budgets (`docs/TESTING.md`)

## 1. Automated Test Layers

1. **Contract & Schema Validation (`scripts/validate_contracts.py`):**
   - Live SQLite DDL execution, PostgreSQL syntax check, OpenAPI 3.1 `$ref` resolution, and JSON Schema checks.
2. **Backend Unit Tests (`backend/tests/`):**
   - `test_foundation.py`: Secret & log redaction, opaque token entropy.
   - `test_p6_backend.py`: Argon2id, Ed25519 JWT, refresh rotation & reuse-detection family revocation, Ed25519 VPN manifest signing, rate limiting.
   - `test_p7_ai.py`: Multi-provider AI routing (AR/EN), context sanitizer, 3-tier RBAC tool authorizer, single-use Nonce enforcement.
3. **Security & Privacy Audit (`tests/security/test_security_audit.py`):**
   - Repo-wide secret scan, weak-password & JWT-tamper rejection, AI prompt-injection/privilege-escalation defense, static Android & Windows security baseline verification.
4. **End-to-End Integration & Performance (`tests/integration/test_e2e_and_performance.py`):**
   - Full cross-module user lifecycle, SQLite WAL 500-checkpoint soak test, Ed25519/redaction latency benchmarks, `tracemalloc` memory-leak regression check, and cross-platform project parity audit.

## 2. Target Performance Budgets

- **Cold App Startup:** `< 1.5 s` (Android & Windows)
- **Download Segment Checkpoint Write (WAL):** `< 5 ms` average
- **Download Resume Recovery After Network Drop:** `< 2.0 s`
- **WireGuard Tunnel Handshake + Exit Verification:** `< 2.5 s`
- **Ed25519 JWT Issue & Verify Overhead:** `< 2 ms` per token
- **Log Redaction Overhead:** `< 1 ms` per structured log entry
- **Memory Stability:** Zero unbounded growth under sustained 500-cycle soak tests
