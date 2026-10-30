-- LANU staged/UNDEPLOYED account erasure transaction.
-- DO NOT move into supabase/migrations or enable Edge dispatch before:
--   authenticated app confirmation, legal retention review, isolated restore,
--   private media Storage object cleanup evidence and physical-device UAT.
-- This file is exercised against disposable PGlite only.
--
-- The Edge's existing P-256 ECDSA signature + nonce verifies the owner
-- BEFORE calling this service_role-only, SECURITY INVOKER RPC.
-- Never grant DELETE on raw tables or EXECUTE on this RPC to app clients.

create or replace function public.lanu_delete_account_v1(
    p_owner text, p_expected_code text
) returns jsonb language plpgsql security invoker
set search_path = '' as $$
declare current_code text;
begin
  if p_owner is null or p_owner !~ '^[a-f0-9]{64}$' or
     p_expected_code is null or
     p_expected_code !~ '^LANU(-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}){5}$' then
    raise exception 'Invalid account deletion request';
  end if;

  -- Fail closed on FK schema drift BEFORE deleting any related row. A new
  -- ON DELETE CASCADE edge can silently erase peer-owned records; unlike a
  -- default NO ACTION FK, it would not raise at the final identity DELETE.
  -- Check exact FK child/parent columns and arity, not just per-table counts:
  -- swapping an FK for another on the same table/mode must also fail closed.
  -- This is the reviewed full-schema inventory, not a dynamic allow-all.
  if exists (
    with expected(parent_rel, child_rel, child_col, parent_col, mode, child_cols, parent_cols, edges) as (
      values
        ('public.lanu_identities'::regclass, 'public.lanu_contacts'::regclass, 'owner_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_contacts'::regclass, 'peer_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_messages'::regclass, 'sender_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_messages'::regclass, 'recipient_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_events'::regclass, 'owner_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_nonces'::regclass, 'owner_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_media_objects'::regclass, 'sender_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_media_objects'::regclass, 'recipient_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_call_signals'::regclass, 'sender_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_call_signals'::regclass, 'recipient_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_call_wake_devices'::regclass, 'owner_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_message_wake_devices'::regclass, 'owner_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_message_wake_claims'::regclass, 'sender_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_identities'::regclass, 'public.lanu_message_wake_claims'::regclass, 'recipient_id', 'id', 'c'::"char", 1, 1, 1),
        ('public.lanu_messages'::regclass, 'public.lanu_events'::regclass, 'message_id', 'id', 'a'::"char", 1, 1, 1),
        ('public.lanu_messages'::regclass, 'public.lanu_message_wake_claims'::regclass, 'message_id', 'id', 'c'::"char", 1, 1, 1)
    ),
    actual as (
      select c.confrelid as parent_rel, c.conrelid as child_rel,
             child_att.attname::text as child_col,
             parent_att.attname::text as parent_col,
             c.confdeltype as mode,
             pg_catalog.array_length(c.conkey, 1) as child_cols,
             pg_catalog.array_length(c.confkey, 1) as parent_cols,
             count(*)::integer as edges
      from pg_catalog.pg_constraint c
      join pg_catalog.pg_attribute child_att
        on child_att.attrelid = c.conrelid and child_att.attnum = c.conkey[1]
      join pg_catalog.pg_attribute parent_att
        on parent_att.attrelid = c.confrelid and parent_att.attnum = c.confkey[1]
      where c.contype = 'f'
        and c.confrelid in ('public.lanu_identities'::regclass,
                            'public.lanu_messages'::regclass)
      group by c.confrelid, c.conrelid, child_att.attname, parent_att.attname,
               c.confdeltype, c.conkey, c.confkey
    )
    select 1 from expected e
    full join actual a using
      (parent_rel, child_rel, child_col, parent_col, mode, child_cols, parent_cols)
    where coalesce(e.edges, 0) <> coalesce(a.edges, 0)
  ) then
    raise exception 'Account schema review required before deletion';
  end if;

  -- An exclusive lock on the identity row blocks new FK references until
  -- the operation commits. Existing signed requests have a short lifetime.
  perform pg_advisory_xact_lock(hashtextextended(p_owner, 0));
  select code into current_code from public.lanu_identities
    where id = p_owner for update;
  if not found or current_code <> p_expected_code then
    return jsonb_build_object('deleted', false);
  end if;

  -- Binary Storage objects are not transactional with Postgres. Refuse even
  -- partial metadata deletion while any associated object is unverified as
  -- purged. A separate signed Storage cleanup + evidence gate must precede.
  if exists (
    select 1 from public.lanu_media_objects
    where (sender_id = p_owner or recipient_id = p_owner)
      -- SQL NULL is UNKNOWN: fail closed even if a future migration
      -- relaxes the current NOT NULL constraint or imports legacy rows.
      and state is distinct from 'purged'
  ) then
    raise exception 'Account media cleanup not verified';
  end if;

  -- Delete all message events, including snapshots in the OTHER participant's
  -- inbox that reference ciphertext of a departing account. Delete claims
  -- before their parent messages, independent of current cascade configuration.
  delete from public.lanu_events
    where owner_id = p_owner or message_id in (
      select id from public.lanu_messages
      where sender_id = p_owner or recipient_id = p_owner);
  delete from public.lanu_message_wake_claims
    where sender_id = p_owner or recipient_id = p_owner
       or message_id in (
         select id from public.lanu_messages
         where sender_id = p_owner or recipient_id = p_owner);
  delete from public.lanu_media_objects
    where sender_id = p_owner or recipient_id = p_owner;
  delete from public.lanu_messages
    where sender_id = p_owner or recipient_id = p_owner;

  -- Never touch another owner's standalone FCM registration, unrelated
  -- conversation, contacts or identity.
  delete from public.lanu_call_signals
    where sender_id = p_owner or recipient_id = p_owner;
  delete from public.lanu_call_wake_devices where owner_id = p_owner;
  delete from public.lanu_message_wake_devices where owner_id = p_owner;
  delete from public.lanu_contacts
    where owner_id = p_owner or peer_id = p_owner;
  delete from public.lanu_nonces where owner_id = p_owner;

  -- If a new child FK/table appears and is not covered above, the DELETE
  -- fails; PostgreSQL automatically rolls back the WHOLE statement/transaction.
  delete from public.lanu_identities
    where id = p_owner and code = p_expected_code;
  if not found then
    raise exception 'Account identity changed before deletion';
  end if;
  return jsonb_build_object('deleted', true);
end $$;

revoke all on function public.lanu_delete_account_v1(text,text)
    from public, anon, authenticated;
grant execute on function public.lanu_delete_account_v1(text,text) to service_role;
