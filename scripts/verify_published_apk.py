#!/usr/bin/env python3
"""Fail the release workflow if published APK, checksum or signing metadata differ.

This is a byte-for-byte *publication* read-back of the already verified CI
artifact, not a substitute for the Android apksigner or on-device upgrade test.
"""
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ASSETS = ("app-debug.apk", "app-debug.apk.sha256", "app-debug.apk.signer.txt")
FINGERPRINT = re.compile(r"[0-9a-f]{64}")
CHECKSUM = re.compile(r"([0-9a-fA-F]{64})  \\*?app-debug\\.apk")


def get_asset(folder: Path, name: str) -> Path:
    matches = [p for p in folder.rglob(name) if p.is_file() and p.name == name]
    if len(matches) != 1:
        raise ValueError(f"Missing or ambiguous release asset: {name}")
    return matches[0]


def read_signer(path: Path) -> dict[str, str]:
    raw = path.read_text(encoding="utf-8")
    if len(raw) > 2048:
        raise ValueError("Signing sidecar is oversized")
    fields: dict[str, str] = {}
    expected = {"apk", "signing_mode", "certificate_sha256", "upgrade_verified_on_device"}
    for line in raw.splitlines():
        if line.count("=") != 1:
            raise ValueError("Malformed signer sidecar line")
        key, value = (part.strip() for part in line.split("=", 1))
        if key not in expected or key in fields or not value:
            raise ValueError("Unexpected or duplicate signer field")
        fields[key] = value
    if fields.get("apk") != "app-debug.apk":
        raise ValueError("Unexpected APK name in signing report")
    if fields.get("signing_mode") not in {"stable", "ephemeral"}:
        raise ValueError("Invalid APK signing mode")
    if not FINGERPRINT.fullmatch(fields.get("certificate_sha256", "")):
        raise ValueError("Malformed certificate fingerprint")
    if fields.get("upgrade_verified_on_device") != "false":
        raise ValueError("Release may not claim a device upgrade without a separate device test")
    return fields


def verify(published: Path, original: Path, expected_mode: str) -> None:
    if expected_mode not in {"stable", "ephemeral"}:
        raise ValueError("Unknown build signing mode")
    for asset in ASSETS:
        source = get_asset(original, asset)
        retrieved = get_asset(published, asset)
        if source.read_bytes() != retrieved.read_bytes():
            raise ValueError(f"Published file differs from verified CI artifact: {asset}")
    apk = get_asset(published, ASSETS[0])
    if apk.stat().st_size <= 0:
        raise ValueError("Published APK is empty")
    checksum = get_asset(published, ASSETS[1]).read_text(encoding="utf-8").strip()
    match = CHECKSUM.fullmatch(checksum)
    if match is None:
        raise ValueError("Invalid portable SHA256 file")
    actual = hashlib.file_digest(apk.open("rb"), "sha256").hexdigest()
    if actual != match.group(1).lower():
        raise ValueError("Published APK SHA256 mismatch")
    fields = read_signer(get_asset(published, ASSETS[2]))
    if fields["signing_mode"] != expected_mode:
        raise ValueError("Published signing mode differs from CI mode")


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: verify_published_apk.py PUBLISHED_DIR CI_ARTIFACT_DIR stable|ephemeral", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
    except (OSError, ValueError, UnicodeError) as exc:
        print(f"FAIL: published APK verification: {exc}", file=sys.stderr)
        return 1
    print("PASS: published APK, SHA256 and signer files match verified CI artifact byte-for-byte")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
