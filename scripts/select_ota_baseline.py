#!/usr/bin/env python3
"""Select the last REAL signed LANU release for a second, recent-version OTA test.

Public GitHub release metadata is untrusted until the workflow checks tag
identity, Git ancestry, the APK digest, and stable signing continuity.
No credentials or device data are read or printed by this script.
"""
from __future__ import annotations

import json
import re
import sys

OWNER_REPO = "kanunal99-jpg/LANU-Hesap-Makinesi"
TAG_RE = re.compile(r"v1\.2\.0-signed\.([1-9][0-9]{0,9})\Z")
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
NAMES = ("app-debug.apk", "app-debug.apk.sha256", "app-debug.apk.signer.txt")
MAX_APK = 90 * 1024 * 1024


def latest_previous_signed_release(releases: object, current_version: int) -> tuple[str, int, str]:
    if not isinstance(releases, list) or type(current_version) is not int or current_version <= 0:
        raise ValueError("Invalid signed-release selection input")
    valid: dict[int, tuple[str, int, str]] = {}
    for release in releases:
        if not isinstance(release, dict) or release.get("draft") is not False:
            continue
        if not isinstance(release.get("published_at"), str) or not release["published_at"]:
            continue
        tag = release.get("tag_name")
        match = TAG_RE.fullmatch(tag) if isinstance(tag, str) else None
        if match is None:
            continue
        version = int(match.group(1))
        if version >= current_version:
            continue
        sha = release.get("target_commitish")
        if not isinstance(sha, str) or SHA_RE.fullmatch(sha) is None:
            continue
        assets = release.get("assets")
        if not isinstance(assets, list):
            continue
        complete = True
        for name in NAMES:
            candidates = [asset for asset in assets
                          if isinstance(asset, dict) and asset.get("name") == name]
            if len(candidates) != 1:
                complete = False
                break
            asset = candidates[0]
            size = asset.get("size")
            limit = MAX_APK if name == NAMES[0] else 2048
            if (asset.get("state") != "uploaded" or type(size) is not int or
                    not 0 < size <= limit or
                    asset.get("browser_download_url") !=
                    f"https://github.com/{OWNER_REPO}/releases/download/{tag}/{name}"):
                complete = False
                break
        if not complete:
            continue
        previous = valid.get(version)
        candidate = tag, version, sha
        if previous is not None and previous != candidate:
            raise ValueError("Ambiguous previous stable release")
        valid[version] = candidate
    if not valid:
        raise ValueError("No complete earlier signed release; OTA publication blocked")
    return valid[max(valid)]


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: select_ota_baseline.py current-version < releases.json", file=sys.stderr)
        return 2
    try:
        if re.fullmatch(r"[1-9][0-9]{0,9}", argv[1]) is None:
            raise ValueError("Invalid candidate version")
        release = latest_previous_signed_release(json.load(sys.stdin), int(argv[1]))
    except (ValueError, TypeError, json.JSONDecodeError) as exc:
        print(f"FAIL: previous signed OTA baseline selection: {exc}", file=sys.stderr)
        return 1
    for item in release:
        print(item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
