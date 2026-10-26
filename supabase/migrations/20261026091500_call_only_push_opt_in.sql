-- Staged additive change: deploy only with FCM-configured LANU Edge relay.
-- No existing messages, E2EE identities, call contents or user settings are changed.
create table if not exists public.lanu_call_wake_devices (
  owner_id text primary key references public.lanu_identities(id) on delete cascade,
  fcm_token text not null check (length(fcm_token) between 20 and 4096),
  updated_at timestamptz not null default now()
);
alter table public.lanu_call_wake_devices enable row level security;
revoke all on public.lanu_call_wake_devices from public, anon, authenticated;
grant select, insert, update, delete on public.lanu_call_wake_devices to service_role;
alter table public.lanu_call_signals add column if not exists wake_requested boolean not null default false;
create index if not exists lanu_call_wake_sender_budget on public.lanu_call_signals(sender_id,recipient_id,created_at)
  where wake_requested;

create or replace function public.lanu_call_wake_register(p_owner text,p_token text)
returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
begin
  if not exists(select 1 from public.lanu_identities where id=p_owner) then
    return jsonb_build_object('registered',false);
  end if;
  if p_token is null then
    delete from public.lanu_call_wake_devices where owner_id=p_owner;
    return jsonb_build_object('registered',true);
  end if;
  if length(p_token) not between 20 and 4096 or p_token !~ '^[A-Za-z0-9_:.-]+$' then
    return jsonb_build_object('registered',false);
  end if;
  insert into public.lanu_call_wake_devices(owner_id,fcm_token,updated_at)
    values(p_owner,p_token,now())
    on conflict(owner_id) do update set fcm_token=excluded.fcm_token,updated_at=now();
  return jsonb_build_object('registered',true);
end $$;

-- Used only by signed/authenticated Edge relay after mutual-contact check.
-- Plaintext contact, caller name, SDP and chat never enter the wake channel.
-- The 'wake_token' field is privileged Edge-only and NEVER reaches Android.
create or replace function public.lanu_call_send_wake(
  p_owner text,p_peer text,p_id uuid,p_call uuid,p_content text
) returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
declare old_row public.lanu_call_signals%rowtype;
declare token text;
declare online boolean;
begin
  perform pg_advisory_xact_lock(hashtext('lanu-call:'||p_owner));
  if p_owner=p_peer or not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner)
    then return jsonb_build_object('unavailable',true); end if;

  select * into old_row from public.lanu_call_signals where id=p_id;
  if found then
    return jsonb_build_object('accepted',old_row.sender_id=p_owner and old_row.recipient_id=p_peer
      and old_row.call_id=p_call and old_row.content=p_content,'created',false,
      'conflict',not(old_row.sender_id=p_owner and old_row.recipient_id=p_peer and old_row.call_id=p_call and old_row.content=p_content));
  end if;
  select exists(select 1 from public.lanu_identities where id=p_peer and call_version=1
    and call_seen_at>now()-interval '20 seconds') into online;
  select fcm_token into token from public.lanu_call_wake_devices
    where owner_id=p_peer;
  if not online and token is null then return jsonb_build_object('unavailable',true); end if;

  if token is not null then
    if (select count(*) from public.lanu_call_signals
      where sender_id=p_owner and recipient_id=p_peer and wake_requested
        and created_at>now()-interval '90 seconds') >= 3
      or exists(select 1 from public.lanu_call_signals
      where sender_id=p_owner and recipient_id=p_peer and wake_requested
        and created_at>now()-interval '10 seconds')
    then return jsonb_build_object('limited',true); end if;
  end if;
  if (select count(*) from public.lanu_call_signals
      where sender_id=p_owner and expires_at>now())>=24
    or (select count(*) from public.lanu_call_signals
      where recipient_id=p_peer and expires_at>now())>=64
    then return jsonb_build_object('limited',true);
  end if;
  insert into public.lanu_call_signals(id,call_id,sender_id,recipient_id,content,wake_requested)
    values(p_id,p_call,p_owner,p_peer,p_content,token is not null)
    on conflict(id) do nothing;
  select * into old_row from public.lanu_call_signals where id=p_id;
  if not(old_row.sender_id=p_owner and old_row.recipient_id=p_peer
    and old_row.call_id=p_call and old_row.content=p_content) then
    return jsonb_build_object('accepted',false,'conflict',true);
  end if;
  return jsonb_build_object('accepted',true,'created',true,
    'wake_token',case when old_row.wake_requested then token else null end);
end $$;

revoke all on function public.lanu_call_wake_register(text,text) from public,anon,authenticated;
revoke all on function public.lanu_call_send_wake(text,text,uuid,uuid,text) from public,anon,authenticated;
grant execute on function public.lanu_call_wake_register(text,text) to service_role;
grant execute on function public.lanu_call_send_wake(text,text,uuid,uuid,text) to service_role;
