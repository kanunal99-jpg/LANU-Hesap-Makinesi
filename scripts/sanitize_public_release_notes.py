#!/usr/bin/env python3
"""Replace only known, public LANU release-note disclosures.

Runs after the signed APK and OTA gates pass, with the publish job's scoped
GitHub token. Does not modify tags, assets, commit targets, signatures or data.
"""
from __future__ import annotations

import json
import os
import re
import subprocess

LEGACY_DISCLOSURE = (
    "LANU 1.2.0: isteğe bağlı gizli mesaj bildirimleri ve "
    "çağrı/güvenlik düzeltmeleri."
)
SAFE_REPLACEMENT = "LANU 1.2.0: doğrulanmış bakım ve kararlılık düzeltmeleri."
STABLE_TAG = re.compile(r"^v1\.(?:1|2)\.0-signed\.[0-9]+$")


def sanitize_notes(body: str) -> str:
    """Preserve commit identifier, installation cautions and all other text."""
    return body.replace(LEGACY_DISCLOSURE, SAFE_REPLACEMENT)


def main() -> None:
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise SystemExit("Refusing to update release notes without a valid repository")
    if not os.environ.get("GH_TOKEN"):
        raise SystemExit("No GitHub release metadata token")

    response = subprocess.run(
        [
            "gh", "api", "--paginate",
            f"repos/{repository}/releases?per_page=100",
            "--jq", '.[] | {id,tag_name,body} | @json',
        ],
        text=True, capture_output=True, check=True, timeout=120,
    )
    changed = 0
    for row in response.stdout.splitlines():
        release = json.loads(row)
        tag = release.get("tag_name", "")
        if not isinstance(tag, str) or not STABLE_TAG.fullmatch(tag):
            continue
        body = release.get("body") or ""
        if not isinstance(body, str) or len(body) > 20000:
            continue
        updated = sanitize_notes(body)
        if updated == body:
            continue
        release_id = release.get("id")
        if type(release_id) is not int or release_id < 1:
            raise RuntimeError("Invalid release metadata identifier")

        # Only edit the release's body; never touch its tag, target or assets.
        subprocess.run(
            [
                "gh", "api", "--method", "PATCH",
                f"repos/{repository}/releases/{release_id}",
                "-f", f"body={updated}", "--silent",
            ],
            text=True, capture_output=True, check=True, timeout=60,
        )
        changed += 1
        print(f"Neutralized public release wording for {tag}")
    print(f"Public release metadata repaired: {changed} record(s)")


if __name__ == "__main__":
    main()
