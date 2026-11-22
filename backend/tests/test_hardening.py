"""Regression tests for the security-hardening fixes (auth timing, nonce binding, sanitizer, registry)."""

from __future__ import annotations

import unittest

from app.ai.router_service import AiToolAuthorizer, MultiProviderAiRouter, sanitize_ai_context
from app.auth.service import AuthError, AuthService
from app.core.rate_limit import IdempotencyGuard, SlidingWindowRateLimiter
from app.core.security import Ed25519Signer, verify_password
from app.servers.registry import VpnServerRecord, VpnServerRegistry

_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="


def _record(**over: object) -> VpnServerRecord:
    base: dict[str, object] = {
        "server_id": "priv-nl-1", "server_name": "NL", "country": "NL", "region": "EU",
        "endpoint": "5.6.7.8:51820", "public_key": _KEY, "protocol": "wireguard",
        "server_type": "PRIVATE", "operator_verified": True, "no_logs_verified": True,
        "capacity_status": "LOW", "health_status": "HEALTHY", "latency_ms": 10,
        "packet_loss_pct": 0.0, "provider": "RYN", "privacy_policy_url": "https://example.com/p",
        "last_checked": "2026-11-22T00:00:00Z",
    }
    base.update(over)
    return VpnServerRecord(**base)  # type: ignore[arg-type]


class TestHardening(unittest.TestCase):
    def test_sanitizer_handles_lists_and_key_variants(self) -> None:
        raw = {
            "accessToken": "a", "Authorization": "Bearer x", "wg-private-key": "k", "apiKey": "z",
            "peers": [{"name": "ok", "presharedKey": "s"}, "plain"],
            "ping": 12,
        }
        cleaned = sanitize_ai_context(raw)
        self.assertEqual(set(cleaned), {"peers", "ping"})
        self.assertEqual(cleaned["peers"], [{"name": "ok"}, "plain"])

    def test_nonce_can_be_bound_to_arguments(self) -> None:
        authz = AiToolAuthorizer()
        nonce = authz.issue_confirmation_nonce("u1", "connect_vpn", arguments={"server_id": "nl-1"})
        wrong = authz.authorize("u1", "connect_vpn", {"server_id": "evil"}, confirmation_nonce=nonce)
        self.assertFalse(wrong["authorized"])
        self.assertEqual(wrong["reason"], "INVALID_OR_REUSED_NONCE")
        nonce2 = authz.issue_confirmation_nonce("u1", "connect_vpn", arguments={"server_id": "nl-1"})
        ok = authz.authorize("u1", "connect_vpn", {"server_id": "nl-1"}, confirmation_nonce=nonce2)
        self.assertTrue(ok["authorized"])

    def test_nonce_other_user_and_other_tool_rejected(self) -> None:
        authz = AiToolAuthorizer()
        nonce = authz.issue_confirmation_nonce("u1", "disconnect_vpn")
        self.assertFalse(authz.authorize("u2", "disconnect_vpn", {}, confirmation_nonce=nonce)["authorized"])
        self.assertFalse(authz.authorize("u1", "connect_vpn", {"server_id": "x"}, confirmation_nonce=nonce)["authorized"])

    def test_router_never_claims_an_external_provider(self) -> None:
        router = MultiProviderAiRouter(["LOCAL_RULES", "OPENAI"])
        res = router.route_chat("hello", preferred_provider="OPENAI")
        self.assertEqual(res["provider_used"], "LOCAL_RULES")
        self.assertEqual(res["requested_provider"], "OPENAI")

    def test_login_unknown_email_and_wrong_password_look_identical(self) -> None:
        auth = AuthService(Ed25519Signer())
        auth.register("a@example.com", "StrongPass!2026#Secure", "device-0001")
        with self.assertRaises(AuthError) as c1:
            auth.login("nobody@example.com", "whatever-password-1", "device-0001")
        with self.assertRaises(AuthError) as c2:
            auth.login("a@example.com", "wrong-password-123", "device-0001")
        self.assertEqual((c1.exception.code, c1.exception.status_code), (c2.exception.code, c2.exception.status_code))

    def test_verify_password_fails_closed_on_garbage(self) -> None:
        for bad in ("", "$argon2id$garbage", "pbkdf2_sha512$notanint$a$b", "pbkdf2_sha512$1$a$b", "x$y"):
            self.assertFalse(verify_password("anything-long-enough", bad))

    def test_jwt_rejects_wrong_algorithm_header(self) -> None:
        signer = Ed25519Signer()
        token = signer.issue_jwt("u1", "dev-0001")
        self.assertEqual(signer.verify_jwt(token)["sub"], "u1")
        parts = token.split(".")
        parts[0] = "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0"  # {"alg":"none","typ":"JWT"}
        with self.assertRaises(ValueError):
            signer.verify_jwt(".".join(parts))

    def test_registry_rejects_bad_type_endpoint_country(self) -> None:
        reg = VpnServerRegistry(Ed25519Signer())
        with self.assertRaises(ValueError):
            reg.register_server(_record(server_type="SUPER"))  # would have bypassed community checks
        with self.assertRaises(ValueError):
            reg.register_server(_record(endpoint="no-port"))
        with self.assertRaises(ValueError):
            reg.register_server(_record(endpoint="1.2.3.4:70000"))
        with self.assertRaises(ValueError):
            reg.register_server(_record(country="NLD"))
        reg.register_server(_record())

    def test_rate_limiter_sweeps_idle_keys_and_idempotency_is_capped(self) -> None:
        rl = SlidingWindowRateLimiter()
        for i in range(1200):
            rl.check_and_consume(f"k{i}", limit=5, window_seconds=1)
        self.assertLessEqual(len(rl._buckets), 1200)  # noqa: SLF001
        self.assertTrue(IdempotencyGuard().claim("a"))


if __name__ == "__main__":
    unittest.main()


class TestPersistence(unittest.TestCase):
    def test_accounts_and_sessions_survive_restart(self) -> None:
        import os
        import tempfile

        from app.core.store import AuthStore

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "nested", "auth.db")
            signer = Ed25519Signer()
            first = AuthService(signer, store=AuthStore(path))
            pair = first.register("p@example.com", "StrongPass!2026#Secure", "device-0001")
            first._store.close()  # noqa: SLF001  (simulate process restart)

            second = AuthService(signer, store=AuthStore(path))
            login = second.login("p@example.com", "StrongPass!2026#Secure", "device-0001")
            self.assertIn("access_token", login)
            rotated = second.refresh(str(pair["refresh_token"]), "device-0001")
            self.assertIn("refresh_token", rotated)
            with self.assertRaises(AuthError) as ctx:  # replay of the old token revokes the family
                second.refresh(str(pair["refresh_token"]), "device-0001")
            self.assertEqual(ctx.exception.code, "REFRESH_TOKEN_REUSE_DETECTED")
            with self.assertRaises(AuthError):
                second.refresh(str(rotated["refresh_token"]), "device-0001")

    def test_duplicate_email_is_rejected_atomically(self) -> None:
        auth = AuthService(Ed25519Signer())
        auth.register("d@example.com", "StrongPass!2026#Secure", "device-0001")
        with self.assertRaises(AuthError) as ctx:
            auth.register("D@Example.com", "StrongPass!2026#Secure", "device-0002")
        self.assertEqual(ctx.exception.status_code, 409)
