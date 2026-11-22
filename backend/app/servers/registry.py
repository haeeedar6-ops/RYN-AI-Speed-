"""Verified VPN Server Registry & Ed25519-Signed Manifest Service."""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from datetime import UTC, datetime

from app.core.security import Ed25519Signer

_WG_PUBKEY_RE = re.compile(r"^[A-Za-z0-9+/]{43}=$")
_ENDPOINT_RE = re.compile(r"^(?:[A-Za-z0-9.-]{1,253}|\[[0-9A-Fa-f:]+\]):(\d{1,5})$")
_COUNTRY_RE = re.compile(r"^[A-Z]{2}$")
_SERVER_TYPES = frozenset({"PRIVATE", "COMMUNITY"})


@dataclass
class VpnServerRecord:
    server_id: str
    server_name: str
    country: str
    region: str
    endpoint: str
    public_key: str
    protocol: str
    server_type: str           # PRIVATE | COMMUNITY
    operator_verified: bool
    no_logs_verified: bool
    capacity_status: str       # LOW | MEDIUM | HIGH | FULL | UNKNOWN
    health_status: str         # HEALTHY | DEGRADED | OFFLINE | UNKNOWN
    latency_ms: int | None
    packet_loss_pct: float | None
    provider: str
    privacy_policy_url: str
    last_checked: str


class VpnServerRegistry:
    """Enforces strict verification on Community servers and cryptographically signs server lists."""

    def __init__(self, signer: Ed25519Signer) -> None:
        self._signer = signer
        self._servers: dict[str, VpnServerRecord] = {}

    def register_server(self, record: VpnServerRecord) -> None:
        if not _WG_PUBKEY_RE.match(record.public_key.strip()):
            raise ValueError("Invalid WireGuard public key format.")
        if record.protocol != "wireguard":
            raise ValueError("Only WireGuard protocol is permitted.")
        if record.server_type not in _SERVER_TYPES:
            raise ValueError("server_type must be PRIVATE or COMMUNITY.")
        endpoint = _ENDPOINT_RE.match(record.endpoint.strip())
        if endpoint is None or not 1 <= int(endpoint.group(1)) <= 65535:
            raise ValueError("Invalid endpoint; expected host:port.")
        if not _COUNTRY_RE.match(record.country.upper()):
            raise ValueError("Invalid country code.")
        if record.server_type == "COMMUNITY" and (
            not record.operator_verified or not record.no_logs_verified
        ):
            raise PermissionError(
                "UNVERIFIED_COMMUNITY_SERVER: Community servers require operator & no-logs verification."
            )
        self._servers[record.server_id] = record

    def update_health(
        self,
        server_id: str,
        health_status: str,
        latency_ms: int | None,
        packet_loss_pct: float | None,
        capacity_status: str,
    ) -> VpnServerRecord:
        rec = self._servers.get(server_id)
        if rec is None:
            raise KeyError(f"Server '{server_id}' not found")
        rec.health_status = health_status
        rec.latency_ms = latency_ms
        rec.packet_loss_pct = packet_loss_pct
        rec.capacity_status = capacity_status
        rec.last_checked = datetime.now(UTC).isoformat()
        return rec

    def get_server(self, server_id: str) -> VpnServerRecord | None:
        return self._servers.get(server_id)

    def list_signed_manifest(
        self, country: str | None = None, server_type: str | None = None
    ) -> tuple[dict[str, object], str]:
        items = list(self._servers.values())
        if country:
            items = [s for s in items if s.country.upper() == country.upper()]
        if server_type:
            items = [s for s in items if s.server_type == server_type.upper()]

        payload: dict[str, object] = {
            "servers": [asdict(s) for s in sorted(items, key=lambda x: x.server_id)],
            "generated_at": datetime.now(UTC).isoformat(),
        }
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        signature = self._signer.sign_manifest(canonical)
        return payload, signature

    def health_summary(self) -> dict[str, object]:
        return {
            "items": [
                {
                    "server_id": s.server_id,
                    "health_status": s.health_status,
                    "capacity_status": s.capacity_status,
                    "latency_ms": s.latency_ms,
                    "packet_loss_pct": s.packet_loss_pct,
                    "last_checked": s.last_checked,
                }
                for s in self._servers.values()
            ],
            "checked_at": datetime.now(UTC).isoformat(),
        }
