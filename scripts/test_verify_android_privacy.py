import unittest
from pathlib import Path
from verify_android_privacy import assert_private_manifest

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res/xml"


class AndroidPrivacyManifestContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        cls.backup = (RES / "backup_rules.xml").read_text(encoding="utf-8")
        cls.extraction = (RES / "data_extraction_rules.xml").read_text(encoding="utf-8")

    def verify(self, manifest=None, backup=None, extraction=None):
        assert_private_manifest(
            self.manifest if manifest is None else manifest,
            self.backup if backup is None else backup,
            self.extraction if extraction is None else extraction,
        )

    def test_current_manifest_is_private(self):
        self.verify()

    def test_android_autobackup_cannot_be_enabled(self):
        with self.assertRaisesRegex(ValueError, "allowBackup"):
            self.verify(manifest=self.manifest.replace('android:allowBackup="false"',
                                                        'android:allowBackup="true"'))

    def test_cloud_backup_cannot_copy_room_database(self):
        with self.assertRaisesRegex(ValueError, "cloud-backup"):
            self.verify(extraction=self.extraction.replace(
                '<exclude domain="root" path="." />', '<include domain="database" path="." />', 1))

    def test_device_transfer_cannot_copy_keystore_bound_identity(self):
        left, right = self.extraction.split("<device-transfer>", 1)
        # Delete just the device-transfer deny rule, leaving cloud-backup intact.
        updated = left + "<device-transfer>" + right.replace(
            '<exclude domain="root" path="." />', "", 1)
        with self.assertRaisesRegex(ValueError, "device-transfer"):
            self.verify(extraction=updated)

    def test_legacy_backup_cannot_be_enabled(self):
        with self.assertRaisesRegex(ValueError, "Legacy backup"):
            self.verify(backup=self.backup.replace(
                '<exclude domain="root" path="." />', '<include domain="root" path="." />'))

    def test_ota_file_provider_cannot_be_exported(self):
        with self.assertRaisesRegex(ValueError, "OTA FileProvider"):
            self.verify(manifest=self.manifest.replace(
                'android:exported="false"', 'android:exported="true"'))


if __name__ == "__main__":
    unittest.main()
