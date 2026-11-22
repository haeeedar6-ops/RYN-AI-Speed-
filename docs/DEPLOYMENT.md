# RYN-AI-Speed v1.0.0 — Deployment & Signing Guide (`docs/DEPLOYMENT.md`)

## 1. GitHub Actions Secrets (Never Commit to Git)
Configure the following repository secrets in **GitHub -> Settings -> Secrets and variables -> Actions**:
- `ANDROID_KEYSTORE_BASE64`: Base64-encoded release `.jks` / `.keystore`
- `ANDROID_KEY_ALIAS`: Signing key alias
- `ANDROID_KEYSTORE_PASSWORD`: Keystore password
- `ANDROID_KEY_PASSWORD`: Private key password
- `WINDOWS_SIGN_CERT_PFX_BASE64`: Base64-encoded Authenticode `.pfx` certificate for MSIX signing
- `WINDOWS_SIGN_CERT_PASSWORD`: Authenticode certificate password

## 2. Cutting an Official Signed Release (`v1.0.0`)
1. Ensure all CI checks (`ci-contracts`, `ci-backend`, `ci-android`, `ci-windows`, `security-scan`, `ci-integration`) are green on `main`.
2. Tag and push the SemVer release:
   ```bash
   git tag -a v1.0.0 -m "RYN-AI-Speed v1.0.0 Release"
   git push origin v1.0.0
   ```
3. `.github/workflows/release.yml` runs all security & E2E gates, builds & signs Android APK and Windows artifacts, generates `SHA256SUMS.txt`, and publishes the GitHub Release.

## 3. Deploying the Central Backend (TLS 1.3 + PostgreSQL 16 + Redis)
1. Copy `backend/.env.example` to `backend/.env` on your production server and set strong `POSTGRES_PASSWORD` and Ed25519 `JWT_PRIVATE_KEY_PEM`.
2. Launch behind a TLS 1.3 reverse proxy (Caddy / Nginx):
   ```bash
   docker compose -f infrastructure/deployment/docker-compose.prod.yml up -d --build
   ```
3. Verify health locally: `curl http://127.0.0.1:8000/health`

## 4. Safe Update & Rollback Protocol
- Never disable an active VPN Kill Switch or corrupt active `.ryntmp` downloads during an app update.
- Pause downloads cleanly -> checkpoint SQLite WAL -> create encrypted backup snapshot -> verify SHA-256 & signature of the new installer -> install -> if startup health check fails, restore the previous version and database snapshot automatically.
