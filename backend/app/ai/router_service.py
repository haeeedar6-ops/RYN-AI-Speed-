"""Multi-Provider AI Router, Context Sanitizer, Local Arabic/English Intent Engine & Strict 3-Tier RBAC Tool Authorizer."""

from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
import uuid
from collections import deque
from dataclasses import dataclass
from typing import Any

ALLOWED_TOOLS: dict[str, dict[str, Any]] = {
    "get_network_stats": {"level": "READ", "args": {"include_history_count"}},
    "run_speed_test": {"level": "SAFE_ACTION", "args": {"mode"}},
    "get_dns_info": {"level": "READ", "args": {"run_benchmark"}},
    "list_vpn_servers": {"level": "READ", "args": {"country", "server_type", "limit"}},
    "connect_vpn": {"level": "PROTECTED_ACTION", "args": {"server_id"}, "required": {"server_id"}},
    "disconnect_vpn": {"level": "PROTECTED_ACTION", "args": set()},
    "get_downloads": {"level": "READ", "args": {"status_filter"}},
    "pause_download": {"level": "SAFE_ACTION", "args": {"download_id"}, "required": {"download_id"}},
    "resume_download": {"level": "SAFE_ACTION", "args": {"download_id"}, "required": {"download_id"}},
    "cancel_download": {"level": "PROTECTED_ACTION", "args": {"download_id", "delete_temp_file"}, "required": {"download_id"}},
    "set_download_limit": {"level": "SAFE_ACTION", "args": {"download_id", "speed_limit_bps"}, "required": {"speed_limit_bps"}},
    "get_server_health": {"level": "READ", "args": {"server_id"}},
}

SENSITIVE_CONTEXT_KEYS = frozenset(
    {"private_key", "password", "token", "access_token", "refresh_token", "cookie", "api_key", "secret", "browsing_history"}
)
# Any key that CONTAINS one of these fragments (after normalisation) is treated as sensitive,
# so "accessToken", "Authorization", "wg-private-key" or "apiKey" can no longer slip through.
_SENSITIVE_FRAGMENTS = (
    "privatekey", "password", "passwd", "token", "cookie", "apikey", "secret",
    "authorization", "browsinghistory", "credential", "presharedkey",
)
_MAX_CONTEXT_DEPTH = 8


def _is_sensitive_key(key: str) -> bool:
    if key.lower() in SENSITIVE_CONTEXT_KEYS:
        return True
    normalized = re.sub(r"[^a-z0-9]", "", key.lower())
    return any(fragment in normalized for fragment in _SENSITIVE_FRAGMENTS)


def _sanitize_value(value: Any, depth: int) -> Any:
    if depth > _MAX_CONTEXT_DEPTH:
        return None
    if isinstance(value, dict):
        return {
            k: _sanitize_value(v, depth + 1)
            for k, v in value.items()
            if isinstance(k, str) and not _is_sensitive_key(k)
        }
    if isinstance(value, list | tuple):
        return [_sanitize_value(v, depth + 1) for v in value]
    return value


def sanitize_ai_context(context: dict[str, Any] | None) -> dict[str, Any]:
    """Strip secrets, private keys, tokens, or browsing history (recursively, incl. lists)."""
    if not context:
        return {}
    cleaned = _sanitize_value(context, 0)
    return cleaned if isinstance(cleaned, dict) else {}


