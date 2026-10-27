"""Fail-closed contract for an APK major/minor tag transition.

Existing v1.1 OTA clients do not recognize v1.2 release tags. The same
pinned-signer APK must remain downloadable under an old compatible alias,
with exact bytes independently re-downloaded and verified for both tags.
These tests do not fetch secrets, access GitHub or install an APK.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class ReleaseVersionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        cls.updater = (
            ROOT / "app/src/main/java/com/example/core/update/SecureOtaUpdater.kt"
        ).read_text(encoding="utf-8")
        cls.workflow = (
            ROOT / ".github/workflows/android.yml"
        ).read_text(encoding="utf-8")
        cls.publisher = (
            ROOT / "scripts/publish_verified_release_retry.sh"
        ).read_text(encoding="utf-8")

    def test_manifest_and_monotonic_published_code(self):
        self.assertIn('versionName = "1.2.0"', self.gradle)
        self.assertRegex(
            self.gradle,
            r'versionCode\s*=\s*providers\.environmentVariable\("LANU_VERSION_CODE"\)'
        )
        self.assertIn('?: 693', self.gradle)
        self.assertIn('LANU_VERSION_CODE: ${{ github.run_number }}', self.workflow)
        self.assertIn('test "$ACTUAL" = "$LANU_VERSION_CODE"', self.workflow)

    def test_old_and_new_ota_tag_families_are_both_recognized(self):
        self.assertIn("1\\.0-signed|2\\.0-signed", self.updater)
        self.assertIn("0\\.0-debug", self.updater)
        self.assertIn("1\\.0-signed|2\\.0-signed", self.publisher)
        self.assertNotIn('"v1.2.0-debug.', self.workflow)

    def test_stable_signer_compares_across_both_version_names(self):
        # A 1.2 release must inherit 1.1's fingerprint, not establish
        # a new unverified first-stable-signing baseline.
        self.assertIn(r"^v1\\.(1|2)\\.0-signed\\.[0-9]+$", self.workflow)
        self.assertIn("verify_stable_signer_continuity.py", self.workflow)

    def test_legacy_bridge_and_primary_tag_share_identical_artifacts(self):
        self.assertIn('TAG="v1.2.0-signed.${{ github.run_number }}"', self.workflow)
        self.assertIn('LEGACY_TAG="v1.1.0-signed.${{ github.run_number }}"', self.workflow)
        self.assertIn(
            'bash scripts/publish_verified_release_retry.sh "$LEGACY_TAG"',
            self.workflow,
        )
        self.assertIn(
            'bash scripts/publish_verified_release_retry.sh "$TAG" "$NOTES"',
            self.workflow,
        )
        self.assertIn(
            'TAGS=("v1.1.0-signed.${{ github.run_number }}" '
            '"v1.2.0-signed.${{ github.run_number }}")',
            self.workflow,
        )
        self.assertIn('for TAG in "${TAGS[@]}"; do', self.workflow)
        self.assertIn(
            'python3 scripts/verify_published_apk.py '
            '"$dir" "$RUNNER_TEMP/lanu-verified" "$SIGNING_MODE"',
            self.workflow,
        )
        self.assertIn(
            'bash scripts/test_publish_verified_release_retry.sh',
            self.workflow,
        )

    def test_release_publisher_restricts_allowed_tags_and_sha(self):
        self.assertIn('^v1\\.(0\\.0-debug|1\\.0-signed|2\\.0-signed)', self.publisher)
        self.assertIn('[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]', self.publisher)
        self.assertIn('if [[ "$target" != "$GITHUB_SHA" ]]; then', self.publisher)


if __name__ == "__main__":
    unittest.main()
