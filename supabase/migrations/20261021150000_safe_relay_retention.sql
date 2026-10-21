-- P1 lifecycle control for the relay. Apply deliberately on the real Supabase DB.
-- This migration does NOT enable a recurring job or delete unacknowledged events.
-- An operator must schedule select public.lanu_prune_retained_data(500) using
-- service_role/DB administration only after staging and backup verification.
create index if not exists lanu_events_acked_retention_idx
  on public.lanu_events(created_at, id) where acked;
create index if not exists lanu_nonces_global_retention_idx
  on public.lanu_nonces(created_at);
create index if not exists lanu_messages_deleted_retention_idx
  on public.lanu_messages(created_at, id) where deleted;

create or replace function public.lanu_prune_retained_data(p_batch integer default 500)
returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare
  removed_events integer := 0;
  removed_nonces integer := 0;
  removed_messages integer := 0;
begin
  if p_batch is null or p_batch < 1 or p_batch > 5000 then
    raise exception 'Retention batch must be between 1 and 5000';
  end if;

  -- Only ACKED snapshots can expire. Unsynchronized devices need their
  -- pending events regardless of age and must never lose them to maintenance.
  with targets as (
    select id from public.lanu_events
    where acked and created_at < now() - interval '30 days'
    order by created_at, id limit p_batch
  )
  delete from public.lanu_events e using targets t where e.id = t.id;
  get diagnostics removed_events = row_count;

  -- Signed requests expire after five minutes; keep nonce replay evidence
  -- for a full day. Unlike per-user cleanups, this covers inactive accounts.
  with targets as (
    select owner_id, request_id from public.lanu_nonces
    where created_at < now() - interval '1 day'
    order by created_at, owner_id, request_id limit p_batch
  )
  delete from public.lanu_nonces n using targets t
  where n.owner_id = t.owner_id and n.request_id = t.request_id;
  get diagnostics removed_nonces = row_count;

  -- An explicit deleted message may expire after 90 days ONLY if no
  -- pending OR retained event references it. Never remove live messages.
  with targets as (
    select m.id from public.lanu_messages m
    where m.deleted and m.created_at < now() - interval '90 days'
      and not exists (
        select 1 from public.lanu_events e where e.message_id = m.id
      )
    order by m.created_at, m.id limit p_batch
  )
  delete from public.lanu_messages m using targets t where m.id = t.id;
  get diagnostics removed_messages = row_count;

  return jsonb_build_object(
    'events', removed_events, 'nonces', removed_nonces,
    'deleted_messages', removed_messages
  );
end $$;

revoke all on function public.lanu_prune_retained_data(integer) from public, anon, authenticated;
grant execute on function public.lanu_prune_retained_data(integer) to service_role;
