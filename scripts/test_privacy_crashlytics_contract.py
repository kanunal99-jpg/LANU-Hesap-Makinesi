"""Privacy regression for Crashlytics: remote crash data needs private opt-in.

No production Firebase keys are read; no crash is fabricated or sent.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class CrashlyticsPrivacyContractTest(unittest.TestCase):
    def test_remote_collection_is_disabled_before_application_code(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name="firebase_crashlytics_collection_enabled" android:value="false"', manifest)
        self.assertIn('android:name="firebase_analytics_collection_enabled" android:value="false"', manifest)

    def test_sdk_is_pinned_and_private_consent_is_explicit(self):
        aliases = (ROOT / "gradle/libs.versions.toml").read_text()
        module = (ROOT / "app/build.gradle.kts").read_text()
        private_panel = (ROOT / "app/src/main/java/com/example/features/security/LocalCrashDiagnosticsPanel.kt").read_text()
        public_calculator = (ROOT / "app/src/main/java/com/example/features/calculator/CalculatorScreen.kt").read_text()
        self.assertIn('crashlyticsGradle = "3.0.8"', aliases)
        self.assertIn('implementation(libs.firebase.crashlytics)', module)
        self.assertIn('configure<CrashlyticsExtension>', module)
        self.assertIn('System.getenv("GITHUB_EVENT_NAME") in setOf("push", "workflow_dispatch")', module)
        self.assertIn('System.getenv("GITHUB_REF") in setOf("refs/heads/main", "refs/heads/master")', module)
        self.assertIn('mappingFileUploadEnabled = false', module)
        self.assertIn('confirmRemote = true', private_panel)
        self.assertIn('setCrashlyticsCollectionEnabled(true)', private_panel)
        self.assertIn('setCrashlyticsCollectionEnabled(false)', private_panel)
        self.assertIn('deleteUnsentReports()', private_panel)
        self.assertIn('private_crashlytics_opt_in', private_panel)
        self.assertNotIn('Crashlytics', public_calculator)
        self.assertNotIn('setCrashlyticsCollectionEnabled(true)',
                         (ROOT / "app/src/main/java/com/example/LanuApplication.kt").read_text())
        self.assertNotIn('recordException(', private_panel)
        self.assertNotIn('setUserId(', private_panel)


if __name__ == "__main__":
    unittest.main()
