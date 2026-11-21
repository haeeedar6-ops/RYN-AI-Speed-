"""Centralized strongly-typed configuration for RYN-AI-Speed Backend."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    app_env: str = field(default_factory=lambda: os.getenv("APP_ENV", "development"))
    app_version: str = field(default_factory=lambda: os.getenv("APP_VERSION", "1.0.0"))
    log_level: str = field(default_factory=lambda: os.getenv("LOG_LEVEL", "INFO"))
    policy_version: str = field(default_factory=lambda: os.getenv("POLICY_VERSION", "2026.11.1"))
    database_url: str = field(
        default_factory=lambda: os.getenv(
            "DATABASE_URL", "postgresql+asyncpg://ryn_user:change_me@localhost:5432/ryn_ai_speed"
        )
    )
    redis_url: str = field(default_factory=lambda: os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    access_token_ttl_seconds: int = field(
        default_factory=lambda: int(os.getenv("ACCESS_TOKEN_TTL_SECONDS", "900"))
    )
    refresh_token_ttl_days: int = field(
        default_factory=lambda: int(os.getenv("REFRESH_TOKEN_TTL_DAYS", "30"))
    )

    @property
    def ai_providers_enabled(self) -> list[str]:
        providers: list[str] = ["LOCAL_RULES"]
        if os.getenv("OPENAI_API_KEY"):
            providers.append("OPENAI")
        if os.getenv("GEMINI_API_KEY"):
            providers.append("GEMINI")
        if os.getenv("ANTHROPIC_API_KEY"):
            providers.append("ANTHROPIC")
        return providers


def get_settings() -> Settings:
    return Settings()
