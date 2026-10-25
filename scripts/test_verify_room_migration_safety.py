import unittest
from pathlib import Path
from verify_room_migration_safety import assert_room_migration_safe, assert_no_production_database_wipes

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


    def test_destructive_call_in_other_production_file_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "CommunicationRepository"):
            assert_no_production_database_wipes({
                "app/src/main/java/com/example/core/repository/CommunicationRepository.kt":
                    'fun wipe(ctx: Context) { ctx.deleteDatabase("lanu_calculator_db") }'
            })

    def test_destructive_room_fallback_outside_database_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "MigrationHelper"):
            assert_no_production_database_wipes({
                "app/src/main/java/com/example/core/database/MigrationHelper.kt":
                    'fun build() { builder.fallbackToDestructiveMigrationFrom(1,2) }'
            })

    def test_comment_only_mentions_and_message_deletes_stay_allowed(self):
        assert_no_production_database_wipes({
            "app/src/main/java/com/example/Example.kt":
                '// builder.fallbackToDestructiveMigration()\n/* ctx.deleteDatabase("db") */\nfun removeOne() { dao.deleteMessage(id) }'
        })

    def test_non_production_paths_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "invalid source path"):
            assert_no_production_database_wipes({"app/src/test/java/com/example/Fake.kt": ""})

    def test_real_production_tree_is_scanned(self):
        root=ROOT/"app/src/main"
        found={p.relative_to(ROOT).as_posix():p.read_text(encoding="utf-8")
               for p in root.rglob("*") if p.is_file() and p.suffix in {".kt",".java"}}
        self.assertTrue(found)
        assert_no_production_database_wipes(found)

if __name__=="__main__":
    unittest.main()
