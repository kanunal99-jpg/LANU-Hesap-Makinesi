"""Regression: 40MiB signed PUT must not be automatically replayed.

This static contract complements the Kotlin/Node transport suites; it never
reads app data or makes a network call.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PrivateMediaNoAutomaticReplayTest(unittest.TestCase):
    def test_large_media_is_hidden_and_never_enrolled_in_flag_off_builds(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        menu = (ROOT / "app/src/main/java/com/example/features/communication/InlineMediaComposer.kt").read_text()
        activity = (ROOT / "app/src/main/java/com/example/MainActivity.kt").read_text()
        repo = (ROOT / "app/src/main/java/com/example/core/repository/CommunicationRepository.kt").read_text()
        self.assertIn('buildConfigField("boolean", "LANU_PRIVATE_MEDIA_UI_ENABLED",', build)
        self.assertIn('if (providers.environmentVariable("LANU_PRIVATE_MEDIA_UI_ENABLED").orNull == "true") "true" else "false"', build)
        self.assertIn('if (BuildConfig.LANU_PRIVATE_MEDIA_UI_ENABLED) {', menu)
        self.assertIn('if (!BuildConfig.LANU_PRIVATE_MEDIA_UI_ENABLED', activity)
        self.assertIn('check(BuildConfig.LANU_PRIVATE_MEDIA_UI_ENABLED)', repo)
        self.assertIn('if (BuildConfig.LANU_PRIVATE_MEDIA_UI_ENABLED &&', repo)
        self.assertNotIn('LANU-belge', activity)

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
