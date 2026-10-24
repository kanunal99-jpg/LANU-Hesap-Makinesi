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
    "20261024105720_private_media_grants_and_quota.sql",
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


    def test_private_media_migration_is_private_and_quota_bounded(self):
        body = (MIGRATIONS / APPLIED[4]).read_text(encoding="utf-8").lower()
        self.assertIn("create table public.lanu_media_objects", body)
        self.assertIn("enable row level security", body)
        self.assertIn("revoke all on public.lanu_media_objects from public, anon, authenticated", body)
        self.assertIn("grant select,insert,update,delete on public.lanu_media_objects to service_role", body)
        self.assertIn("lanu_reserve_private_media", body)
        self.assertIn("lanu_mark_private_media_message", body)
        self.assertIn("lanu_media_aware_events", body)
        self.assertIn("p_owner = p_peer", body)
        self.assertIn("owner_daily>=4", body)
        self.assertIn("total_bytes+p_bytes>536870912", body)
        self.assertIn("owner_bytes+p_bytes>268435456", body)
        self.assertIn("array['application/octet-stream']", body)
        self.assertIn("false,44040192", body)
        self.assertIn("no anon/authenticated storage policy is created", body)
        self.assertNotIn("to anon;", body)
        self.assertNotIn("to authenticated;", body)


if __name__ == "__main__":
    unittest.main()
