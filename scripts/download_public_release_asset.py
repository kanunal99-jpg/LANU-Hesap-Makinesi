#!/usr/bin/env python3
"""Download public release assets with an independent anonymous GitHub API fallback.

The caller MUST still verify APK checksum, pinned signer and original CI bytes.
Neither path uses a token. Never replace a previous file with a partial download.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ASSETS = {"app-debug.apk": 256 * 1024 * 1024,
          "app-debug.apk.sha256": 4096, "app-debug.apk.signer.txt": 4096}

def download(repo: str, tag: str, name: str, destination: Path, runner=subprocess.run) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", repo):
        raise ValueError("Invalid repository")
    if not re.fullmatch(r"v1\.(?:0\.0-debug|1\.0-signed|2\.0-signed)\.[0-9]+", tag):
        raise ValueError("Invalid release tag")
    if name not in ASSETS:
        raise ValueError("Invalid release asset")
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="lanu-download-", dir=destination.parent) as folder:
        staged = Path(folder) / "asset"
        metadata = Path(folder) / "metadata"
        def fetch(url: str, target: Path, accept: str, limit: int, seconds: int) -> bool:
            try:
                result = runner(["curl", "--silent", "--show-error", "--fail", "--location",
                    "--retry", "1", "--retry-delay", "2", "--max-time", str(seconds),
                    "--proto", "=https", "--proto-redir", "=https", "--max-filesize", str(limit),
                    "--header", "Accept: " + accept, "--output", str(target), url],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    check=False, timeout=seconds * 2 + 10)
                return result.returncode == 0 and target.is_file() and 0 < target.stat().st_size <= limit
            except (OSError, subprocess.SubprocessError):
                return False
        public = f"https://github.com/{repo}/releases/download/{tag}/{name}"
        if fetch(public, staged, "application/octet-stream", ASSETS[name], 40):
            route = "public URL"
        else:
            # Never parse or forward a signed redirect, token or arbitrary API URL.
            url = f"https://api.github.com/repos/{repo}/releases/tags/{tag}"
            if not fetch(url, metadata, "application/vnd.github+json", 1024 * 1024, 30):
                raise ValueError("Public release metadata unavailable")
            value = json.loads(metadata.read_text(encoding="utf-8"))
            if not isinstance(value, dict) or value.get("tag_name") != tag or value.get("draft") is not False:
                raise ValueError("Unexpected release metadata")
            assets = value.get("assets")
            if not isinstance(assets, list):
                raise ValueError("Invalid asset metadata")
            matches = [a for a in assets if isinstance(a, dict) and a.get("name") == name]
            if len(matches) != 1:
                raise ValueError("Missing or ambiguous public asset")
            asset = matches[0]
            asset_id = asset.get("id")
            if type(asset_id) is not int or asset_id <= 0 or asset.get("state") != "uploaded":
                raise ValueError("Invalid public asset identity")
            # Metadata cannot redirect us to another host, repository or arbitrary path.
            url = f"https://api.github.com/repos/{repo}/releases/assets/{asset_id}"
            if not fetch(url, staged, "application/octet-stream", ASSETS[name], 180):
                raise ValueError("Both public download paths unavailable")
            route = "anonymous public API"
        os.replace(staged, destination)
        return route

def main() -> int:
    if len(sys.argv) != 5:
        print("usage: download_public_release_asset.py OWNER/REPO TAG ASSET DEST", file=sys.stderr)
        return 2
    try:
        route = download(sys.argv[1], sys.argv[2], sys.argv[3], Path(sys.argv[4]))
    except (OSError, ValueError, TypeError):
        print("FAIL: public release download; existing file preserved", file=sys.stderr)
        return 1
    print("PASS: public release asset downloaded via " + route)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
