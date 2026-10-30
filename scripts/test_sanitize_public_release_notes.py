"""Offline negative tests for public LANU release-note sanitization."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "lanu_release_note_sanitizer", ROOT / "sanitize_public_release_notes.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublicReleaseNoteSanitizerTest(unittest.TestCase):
    def test_exact_old_public_disclosure_is_removed_without_modifying_commit(self):
        original = (
            "Commit: abcdef01. " + module.LEGACY_DISCLOSURE +
            " Mevcut uygulamayı kaldırmayın; aynı stable imza ve daha yüksek versionCode gerekir."
        )
        result = module.sanitize_notes(original)
        self.assertNotIn("gizli", result.casefold())
        self.assertNotIn("mesaj", result.casefold())
        self.assertNotIn("çağrı", result.casefold())
        self.assertIn("Commit: abcdef01.", result)
        self.assertIn("Mevcut uygulamayı kaldırmayın", result)
        self.assertEqual(result, module.sanitize_notes(result))

    def test_unknown_release_notes_are_left_unmodified(self):
        text = "Commit: 234. Verified signed APK; never remove installed application."
        self.assertEqual(text, module.sanitize_notes(text))

    def test_only_expected_signed_tag_pattern_is_eligible(self):
        self.assertIsNotNone(module.STABLE_TAG.fullmatch("v1.2.0-signed.888"))
        self.assertIsNotNone(module.STABLE_TAG.fullmatch("v1.1.0-signed.888"))
        for text in (
            "v1.2.0-signed.888/../other", "v1.0.0-debug.1",
            "v1.2.0-signed.abc", "anything", "v2.0.0-signed.888"
        ):
            self.assertIsNone(module.STABLE_TAG.fullmatch(text))


if __name__ == "__main__":
    unittest.main()