def _args_digest(arguments: dict[str, Any]) -> str:
    canonical = json.dumps(arguments, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@dataclass
class ConfirmationNonceRecord:
    user_id: str
    tool_name: str
    expires_at: float
    used: bool = False
    args_digest: str | None = None  # when set, the nonce is only valid for exactly these arguments


class AiToolAuthorizer:
    """Strict whitelist + schema + 3-tier RBAC + single-use confirmation nonce enforcement."""

    def __init__(self) -> None:
        self._nonces: dict[str, ConfirmationNonceRecord] = {}
        self.audit_log: deque[dict[str, Any]] = deque(maxlen=10_000)

    def _purge_nonces(self) -> None:
        now = time.monotonic()
        for k in [k for k, r in self._nonces.items() if r.used or r.expires_at < now]:
            del self._nonces[k]

    def issue_confirmation_nonce(
        self,
        user_id: str,
        tool_name: str,
        ttl_seconds: int = 120,
        arguments: dict[str, Any] | None = None,
    ) -> str:
        if tool_name not in ALLOWED_TOOLS:
            raise PermissionError(f"TOOL_NOT_ALLOWED: '{tool_name}' is not in the approved registry.")
        self._purge_nonces()
        if len(self._nonces) >= 50_000:
            raise PermissionError("TOO_MANY_PENDING_CONFIRMATIONS")
        nonce = secrets.token_urlsafe(24)
        self._nonces[nonce] = ConfirmationNonceRecord(
            user_id=user_id,
            tool_name=tool_name,
            expires_at=time.monotonic() + ttl_seconds,
            args_digest=_args_digest(arguments) if arguments is not None else None,
        )
        return nonce

    def authorize(
        self,
        user_id: str,
        tool_name: str,
        arguments: dict[str, Any],
        confirmation_nonce: str | None = None,
    ) -> dict[str, Any]:
        audit_id = str(uuid.uuid4())
        spec = ALLOWED_TOOLS.get(tool_name)

        if spec is None:
            return self._record(audit_id, user_id, tool_name, "DENIED", False, "UNKNOWN", "TOOL_NOT_ALLOWED")

        allowed_args: set[str] = spec["args"]
        required_args: set[str] = spec.get("required", set())
        provided_keys = set(arguments.keys())

        if not provided_keys.issubset(allowed_args) or not required_args.issubset(provided_keys):
            return self._record(audit_id, user_id, tool_name, "DENIED", False, spec["level"], "INVALID_TOOL_ARGUMENTS")

        level = str(spec["level"])
        if level == "PROTECTED_ACTION":
            if not confirmation_nonce:
                return self._record(audit_id, user_id, tool_name, "DENIED", False, level, "CONFIRMATION_REQUIRED")
            rec = self._nonces.get(confirmation_nonce)
            if (
                rec is None
                or rec.used
                or rec.user_id != user_id
                or rec.tool_name != tool_name
                or rec.expires_at < time.monotonic()
                or (rec.args_digest is not None and rec.args_digest != _args_digest(arguments))
            ):
                return self._record(audit_id, user_id, tool_name, "DENIED", False, level, "INVALID_OR_REUSED_NONCE")
            rec.used = True

        return self._record(audit_id, user_id, tool_name, "AUTHORIZED", True, level, "OK")

    def _record(
        self,
        audit_id: str,
        user_id: str,
        tool_name: str,
        status: str,
        authorized: bool,
        level: str,
        reason: str,
    ) -> dict[str, Any]:
        entry = {
            "audit_id": audit_id,
            "user_id": user_id,
            "tool_name": tool_name,
            "status": status,
            "permission_level": level,
            "reason": reason,
        }
        self.audit_log.append(entry)
        return {
            "authorized": authorized,
            "permission_level": level,
            "reason": reason,
            "audit_id": audit_id,
        }


class MultiProviderAiRouter:
    """Routes Arabic & English user commands across configured providers with local-first deterministic fallback."""

    def __init__(self, enabled_providers: list[str]) -> None:
        self._providers = enabled_providers or ["LOCAL_RULES"]

    def route_chat(
        self,
        message: str,
        language: str = "ar",
        preferred_provider: str = "AUTO",
        sanitized_context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        _ = sanitize_ai_context(sanitized_context)
        text = message.strip().lower()
        proposals: list[dict[str, Any]] = []

        if any(k in text for k in ("افحص", "سرعة", "سرعه", "speed", "ping", "انترنت", "الإنترنت", "حلل")):
            proposals.append({
                "tool_name": "get_network_stats",
                "arguments": {"include_history_count": 5},
                "permission_level": "READ",
                "requires_confirmation": False,
            })
            proposals.append({
                "tool_name": "run_speed_test",
                "arguments": {"mode": "QUICK"},
                "permission_level": "SAFE_ACTION",
                "requires_confirmation": False,
            })
            reply = (
                "سأفحص حالة اتصالك الحالية وأشغّل اختبار سرعة و Ping حقيقي لتشخيص المشكلة."
                if language == "ar"
                else "I will inspect your live network stats and run a real speed and ping test."
            )
        elif any(k in text for k in ("سيرفر", "vpn", "في بي ان")):
            proposals.append({
                "tool_name": "list_vpn_servers",
                "arguments": {"server_type": "ALL", "limit": 5},
                "permission_level": "READ",
                "requires_confirmation": False,
            })
            reply = (
                "سأجلب قائمة سيرفرات VPN الموثّقة مرتّبة حسب أقل Ping وفقدان حزم، ولن أتصل بأي سيرفر إلا بعد موافقتك."
                if language == "ar"
                else "I will list verified VPN servers ranked by lowest ping and packet loss; connecting requires your explicit approval."
            )
        elif any(k in text for k in ("استكمل", "تنزيل", "التنزيلات", "download", "بطيء")):
            proposals.append({
                "tool_name": "get_downloads",
                "arguments": {"status_filter": "ALL"},
                "permission_level": "READ",
                "requires_confirmation": False,
            })
            reply = (
                "سأراجع قائمة التنزيلات الحالية وحالة الأجزاء لمعرفة سبب البطء أو استكمال التنزيلات المتوقفة."
                if language == "ar"
                else "I will inspect your current downloads and segment states to diagnose speed or resume paused items."
            )
        else:
            reply = (
                "أنا مساعد RYN-AI-Speed. يمكنني فحص الإنترنت، مقارنة خوادم DNS، ترشيح أفضل سيرفر VPN، أو إدارة تنزيلاتك."
                if language == "ar"
                else "I am the RYN-AI-Speed Assistant. I can diagnose your network, benchmark DNS, rank VPN servers, or manage downloads."
            )

        # HONESTY RULE (plan: "never create fake functionality"): no external provider is called yet,
        # every reply above is produced by the deterministic local rules, so we must report exactly that.
        requested = preferred_provider if preferred_provider in self._providers else "AUTO"
        return {
            "reply": reply,
            "provider_used": "LOCAL_RULES",
            "requested_provider": requested,
            "proposed_tools": proposals,
        }
