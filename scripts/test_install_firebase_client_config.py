import base64
import importlib.util
import json
import pathlib
import tempfile
import unittest

MODULE = pathlib.Path(__file__).with_name("install_firebase_client_config.py")
spec = importlib.util.spec_from_file_location("firebase_setup", MODULE)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def fake_config(package=installer.EXPECTED_APPLICATION_ID,
                project=installer.EXPECTED_FIREBASE_PROJECT_ID):
    return base64.b64encode(json.dumps({
        "project_info": {"project_id": project, "project_number": "12345678"},
        "client": [{
            "client_info": {
                "mobilesdk_app_id": "1:12345678:android:fixture",
                "android_client_info": {"package_name": package}
            },
            "api_key": [{"current_key": "fixture-not-a-real-api-key"}]
        }]
    }).encode()).decode()


class FirebaseConfigInstallationTest(unittest.TestCase):
    def test_missing_config_is_explicitly_disabled(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            self.assertFalse(installer.install(out, ""))
            self.assertFalse(out.exists())

    def test_valid_matching_package_installs_private_atomic_file(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            self.assertTrue(installer.install(out, fake_config()))
            self.assertEqual(json.loads(out.read_text())["project_info"]["project_id"],
                             installer.EXPECTED_FIREBASE_PROJECT_ID)
            self.assertEqual(out.stat().st_mode & 0o777, 0o600)

    def test_raw_json_from_protected_secret_is_accepted_without_base64(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            original = base64.b64decode(fake_config()).decode("utf-8")
            self.assertTrue(installer.install(out, None, raw_json=original))
            self.assertEqual(json.loads(out.read_text())["project_info"]["project_id"],
                             installer.EXPECTED_FIREBASE_PROJECT_ID)
            self.assertEqual(out.stat().st_mode & 0o777, 0o600)

    def test_wrong_firebase_project_and_conflicting_secrets_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            current = base64.b64decode(fake_config()).decode("utf-8")
            for encoded, raw in (
                (fake_config(project="other-project"), None),
                (None, base64.b64decode(fake_config(project="other-project")).decode("utf-8")),
                (fake_config(), current),
                (None, "{not-json"),
                (None, '{"type":"service_account","private_key":"not-allowed"}'),
            ):
                with self.assertRaises(ValueError):
                    installer.install(out, encoded, raw_json=raw)
                self.assertFalse(out.exists())

    def test_invalid_wrong_package_and_oversized_preserve_existing_config(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            out.parent.mkdir()
            out.write_text("previous")
            for data in ("not base64", fake_config("com.attacker.fake"), "A" * 150000,
                         base64.b64encode(b"null").decode()):
                with self.assertRaises(ValueError):
                    installer.install(out, data)
                self.assertEqual(out.read_text(), "previous")

    def test_duplicate_matching_clients_are_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            cfg = json.loads(base64.b64decode(fake_config()))
            cfg["client"].append(cfg["client"][0])
            out = pathlib.Path(td) / "app" / "google-services.json"
            with self.assertRaises(ValueError):
                installer.install(out, base64.b64encode(json.dumps(cfg).encode()).decode())
            self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
