"""Protect LANU's deployed Supabase migration provenance and bounded retention contract."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"

APPLIED = (
    "20261019073855_persistent_user_code_messaging.sql",
    "20261021160108_safe_relay_retention_20261021.sql",
    "20261021160752_enable_pgcron_for_safe_lanu_retention_20261021.sql",
    "20261021160807_schedule_lanu_safe_retention_daily_20261021.sql",
)


class SupabaseMigrationProvenanceTest(unittest.TestCase):
    def test_applied_versions_are_tracked_once_and_ordered(self):
        files = sorted(p.name for p in MIGRATIONS.glob("*.sql"))
        self.assertTrue(all(f in files for f in APPLIED))
        versions = [n.split("_", 1)[0] for n in files]
        self.assertEqual(len(versions), len(set(versions)), "A duplicate migration version can break deploys")
        self.assertTrue(all(re.fullmatch(r"[0-9]{14}", n) for n in versions))
        self.assertEqual(list(APPLIED), sorted(APPLIED))

    def test_retention_can_never_delete_undelivered_messages(self):
        body = (MIGRATIONS / APPLIED[1]).read_text(encoding="utf-8").lower()
        self.assertIn("where acked and created_at <", body)
        self.assertIn("where m.deleted and m.created_at <", body)
        self.assertIn("not exists (", body)
        self.assertIn("select 1 from public.lanu_events", body)
        self.assertIn("p_batch > 5000", body)
        self.assertIn("security invoker", body)
        self.assertIn("to service_role", body)
        self.assertIn("from public, anon, authenticated", body)

    def test_live_cron_job_is_bounded_and_reproducible(self):
        extension = (MIGRATIONS / APPLIED[2]).read_text(encoding="utf-8").lower()
        schedule = (MIGRATIONS / APPLIED[3]).read_text(encoding="utf-8").lower()
        self.assertIn("create extension if not exists pg_cron", extension)
        self.assertIn("lanu-safe-retention-daily", schedule)
        self.assertIn("15 3 * * *", schedule)
        self.assertIn("public.lanu_prune_retained_data(500)", schedule)
        self.assertNotIn("truncate", schedule)
        self.assertNotIn("delete from", schedule)


if __name__ == "__main__":
    unittest.main()
