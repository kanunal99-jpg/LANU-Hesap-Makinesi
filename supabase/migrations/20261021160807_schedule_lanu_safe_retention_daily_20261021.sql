-- Applied to LANU Code Messaging on 2026-10-21.
-- Runs daily at 03:15 UTC (06:15 Türkiye); maximum 500 rows per class per run.
-- Read the tested safety contract in 20261021160108_safe_relay_retention_20261021.sql.
select cron.schedule('lanu-safe-retention-daily', '15 3 * * *',
  $$select public.lanu_prune_retained_data(500);$$);
