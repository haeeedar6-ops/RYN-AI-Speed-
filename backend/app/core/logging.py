"""Structured JSON logging with mandatory PII & secret redaction."""

from __future__ import annotations

import json
import logging
import re
from datetime import UTC, datetime
from typing import Any

_JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
_BEARER_PATTERN = re.compile(r"(?i)(Bearer\s+)[A-Za-z0-9._\-~+/]+=*")
_SENSITIVE_QS_PATTERN = re.compile(
    r"(?i)([?&](?:token|key|secret|sig|password|auth|api_key)=)[^&\s]+"
)
_WG_KEY_PATTERN = re.compile(r"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{43}=(?![A-Za-z0-9+/=])")

SENSITIVE_HEADER_KEYS = frozenset(
    {"authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization"}
)


def redact_text(value: str) -> str:
    """Strip JWTs, Bearer tokens, WireGuard keys, and sensitive query parameters."""
    redacted = _JWT_PATTERN.sub("[REDACTED_JWT]", value)
    redacted = _BEARER_PATTERN.sub(r"\1[REDACTED_TOKEN]", redacted)
    redacted = _SENSITIVE_QS_PATTERN.sub(r"\1[REDACTED]", redacted)
    redacted = _WG_KEY_PATTERN.sub("[REDACTED_KEY]", redacted)
    return redacted


def redact_headers(headers: dict[str, str]) -> dict[str, str]:
    """Return a copy of HTTP headers with all sensitive fields masked."""
    return {
        k: ("[REDACTED]" if k.lower() in SENSITIVE_HEADER_KEYS else redact_text(v))
        for k, v in headers.items()
    }


class RedactedJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        if hasattr(record, "request_id"):
            payload["request_id"] = record.request_id
        return json.dumps(payload, ensure_ascii=False)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(RedactedJsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
