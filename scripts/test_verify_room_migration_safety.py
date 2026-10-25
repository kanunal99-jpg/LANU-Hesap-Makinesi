import unittest
from pathlib import Path
from verify_room_migration_safety import assert_room_migration_safe

ROOT=Path(__file__).resolve().parents[1]

class RoomMigrationSafetyGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline=(ROOT/"app/src/main/java/com/example/core/database/AppDatabase.kt").read_text(encoding="utf-8")

    def test_current_durable_room_factory_is_non_destructive(self):
        assert_room_migration_safe(self.baseline)

    def test_destructive_migration_fails_even_with_optional_argument(self):
        for method in (".fallbackToDestructiveMigration()",
                       ".fallbackToDestructiveMigration(true)",
                       ".fallbackToDestructiveMigrationFrom(1,2)"):
            with self.subTest(method=method), self.assertRaisesRegex(ValueError,"Destructive"):
                assert_room_migration_safe(self.baseline.replace(".build()",method+"\n.build()"))

    def test_removing_schema_history_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"schema history"):
            assert_room_migration_safe(self.baseline.replace("exportSchema = true","exportSchema = false"))

    def test_explicit_database_deletion_is_blocked(self):
        with self.assertRaisesRegex(ValueError,"Deleting"):
            assert_room_migration_safe(self.baseline.replace(".build()",".deleteDatabase(\"lanu_calculator_db\")\n.build()"))

    def test_false_positives_in_comments_do_not_fail(self):
        assert_room_migration_safe(self.baseline+"\n// .fallbackToDestructiveMigration() must never be added")
        assert_room_migration_safe(self.baseline+"\n/* .deleteDatabase(\"db\") */")

if __name__=="__main__":
    unittest.main()
