"""Live automated security & intent tests for Phase P7 AI Assistant & Tool Sandbox."""

from __future__ import annotations

import unittest

from app.ai.router_service import AiToolAuthorizer, MultiProviderAiRouter, sanitize_ai_context


class TestPhaseP7AiEngine(unittest.TestCase):
    def test_context_sanitizer_strips_secrets_and_browsing_history(self) -> None:
        raw = {
            "ping_ms": 28.5,
            "private_key": "SECRET_WG_KEY",
            "password": "MyPassword123!",
            "browsing_history": ["https://private.site"],
            "nested": {"token": "abc", "vpn_active": True},
        }
        cleaned = sanitize_ai_context(raw)
        self.assertEqual(cleaned["ping_ms"], 28.5)
        self.assertNotIn("private_key", cleaned)
        self.assertNotIn("password", cleaned)
        self.assertNotIn("browsing_history", cleaned)
        self.assertNotIn("token", cleaned["nested"])
        self.assertTrue(cleaned["nested"]["vpn_active"])

    def test_tool_authorizer_blocks_unlisted_and_injected_args(self) -> None:
        authz = AiToolAuthorizer()
        r1 = authz.authorize("u1", "exec_shell", {"cmd": "rm -rf /"})
        self.assertFalse(r1["authorized"])
        self.assertEqual(r1["reason"], "TOOL_NOT_ALLOWED")

        r2 = authz.authorize("u1", "get_downloads", {"status_filter": "ALL", "injected_arg": True})
        self.assertFalse(r2["authorized"])
        self.assertEqual(r2["reason"], "INVALID_TOOL_ARGUMENTS")

    def test_protected_action_requires_single_use_confirmation_nonce(self) -> None:
        authz = AiToolAuthorizer()
        # Without nonce -> MUST be denied
        denied = authz.authorize("u1", "connect_vpn", {"server_id": "nl-1"})
        self.assertFalse(denied["authorized"])
        self.assertEqual(denied["reason"], "CONFIRMATION_REQUIRED")

        # With valid nonce -> authorized ONCE
        nonce = authz.issue_confirmation_nonce("u1", "connect_vpn")
        allowed = authz.authorize("u1", "connect_vpn", {"server_id": "nl-1"}, confirmation_nonce=nonce)
        self.assertTrue(allowed["authorized"])
        self.assertEqual(allowed["permission_level"], "PROTECTED_ACTION")

        # Replaying the exact same nonce -> MUST be denied
        replay = authz.authorize("u1", "connect_vpn", {"server_id": "nl-1"}, confirmation_nonce=nonce)
        self.assertFalse(replay["authorized"])
        self.assertEqual(replay["reason"], "INVALID_OR_REUSED_NONCE")

    def test_arabic_and_english_offline_routing(self) -> None:
        router = MultiProviderAiRouter(["LOCAL_RULES"])
        ar = router.route_chat("افحص الإنترنت عندي وقلي شو المشكلة", language="ar")
        self.assertEqual(ar["provider_used"], "LOCAL_RULES")
        tool_names = [t["tool_name"] for t in ar["proposed_tools"]]
        self.assertIn("get_network_stats", tool_names)
        self.assertIn("run_speed_test", tool_names)

        en = router.route_chat("pick fastest vpn server", language="en")
        self.assertEqual(en["proposed_tools"][0]["tool_name"], "list_vpn_servers")


if __name__ == "__main__":
    unittest.main()
