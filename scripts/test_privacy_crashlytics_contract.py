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
        # All jobs compile the release variant, but only the trusted build
        # with verified Firebase configuration may upload the mapping file.
        self.assertIn('System.getenv("LANU_FIREBASE_CONFIG_VERIFIED") == "true"', module)
        self.assertIn('file("google-services.json").isFile', module)
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn('echo "LANU_FIREBASE_CONFIG_VERIFIED=true" >> "$GITHUB_ENV"', workflow)
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


    def test_manifest_privacy_flags_are_unique_xml_defaults(self):
        # A string search can pass even when a second, later metadata entry
        # overrides the first. Check the actual XML and require one false
        # entry per SDK before any Application code executes.
        from xml.etree import ElementTree
        manifest = ElementTree.fromstring(
            (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        )
        application = manifest.find("application")
        self.assertIsNotNone(application)
        android = "{http://schemas.android.com/apk/res/android}"
        entries = application.findall("meta-data")
        for key in (
            "firebase_crashlytics_collection_enabled",
            "firebase_analytics_collection_enabled",
        ):
            values = [
                entry.get(android + "value")
                for entry in entries
                if entry.get(android + "name") == key
            ]
            self.assertEqual(
                values, ["false"],
                f"{key} must appear exactly once and default to false",
            )

    def test_remote_crash_collection_can_only_be_enabled_in_private_dialog(self):
        # No future public screen, Application startup, service or worker may
        # silently enable remote crash collection. This is a source boundary
        # guard, NOT proof of Firebase delivery or physical device consent.
        import re
        source_root = ROOT / "app/src/main/java"
        enabled_at = [
            path.relative_to(source_root).as_posix()
            for path in source_root.rglob("*.kt")
            if re.search(
                r"\bsetCrashlyticsCollectionEnabled\s*\(\s*true\s*\)",
                path.read_text(encoding="utf-8"),
            )
        ]
        self.assertEqual(
            enabled_at,
            ["com/example/features/security/LocalCrashDiagnosticsPanel.kt"],
            "Remote Crashlytics activation must stay behind private opt-in",
        )
        panel = (
            source_root
            / "com/example/features/security/LocalCrashDiagnosticsPanel.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("if (confirmRemote)", panel)
        self.assertIn("confirm_crashlytics_opt_in", panel)
        self.assertIn("setCrashlyticsCollectionEnabled(false)", panel)
        self.assertIn("deleteUnsentReports()", panel)


if __name__ == "__main__":
    unittest.main()
