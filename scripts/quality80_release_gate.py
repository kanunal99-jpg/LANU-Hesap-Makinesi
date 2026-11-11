#!/usr/bin/env python3
"""Structural QUALITY80 major-release admission gate.

A signed human/device attestation is still REQUIRED: a green structural check
never proves that a physical device, a real account deletion or cryptography
review has actually happened. No network access, writes or secret collection.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

EXPECTED_BASELINES = {
    "Q80-01": 73, "Q80-02": 70, "Q80-03": 75,
    "Q80-04": 73, "Q80-05": 68, "Q80-06": 66,
    "Q80-07": 68, "Q80-08": 67, "Q80-09": 75,
    "Q80-10": 74, "Q80-11": 61,
}
RELEASE_PROOF_KEYS = (
    "currentHeadPrCi", "newMainCi", "signedOtaLegacyAndStable",
    "stableSignerContinuity", "publicShaSignerReadback",
    "offDeviceSignerRestore", "legalKvkkApproval",
)
EXTERNAL_REVIEW_CATEGORIES = frozenset(("Q80-01", "Q80-07", "Q80-11"))


def _evidence(values: Any) -> bool:
    """A non-empty reference list, never a 'passed' flag or score label."""
    return (
        isinstance(values, list) and bool(values)
        and all(
            isinstance(item, str) and item.startswith("https://")
            and 15 <= len(item) <= 512 and not any(c.isspace() for c in item)
            for item in values
        )
    )


def evaluate(manifest: Any) -> tuple[list[str], dict[str, list[str]]]:
    """Return missing admission conditions. Does NOT validate URLs externally."""
    if not isinstance(manifest, dict):
        return ["Manifest must be an object"], {}
    missing: list[str] = []
    per_category: dict[str, list[str]] = {}
    if manifest.get("schema_version") != 1:
        missing.append("Unsupported evidence schema version")
    if manifest.get("program") != "QUALITY80-UNIFIED-MAJOR-RELEASE":
        missing.append("Wrong major-release program identity")
    baseline = manifest.get("baseline")
    if not isinstance(baseline, dict) or (
        baseline.get("tag") != "v1.2.0-signed.1284"
        or baseline.get("sha") != "142d4540c5df5000c29f87959ddf781fce3636a1"
    ):
        missing.append("Baseline tag and source commit do not match the audited release")
    policy = manifest.get("policy")
    if not isinstance(policy, dict) or (
        policy.get("minimum_verified_score") != 80
        or policy.get("category_count") != 11
        or policy.get("release_after_all_pass") is not True
        or policy.get("release_channel") != "github-releases-ota-only"
    ):
        missing.append("Binding 11/11 acceptance policy is invalid")

    categories = manifest.get("categories")
    if not isinstance(categories, list) or len(categories) != len(EXPECTED_BASELINES):
        return missing + ["Exactly 11 category records are required"], per_category

    ids = [c.get("id") if isinstance(c, dict) else None for c in categories]
    if any(type(cid) is not str for cid in ids):
        return missing + ["Category ID must be a string"], per_category
    if len(ids) != len(set(ids)) or set(ids) != set(EXPECTED_BASELINES):
        return missing + ["Unknown, duplicated or missing category ID"], per_category
    for c in categories:
        cid = c["id"]
        errors: list[str] = []
        if c.get("baselineScore") != EXPECTED_BASELINES[cid]:
            errors.append("baseline differs from audited baseline")
        if c.get("targetScore") != 80:
            errors.append("target must be 80 (not claimed as achieved)")
        score = c.get("verifiedScore")
        if type(score) is not int or not (80 <= score <= 100):
            errors.append("independently verified score >=80 absent")
        if not _evidence(c.get("codeCiEvidence")):
            errors.append("current code/CI proof absent")
        if not _evidence(c.get("negativeTestEvidence")):
            errors.append("negative/rollback proof absent")
        if c.get("requiresDevice") is not True or not _evidence(c.get("deviceEvidence")):
            errors.append("two real-device acceptance proof absent")
        if cid in EXTERNAL_REVIEW_CATEGORIES and (
            c.get("requiresIndependentReview") is not True
            or not _evidence(c.get("independentReviewEvidence"))
        ):
            errors.append("mandatory independent/legal/crypto review absent")
        blockers = c.get("blockers")
        if not isinstance(blockers, list) or blockers:
            errors.append("unresolved critical blocker(s) or invalid blocker list")
        per_category[cid] = errors

    release = manifest.get("releaseEvidence")
    if not isinstance(release, dict):
        missing.append("No final release evidence")
    else:
        if release.get("allElevenPassed") is not True:
            missing.append("11/11 category acceptance not signed off")
        if release.get("zeroCriticalOpen") is not True:
            missing.append("critical issue count not independently cleared")
        for key in RELEASE_PROOF_KEYS:
            if not _evidence(release.get(key)):
                missing.append(f"Missing final release proof: {key}")
    if any(per_category.values()):
        missing.append("Not all categories passed the non-negotiable 80+ gates")
    return missing, per_category


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit the single unified QUALITY80 release gates")
    parser.add_argument("--manifest", type=Path,
                        default=Path(__file__).resolve().parents[1] / "quality80" / "acceptance.json")
    parser.add_argument("--enforce", action="store_true",
                        help="Exit nonzero unless every category and every release gate is complete")
    args = parser.parse_args()

    try:
        evidence = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"QUALITY80 NOT READY: cannot read evidence manifest: {type(exc).__name__}")
        return 1
    missing, categories = evaluate(evidence)
    for cid, errors in sorted(categories.items()):
        label = "PASS" if not errors else "NOT VERIFIED"
        print(f"{cid}: {label}" + (f" — {', '.join(errors)}" if errors else ""))
    for detail in missing:
        print(f"RELEASE GATE: {detail}")
    ready = not missing and all(not errors for errors in categories.values())
    print(f"QUALITY80: {'STRUCTURALLY READY — still requires authentic external evidence review' if ready else 'NOT READY — current signed APK remains unchanged'}")
    return 1 if args.enforce and not ready else 0


if __name__ == "__main__":
    raise SystemExit(main())
