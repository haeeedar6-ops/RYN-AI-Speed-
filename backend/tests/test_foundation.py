"""Foundation unit tests for RYN-AI-Speed Backend (Phase P1)."""

from __future__ import annotations

from app.core.logging import redact_headers, redact_text
from app.core.security import constant_time_equals, generate_opaque_token, hash_token_sha256


def test_redact_text_strips_jwt_bearer_qs_and_wg_key() -> None:
    sample = (
        "Authorization: Bearer super_secret_tok_123 "
        "jwt=eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ1MSJ9.c2lnbmF0dXJl "
        "https://cdn.example.com/file.zip?token=abc123&sig=xyz999 "
        "wg=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    )
    cleaned = redact_text(sample)
    assert "super_secret_tok_123" not in cleaned
    assert "eyJhbGciOiJFZERTQSJ9" not in cleaned
    assert "abc123" not in cleaned
    assert "xyz999" not in cleaned
    assert "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" not in cleaned


def test_redact_headers_masks_sensitive_keys() -> None:
    headers = {
        "Authorization": "Bearer secret-jwt",
        "Cookie": "session=1234",
        "X-Request-ID": "req-001",
    }
    redacted = redact_headers(headers)
    assert redacted["Authorization"] == "[REDACTED]"
    assert redacted["Cookie"] == "[REDACTED]"
    assert redacted["X-Request-ID"] == "req-001"


def test_opaque_token_entropy_and_sha256_hashing() -> None:
    tok1 = generate_opaque_token()
    tok2 = generate_opaque_token()
    assert tok1 != tok2
    assert len(tok1) >= 40
    h1 = hash_token_sha256(tok1)
    assert len(h1) == 64
    assert constant_time_equals(h1, hash_token_sha256(tok1))
    assert not constant_time_equals(h1, hash_token_sha256(tok2))
