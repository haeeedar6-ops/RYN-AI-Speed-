"""RYN-AI-Speed Backend Foundation (Phase P1) — FastAPI Entrypoint."""

from __future__ import annotations

import uuid
from typing import Any

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from app.core.config import get_settings
from app.core.logging import configure_logging

settings = get_settings()
configure_logging(settings.log_level)

app = FastAPI(
    title="RYN-AI-Speed Backend API",
    version=settings.app_version,
    docs_url="/docs" if settings.app_env != "production" else None,
    redoc_url=None,
)


@app.middleware("http")
async def security_headers_and_request_id(request: Request, call_next: Any) -> Any:
    request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Strict-Transport-Security"] = "max-age=63072000; includeSubDomains; preload"
    response.headers["Cache-Control"] = "no-store"
    return response


@app.exception_handler(Exception)
async def problem_details_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """RFC 9457 Problem Details envelope with zero stack-trace leakage."""
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    return JSONResponse(
        status_code=500,
        media_type="application/problem+json",
        content={
            "type": "https://ryn-ai-speed.example/errors/internal-error",
            "title": "Internal Server Error",
            "status": 500,
            "detail": "An unexpected error occurred.",
            "error_code": "INTERNAL_SERVER_ERROR",
            "request_id": request_id,
        },
    )


@app.get("/health", tags=["system"])
async def health_check() -> dict[str, str]:
    return {"status": "ok", "version": settings.app_version, "env": settings.app_env}


@app.get("/api/v1/config", tags=["config"])
async def get_app_config() -> dict[str, Any]:
    return {
        "policy_version": settings.policy_version,
        "feature_flags": {
            "download_engine_v2": True,
            "vpn_kill_switch_enforced": True,
            "ai_local_rules_enabled": True,
        },
        "ai_providers_enabled": settings.ai_providers_enabled,
    }


@app.get("/api/v1/version", tags=["version"])
async def get_latest_version(
    platform: str = Query(..., pattern="^(ANDROID|WINDOWS)$"),
) -> dict[str, str]:
    return {
        "platform": platform,
        "latest_version": settings.app_version,
        "min_supported_version": "1.0.0",
        "download_url": f"https://releases.ryn-ai-speed.example/{platform.lower()}/v{settings.app_version}",
        "checksum_sha256": "pending_p11_signed_release",
        "signature_ed25519": "pending_p11_signed_release",
        "release_notes_md": f"RYN-AI-Speed v{settings.app_version} Foundation Build",
    }
