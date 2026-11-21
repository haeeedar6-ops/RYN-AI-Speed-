# RYN-AI-Speed — Android & Windows Build, Signing & CI/CD Strategy

**Versioning Standard:** Semantic Versioning (`MAJOR.MINOR.PATCH`, starting at `1.0.0`)  
**CI/CD Engine:** GitHub Actions (Strict PR Gate — no merge allowed on test or security failure)

---

## 1. Android Build Strategy (`android/`)

- **Language & Toolchain:** Kotlin 2.1+, Java 17/21 Toolchain, Android Gradle Plugin (AGP) 8.7+, Gradle Kotlin DSL (`build.gradle.kts`) with centralized `gradle/libs.versions.toml` Version Catalog.
- **SDK Targets:**
  - `compileSdk = 35`
  - `targetSdk = 35`
  - `minSdk = 26` (Android 8.0+ required for strong Keystore, Always-on VPN Lockdown, and modern WorkManager).
- **Module Layout:**
  - `:app` (Compose UI, Navigation, Hilt root)
  - `:core:common`, `:core:database` (Room + SQLCipher), `:core:networking` (OkHttp 4/5 + TLS 1.3), `:core:security` (Android Keystore + Redaction), `:core:settings` (DataStore)
  - `:feature:downloads`, `:feature:vpn`, `:feature:network`, `:feature:browser`, `:feature:ai`, `:feature:notifications`
- **Build Variants & Hardening:**
  - `debug`: Unminified, LeakCanary enabled, strict StrictMode thread/VM policies.
  - `release`: `isMinifyEnabled = true`, `isShrinkResources = true` (R8 full mode), APK Signature Scheme v2 + v3, signing keystore injected strictly via GitHub Actions encrypted secrets (`ANDROID_KEYSTORE_BASE64`), NEVER committed to Git.
- **Quality Gates:** `ktlintCheck`, `detekt`, `./gradlew testDebugUnitTest`, `./gradlew assembleRelease`, Room schema migration verification.

---

## 2. Windows Build Strategy (`windows/`)

- **Language & Runtime:** C# 13 / **.NET 10 LTS** (`net10.0-windows10.0.22621.0`).
- **UI & Browser:** **WinUI 3** (Windows App SDK 1.6+) + **Microsoft WebView2**.
- **Solution & Package Management:**
  - Single solution `RynSpeed.sln` with **Central Package Management (`Directory.Packages.props`)** and `Directory.Build.props` enforcing `<Nullable>enable</Nullable>`, `<TreatWarningsAsErrors>true</TreatWarningsAsErrors>`, and `<AnalysisLevel>latest-recommended</AnalysisLevel>`.
  - Projects: `RynSpeed.App`, `RynSpeed.Core`, `RynSpeed.Database`, `RynSpeed.Security`, `RynSpeed.Downloads`, `RynSpeed.VPN`, `RynSpeed.Network`, `RynSpeed.Browser`, `RynSpeed.AI`, `RynSpeed.Notifications`.
- **Packaging, Signing & Rollback:**
  - Packaged as signed **MSIX** (`x64` and `arm64`) with Authenticode code signing via CI secrets (`WINDOWS_SIGN_CERT_PFX_BASE64`).
  - **Safe Update Protocol:** Never kill active WireGuard Kill Switch or corrupt active `.ryntmp` downloads during update. Pause downloads cleanly -> checkpoint SQLite WAL -> verify SHA-256 & signature of new MSIX -> install with automatic rollback to previous MSIX if startup health-check fails.

---

## 3. Backend Build Strategy (`backend/`)

- **Runtime:** Python 3.12+, FastAPI, Uvicorn/Gunicorn, SQLAlchemy 2.0 (async) + AsyncPG, Alembic, Pydantic v2, Redis.
- **Quality Gates:** `ruff check`, `mypy --strict`, `bandit -r app/`, `pytest --cov=app --cov-fail-under=80`, Docker multi-stage non-root image build.

---

## 4. GitHub Actions Pipeline Blueprint (`.github/workflows/`)

- **`ci-contracts.yml` (Active from P1):** Validates `shared/schemas/*.sql`, `shared/api-spec/openapi.yaml`, and `shared/contracts/*.json` on every PR.
- **`ci-android.yml`:** Runs on `ubuntu-latest` (lint, detekt, unit tests, debug/release APK build).
- **`ci-windows.yml`:** Runs on `windows-latest` (`dotnet build`, Roslyn analyzers, xUnit tests, MSIX packaging).
- **`ci-backend.yml`:** Runs on `ubuntu-latest` with PostgreSQL 16 & Redis service containers (ruff, mypy, bandit, alembic upgrade, pytest).
- **`security-scan.yml`:** Runs Gitleaks secret scanning, CodeQL, and dependency vulnerability audits on every PR and weekly schedule.
- **`release.yml`:** Triggered only on signed SemVer tags (`v*.*.*`), produces signed APK + signed MSIX + SHA-256 `checksums.txt` + automated Release Notes.
