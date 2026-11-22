"""RYN-AI-Speed Backend (Phase P8) — Full Auth, Signed VPN Registry, AI Router, Tool Sandbox & Config."""

from __future__ import annotations

import os
import uuid
from dataclasses import asdict
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.ai.router_service import AiToolAuthorizer, MultiProviderAiRouter
from app.auth.service import AuthError, AuthService
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.core.rate_limit import IdempotencyGuard, SlidingWindowRateLimiter
from app.core.security import Ed25519Signer
from app.servers.registry import VpnServerRegistry

settings = get_settings()
configure_logging(settings.log_level)

_pem = os.getenv("JWT_PRIVATE_KEY_PEM", "").strip()
signer = Ed25519Signer.from_pem(_pem) if _pem else Ed25519Signer()
auth_service = AuthService(
    signer=signer,
    access_ttl_sec=settings.access_token_ttl_seconds,
    refresh_ttl_days=settings.refresh_token_ttl_days,
)
server_registry = VpnServerRegistry(signer=signer)
ai_router = MultiProviderAiRouter(settings.ai_providers_enabled)
ai_authorizer = AiToolAuthorizer()
rate_limiter = SlidingWindowRateLimiter()
idempotency_guard = IdempotencyGuard()

app = FastAPI(
    title="RYN-AI-Speed Backend API",
    version=settings.app_version,
    docs_url="/docs" if settings.app_env != "production" else None,
    redoc_url=None,
)


