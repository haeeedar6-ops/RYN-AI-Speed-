"""Live automated tests for Phase P6: Auth Rotation/Reuse-Detection, Ed25519 Manifests & Rate Limiting."""

from __future__ import annotations

import json
import unittest

from app.auth.service import AuthError, AuthService
from app.core.rate_limit import IdempotencyGuard, SlidingWindowRateLimiter
from app.core.security import Ed25519Signer
from app.servers.registry import VpnServerRecord, VpnServerRegistry


class TestPhaseP6Backend(unittest.TestCase):
    def test_auth_ed25519_jwt_and_refresh_reuse_revokes_family(self) -> None:
        signer = Ed25519Signer()
        auth = AuthService(signer=signer)
        dev = "dev-uuid-1001"

        reg = auth.register("haydar@example.com", "StrongPass!2026#Secure", dev, "Haydar")
        claims = signer.verify_jwt(str(reg["access_token"]))
        self.assertEqual(claims["dev"], dev)

        old_refresh = str(reg["refresh_token"])
        rotated = auth.refresh(old_refresh, dev)
        new_refresh = str(rotated["refresh_token"])
        self.assertNotEqual(new_refresh, old_refresh)

        # Replaying the ALREADY USED refresh token must trigger family revocation!
        with self.assertRaises(AuthError) as ctx:
            auth.refresh(old_refresh, dev)
        self.assertEqual(ctx.exception.code, "REFRESH_TOKEN_REUSE_DETECTED")

        # Even the newer legitimate refresh token in the compromised family is now revoked!
        with self.assertRaises(AuthError):
            auth.refresh(new_refresh, dev)

    def test_vpn_registry_rejects_unverified_community_and_signs_manifest(self) -> None:
        signer = Ed25519Signer()
        reg = VpnServerRegistry(signer=signer)
        valid_key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        unverified = VpnServerRecord(
            server_id="comm-bad", server_name="Bad", country="DE", region="EU",
            endpoint="1.2.3.4:51820", public_key=valid_key, protocol="wireguard",
            server_type="COMMUNITY", operator_verified=False, no_logs_verified=False,
            capacity_status="LOW", health_status="HEALTHY", latency_ms=20, packet_loss_pct=0.0,
            provider="Unknown", privacy_policy_url="https://example.com/p", last_checked="2026-11-22T00:00:00Z"
        )
        with self.assertRaises(PermissionError):
            reg.register_server(unverified)

        verified = VpnServerRecord(
            server_id="priv-nl-1", server_name="NL Private 1", country="NL", region="EU",
            endpoint="5.6.7.8:51820", public_key=valid_key, protocol="wireguard",
            server_type="PRIVATE", operator_verified=True, no_logs_verified=True,
            capacity_status="LOW", health_status="HEALTHY", latency_ms=18, packet_loss_pct=0.0,
            provider="RYN Infra", privacy_policy_url="https://ryn-ai-speed.example/privacy",
            last_checked="2026-11-22T00:00:00Z"
        )
        reg.register_server(verified)

        payload, sig = reg.list_signed_manifest()
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        self.assertTrue(signer.verify_manifest(canonical, sig))
        self.assertFalse(signer.verify_manifest(canonical + b"tampered", sig))

    def test_rate_limiter_and_idempotency_guard(self) -> None:
        rl = SlidingWindowRateLimiter()
        for _ in range(3):
            allowed, _, _ = rl.check_and_consume("login:1.2.3.4", limit=3, window_seconds=60)
            self.assertTrue(allowed)
        allowed, rem, retry = rl.check_and_consume("login:1.2.3.4", limit=3, window_seconds=60)
        self.assertFalse(allowed)
        self.assertEqual(rem, 0)
        self.assertGreaterEqual(retry, 1)

        idem = IdempotencyGuard()
        self.assertTrue(idem.claim("req-key-1"))
        self.assertFalse(idem.claim("req-key-1"))


if __name__ == "__main__":
    unittest.main()
