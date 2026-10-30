"""Regression: 40MiB signed PUT must not be automatically replayed.

This static contract complements the Kotlin/Node transport suites; it never
reads app data or makes a network call.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PrivateMediaNoAutomaticReplayTest(unittest.TestCase):
    def test_upload_body_is_explicitly_one_shot_and_loss_ack_is_verified(self):
        http = (ROOT / "app/src/main/java/com/example/core/media/PrivateMediaTransferHttp.kt").read_text()
        owner = (ROOT / "app/src/main/java/com/example/core/repository/CommunicationRepository.kt").read_text()
        self.assertIn('val requestBody = object : RequestBody() {', http)
        self.assertIn('override fun isOneShot(): Boolean = true', http)
        self.assertIn('followRedirects(false).followSslRedirects(false)', http)
        self.assertIn('relay.request(own, "mediaComplete"', owner)
        self.assertIn('catch (e: java.io.IOException) { uploadError = e }', owner)
        self.assertIn('if (uploadError != null) throw uploadError', owner)
        self.assertNotIn('retryOnConnectionFailure(true)', http)


if __name__ == "__main__":
    unittest.main()
