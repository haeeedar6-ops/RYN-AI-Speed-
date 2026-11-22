"""Authentication & Session Service with Refresh Token Rotation and Reuse-Detection."""

from __future__ import annotations

import time
import uuid

from app.core.security import (
    Ed25519Signer,
    generate_opaque_token,
    hash_password,
    hash_token_sha256,
    verify_password,
)
from app.core.store import AuthStore, EmailAlreadyExistsError, StoredRefresh, StoredUser


class AuthError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 401) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


_DUMMY_HASH: str | None = None


def _dummy_hash() -> str:
    """Lazily built hash used to equalize login timing for unknown emails (anti user-enumeration)."""
    global _DUMMY_HASH  # noqa: PLW0603
    if _DUMMY_HASH is None:
        _DUMMY_HASH = hash_password("timing-equalizer-not-a-real-password")
    return _DUMMY_HASH


class AuthService:
    def __init__(
        self,
        signer: Ed25519Signer,
        access_ttl_sec: int = 900,
        refresh_ttl_days: int = 30,
        store: AuthStore | None = None,
    ) -> None:
        self._signer = signer
        self._access_ttl_sec = access_ttl_sec
        self._refresh_ttl_days = refresh_ttl_days
        self._store = store or AuthStore(":memory:")

    def register(self, email: str, password: str, device_uuid: str, display_name: str | None = None) -> dict[str, object]:
        normalized = email.strip().lower()
        user = StoredUser(
            id=str(uuid.uuid4()),
            email=normalized,
            password_hash=hash_password(password),
            display_name=display_name,
        )
        try:
            self._store.add_user(user, time.time())
        except EmailAlreadyExistsError as exc:
            raise AuthError("EMAIL_ALREADY_EXISTS", "An account with this email already exists.", 409) from exc
        return self._issue_session_pair(user.id, device_uuid, family_id=str(uuid.uuid4()))

    def login(self, email: str, password: str, device_uuid: str) -> dict[str, object]:
        user = self._store.get_user_by_email(email.strip().lower())
        if user is None:
            verify_password(password, _dummy_hash())  # same CPU cost as a real check
            raise AuthError("INVALID_CREDENTIALS", "Invalid email or password.", 401)
        if not verify_password(password, user.password_hash):
            raise AuthError("INVALID_CREDENTIALS", "Invalid email or password.", 401)
        return self._issue_session_pair(user.id, device_uuid, family_id=str(uuid.uuid4()))

    def refresh(self, refresh_token: str, device_uuid: str) -> dict[str, object]:
        token_hash = hash_token_sha256(refresh_token)
        rec = self._store.get_refresh(token_hash)
        if rec is None or rec.device_uuid != device_uuid:
            raise AuthError("INVALID_REFRESH_TOKEN", "Refresh token is invalid.", 401)

        if rec.is_used or rec.is_revoked:
            # TOKEN REUSE DETECTED: immediately revoke entire session family
            self._store.revoke_family(rec.family_id)
            raise AuthError(
                "REFRESH_TOKEN_REUSE_DETECTED",
                "Security alert: rotated refresh token reuse detected; session family revoked.",
                401,
            )

        if rec.expires_at < time.time():
            self._store.revoke_token(token_hash)
            raise AuthError("REFRESH_TOKEN_EXPIRED", "Refresh token has expired.", 401)

        # Atomic claim: two concurrent refreshes with the same token cannot both succeed.
        if not self._store.claim_refresh(token_hash):
            self._store.revoke_family(rec.family_id)
            raise AuthError(
                "REFRESH_TOKEN_REUSE_DETECTED",
                "Security alert: rotated refresh token reuse detected; session family revoked.",
                401,
            )
        return self._issue_session_pair(rec.user_id, rec.device_uuid, family_id=rec.family_id)

    def logout(self, user_id: str, device_uuid: str) -> None:
        self._store.revoke_user_device(user_id, device_uuid)

    def _issue_session_pair(self, user_id: str, device_uuid: str, family_id: str) -> dict[str, object]:
        self._store.purge_expired(time.time())
        access_token = self._signer.issue_jwt(user_id, device_uuid, self._access_ttl_sec)
        raw_refresh = generate_opaque_token(32)
        self._store.put_refresh(
            StoredRefresh(
                token_hash=hash_token_sha256(raw_refresh),
                user_id=user_id,
                device_uuid=device_uuid,
                family_id=family_id,
                expires_at=time.time() + self._refresh_ttl_days * 86400,
                is_used=False,
                is_revoked=False,
            )
        )
        return {
            "access_token": access_token,
            "refresh_token": raw_refresh,
            "token_type": "Bearer",
            "expires_in_sec": self._access_ttl_sec,
        }
