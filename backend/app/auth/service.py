"""Authentication & Session Service with Refresh Token Rotation and Reuse-Detection."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from app.core.security import (
    Ed25519Signer,
    generate_opaque_token,
    hash_password,
    hash_token_sha256,
    verify_password,
)


class AuthError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 401) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


@dataclass
class UserRecord:
    id: str
    email: str
    password_hash: str
    display_name: str | None


@dataclass
class RefreshTokenRecord:
    token_hash: str
    user_id: str
    device_uuid: str
    family_id: str
    expires_at: datetime
    is_used: bool = False
    is_revoked: bool = False


class AuthService:
    def __init__(self, signer: Ed25519Signer, access_ttl_sec: int = 900, refresh_ttl_days: int = 30) -> None:
        self._signer = signer
        self._access_ttl_sec = access_ttl_sec
        self._refresh_ttl_days = refresh_ttl_days
        self._users_by_email: dict[str, UserRecord] = {}
        self._refresh_by_hash: dict[str, RefreshTokenRecord] = {}

    def register(self, email: str, password: str, device_uuid: str, display_name: str | None = None) -> dict[str, object]:
        normalized = email.strip().lower()
        if normalized in self._users_by_email:
            raise AuthError("EMAIL_ALREADY_EXISTS", "An account with this email already exists.", 409)
        user = UserRecord(
            id=str(uuid.uuid4()),
            email=normalized,
            password_hash=hash_password(password),
            display_name=display_name,
        )
        self._users_by_email[normalized] = user
        return self._issue_session_pair(user.id, device_uuid, family_id=str(uuid.uuid4()))

    def login(self, email: str, password: str, device_uuid: str) -> dict[str, object]:
        normalized = email.strip().lower()
        user = self._users_by_email.get(normalized)
        if user is None or not verify_password(password, user.password_hash):
            raise AuthError("INVALID_CREDENTIALS", "Invalid email or password.", 401)
        return self._issue_session_pair(user.id, device_uuid, family_id=str(uuid.uuid4()))

    def refresh(self, refresh_token: str, device_uuid: str) -> dict[str, object]:
        token_hash = hash_token_sha256(refresh_token)
        rec = self._refresh_by_hash.get(token_hash)
        if rec is None or rec.device_uuid != device_uuid:
            raise AuthError("INVALID_REFRESH_TOKEN", "Refresh token is invalid.", 401)

        if rec.is_used or rec.is_revoked:
            # TOKEN REUSE DETECTED: immediately revoke entire session family
            self._revoke_family(rec.family_id)
            raise AuthError(
                "REFRESH_TOKEN_REUSE_DETECTED",
                "Security alert: rotated refresh token reuse detected; session family revoked.",
                401,
            )

        if rec.expires_at < datetime.now(timezone.utc):
            rec.is_revoked = True
            raise AuthError("REFRESH_TOKEN_EXPIRED", "Refresh token has expired.", 401)

        rec.is_used = True
        return self._issue_session_pair(rec.user_id, rec.device_uuid, family_id=rec.family_id)

    def logout(self, user_id: str, device_uuid: str) -> None:
        for rec in self._refresh_by_hash.values():
            if rec.user_id == user_id and rec.device_uuid == device_uuid:
                rec.is_revoked = True

    def _revoke_family(self, family_id: str) -> None:
        for rec in self._refresh_by_hash.values():
            if rec.family_id == family_id:
                rec.is_revoked = True

    def _issue_session_pair(self, user_id: str, device_uuid: str, family_id: str) -> dict[str, object]:
        access_token = self._signer.issue_jwt(user_id, device_uuid, self._access_ttl_sec)
        raw_refresh = generate_opaque_token(32)
        rec = RefreshTokenRecord(
            token_hash=hash_token_sha256(raw_refresh),
            user_id=user_id,
            device_uuid=device_uuid,
            family_id=family_id,
            expires_at=datetime.now(timezone.utc) + timedelta(days=self._refresh_ttl_days),
        )
        self._refresh_by_hash[rec.token_hash] = rec
        return {
            "access_token": access_token,
            "refresh_token": raw_refresh,
            "token_type": "Bearer",
            "expires_in_sec": self._access_ttl_sec,
        }
