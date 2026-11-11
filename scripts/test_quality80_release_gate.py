#!/usr/bin/env python3
"""Fail-closed QUALITY80 release-gate regression suite; no server calls."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from contextlib import redirect_stderr
from io import StringIO

sys.path.insert(0, str(Path(__file__).parent))
import quality80_release_gate as gate

COMMIT = "a" * 40
DOCUMENT_SHA = "b" * 40
GITHUB = "https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi"
FIXTURE_DOC = f"{GITHUB}/blob/{DOCUMENT_SHA}/docs/quality80/verified_acceptance.md"
CI = f"{GITHUB}/actions/runs/42995124500"
CODE_PR = f"{GITHUB}/pull/379"


def accepted_fixture():
    def row(category):
        proof = {}
        for required in gate.REQUIRED_PROOFS[category]:
            if required == "code":
                url = CODE_PR
            elif required in ("ci", "fallback", "smoke"):
                url = CI
            else:
                url = FIXTURE_DOC
            proof[required] = url
        return {
            "id": category,
            "score": 80,
            "approved": True,
            "review": CODE_PR,
            "proof": proof,
        }

    return {
        "schema": 1,
        "program": gate.PROGRAM,
        "candidate_commit": COMMIT,
        "categories": [row(category) for category in gate.REQUIRED_PROOFS],
    }


class Quality80GateTest(unittest.TestCase):
    def test_exactly_eleven_distinct_workstreams_are_required(self):
        fixture = accepted_fixture()
        self.assertEqual(11, len(fixture["categories"]))
        self.assertEqual([], gate.validate(fixture, COMMIT))
        fixture["categories"].pop()
        self.assertTrue(any("missing category" in e for e in gate.validate(fixture, COMMIT)))

    def test_any_sub80_score_blocks_entire_release(self):
        for category, baseline in gate.BASELINE_SCORES.items():
            fixture = accepted_fixture()
            row = next(r for r in fixture["categories"] if r["id"] == category)
            row["score"] = baseline
            self.assertTrue(any(category in e and "80" in e for e in
                                gate.validate(fixture, COMMIT)), category)

    def test_false_or_numeric_approval_is_not_accepted(self):
        for bad in [False, 1, 0, "true", "yes", None]:
            fixture = accepted_fixture()
            fixture["categories"][0]["approved"] = bad
            self.assertTrue(any("acceptance approval" in e for e in gate.validate(fixture, COMMIT)))

    def test_zero_legal_evidence_blocks_even_if_other_ten_pass(self):
        fixture = accepted_fixture()
        row = next(r for r in fixture["categories"] if r["id"] == "kvkk_erasure")
        row["proof"].pop("legal")
        self.assertTrue(any("kvkk_erasure" in e and "legal" in e for e in
                            gate.validate(fixture, COMMIT)))

    def test_physical_device_evidence_must_be_frozen_report(self):
        fixture = accepted_fixture()
        row = next(r for r in fixture["categories"] if r["id"] == "audio_apm")
        row["proof"]["device"] = CI
        self.assertTrue(any("audio_apm" in e and "device" in e for e in
                            gate.validate(fixture, COMMIT)))

    def test_code_and_ci_evidence_cannot_be_swapped(self):
        fixture = accepted_fixture()
        row = fixture["categories"][0]
        row["proof"]["code"] = CI
        row["proof"]["ci"] = CODE_PR
        errors = gate.validate(fixture, COMMIT)
        self.assertTrue(any("code evidence" in e for e in errors))
        self.assertTrue(any("ci evidence" in e for e in errors))

    def test_same_release_commit_is_mandatory(self):
        fixture = accepted_fixture()
        self.assertTrue(any("candidate_commit" in e for e in gate.validate(fixture, "c" * 40)))
        self.assertTrue(any("invalid build" in e for e in gate.validate(fixture, "not-a-sha")))
        fixture["candidate_commit"] = None
        self.assertTrue(any("candidate_commit" in e for e in gate.validate(fixture, COMMIT)))

    def test_duplicate_and_extra_categories_fail(self):
        fixture = accepted_fixture()
        fixture["categories"].append(copy.deepcopy(fixture["categories"][0]))
        self.assertTrue(any("duplicate" in e for e in gate.validate(fixture, COMMIT)))
        fixture = accepted_fixture()
        fixture["categories"][0]["id"] = "unknown"
        self.assertTrue(any("unknown" in e for e in gate.validate(fixture, COMMIT)))

    def test_type_confusion_invalid_score_and_schema(self):
        for score in [True, 80.0, "80", float("nan"), -80, 101]:
            fixture = accepted_fixture()
            fixture["categories"][0]["score"] = score
            self.assertTrue(any("score" in e for e in gate.validate(fixture, COMMIT)))
        fixture = accepted_fixture()
        fixture["schema"] = True
        self.assertTrue(gate.validate(fixture, COMMIT))

    def test_untrusted_or_token_bearing_urls_are_rejected(self):
        invalid = [
            "http://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/379",
            "https://github.com.evil.example/kanunal99-jpg/LANU-Hesap-Makinesi/pull/379",
            "https://attacker@gitHub.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/379",
            f"{CODE_PR}?access_token=SECRET",
            f"{CODE_PR}#private",
            f"{GITHUB}/issues/17?key=SECRET",
            f"{GITHUB}/pull/0/../../other",
            f"{GITHUB}/blob/{DOCUMENT_SHA}/docs/../../secret.txt",
            "garbage",
        ]
        for url in invalid:
            self.assertFalse(gate.is_evidence_link(url), url)

    def test_json_duplicate_keys_blocked(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            json.loads('{"approved":true,"approved":false}',
                       object_pairs_hook=gate.reject_duplicate_keys)

    def test_checked_in_evidence_remains_blocked_until_real_acceptance(self):
        path = Path(__file__).resolve().parent.parent / "quality80/evidence.json"
        manifest = json.loads(path.read_text(encoding="utf-8"),
                              object_pairs_hook=gate.reject_duplicate_keys)
        self.assertEqual(11, len(manifest["categories"]))
        errors = gate.validate(manifest, COMMIT)
        self.assertTrue(any("score" in e for e in errors))
        self.assertTrue(any("approval" in e for e in errors))

    def test_cli_returns_nonzero_for_missing_and_unproven_manifest(self):
        with redirect_stderr(StringIO()):
            self.assertEqual(2, gate.main(["--manifest", "/does-not-exist.json",
                                            "--commit", COMMIT]))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "manifest.json"
            path.write_text(json.dumps(accepted_fixture()), encoding="utf-8")
            self.assertEqual(0, gate.main(["--manifest", str(path), "--commit", COMMIT]))
            path.write_text(json.dumps({}), encoding="utf-8")
            with redirect_stderr(StringIO()):
                self.assertNotEqual(0, gate.main(["--manifest", str(path), "--commit", COMMIT]))


if __name__ == "__main__":
    unittest.main()
