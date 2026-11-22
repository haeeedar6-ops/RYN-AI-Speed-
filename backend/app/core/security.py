"""Cryptographic primitives for RYN-AI-Speed Backend:
- Argon2id password hashing (with strict PBKDF2-HMAC-SHA512 fallback if argon2-cffi unavailable)
- Ed25519 (EdDSA) asymmetric JWT issuance & verification
- Ed25519 detached manifest signing for VPN server lists
- 256-bit opaque refresh token generation & SHA-256 hashing
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

try:
    from argon2 import PasswordHasher
    from argon2.exceptions import VerifyMismatchError

    _ARGON2 = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4)
except ImportError:  # pragma: no cover
    _ARGON2 = None
    VerifyMismatchError = Exception  # type: ignore[misc,assignment]


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64url_decode(data: str) -> bytes:
    padding = "=" * ((4 - len(data) % 4) % 4)
    return base64.urlsafe_b64decode(data + padding)


def generate_opaque_token(num_bytes: int = 32) -> str:
    if num_bytes < 32:
        raise ValueError("Opaque token entropy must be at least 32 bytes (256 bits).")
    return secrets.token_urlsafe(num_bytes)


def hash_token_sha256(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


def hash_password(password: str) -> str:
    if len(password) < 12:
        raise ValueError("Password must be at least 12 characters.")
    if _ARGON2 is not None:
        return _ARGON2.hash(password)
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha512", password.encode("utf-8"), salt, 310_000)
    return f"pbkdf2_sha512$310000${_b64url_encode(salt)}${_b64url_encode(dk)}"


def verify_password(password: str, stored_hash: str) -> bool:
    if stored_hash.startswith("$argon2") and _ARGON2 is not None:
        try:
            return _ARGON2.verify(stored_hash, password)
        except VerifyMismatchError:
            return False
    if stored_hash.startswith("pbkdf2_sha512$"):
        parts = stored_hash.split("$")
        if len(parts) != 4:
            return False
        iterations = int(parts[1])
        salt = _b64url_decode(parts[2])
        expected = _b64url_decode(parts[3])
        actual = hashlib.pbkdf2_hmac("sha512", password.encode("utf-8"), salt, iterations)
        return hmac.compare_digest(actual, expected)
    return False


class Ed25519Signer:
    """Asymmetric Ed25519 signer for JWT (EdDSA) and VPN Server Manifests."""

    def __init__(self, private_key: Ed25519PrivateKey | None = None) -> None:
        self._private = private_key or Ed25519PrivateKey.generate()
        self._public = self._private.public_key()

    @classmethod
    def from_pem(cls, private_pem: str) -> Ed25519Signer:
        key = serialization.load_pem_private_key(private_pem.encode("utf-8"), password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise TypeError("Expected Ed25519 private key.")
        return cls(key)

    @property
    def public_key(self) -> Ed25519PublicKey:
        return self._public

    def issue_jwt(self, subject: str, device_uuid: str, ttl_seconds: int = 900) -> str:
        now = int(time.time())
        header = {"alg": "EdDSA", "typ": "JWT"}
        payload = {
            "sub": subject,
            "dev": device_uuid,
            "iat": now,
            "exp": now + ttl_seconds,
            "jti": secrets.token_hex(16),
        }
        signing_input = (
            f"{_b64url_encode(json.dumps(header, separators=(',', ':')).encode())}."
            f"{_b64url_encode(json.dumps(payload, separators=(',', ':')).encode())}"
        )
        sig = self._private.sign(signing_input.encode("ascii"))
        return f"{signing_input}.{_b64url_encode(sig)}"

    def verify_jwt(self, token: str) -> dict[str, Any]:
        parts = token.split(".")
        if len(parts) != 3:
            raise ValueError("Malformed JWT")
        signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
        sig = _b64url_decode(parts[2])
        self._public.verify(sig, signing_input)
        payload: dict[str, Any] = json.loads(_b64url_decode(parts[1]).decode("utf-8"))
        if int(payload.get("exp", 0)) < int(time.time()):
            raise ValueError("JWT expired")
        return payload

    def sign_manifest(self, canonical_payload: bytes) -> str:
        return _b64url_encode(self._private.sign(canonical_payload))

    def verify_manifest(self, canonical_payload: bytes, signature_b64: str) -> bool:
        try:
            self._public.verify(_b64url_decode(signature_b64), canonical_payload)
            return True
        except Exception:
            return False
