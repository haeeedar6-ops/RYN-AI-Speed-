"""Durable SQLite store for accounts and refresh-token families (stdlib only, thread-safe, WAL).

Replaces the in-memory dicts so users and sessions survive restarts. Use ":memory:" for tests.
"""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  display_name TEXT,
  created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_uuid TEXT NOT NULL,
  family_id TEXT NOT NULL,
  expires_at REAL NOT NULL,
  is_used INTEGER NOT NULL DEFAULT 0 CHECK (is_used IN (0,1)),
  is_revoked INTEGER NOT NULL DEFAULT 0 CHECK (is_revoked IN (0,1))
);
CREATE INDEX IF NOT EXISTS idx_rt_family ON refresh_tokens(family_id);
CREATE INDEX IF NOT EXISTS idx_rt_user_dev ON refresh_tokens(user_id, device_uuid);
"""


class EmailAlreadyExistsError(Exception):
    pass


@dataclass
class StoredUser:
    id: str
    email: str
    password_hash: str
    display_name: str | None


@dataclass
class StoredRefresh:
    token_hash: str
    user_id: str
    device_uuid: str
    family_id: str
    expires_at: float
    is_used: bool
    is_revoked: bool


class AuthStore:
    def __init__(self, path: str = ":memory:") -> None:
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA foreign_keys = ON")
        if path != ":memory:":
            self._db.execute("PRAGMA journal_mode = WAL")
            self._db.execute("PRAGMA synchronous = NORMAL")
        self._db.execute("PRAGMA busy_timeout = 5000")
        self._db.executescript(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # ---- users ----
    def add_user(self, user: StoredUser, created_at: float) -> None:
        with self._lock:
            try:
                self._db.execute(
                    "INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?,?,?,?,?)",
                    (user.id, user.email, user.password_hash, user.display_name, created_at),
                )
            except sqlite3.IntegrityError as exc:
                raise EmailAlreadyExistsError(user.email) from exc

    def get_user_by_email(self, email: str) -> StoredUser | None:
        with self._lock:
            row = self._db.execute(
                "SELECT id,email,password_hash,display_name FROM users WHERE email=?", (email,)
            ).fetchone()
        return StoredUser(*row) if row else None

    # ---- refresh tokens ----
    def put_refresh(self, rec: StoredRefresh) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO refresh_tokens(token_hash,user_id,device_uuid,family_id,expires_at,is_used,is_revoked)"
                " VALUES (?,?,?,?,?,?,?)",
                (rec.token_hash, rec.user_id, rec.device_uuid, rec.family_id, rec.expires_at,
                 int(rec.is_used), int(rec.is_revoked)),
            )

    def get_refresh(self, token_hash: str) -> StoredRefresh | None:
        with self._lock:
            row = self._db.execute(
                "SELECT token_hash,user_id,device_uuid,family_id,expires_at,is_used,is_revoked"
                " FROM refresh_tokens WHERE token_hash=?",
                (token_hash,),
            ).fetchone()
        if not row:
            return None
        return StoredRefresh(row[0], row[1], row[2], row[3], row[4], bool(row[5]), bool(row[6]))

    def claim_refresh(self, token_hash: str) -> bool:
        """Atomically mark a token used. False if it was already used/revoked (replay or race)."""
        with self._lock:
            cur = self._db.execute(
                "UPDATE refresh_tokens SET is_used=1 WHERE token_hash=? AND is_used=0 AND is_revoked=0",
                (token_hash,),
            )
            return cur.rowcount == 1

    def revoke_token(self, token_hash: str) -> None:
        with self._lock:
            self._db.execute("UPDATE refresh_tokens SET is_revoked=1 WHERE token_hash=?", (token_hash,))

    def revoke_family(self, family_id: str) -> None:
        with self._lock:
            self._db.execute("UPDATE refresh_tokens SET is_revoked=1 WHERE family_id=?", (family_id,))

    def revoke_user_device(self, user_id: str, device_uuid: str) -> None:
        with self._lock:
            self._db.execute(
                "UPDATE refresh_tokens SET is_revoked=1 WHERE user_id=? AND device_uuid=?",
                (user_id, device_uuid),
            )

    def purge_expired(self, now: float) -> None:
        with self._lock:
            self._db.execute("DELETE FROM refresh_tokens WHERE expires_at < ?", (now,))
