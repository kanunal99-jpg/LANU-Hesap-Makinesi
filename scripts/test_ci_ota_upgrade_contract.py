"""Guard against replacing real signed-upgrade acceptance with a synthetic pass."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent


class TestOtaContract(unittest.TestCase):
    def test_runtime_harness_never_uninstalls_or_clears_package(self):
        content = (ROOT / "scripts/ci_ota_upgrade_acceptance.sh").read_text()
        commands = [line.strip() for line in content.splitlines()
                    if line.lstrip().startswith("adb ")]
        self.assertGreaterEqual(sum(line.startswith("adb install -r ") for line in commands), 4)
        self.assertFalse(any(" uninstall " in (" " + line + " ") or
                             " pm clear " in (" " + line + " ")
                             for line in commands))
        self.assertIn("cert \"$old\"", content)
        self.assertIn("cert \"$candidate\"", content)
        self.assertIn("seedPreviouslyInstalledStableRelease", content)
        self.assertIn("lanu_ota_verified_phase=$phase", content)
        self.assertIn("verifySameAccountAndAllSevenTablesAfterRealApkReplacement", content)

    def test_publish_requires_main_only_signed_upgrade_acceptance(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        self.assertIn("    needs: [build-and-test, android-runtime-acceptance, ota-upgrade-acceptance]", workflow)
        self.assertIn("  ota-upgrade-acceptance:", workflow)
        self.assertIn("github.event_name == 'push'", workflow)
        self.assertIn("scripts/ci_prepare_android_image.sh", workflow)
        self.assertIn("scripts/ci_ota_upgrade_acceptance.sh", workflow)
        self.assertIn("Checkout pinned previous release source", workflow)
        self.assertIn("Compile baseline instrumentation against the actual previous release ABI", workflow)

    def test_probe_is_explicit_emulator_only_and_checks_real_record_continuity(self):
        source = (ROOT / "app/src/androidTest/java/com/example/RoomActualOtaUpgradeTest.kt").read_text()
        for marker in ("lanu_ota_ci", "ranchu", "goldfish", "pin_hash",
                       "getPendingMessages", "EncryptedMessagingIdentityStore",
                       "isConsistentIdentity", "getAllHistory", "getAllCallLogs",
                       "getMessage", "getContactByPhone", "getConversationById"):
            self.assertIn(marker, source)
        self.assertIn("confirmPhase(\"seed\")", source)
        self.assertIn("confirmPhase(\"verify\")", source)
        self.assertNotIn("deleteDatabase(", source)
        self.assertNotIn("clearApplicationUserData", source)


if __name__ == "__main__":
    unittest.main()
