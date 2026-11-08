-- Additive protocol admission. No existing identity, message or event is deleted.
alter table public.lanu_messages add column protocol_version integer not null default 1
  check (protocol_version in (1,3));
create table public.lanu_ratchet_bundles (
  owner_id text not null references public.lanu_identities(id),
  peer_id text not null references public.lanu_identities(id),
  bundle jsonb not null check (octet_length(bundle::text)<=2048),
  created_at timestamptz not null default now(),
  primary key(owner_id,peer_id), check(owner_id<>peer_id)
);
create index lanu_ratchet_peer_idx on public.lanu_ratchet_bundles(peer_id,owner_id);
alter table public.lanu_ratchet_bundles enable row level security;
revoke all on public.lanu_ratchet_bundles from public,anon,authenticated;
grant select,insert on public.lanu_ratchet_bundles to service_role;

create function public.lanu_ratchet_publish(p_owner text,p_peer text,p_bundle jsonb) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare old_bundle jsonb; remote_bundle jsonb;
begin
  if p_owner !~ '^[a-f0-9]{64}$' or p_peer !~ '^[a-f0-9]{64}$' or p_owner=p_peer
    or jsonb_typeof(p_bundle)<>'object' or octet_length(p_bundle::text)>2048
    or p_bundle->>'owner' is distinct from p_owner or p_bundle->>'peer' is distinct from p_peer
    or (select count(*) from jsonb_object_keys(p_bundle))<>6
    or not(p_bundle ?& array['owner','peer','generation','identity','oneTime','signature']) then
    raise exception using errcode='22023',message='Invalid signed session bundle';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(p_owner,p_peer)||'|'||greatest(p_owner,p_peer),0));
  if not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    return jsonb_build_object('accepted',false,'reason','mutual_required');
  end if;
  select bundle into old_bundle from public.lanu_ratchet_bundles where owner_id=p_owner and peer_id=p_peer;
  if old_bundle is not null and old_bundle<>p_bundle then
    return jsonb_build_object('accepted',false,'reason','explicit_reset_required');
  end if;
  -- Both old inbox snapshots AND undelivered ciphertext must drain first.
  -- The insert trigger takes this SAME pair lock, closing a v1-send race.
  if old_bundle is null and (
    exists(select 1 from public.lanu_messages m where m.protocol_version=1 and not m.deleted and m.delivered_at is null
      and ((m.sender_id=p_owner and m.recipient_id=p_peer) or (m.sender_id=p_peer and m.recipient_id=p_owner)))
    or exists(select 1 from public.lanu_events e join public.lanu_messages m on m.id=e.message_id
      where not e.acked and m.protocol_version=1 and
      ((m.sender_id=p_owner and m.recipient_id=p_peer) or (m.sender_id=p_peer and m.recipient_id=p_owner)))
  ) then return jsonb_build_object('accepted',false,'reason','drain_legacy_queue'); end if;
  insert into public.lanu_ratchet_bundles(owner_id,peer_id,bundle) values(p_owner,p_peer,p_bundle)
    on conflict(owner_id,peer_id) do nothing;
  select bundle into remote_bundle from public.lanu_ratchet_bundles where owner_id=p_peer and peer_id=p_owner;
  return jsonb_build_object('accepted',true,'ready',remote_bundle is not null,'peerBundle',remote_bundle);
end $$;
revoke all on function public.lanu_ratchet_publish(text,text,jsonb) from public,anon,authenticated;
grant execute on function public.lanu_ratchet_publish(text,text,jsonb) to service_role;

create function public.lanu_ratchet_admit_message() returns trigger
language plpgsql security invoker set search_path='' as $$
declare ready boolean;
begin
  if tg_op='UPDATE' then
    if new.protocol_version<>old.protocol_version then
      raise exception using errcode='23514',message='Message protocol is immutable';
    end if;
    return new;
  end if;
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(new.sender_id,new.recipient_id)||'|'||greatest(new.sender_id,new.recipient_id),0));
  select exists(select 1 from public.lanu_ratchet_bundles where owner_id=new.sender_id and peer_id=new.recipient_id)
    and exists(select 1 from public.lanu_ratchet_bundles where owner_id=new.recipient_id and peer_id=new.sender_id) into ready;
  if ready<>(new.protocol_version=3) then
    raise exception using errcode='23514',message='Conversation protocol barrier mismatch';
  end if;
  return new;
end $$;
revoke all on function public.lanu_ratchet_admit_message() from public,anon,authenticated;
grant execute on function public.lanu_ratchet_admit_message() to service_role;
create trigger lanu_ratchet_admission before insert or update of protocol_version on public.lanu_messages
for each row execute function public.lanu_ratchet_admit_message();

-- Preserve the deployed old RPC shape, filtering ratchet BEFORE LIMIT.
create or replace function public.lanu_media_aware_events(p_owner text,p_media_version integer)
returns setof public.lanu_events
language sql security invoker set search_path='' as $$
  select e.* from public.lanu_events e join public.lanu_messages m on m.id=e.message_id
  where e.owner_id=p_owner and not e.acked and m.protocol_version=1
    and (p_media_version=1 or not m.private_media)
  order by e.created_at,e.id limit 200;
$$;
create function public.lanu_ratchet_aware_events(p_owner text,p_media_version integer)
returns table(id uuid,snapshot jsonb)
language sql security invoker set search_path='' as $$
  select e.id,e.snapshot||jsonb_build_object('protocolVersion',m.protocol_version)
  from public.lanu_events e join public.lanu_messages m on m.id=e.message_id
  where e.owner_id=p_owner and not e.acked and (p_media_version=1 or not m.private_media)
  order by e.created_at,e.id limit 200;
$$;
revoke all on function public.lanu_ratchet_aware_events(text,integer) from public,anon,authenticated;
grant execute on function public.lanu_ratchet_aware_events(text,integer) to service_role;
