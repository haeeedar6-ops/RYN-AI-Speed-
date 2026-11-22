"""Phase P9 Automated Security & Privacy Hardening Audit Suite.
Runs live security assertions across Backend, Android, Windows, and Shared Contracts.
"""

from __future__ import annotations

import os
import re
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(os.getenv("RYN_REPO_ROOT", Path(__file__).resolve().parent.parent.parent))
BACKEND_DIR = REPO_ROOT / "backend"
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.ai.router_service import AiToolAuthorizer, sanitize_ai_context  # noqa: E402
from app.auth.service import AuthError, AuthService  # noqa: E402
from app.core.logging import redact_text  # noqa: E402
from app.core.rate_limit import IdempotencyGuard, SlidingWindowRateLimiter  # noqa: E402
from app.core.security import Ed25519Signer  # noqa: E402

SECRET_PATTERNS = [
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |)PRIVATE KEY-----"),
    re.compile(r"sk-[A-Za-z0-9]{20,}"),
    re.compile(r"AIza[0-9A-Za-z\-_]{35}"),
    re.compile(r"ghp_[A-Za-z0-9]{36}"),
]


class TestSecurityAuditP9(unittest.TestCase):
    def test_01_zero_hardcoded_secrets_in_repository(self) -> None:
        violations: list[str] = []
        for path in REPO_ROOT.rglob("*"):
            if not path.is_file() or ".git" in path.parts or "__pycache__" in path.parts:
                continue
            if path.suffix in {".zip", ".png", ".jpg", ".pdf", ".pyc"}:
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            for pat in SECRET_PATTERNS:
                if pat.search(text):
                    violations.append(f"{path.relative_to(REPO_ROOT)} matched {pat.pattern}")
        self.assertEqual(violations, [], f"Hardcoded secret(s) detected: {violations}")

    def test_02_auth_hardening_and_jwt_tamper_rejection(self) -> None:
        signer = Ed25519Signer()
        auth = AuthService(signer=signer)

        with self.assertRaises(ValueError):
            auth.register("short@example.com", "weak123", "dev-1")

        tokens = auth.register("sec@example.com", "VeryStrongPass!2026#", "dev-1")
        tampered = str(tokens["access_token"])[:-4] + "AAAA"
        with self.assertRaises(Exception):
            signer.verify_jwt(tampered)

        with self.assertRaises(AuthError):
            auth.login("sec@example.com", "WrongPassword123!", "dev-1")

    def test_03_rate_limiting_and_replay_protection(self) -> None:
        rl = SlidingWindowRateLimiter()
        for _ in range(5):
            ok, _, _ = rl.check_and_consume("audit:ip", limit=5, window_seconds=60)
            self.assertTrue(ok)
        ok, _, retry = rl.check_and_consume("audit:ip", limit=5, window_seconds=60)
        self.assertFalse(ok)
        self.assertGreaterEqual(retry, 1)

        idem = IdempotencyGuard()
        self.assertTrue(idem.claim("audit-key-99"))
        self.assertFalse(idem.claim("audit-key-99"))

    def test_04_ai_prompt_injection_and_privilege_escalation_blocked(self) -> None:
        authz = AiToolAuthorizer()
        for evil_tool in ("run_shell", "delete_all_files", "export_private_keys", "eval"):
            res = authz.authorize("u1", evil_tool, {})
            self.assertFalse(res["authorized"])

        # Attempt protected action without confirmation nonce
        res_vpn = authz.authorize("u1", "connect_vpn", {"server_id": "nl-1"})
        self.assertFalse(res_vpn["authorized"])
        self.assertEqual(res_vpn["reason"], "CONFIRMATION_REQUIRED")

        # Sensitive context stripping
        cleaned = sanitize_ai_context({"private_key": "K", "password": "P", "ping_ms": 20})
        self.assertEqual(cleaned, {"ping_ms": 20})

    def test_05_log_redaction_blocks_secret_leakage(self) -> None:
        raw = "Bearer mySecretTok123 ?api_key=XYZ987 eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxIn0.sig"
        clean = redact_text(raw)
        self.assertNotIn("mySecretTok123", clean)
        self.assertNotIn("XYZ987", clean)
        self.assertNotIn("eyJhbGciOiJFZERTQSJ9", clean)

    def test_06_android_and_windows_static_security_controls(self) -> None:
        manifest = (REPO_ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn('android:allowBackup="false"', manifest)
        self.assertIn('android:usesCleartextTraffic="false"', manifest)
        self.assertIn("BIND_VPN_SERVICE", manifest)

        and_br = (REPO_ROOT / "android/feature/browser/src/main/kotlin/com/ryn/aispeed/feature/browser/HardenedBrowserEngine.kt").read_text(encoding="utf-8")
        self.assertIn("allowFileAccess = false", and_br)
        self.assertIn("safeBrowsingEnabled = true", and_br)

        win_props = (REPO_ROOT / "windows/Directory.Build.props").read_text(encoding="utf-8")
        self.assertIn("<TreatWarningsAsErrors>true</TreatWarningsAsErrors>", win_props)
        self.assertIn("<Nullable>enable</Nullable>", win_props)

        win_dl = (REPO_ROOT / "windows/RynSpeed.Downloads/DownloadPrimitives.cs").read_text(encoding="utf-8")
        self.assertIn("Zone.Identifier", win_dl)


if __name__ == "__main__":
    unittest.main()