def _problem(status: int, code: str, title: str, detail: str, request_id: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        media_type="application/problem+json",
        content={
            "type": f"https://ryn-ai-speed.example/errors/{code.lower()}",
            "title": title,
            "status": status,
            "detail": detail,
            "error_code": code,
            "request_id": request_id,
        },
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


@app.exception_handler(AuthError)
async def auth_error_handler(request: Request, exc: AuthError) -> JSONResponse:
    return _problem(exc.status_code, exc.code, "Authentication Error", exc.message, request.state.request_id)


@app.exception_handler(Exception)
async def problem_details_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    return _problem(500, "INTERNAL_SERVER_ERROR", "Internal Server Error", "An unexpected error occurred.", request_id)


def require_rate_limit(route_group: str, limit: int, window_sec: int = 60) -> Any:
    def _dep(request: Request, response: Response) -> None:
        client_ip = request.client.host if request.client else "unknown"
        allowed, remaining, retry_after = rate_limiter.check_and_consume(
            f"{route_group}:{client_ip}", limit=limit, window_seconds=window_sec
        )
        response.headers["X-RateLimit-Limit"] = str(limit)
        response.headers["X-RateLimit-Remaining"] = str(remaining)
        if not allowed:
            raise HTTPException(status_code=429, detail="Rate limit exceeded", headers={"Retry-After": str(retry_after)})
    return _dep


def get_current_claims(authorization: str = Header(...)) -> dict[str, Any]:
    if not authorization.startswith("Bearer "):
        raise AuthError("MISSING_BEARER_TOKEN", "Bearer token required.", 401)
    try:
        return signer.verify_jwt(authorization[7:].strip())
    except Exception as exc:
        raise AuthError("INVALID_ACCESS_TOKEN", "Access token is invalid or expired.", 401) from exc


class RegisterPayload(BaseModel):
    email: str = Field(..., min_length=5, max_length=254)
    password: str = Field(..., min_length=12, max_length=128)
    display_name: str | None = Field(default=None, max_length=80)
    device_uuid: str = Field(..., min_length=8, max_length=64)
    platform: str = Field(..., pattern="^(ANDROID|WINDOWS)$")
    device_name: str = Field(..., min_length=1, max_length=80)
    app_version: str = Field(..., min_length=1, max_length=32)


class LoginPayload(BaseModel):
    email: str = Field(..., min_length=5, max_length=254)
    password: str = Field(..., min_length=1, max_length=128)
    device_uuid: str = Field(..., min_length=8, max_length=64)
    platform: str = Field(..., pattern="^(ANDROID|WINDOWS)$")
    app_version: str = Field(..., min_length=1, max_length=32)


class RefreshPayload(BaseModel):
    refresh_token: str = Field(..., min_length=32)
    device_uuid: str = Field(..., min_length=8, max_length=64)


class AiChatPayload(BaseModel):
    session_id: str = Field(..., min_length=8, max_length=64)
    message: str = Field(..., min_length=1, max_length=4000)
    language: str = Field(default="ar", pattern="^(ar|en)$")
    preferred_provider: str = Field(default="AUTO")
    sanitized_context: dict[str, Any] | None = None


class AiActionPayload(BaseModel):
    tool_name: str = Field(..., min_length=2, max_length=64)
    arguments: dict[str, Any] = Field(default_factory=dict)
    user_confirmation_nonce: str | None = None


class ConfirmNoncePayload(BaseModel):
    tool_name: str = Field(..., min_length=2, max_length=64)


@app.get("/health", tags=["system"])
async def health_check() -> dict[str, str]:
    return {"status": "ok", "version": settings.app_version, "env": settings.app_env}


@app.post("/api/v1/auth/register", status_code=201, tags=["auth"], dependencies=[Depends(require_rate_limit("auth_reg", 5, 60))])
async def register_user(payload: RegisterPayload, idempotency_key: str = Header(..., alias="Idempotency-Key")) -> dict[str, object]:
    if not idempotency_guard.claim(f"reg:{idempotency_key}"):
        raise AuthError("DUPLICATE_REQUEST", "Duplicate Idempotency-Key replay blocked.", 409)
    return auth_service.register(payload.email, payload.password, payload.device_uuid, payload.display_name)


@app.post("/api/v1/auth/login", tags=["auth"], dependencies=[Depends(require_rate_limit("auth_login", 10, 60))])
async def login_user(payload: LoginPayload) -> dict[str, object]:
    return auth_service.login(payload.email, payload.password, payload.device_uuid)


@app.post("/api/v1/auth/refresh", tags=["auth"], dependencies=[Depends(require_rate_limit("auth_refresh", 20, 60))])
async def refresh_tokens(payload: RefreshPayload) -> dict[str, object]:
    return auth_service.refresh(payload.refresh_token, payload.device_uuid)


@app.post("/api/v1/auth/logout", status_code=204, tags=["auth"])
async def logout_user(claims: dict[str, Any] = Depends(get_current_claims)) -> Response:
    auth_service.logout(str(claims["sub"]), str(claims["dev"]))
    return Response(status_code=204)


@app.get("/api/v1/servers", tags=["vpn"])
async def list_vpn_servers(
    response: Response,
    country: str | None = Query(default=None, pattern="^[A-Z]{2}$"),
    server_type: str | None = Query(default=None, pattern="^(PRIVATE|COMMUNITY)$"),
    _claims: dict[str, Any] = Depends(get_current_claims),
) -> dict[str, object]:
    payload, signature = server_registry.list_signed_manifest(country=country, server_type=server_type)
    response.headers["X-Manifest-Signature"] = signature
    return payload


@app.get("/api/v1/servers/health", tags=["vpn"])
async def get_servers_health(_claims: dict[str, Any] = Depends(get_current_claims)) -> dict[str, object]:
    return server_registry.health_summary()


@app.get("/api/v1/servers/{server_id}", tags=["vpn"])
async def get_vpn_server_by_id(server_id: str, request: Request, _claims: dict[str, Any] = Depends(get_current_claims)) -> Any:
    rec = server_registry.get_server(server_id)
    if rec is None:
        return _problem(404, "SERVER_NOT_FOUND", "Not Found", f"Server '{server_id}' not found.", request.state.request_id)
    return asdict(rec)


@app.post("/api/v1/ai/chat", tags=["ai"], dependencies=[Depends(require_rate_limit("ai_chat", 30, 60))])
async def ai_chat(payload: AiChatPayload, _claims: dict[str, Any] = Depends(get_current_claims)) -> dict[str, Any]:
    return ai_router.route_chat(
        message=payload.message,
        language=payload.language,
        preferred_provider=payload.preferred_provider,
        sanitized_context=payload.sanitized_context,
    )


@app.post("/api/v1/ai/confirm-nonce", tags=["ai"], dependencies=[Depends(require_rate_limit("ai_nonce", 20, 60))])
async def create_confirmation_nonce(payload: ConfirmNoncePayload, claims: dict[str, Any] = Depends(get_current_claims)) -> dict[str, str]:
    try:
        nonce = ai_authorizer.issue_confirmation_nonce(str(claims["sub"]), payload.tool_name)
    except PermissionError as exc:
        raise AuthError("TOOL_NOT_ALLOWED", str(exc), 403) from exc
    return {"user_confirmation_nonce": nonce, "tool_name": payload.tool_name}


@app.post("/api/v1/ai/action", tags=["ai"], dependencies=[Depends(require_rate_limit("ai_action", 30, 60))])
async def authorize_ai_action(
    payload: AiActionPayload,
    idempotency_key: str = Header(..., alias="Idempotency-Key"),
    claims: dict[str, Any] = Depends(get_current_claims),
) -> dict[str, Any]:
    if not idempotency_guard.claim(f"ai_act:{idempotency_key}"):
        raise AuthError("DUPLICATE_REQUEST", "Duplicate Idempotency-Key replay blocked.", 409)
    res = ai_authorizer.authorize(
        user_id=str(claims["sub"]),
        tool_name=payload.tool_name,
        arguments=payload.arguments,
        confirmation_nonce=payload.user_confirmation_nonce,
    )
    if not res["authorized"]:
        raise AuthError(str(res["reason"]), f"AI action denied: {res['reason']}", 403)
    return res


@app.get("/api/v1/config", tags=["config"])
async def get_app_config(_claims: dict[str, Any] = Depends(get_current_claims)) -> dict[str, Any]:
    return {
        "policy_version": settings.policy_version,
        "feature_flags": {
            "download_engine_v2": True,
            "vpn_kill_switch_enforced": True,
            "ai_local_rules_enabled": True,
            "ai_auto_manager_enabled": True,
        },
        "ai_providers_enabled": settings.ai_providers_enabled,
    }


@app.get("/api/v1/version", tags=["version"])
async def get_latest_version(platform: str = Query(..., pattern="^(ANDROID|WINDOWS)$")) -> dict[str, str]:
    return {
        "platform": platform,
        "latest_version": settings.app_version,
        "min_supported_version": "1.0.0",
        "download_url": f"https://releases.ryn-ai-speed.example/{platform.lower()}/v{settings.app_version}",
        "checksum_sha256": "pending_p11_signed_release",
        "signature_ed25519": "pending_p11_signed_release",
        "release_notes_md": f"RYN-AI-Speed v{settings.app_version}",
    }
