"""CI Contract Validator for RYN-AI-Speed shared schemas and OpenAPI specs."""

from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    errors: list[str] = []

    def check(cond: bool, msg: str) -> None:
        if not cond:
            errors.append(msg)
            print(f"[FAIL] {msg}")
        else:
            print(f"[PASS] {msg}")

    # 1. SQLite DDL live check
    sqlite_path = ROOT / "shared/schemas/schema_sqlite.sql"
    check(sqlite_path.exists(), "shared/schemas/schema_sqlite.sql exists")
    if sqlite_path.exists():
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(sqlite_path.read_text(encoding="utf-8"))
            tables = {
                r[0]
                for r in conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                ).fetchall()
            }
            check(len(tables) >= 15, f"SQLite schema created {len(tables)} tables")
        except Exception as exc:
            check(False, f"SQLite DDL error: {exc}")
        finally:
            conn.close()

    # 2. PostgreSQL DDL sanity
    pg_path = ROOT / "shared/schemas/schema_postgres.sql"
    check(pg_path.exists(), "shared/schemas/schema_postgres.sql exists")
    if pg_path.exists():
        pg_sql = pg_path.read_text(encoding="utf-8")
        check(pg_sql.count("(") == pg_sql.count(")"), "PostgreSQL DDL balanced parentheses")

    # 3. OpenAPI 3.1 check
    openapi_path = ROOT / "shared/api-spec/openapi.yaml"
    check(openapi_path.exists(), "shared/api-spec/openapi.yaml exists")
    if openapi_path.exists():
        spec = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
        check(spec.get("openapi") == "3.1.0", "OpenAPI version is 3.1.0")
        check(len(spec.get("paths", {})) >= 11, "OpenAPI contains required paths")

    # 4. JSON contracts check
    for rel in [
        "shared/contracts/ai_tools_schema.json",
        "shared/contracts/download_contract.json",
    ]:
        p = ROOT / rel
        check(p.exists(), f"{rel} exists")
        if p.exists():
            try:
                json.loads(p.read_text(encoding="utf-8"))
                check(True, f"{rel} valid JSON")
            except Exception as exc:
                check(False, f"{rel} invalid JSON: {exc}")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
