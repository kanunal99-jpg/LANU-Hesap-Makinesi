#!/usr/bin/env python3
"""QUALITY80 release gate: validate evidence completeness before public APK.

This is a structural fail-closed gate, NOT a measurement, security audit or
evidence authenticator. Independent reviewers still need to read every proof
before marking acceptance in a reviewed commit.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

PROGRAM = "LANU-QUALITY80-11"
REPO_PREFIX = "/kanunal99-jpg/LANU-Hesap-Makinesi/"
SHA = re.compile(r"[0-9a-f]{40}", re.ASCII)

# Every workstream needs code, CI, failure fallback and smoke evidence.
BASE_PROOFS = frozenset(("code", "ci", "fallback", "smoke"))
REQUIRED_PROOFS = {
    "e2ee_identity": BASE_PROOFS | {"security", "device"},
    "private_media": BASE_PROOFS | {"security", "device", "quota"},
    "voice_calls": BASE_PROOFS | {"device", "network"},
    "video_calls": BASE_PROOFS | {"device", "network"},
    "audio_apm": BASE_PROOFS | {"device", "metrics"},
    "background_fcm": BASE_PROOFS | {"device", "notification"},
    "room_encryption": BASE_PROOFS | {"security", "device", "backup"},
    "android_oem": BASE_PROOFS | {"device", "compatibility"},
    "performance": BASE_PROOFS | {"device", "metrics"},
    "accessibility": BASE_PROOFS | {"device", "a11y"},
    "kvkk_erasure": BASE_PROOFS | {"security", "server", "legal"},
}
BASELINE_SCORES = {
    "e2ee_identity": 73,
    "private_media": 70,
    "voice_calls": 75,
    "video_calls": 73,
    "audio_apm": 68,
    "background_fcm": 66,
    "room_encryption": 68,
    "android_oem": 67,
    "performance": 75,
    "accessibility": 74,
    "kvkk_erasure": 61,
}


def reject_duplicate_keys(pairs):
    parsed = {}
    for key, value in pairs:
        if key in parsed:
            raise ValueError("duplicate JSON key")
        parsed[key] = value
    return parsed


def is_evidence_link(value):
    if not isinstance(value, str) or len(value) > 1024:
        return False
    value = value.strip()
    if not value or any(ord(char) < 33 for char in value):
        return False
    url = urlparse(value)
    # No arbitrary HTTP endpoint, token, credentials, query, or fragment in
    # immutable quality proof; public/GitHub review links are inspectable.
    if (url.scheme != "https" or url.hostname != "github.com" or
            url.username or url.password or url.query or url.fragment or
            not url.path.startswith(REPO_PREFIX)):
        return False
    target = url.path[len(REPO_PREFIX):]
    return bool(re.fullmatch(
        r"(?:issues/\d+|pull/\d+|actions/runs/\d+|"
        r"blob/[0-9a-f]{40}/docs/[a-zA-Z0-9_./-]+)",
        target,
    ))


def evidence_matches_kind(value, key):
    if not is_evidence_link(value):
        return False
    target = urlparse(value).path[len(REPO_PREFIX):]
    if key == "code":
        return target.startswith("pull/")
    if key in ("ci", "fallback", "smoke"):
        return target.startswith("actions/runs/")
    # A frozen, reviewed report is necessary for phone measurements, private
    # data migration, legal review, quota and independent crypto approval.
    return target.startswith("blob/")


def validate(manifest, expected_commit):
    errors = []
    if not isinstance(manifest, dict):
        return ["manifest root must be an object"]
    if manifest.get("program") != PROGRAM or type(manifest.get("schema")) is not int or manifest["schema"] != 1:
        errors.append("program/schema mismatch")
    candidate = manifest.get("candidate_commit")
    if not isinstance(candidate, str) or not SHA.fullmatch(candidate):
        errors.append("missing or invalid candidate_commit")
    if not isinstance(expected_commit, str) or not SHA.fullmatch(expected_commit):
        errors.append("invalid build commit supplied to gate")
    if candidate != expected_commit:
        errors.append("candidate_commit does not match built source revision")
    rows = manifest.get("categories")
    if not isinstance(rows, list):
        return errors + ["categories must be an array"]
    seen = set()
    for row in rows:
        if not isinstance(row, dict):
            errors.append("category row must be an object")
            continue
        category = row.get("id")
        if not isinstance(category, str) or category not in REQUIRED_PROOFS:
            errors.append("unknown category id")
            continue
        if category in seen:
            errors.append("duplicate category id: " + category)
            continue
        seen.add(category)
        score = row.get("score")
        if type(score) is not int or not 80 <= score <= 100:
            errors.append(category + ": score is not verified 80..100")
        if row.get("approved") is not True:
            errors.append(category + ": no recorded acceptance approval")
        review = row.get("review")
        if not is_evidence_link(review) or "/pull/" not in str(review):
            errors.append(category + ": missing PR review evidence")
        proof = row.get("proof")
        if not isinstance(proof, dict):
            errors.append(category + ": missing proof object")
            continue
        for key in sorted(REQUIRED_PROOFS[category]):
            if not evidence_matches_kind(proof.get(key), key):
                errors.append(category + ": missing or malformed " + key + " evidence")
    for category in sorted(set(REQUIRED_PROOFS) - seen):
        errors.append("missing category: " + category)
    if len(rows) != len(REQUIRED_PROOFS):
        errors.append("required exactly 11 acceptance rows")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description="Fail closed until all 11 quality areas are proven >=80")
    parser.add_argument("--manifest", type=Path, default=Path("quality80/evidence.json"))
    parser.add_argument("--commit", required=True, help="Exact SHA of published build")
    args = parser.parse_args(argv)
    try:
        with args.manifest.open("r", encoding="utf-8") as handle:
            manifest = json.load(handle, object_pairs_hook=reject_duplicate_keys,
                                 parse_constant=lambda x: (_ for _ in ()).throw(ValueError("non-finite JSON number")))
    except (OSError, UnicodeError, ValueError, TypeError) as exc:
        print("QUALITY80 BLOCKED: evidence manifest unavailable or malformed (" +
              type(exc).__name__ + ")", file=sys.stderr)
        return 2
    errors = validate(manifest, args.commit)
    if errors:
        for error in errors:
            # Never print document content, URLs, tokens, device IDs or secrets.
            print("QUALITY80 BLOCKED: " + error, file=sys.stderr)
        return 1
    print("QUALITY80 STRUCTURAL GATE PASSED: 11 evidence rows; human and physical proof authenticity still requires review")
    return 0


if __name__ == "__main__":
    sys.exit(main())
