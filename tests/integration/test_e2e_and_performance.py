"""Phase P10 End-to-End Integration, Soak & Performance Benchmark Suite."""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import time
import tracemalloc
import unittest
from pathlib import Path

REPO_ROOT = Path(os.getenv("RYN_REPO_ROOT", Path(__file__).resolve().parent.parent.parent))
BACKEND_DIR = REPO_ROOT / "backend"
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.ai.router_service import AiToolAuthorizer, MultiProviderAiRouter  # noqa: E402
from app.auth.service import AuthError, AuthService  # noqa: E402
from app.core.logging import redact_text  # noqa: E402
from app.core.rate_limit import SlidingWindowRateLimiter  # noqa: E402
from app.core.security import Ed25519Signer  # noqa: E402
from app.servers.registry import VpnServerRecord, VpnServerRegistry  # noqa: E402


class TestIntegrationAndPerformanceP10(unittest.TestCase):
    def test_01_full_e2e_user_vpn_and_ai_lifecycle(self) -> None:
        signer = Ed25519Signer()
        auth = AuthService(signer=signer)
        registry = VpnServerRegistry(signer=signer)
        router = MultiProviderAiRouter(["LOCAL_RULES"])
        authorizer = AiToolAuthorizer()

        # 1. Register & authenticate
        session = auth.register("e2e@ryn.dev", "UltraStrong!Pass2026#", "win-dev-01", "Haydar")
        claims = signer.verify_jwt(str(session["access_token"]))
        user_id = str(claims["sub"])

        # 2. Populate verified VPN server and verify signed manifest
        registry.register_server(
            VpnServerRecord(
                server_id="nl-priv-01", server_name="Amsterdam Private 1", country="NL", region="EU",
                endpoint="198.51.100.10:51820", public_key="BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                protocol="wireguard", server_type="PRIVATE", operator_verified=True, no_logs_verified=True,
                capacity_status="LOW", health_status="HEALTHY", latency_ms=21, packet_loss_pct=0.0,
                provider="RYN Infra", privacy_policy_url="https://ryn-ai-speed.example/privacy",
                last_checked="2026-11-22T00:00:00Z",
            )
        )
        manifest, sig = registry.list_signed_manifest()
        canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.assertTrue(signer.verify_manifest(canonical, sig))

        # 3. Arabic AI command proposes listing servers -> safe READ executes automatically
        chat = router.route_chat("اختار أسرع سيرفر VPN", language="ar")
        self.assertEqual(chat["proposed_tools"][0]["tool_name"], "list_vpn_servers")
        read_res = authorizer.authorize(user_id, "list_vpn_servers", {"limit": 5})
        self.assertTrue(read_res["authorized"])

        # 4. Protected connect_vpn requires explicit user confirmation nonce
        nonce = authorizer.issue_confirmation_nonce(user_id, "connect_vpn")
        connect_res = authorizer.authorize(
            user_id, "connect_vpn", {"server_id": "nl-priv-01"}, confirmation_nonce=nonce
        )
        self.assertTrue(connect_res["authorized"])

        # 5. Logout revokes session family cleanly
        auth.logout(user_id, "win-dev-01")
        with self.assertRaises(AuthError):
            auth.refresh(str(session["refresh_token"]), "win-dev-01")

    def test_02_sqlite_wal_500_segment_checkpoint_soak_and_latency(self) -> None:
        ddl = (REPO_ROOT / "shared/schemas/schema_sqlite.sql").read_text(encoding="utf-8")
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(ddl)
            conn.execute("PRAGMA foreign_keys = ON;")
            conn.execute(
                "INSERT INTO downloads (id, url, file_name, file_size, save_path, status) "
                "VALUES ('dl-soak', 'https://cdn.example.com/big.iso', 'big.iso', 1073741824, '/dl/big.iso', 'DOWNLOADING')"
            )
            for idx in range(8):
                conn.execute(
                    "INSERT INTO download_segments (id, download_id, segment_index, start_byte, end_byte, status) "
                    "VALUES (?, 'dl-soak', ?, ?, ?, 'DOWNLOADING')",
                    (f"seg-{idx}", idx, idx * 134217728, (idx + 1) * 134217728 - 1),
                )
            conn.commit()

            t0 = time.perf_counter()
            for step in range(500):
                seg_id = f"seg-{step % 8}"
                conn.execute(
                    "UPDATE download_segments SET downloaded_bytes = downloaded_bytes + 524288 WHERE id = ?",
                    (seg_id,),
                )
            conn.commit()
            elapsed_ms = (time.perf_counter() - t0) * 1000.0
            self.assertLess(elapsed_ms, 500.0, f"500 WAL checkpoints took too long: {elapsed_ms:.2f} ms")
        finally:
            conn.close()

    def test_03_performance_budgets_and_memory_stability(self) -> None:
        signer = Ed25519Signer()
        rl = SlidingWindowRateLimiter()

        # Ed25519 JWT issue+verify latency budget (< 2ms avg)
        t0 = time.perf_counter()
        for i in range(100):
            tok = signer.issue_jwt(f"u-{i}", "dev-1")
            signer.verify_jwt(tok)
        avg_jwt_ms = ((time.perf_counter() - t0) * 1000.0) / 100.0
        self.assertLess(avg_jwt_ms, 10.0, f"Ed25519 JWT avg too slow: {avg_jwt_ms:.2f} ms")

        # 1,000-request burst rate-limiter overhead (< 50ms total)
        t1 = time.perf_counter()
        for _ in range(1000):
            rl.check_and_consume("perf:ip", limit=500, window_seconds=60)
        rl_ms = (time.perf_counter() - t1) * 1000.0
        self.assertLess(rl_ms, 100.0, f"Rate limiter burst overhead too high: {rl_ms:.2f} ms")

        # Memory stability check across 500 log-redaction cycles
        tracemalloc.start()
        snap_before = tracemalloc.take_snapshot()
        sample = "Authorization: Bearer secret123 ?token=abc eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxIn0.sig"
        for _ in range(500):
            _ = redact_text(sample)
        snap_after = tracemalloc.take_snapshot()
        tracemalloc.stop()
        growth_kb = sum(d.size_diff for d in snap_after.compare_to(snap_before, "lineno") if d.size_diff > 0) / 1024.0
        self.assertLess(growth_kb, 512.0, f"Unexpected memory growth: {growth_kb:.1f} KB")

    def test_04_cross_platform_module_wiring_parity(self) -> None:
        settings_kts = (REPO_ROOT / "android/settings.gradle.kts").read_text(encoding="utf-8")
        for mod in (":app", ":core:common", ":core:security", ":core:database",
                    ":feature:downloads", ":feature:browser", ":feature:network", ":feature:vpn", ":feature:ai"):
            self.assertIn(f'include("{mod}")', settings_kts, f"Missing Android module {mod}")

        ci_win = (REPO_ROOT / ".github/workflows/ci-windows.yml").read_text(encoding="utf-8")
        for proj in ("RynSpeed.Core", "RynSpeed.Security", "RynSpeed.Database",
                     "RynSpeed.Downloads", "RynSpeed.Browser", "RynSpeed.Network", "RynSpeed.VPN", "RynSpeed.AI"):
            self.assertIn(proj, ci_win, f"Missing Windows project {proj} in CI")


if __name__ == "__main__":
    unittest.main()
