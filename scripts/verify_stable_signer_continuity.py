#!/usr/bin/env python3
"""Fail a new stable APK release when its certificate differs from the last stable release.

This compares release signer metadata that was independently checked against the
APK by the publishing CI. It does NOT prove on-device, data-preserving upgrades.
"""
from __future__ import annotations

import hmac
import sys
from pathlib import Path

from verify_published_apk import read_signer


def verify_stable_signer_continuity(current: Path, previous: Path) -> None:
    current_fields = read_signer(current)
    previous_fields = read_signer(previous)
    if current_fields["signing_mode"] != "stable" or previous_fields["signing_mode"] != "stable":
        raise ValueError("Both releases must use protected stable signing")
    if not hmac.compare_digest(
        current_fields["certificate_sha256"], previous_fields["certificate_sha256"]
    ):
        raise ValueError("Signing identity changed since the previous stable APK release")


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: verify_stable_signer_continuity.py CURRENT_SIGNER PREVIOUS_SIGNER", file=sys.stderr)
        return 2
    try:
        verify_stable_signer_continuity(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError, UnicodeError) as exc:
        print(f"FAIL: stable APK signing continuity: {exc}", file=sys.stderr)
        return 1
    print("PASS: stable APK signer fingerprint matches the previous stable release")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
