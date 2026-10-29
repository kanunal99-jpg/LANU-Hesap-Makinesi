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

    def test_signer_mismatch_names_only_the_failing_apk_and_never_installs(self):
        """Exercise the real OTA shell preflight against disposable signer mocks."""
        import os
        import subprocess
        import tempfile

        good, bad = "a" * 64, "b" * 64
        cases = {
            "new.apk": "candidate release vs published baseline",
            "old-tests.apk": "baseline instrumentation vs published baseline",
            "new-tests.apk": "candidate instrumentation vs published baseline",
        }
        with tempfile.TemporaryDirectory() as root:
            base = pathlib.Path(root)
            build_tools = base / "sdk" / "build-tools" / "35.0.0"
            build_tools.mkdir(parents=True)
            aapt = build_tools / "aapt"
            aapt.write_text("""#!/usr/bin/env bash
set -eu
case "$(basename "$3")" in
  *tests.apk) name=com.aistudio.lanucalc.kxwzqm.test ;;
  *) name=com.aistudio.lanucalc.kxwzqm ;;
esac
case "$(basename "$3")" in
  old*) version=828 ;;
  *) version=1200 ;;
esac
echo "package: name='$name' versionCode='$version' versionName='1.2.0'"
""")
            signer = build_tools / "apksigner"
            signer.write_text("""#!/usr/bin/env bash
set -eu
if [[ "$(basename "$3")" == "$BAD_APK" ]]; then
  digest="$BAD_DIGEST"
else
  digest="$GOOD_DIGEST"
fi
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$digest"
""")
            for tool in (aapt, signer):
                tool.chmod(0o755)
            artifacts = [base / name for name in
                         ("old.apk", "new.apk", "old-tests.apk", "new-tests.apk")]
            for path in artifacts:
                path.write_bytes(b"disposable-test-apk")
            for bad_apk, expected in cases.items():
                with self.subTest(bad_apk=bad_apk):
                    env = dict(os.environ, ANDROID_HOME=str(base / "sdk"),
                               BAD_APK=bad_apk, GOOD_DIGEST=good, BAD_DIGEST=bad)
                    proc = subprocess.run(
                        ["bash", str(ROOT / "scripts/ci_ota_upgrade_acceptance.sh"),
                         *(str(x) for x in artifacts), "828", "1200"],
                        env=env, capture_output=True, text=True, check=False)
                    output = proc.stdout + proc.stderr
                    self.assertNotEqual(0, proc.returncode)
                    self.assertIn(expected, output)
                    self.assertIn("preserve previous installation", output)
                    self.assertNotIn(good, output)
                    self.assertNotIn(bad, output)
                    self.assertNotIn("Installing the EXACT", output)

    def test_seed_failure_diagnostics_redact_private_instrumentation(self):
        """An assertion failure must remain fatal without leaking probe output."""
        import os
        import subprocess
        import tempfile

        with tempfile.TemporaryDirectory() as root:
            base = pathlib.Path(root)
            build_tools = base / "sdk" / "build-tools" / "35.0.0"
            bin_dir = base / "bin"
            build_tools.mkdir(parents=True)
            bin_dir.mkdir()
            aapt = build_tools / "aapt"
            aapt.write_text("""#!/usr/bin/env bash
set -eu
case "$(basename "$3")" in
  *tests.apk) name=com.aistudio.lanucalc.kxwzqm.test ;;
  *) name=com.aistudio.lanucalc.kxwzqm ;;
esac
case "$(basename "$3")" in
  old*) version=828 ;;
  *) version=1200 ;;
esac
echo "package: name='$name' versionCode='$version' versionName='1.2.0'"
""")
            signer = build_tools / "apksigner"
            signer.write_text("""#!/usr/bin/env bash
printf 'Signer #1 certificate SHA-256 digest: %064d\n' 0
""")
            adb = bin_dir / "adb"
            adb.write_text("""#!/usr/bin/env bash
printf '%s\n' "$*" >> "$MOCK_ADB_LOG"
if [[ "$1" == "shell" ]]; then
  cat <<'EOF'
INSTRUMENTATION_STATUS: lanu_ota_checkpoint=gate
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: PRIVATE_KEY_DONT_LOG PIN_DONT_LOG
INSTRUMENTATION_STATUS: stream=PRIVATE_MESSAGE_DONT_LOG
INSTRUMENTATION_STATUS_CODE: -2
FAILURES!!!
INSTRUMENTATION_CODE: -1
EOF
fi
""")
            for tool in (aapt, signer, adb):
                tool.chmod(0o755)
            artifacts = [base / name for name in
                         ("old.apk", "new.apk", "old-tests.apk", "new-tests.apk")]
            for artifact in artifacts:
                artifact.write_bytes(b"disposable-test-apk")
            adb_log = base / "adb-calls.txt"
            env = dict(os.environ, ANDROID_HOME=str(base / "sdk"),
                       PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                       MOCK_ADB_LOG=str(adb_log))
            proc = subprocess.run(
                ["bash", str(ROOT / "scripts/ci_ota_upgrade_acceptance.sh"),
                 *(str(x) for x in artifacts), "828", "1200"],
                env=env, capture_output=True, text=True, check=False)
            output = proc.stdout + proc.stderr
            self.assertNotEqual(0, proc.returncode)
            self.assertIn("OTA data preservation assertion failed in seed phase", output)
            self.assertIn("OTA seed checkpoint: gate", output)
            self.assertIn("OTA seed exception type: java.lang.AssertionError", output)
            self.assertNotIn("PRIVATE_KEY_DONT_LOG", output)
            self.assertNotIn("PIN_DONT_LOG", output)
            self.assertNotIn("PRIVATE_MESSAGE_DONT_LOG", output)
            self.assertNotIn("Applying the REAL candidate upgrade", output)
            calls = adb_log.read_text().splitlines()
            self.assertEqual(2, sum(line.startswith("install -r ") for line in calls))

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
        self.assertIn("EncryptedMessagingIdentityStore(context, null)", source)
        self.assertNotIn("EncryptedMessagingIdentityStore(context)", source)
        # Published v828 R8 inlines UserCodes.create(), so OTA seed must not link it.
        self.assertIn("makeLegacyCompatibleIdentity()", source)
        self.assertNotIn("val identity = UserCodes.create()", source)
        for checkpoint in ("prefs-empty", "identity-store-created", "identity-store-empty",
                           "identity-generated", "identity-saved", "pin-generated", "cipher-generated"):
            self.assertIn(f'checkpoint("{checkpoint}")', source)
            self.assertIn(checkpoint, (ROOT / "scripts/ci_ota_upgrade_acceptance.sh").read_text())
        self.assertIn("confirmPhase(\"seed\")", source)
        self.assertIn("confirmPhase(\"verify\")", source)
        self.assertNotIn("deleteDatabase(", source)
        self.assertNotIn("clearApplicationUserData", source)


if __name__ == "__main__":
    unittest.main()
