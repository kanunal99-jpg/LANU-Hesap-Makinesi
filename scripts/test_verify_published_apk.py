import hashlib
import shutil
import tempfile
import unittest
from pathlib import Path

from verify_published_apk import verify


class PublishedApkVerificationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.ci = root / "ci"
        self.release = root / "github-release"
        self.ci.mkdir()
        self.release.mkdir()
        apk = b"sample APK artifact bytes - CI checks real APK signatures"
        self.ci.joinpath("app-debug.apk").write_bytes(apk)
        self.ci.joinpath("app-debug.apk.sha256").write_text(
            hashlib.sha256(apk).hexdigest() + "  app-debug.apk\n", encoding="utf-8"
        )
        self.ci.joinpath("app-debug.apk.signer.txt").write_text(
            "apk=app-debug.apk\nsigning_mode=ephemeral\n"
            "certificate_sha256=" + "a" * 64 +
            "\nupgrade_verified_on_device=false\n", encoding="utf-8"
        )
        for entry in self.ci.iterdir():
            shutil.copyfile(entry, self.release / entry.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_byte_identical_release_with_matching_sha_is_accepted(self):
        verify(self.release, self.ci, "ephemeral")

    def test_tampered_public_apk_is_rejected(self):
        with (self.release / "app-debug.apk").open("ab") as f:
            f.write(b"attacker")
        with self.assertRaisesRegex(ValueError, "differs"):
            verify(self.release, self.ci, "ephemeral")

    def test_absent_signer_asset_fails_publication(self):
        (self.release / "app-debug.apk.signer.txt").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
            verify(self.release, self.ci, "ephemeral")

    def test_conflicting_signing_modes_fail(self):
        with self.assertRaisesRegex(ValueError, "signing mode"):
            verify(self.release, self.ci, "stable")

    def test_republished_sidecar_with_different_fingerprint_fails(self):
        path = self.release / "app-debug.apk.signer.txt"
        path.write_text(path.read_text().replace("a" * 64, "b" * 64), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            verify(self.release, self.ci, "ephemeral")

    def test_wrong_checksum_fails_even_if_both_directories_match(self):
        broken = "0" * 64 + "  app-debug.apk\n"
        for root in (self.release, self.ci):
            root.joinpath("app-debug.apk.sha256").write_text(broken, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
            verify(self.release, self.ci, "ephemeral")


if __name__ == "__main__":
    unittest.main()
