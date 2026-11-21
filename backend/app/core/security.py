"""Cryptographic primitives for RYN-AI-Speed Backend (Opaque tokens, SHA-256, constant-time checks)."""

from __future__ import annotations

import hashlib
import hmac
import secrets


def generate_opaque_token(num_bytes: int = 32) -> str:
    """Generate a cryptographically secure URL-safe opaque token (256-bit default)."""
    if num_bytes < 32:
        raise ValueError("Opaque token entropy must be at least 32 bytes (256 bits).")
    return secrets.token_urlsafe(num_bytes)


def hash_token_sha256(token: str) -> str:
    """Hash opaque refresh tokens before persisting to PostgreSQL."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def constant_time_equals(a: str, b: str) -> bool:
    """Timing-attack safe string comparison."""
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))
