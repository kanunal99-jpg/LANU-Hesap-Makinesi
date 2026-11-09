"""Fail-closed tests for selecting the actual previous signed OTA release."""
import pathlib
import subprocess
import sys
import unittest
import json

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from select_ota_baseline import NAMES, OWNER_REPO, latest_previous_signed_release


def release(version: int, *, sha: str = "b" * 40, **changes) -> dict:
    tag = f"v1.2.0-signed.{version}"
    obj = {
        "tag_name": tag, "target_commitish": sha, "draft": False,
        "published_at": "2026-11-09T20:09:03Z",
        "prerelease": True,
        "assets": [
            {"name": name, "state": "uploaded",
             "size": 62000000 if name.endswith(".apk") else 100,
             "browser_download_url": (
                 f"https://github.com/{OWNER_REPO}/releases/download/{tag}/{name}")}
            for name in NAMES
        ],
    }
    obj.update(changes)
    return obj


class PreviousSignedReleaseSelection(unittest.TestCase):
    def test_picks_latest_complete_prior_even_if_releases_unordered(self):
        data = [release(828), release(1202), release(1190), release(1204)]
        self.assertEqual(
            latest_previous_signed_release(data, 1204),
            ("v1.2.0-signed.1202", 1202, "b" * 40))

    def test_ignores_future_drafts_debug_old_channel_and_incomplete(self):
        older = release(1190)
        legacy = release(1203, tag_name="v1.1.0-signed.1203")
        debug = release(1204, tag_name="v1.0.0-debug.1204")
        bad = release(1202)
        bad["assets"] = bad["assets"][:2]
        draft = release(1205, draft=True)
        self.assertEqual(
            latest_previous_signed_release(
                [release(1300), legacy, debug, bad, draft, older], 1206)[1], 1190)

    def test_rejects_wrong_download_host_or_duplicate_asset(self):
        wrong = release(1202)
        wrong["assets"][0]["browser_download_url"] = "https://example.invalid/malware"
        duplicate = release(1203)
        duplicate["assets"].append(dict(duplicate["assets"][0]))
        self.assertEqual(
            latest_previous_signed_release([wrong, duplicate, release(1190)], 1204)[1],
            1190)

    def test_rejects_missing_sha_unuploaded_asset_and_oversized_apk(self):
        malformed = release(1202, target_commitish="main")
        not_uploaded = release(1203)
        not_uploaded["assets"][1]["state"] = "new"
        oversized = release(1204)
        oversized["assets"][0]["size"] = 91 * 1024 * 1024
        self.assertEqual(
            latest_previous_signed_release(
                [malformed, not_uploaded, oversized, release(1190)], 1205)[1], 1190)

    def test_ambiguous_same_version_is_fatal(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            latest_previous_signed_release(
                [release(1202), release(1202, sha="c" * 40)], 1203)

    def test_missing_previous_release_fails_closed(self):
        for payload in ([], [release(1202)], "not a list"):
            with self.subTest(payload=str(payload)[:20]):
                with self.assertRaises(ValueError):
                    latest_previous_signed_release(payload, 1202)
        with self.assertRaises(ValueError):
            latest_previous_signed_release([release(828)], 0)

    def test_cli_returns_only_tag_version_and_sha(self):
        run = subprocess.run(
            [sys.executable, str(ROOT / "scripts/select_ota_baseline.py"), "1203"],
            input=json.dumps([release(1202)]), text=True, capture_output=True)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertEqual(
            run.stdout.splitlines(),
            ["v1.2.0-signed.1202", "1202", "b" * 40])
        self.assertEqual(run.stderr, "")

    def test_cli_rejects_bad_json_and_never_falls_back_to_guessed_version(self):
        for input_data in ("{}", "not-json", "[]"):
            with self.subTest(input_data=input_data):
                run = subprocess.run(
                    [sys.executable, str(ROOT / "scripts/select_ota_baseline.py"), "1203"],
                    input=input_data, text=True, capture_output=True)
                self.assertNotEqual(run.returncode, 0)
                self.assertEqual(run.stdout, "")


if __name__ == "__main__":
    unittest.main()
