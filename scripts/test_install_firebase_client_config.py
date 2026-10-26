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


    def test_safe_error_cause_for_wrong_secret_without_exposing_its_contents(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "app" / "google-services.json"
            wrong_project = json.loads(base64.b64decode(fake_config()))
            wrong_project["project_info"]["project_id"] = "SENSITIVE_WRONG_PROJECT"
            bad_clients = json.loads(base64.b64decode(fake_config()))
            bad_clients["project_info"]["project_number"] = 12345678
            cases = (
                ({"type": "service_account", "private_key": "SENSITIVE_PRIVATE_KEY"},
                 "Service-account JSON is not Android google-services.json"),
                ({"other": "SENSITIVE_CONFIG"},
                 "Android google-services.json missing project_info"),
                (wrong_project, "Firebase Android project mismatch"),
                (bad_clients, "Invalid Firebase Android client project_number or client list"),
            )
            for payload, expected in cases:
                with self.subTest(expected=expected):
                    with self.assertRaises(ValueError) as error:
                        installer.install(out, None, raw_json=json.dumps(payload))
                    self.assertIn(expected, str(error.exception))
                    self.assertNotIn("SENSITIVE", str(error.exception))
                    self.assertFalse(out.exists())

    def test_protected_secret_shape_diagnostics_are_specific_and_never_leak_values(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "google-services.json"
            valid = base64.b64decode(fake_config()).decode("utf-8")
            cases = (
                (json.dumps(valid), "JSON-encoded string"),
                (json.dumps({"projectId": "SENSITIVE-PROJECT", "apiKey": "SENSITIVE-KEY"}),
                 "Firebase Web SDK config"),
                (json.dumps({"content": valid, "token": "SENSITIVE-TOKEN"}),
                 "wrapper object"),
                (json.dumps({"client": [], "note": "SENSITIVE-NOTE"}),
                 "top-level project_info is missing"),
                (json.dumps({"other": "SENSITIVE-VALUE"}),
                 "not the top-level Android google-services.json"),
            )
            for payload, expected in cases:
                with self.subTest(expected=expected):
                    with self.assertRaises(ValueError) as result:
                        installer.install(out, None, raw_json=payload)
                    self.assertIn(expected, str(result.exception))
                    self.assertNotIn("SENSITIVE", str(result.exception))
                    self.assertFalse(out.exists())

if __name__ == "__main__":
    unittest.main()
