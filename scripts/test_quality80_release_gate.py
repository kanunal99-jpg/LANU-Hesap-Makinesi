#!/usr/bin/env python3
"""No real scores/receipts are asserted here; only the gate contract is tested."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("quality80_release_gate", ROOT / "scripts" / "quality80_release_gate.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
manifest = json.loads((ROOT / "quality80" / "acceptance.json").read_text(encoding="utf-8"))


def synthetic_ready_fixture():
    """Only for unit tests: synthetic evidence is NOT a physical acceptance."""
    data = copy.deepcopy(manifest)
    for row in data["categories"]:
        row["verifiedScore"] = 80
        row["codeCiEvidence"] = ["https://example.test/mock/ci"]
        row["negativeTestEvidence"] = ["https://example.test/mock/negative"]
        row["deviceEvidence"] = ["https://example.test/mock/two-devices"]
        row["independentReviewEvidence"] = ["https://example.test/mock/review"]
        row["blockers"] = []
    release = data["releaseEvidence"]
    release["allElevenPassed"] = True
    release["zeroCriticalOpen"] = True
    for k in module.RELEASE_PROOF_KEYS:
        release[k] = ["https://example.test/mock/" + k]
    return data


class Quality80ReleaseGateTest(unittest.TestCase):
    def test_actual_manifest_stays_unverified_until_evidence_exists(self):
        errors, rows = module.evaluate(manifest)
        self.assertTrue(errors)
        self.assertEqual(11, len(rows))
        self.assertTrue(all(details for details in rows.values()))
        self.assertTrue(all(c["verifiedScore"] is None for c in manifest["categories"]))

    def test_no_score_inflation_from_target_or_passing_ci(self):
        data = synthetic_ready_fixture()
        data["categories"][0]["verifiedScore"] = None
        data["categories"][0]["targetScore"] = 80
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertIn("independently verified score >=80 absent", rows["Q80-01"])

    def test_80_alone_cannot_bypass_device_and_independent_review(self):
        data = synthetic_ready_fixture()
        data["categories"][0]["deviceEvidence"] = []
        data["categories"][0]["independentReviewEvidence"] = []
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertIn("two real-device acceptance proof absent", rows["Q80-01"])
        self.assertIn("mandatory independent/legal/crypto review absent", rows["Q80-01"])

    def test_one_open_critical_issue_blocks_entire_major_release(self):
        data = synthetic_ready_fixture()
        data["categories"][-1]["blockers"] = ["https://github.com/example/issues/1"]
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertTrue(rows["Q80-11"])

    def test_missing_signed_ota_or_signer_proof_blocks_publication(self):
        for gate in ("signedOtaLegacyAndStable", "stableSignerContinuity", "publicShaSignerReadback"):
            data = synthetic_ready_fixture()
            data["releaseEvidence"][gate] = []
            errors, rows = module.evaluate(data)
            self.assertTrue(any(gate in x for x in errors))
            self.assertTrue(all(not x for x in rows.values()))

    def test_missing_one_category_or_duplicate_fails_closed(self):
        data = synthetic_ready_fixture()
        data["categories"][-1]["id"] = data["categories"][0]["id"]
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertFalse(rows)
        data = synthetic_ready_fixture()
        data["categories"] = data["categories"][:-1]
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertFalse(rows)

    def test_wrong_bool_score_and_empty_or_unsafe_evidence_fail(self):
        data = synthetic_ready_fixture()
        data["categories"][0]["verifiedScore"] = True
        data["categories"][0]["codeCiEvidence"] = ["http://localhost/fake"]
        errors, rows = module.evaluate(data)
        self.assertTrue(errors)
        self.assertTrue(rows["Q80-01"])

    def test_synthetic_fixture_only_proves_structure_not_actual_acceptance(self):
        data = synthetic_ready_fixture()
        errors, rows = module.evaluate(data)
        self.assertEqual([], errors)
        self.assertTrue(all(not x for x in rows.values()))
        # A green structural check cannot authenticate fake URLs or certify
        # real Samsung / other-OEM devices: humans must inspect evidence.


if __name__ == "__main__":
    unittest.main()
