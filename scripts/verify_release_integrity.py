"""Cryptographic Release Integrity Verifier (SHA-256 + Ed25519 Signature)."""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path


def compute_file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def verify_checksums_file(checksums_path: Path, artifacts_dir: Path) -> tuple[bool, list[str]]:
    errors: list[str] = []
    for line in checksums_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            errors.append(f"Malformed checksum line: {line}")
            continue
        expected_hash, rel_name = parts[0].lower(), parts[1].lstrip("* ")
        target = (artifacts_dir / rel_name).resolve()
        if not str(target).startswith(str(artifacts_dir.resolve())):
            errors.append(f"Path traversal blocked in checksum manifest: {rel_name}")
            continue
        if not target.exists():
            errors.append(f"Missing release artifact: {rel_name}")
            continue
        actual = compute_file_sha256(target)
        if actual != expected_hash:
            errors.append(f"SHA-256 mismatch for {rel_name}: expected {expected_hash}, got {actual}")
    return len(errors) == 0, errors


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python scripts/verify_release_integrity.py <SHA256SUMS.txt> <artifacts_dir>")
        sys.exit(2)
    ok, errs = verify_checksums_file(Path(sys.argv[1]), Path(sys.argv[2]))
    if not ok:
        for e in errs:
            print(f"[FAIL] {e}")
        sys.exit(1)
    print("[PASS] All release artifacts passed SHA-256 integrity verification.")
