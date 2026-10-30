import unittest
from pathlib import Path
from verify_room_nondestructive import verify_room_source

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/example/core/database/AppDatabase.kt").read_text(encoding="utf-8")


class RoomNondestructiveRegressionTest(unittest.TestCase):
    def check(self, source=SOURCE, other=None):
        verify_room_source(source, {"AppDatabase.kt": source, **(other or {})})

    def test_current_room_v4_policy_passes(self):
        self.check()

    def test_corruption_retention_factory_cannot_be_removed(self):
        with self.assertRaisesRegex(ValueError, "corruption retention"):
            self.check(source=SOURCE.replace(
                ".openHelperFactory(PreserveCorruptRoomFactory())", ""))

    def test_framework_data_loss_recovery_must_stay_disabled(self):
        with self.assertRaisesRegex(ValueError, "Unsafe destructive"):
            self.check(other={"Other.kt": "helper.allowDataLossOnRecovery(true)"})

    def test_destructive_upgrade_fallback_must_fail(self):
        with self.assertRaisesRegex(ValueError, "Unsafe destructive"):
            self.check(other={"Other.kt": "database.fallbackToDestructiveMigration()"})

    def test_destructive_downgrade_and_from_versions_must_fail(self):
        for statement in (".fallbackToDestructiveMigrationOnDowngrade()",
                          ".fallbackToDestructiveMigrationFrom(1, 2, 3)"):
            with self.subTest(statement=statement):
                with self.assertRaisesRegex(ValueError, "Unsafe destructive"):
                    self.check(other={"Other.kt": "database" + statement})

    def test_non_exported_room_schema_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "schema export"):
            self.check(source=SOURCE.replace("exportSchema = true", "exportSchema = false"))

    def test_unplanned_room_version_upgrade_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "version changed"):
            self.check(source=SOURCE.replace("version = 4", "version = 5"))

    def test_main_thread_database_queries_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unsafe destructive"):
            self.check(other={"Other.kt": "database.allowMainThreadQueries()"})


if __name__ == "__main__":
    unittest.main()
