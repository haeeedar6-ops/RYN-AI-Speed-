"""Sliding-window rate limiter and Idempotency-Key replay guard."""

from __future__ import annotations

import time
from collections import defaultdict, deque


class SlidingWindowRateLimiter:
    """Thread/async-safe in-process sliding-window limiter (Redis-compatible interface)."""

    def __init__(self) -> None:
        self._buckets: dict[str, deque[float]] = defaultdict(deque)

    def check_and_consume(self, key: str, limit: int, window_seconds: int) -> tuple[bool, int, int]:
        """Returns (allowed, remaining, retry_after_seconds)."""
        now = time.monotonic()
        cutoff = now - window_seconds
        bucket = self._buckets[key]
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()

        if len(bucket) >= limit:
            retry_after = max(1, int(window_seconds - (now - bucket[0])) + 1)
            return False, 0, retry_after

        bucket.append(now)
        return True, max(0, limit - len(bucket)), 0


class IdempotencyGuard:
    """Prevents replay/duplicate execution of sensitive mutating POST requests."""

    def __init__(self, ttl_seconds: int = 3600) -> None:
        self._ttl = ttl_seconds
        self._seen: dict[str, float] = {}

    def claim(self, idempotency_key: str) -> bool:
        now = time.monotonic()
        expired = [k for k, exp in self._seen.items() if exp <= now]
        for k in expired:
            del self._seen[k]

        if idempotency_key in self._seen:
            return False
        self._seen[idempotency_key] = now + self._ttl
        return True
