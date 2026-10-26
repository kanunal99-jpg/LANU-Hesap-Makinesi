import tempfile
import unittest
from pathlib import Path

from verify_stable_signer_continuity import verify_stable_signer_continuity


class StableSignerContinuityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.current = Path(self.temp.name) / "current.signer.txt"
        self.previous = Path(self.temp.name) / "previous.signer.txt"
        self.write(self.current, "a" * 64)
        self.write(self.previous, "a" * 64)

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def write(path: Path, fingerprint: str, mode: str = "stable") -> None:
        path.write_text(
            "apk=app-debug.apk\n"
            f"signing_mode={mode}\n"
            f"certificate_sha256={fingerprint}\n"
            "upgrade_verified_on_device=false\n", encoding="utf-8"
        )

    def test_same_stable_certificate_passes(self):
        verify_stable_signer_continuity(self.current, self.previous)

    def test_changed_certificate_blocks_release(self):
        self.write(self.current, "b" * 64)
        with self.assertRaisesRegex(ValueError, "Signing identity changed"):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_previous_ephemeral_certificate_is_not_a_stable_baseline(self):
        self.write(self.previous, "a" * 64, mode="ephemeral")
        with self.assertRaisesRegex(ValueError, "protected stable"):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_current_ephemeral_certificate_is_rejected(self):
        self.write(self.current, "a" * 64, mode="ephemeral")
        with self.assertRaisesRegex(ValueError, "protected stable"):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_invalid_previous_fingerprint_fails_closed(self):
        self.write(self.previous, "bad")
        with self.assertRaisesRegex(ValueError, "Malformed"):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_missing_baseline_file_fails_closed(self):
        self.previous.unlink()
        with self.assertRaises(FileNotFoundError):
            verify_stable_signer_continuity(self.current, self.previous)


    def test_missing_current_signer_evidence_stops_release(self):
        self.current.unlink()
        with self.assertRaises(FileNotFoundError):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_truncated_current_certificate_stops_release(self):
        self.write(self.current, "0" * 63)
        with self.assertRaisesRegex(ValueError, "Malformed"):
            verify_stable_signer_continuity(self.current, self.previous)

    def test_release_certificate_must_be_same_in_both_directions(self):
        self.write(self.current, "a" * 64)
        self.write(self.previous, "b" * 64)
        with self.assertRaisesRegex(ValueError, "Signing identity changed"):
            verify_stable_signer_continuity(self.current, self.previous)


if __name__ == "__main__":
    unittest.main()
