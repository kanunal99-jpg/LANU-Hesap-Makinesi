#!/usr/bin/env python3
"""Release invariant: do not cloud-backup or device-transfer Keystore-bound LANU data."""
from __future__ import annotations

import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def assert_private_manifest(manifest: str, backup_rules: str, extraction_rules: str) -> None:
    app = ElementTree.fromstring(manifest).find("application")
    if app is None:
        raise ValueError("Android application element missing")
    required = {
        "allowBackup": "false",
        "fullBackupContent": "@xml/backup_rules",
        "dataExtractionRules": "@xml/data_extraction_rules",
    }
    for name, expected in required.items():
        if app.get(ANDROID + name) != expected:
            raise ValueError(f"Private backup guard failed: android:{name} must equal {expected}")
    providers = [p for p in app.findall("provider")
                 if p.get(ANDROID + "name") == "androidx.core.content.FileProvider"]
    if len(providers) != 1 or providers[0].get(ANDROID + "exported") != "false":
        raise ValueError("OTA FileProvider must exist and must never be exported")

    backup = ElementTree.fromstring(backup_rules)
    if backup.tag != "full-backup-content":
        raise ValueError("Invalid legacy full-backup-content root")
    if not any(p.get("domain") == "root" and p.get("path") == "."
               for p in backup.findall("exclude")):
        raise ValueError("Legacy backup must exclude all app private data")

    extraction = ElementTree.fromstring(extraction_rules)
    if extraction.tag != "data-extraction-rules":
        raise ValueError("Invalid data extraction policy root")
    for section in ("cloud-backup", "device-transfer"):
        policies = extraction.findall(section)
        if len(policies) != 1:
            raise ValueError(f"Android 12+ {section} section must exist exactly once")
        if not any(p.get("domain") == "root" and p.get("path") == "."
                   for p in policies[0].findall("exclude")):
            raise ValueError(f"Android 12+ {section} must exclude all private data")


def main() -> None:
    res = ROOT / "app/src/main/res/xml"
    try:
        assert_private_manifest(
            (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"),
            (res / "backup_rules.xml").read_text(encoding="utf-8"),
            (res / "data_extraction_rules.xml").read_text(encoding="utf-8"),
        )
    except (ValueError, ElementTree.ParseError, OSError) as exc:
        raise SystemExit(f"FAILED: LANU private data backup configuration: {exc}")
    print("PASS: LANU cloud backup, device transfer and OTA provider security invariants")


if __name__ == "__main__":
    main()
