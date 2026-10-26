"""Pure stub-based tests: never need a real Android phone or signing key."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
CHECK = ROOT / "verify_existing_install_signer.sh"


class InstalledSignerSafetyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.stubs = base / "stubs"
        self.stubs.mkdir()
        self.candidate = base / "app-debug.apk"
        self.installed = base / "device.apk"
        self.sha = base / "app-debug.apk.sha256"
        self.signer = base / "app-debug.apk.signer.txt"
        self.candidate.write_bytes(b"LANU PUBLIC CANDIDATE APK TEST DATA")
        self.installed.write_bytes(b"LANU PUBLIC INSTALLED APK TEST DATA")
        self.sha.write_text(hashlib.sha256(self.candidate.read_bytes()).hexdigest()
                            + "  app-debug.apk\n", encoding="ascii")
        self.signer.write_text("apk=app-debug.apk\nsigning_mode=stable\n"
                               + "certificate_sha256=" + "a" * 64
                               + "\nupgrade_verified_on_device=false\n", encoding="ascii")
        stubs = {
            "adb": """#!/bin/sh
case "$1" in
  get-state) printf '%s\\n' "${TEST_DEVICE_STATE:-device}" ;;
  shell) printf '%s\\n' 'package:/data/app/example/base.apk' ;;
  pull) cp "$TEST_INSTALLED_APK" "$3" ;;
  *) exit 1 ;;
esac
""",
            "apksigner": """#!/bin/sh
case "$3" in
  */installed.apk) cert="${TEST_INSTALLED_CERT}" ;;
  *) cert="${TEST_NEW_CERT}" ;;
esac
printf 'Signer #1 certificate SHA-256 digest: %s\\n' "$cert"
""",
            "aapt": """#!/bin/sh
case "$3" in
  */installed.apk) version="${TEST_INSTALLED_VERSION:-538}" ;;
  *) version="${TEST_NEW_VERSION:-549}" ;;
esac
name="${TEST_PACKAGE:-com.aistudio.lanucalc.kxwzqm}"
printf "package: name='%s' versionCode='%s' versionName='1.1.0'\\n" "$name" "$version"
"""
        }
        for name, script in stubs.items():
            script_file = self.stubs / name
            script_file.write_text(script, encoding="utf-8")
            script_file.chmod(0o700)
        self.env = os.environ.copy()
        self.env.update({
            "PATH": str(self.stubs) + os.pathsep + os.environ["PATH"],
            "TEST_INSTALLED_APK": str(self.installed),
            "TEST_NEW_CERT": "a" * 64,
            "TEST_INSTALLED_CERT": "a" * 64,
        })

    def check(self, **overrides):
        env = dict(self.env)
        env.update(overrides)
        return subprocess.run(
            ["bash", str(CHECK), str(self.candidate),
             str(self.sha), str(self.signer)],
            capture_output=True, text=True, env=env, timeout=10,
            check=False
        )

    def test_matching_real_install_certificate_and_newer_version_pass(self):
        outcome = self.check()
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        self.assertIn("PASS: installed LANU APK", outcome.stdout)
        self.assertEqual(self.installed.read_bytes(),
                         b"LANU PUBLIC INSTALLED APK TEST DATA")

    def test_current_installed_certificate_mismatch_blocks_upgrade(self):
        result = self.check(TEST_INSTALLED_CERT="b" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Do NOT uninstall", result.stderr)

    def test_candidate_certificate_differs_from_release_metadata(self):
        result = self.check(TEST_NEW_CERT="c" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)

    def test_downgrade_or_same_build_blocks_upgrade(self):
        for code in ("538", "1"):
            with self.subTest(code=code):
                result = self.check(TEST_NEW_VERSION=code)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("strictly newer", result.stderr)

    def test_corrupted_sha256_is_rejected_before_accessing_device(self):
        self.candidate.write_bytes(b"modified")
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA-256 mismatch", result.stderr)

    def test_wrong_android_application_id_is_rejected(self):
        result = self.check(TEST_PACKAGE="com.unrelated.app")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("applicationId", result.stderr)

    def test_unavailable_device_fails_closed(self):
        result = self.check(TEST_DEVICE_STATE="unauthorized")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not connected", result.stderr)


if __name__ == "__main__":
    unittest.main()
