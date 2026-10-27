-- 2026-10-27: separate, explicitly consented chat wake. No messages or identities changed.
-- All operations run only via signed LANU Edge requests using service_role.
create table if not exists public.lanu_message_wake_devices (
  owner_id text primary key references public.lanu_identities(id) on delete cascade,
  fcm_token text not null check (length(fcm_token) between 20 and 4096),
  updated_at timestamptz not null default now()
);
alter table public.lanu_message_wake_devices enable row level security;
revoke all on public.lanu_message_wake_devices from public, anon, authenticated;
grant select, insert, update, delete on public.lanu_message_wake_devices to service_role;

-- Persist once-per-message claims without mutating message revisions or creating read events.
create table if not exists public.lanu_message_wake_claims (
  message_id uuid primary key references public.lanu_messages(id) on delete cascade,
  sender_id text not null references public.lanu_identities(id) on delete cascade,
  recipient_id text not null references public.lanu_identities(id) on delete cascade,
  created_at timestamptz not null default now()
);
create index if not exists lanu_message_wake_claims_rate
  on public.lanu_message_wake_claims(recipient_id, created_at);
alter table public.lanu_message_wake_claims enable row level security;
revoke all on public.lanu_message_wake_claims from public, anon, authenticated;
grant select, insert, update, delete on public.lanu_message_wake_claims to service_role;

create or replace function public.lanu_message_wake_register(p_owner text,p_token text)
returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
begin
  if not exists (select 1 from public.lanu_identities where id=p_owner) then
    return jsonb_build_object('registered',false);
  end if;
  if p_token is null then
    delete from public.lanu_message_wake_devices where owner_id=p_owner;
    return jsonb_build_object('registered',true);
  end if;
  if length(p_token) not between 20 and 4096 or p_token !~ '^[A-Za-z0-9_:.-]+$' then
    return jsonb_build_object('registered',false);
  end if;
  insert into public.lanu_message_wake_devices(owner_id,fcm_token,updated_at)
    values(p_owner,p_token,now())
    on conflict(owner_id) do update set fcm_token=excluded.fcm_token, updated_at=now();
  return jsonb_build_object('registered',true);
end $$;

-- Called only after the encrypted message was accepted by the relay.
-- Idempotent across retries, bounded per recipient, and no plaintext or token in client response.
create or replace function public.lanu_message_wake_claim(p_sender text,p_recipient text,p_message uuid)
returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
declare token text;
declare affected integer;
declare recent integer;
begin
  perform pg_advisory_xact_lock(hashtext('lanu-msg-wake:'||p_recipient));
  if not exists (select 1 from public.lanu_messages
       where id=p_message and sender_id=p_sender and recipient_id=p_recipient
         and deleted=false and created_at > now()-interval '2 minutes')
     or not exists (select 1 from public.lanu_contacts
       where owner_id=p_sender and peer_id=p_recipient)
     or not exists (select 1 from public.lanu_contacts
       where owner_id=p_recipient and peer_id=p_sender) then
    return jsonb_build_object('claimed',false);
  end if;
  select fcm_token into token from public.lanu_message_wake_devices where owner_id=p_recipient;
  if token is null then return jsonb_build_object('claimed',false); end if;
  select count(*) into recent from public.lanu_message_wake_claims
    where recipient_id=p_recipient and created_at>now()-interval '1 minute';
  insert into public.lanu_message_wake_claims(message_id,sender_id,recipient_id)
    values(p_message,p_sender,p_recipient) on conflict(message_id) do nothing;
  get diagnostics affected = row_count;
  if affected <> 1 or recent >= 6 then
    return jsonb_build_object('claimed',false);
  end if;
  return jsonb_build_object('claimed',true,'wake_token',token);
end $$;

create or replace function public.lanu_message_wake_invalidate(p_owner text,p_token text)
returns jsonb language plpgsql security invoker set search_path=public,pg_temp as $$
declare affected integer;
begin
  if p_token is null or length(p_token) not between 20 and 4096
      or p_token !~ '^[A-Za-z0-9_:.-]+$' then
    return jsonb_build_object('invalidated',false);
  end if;
  delete from public.lanu_message_wake_devices
    where owner_id=p_owner and fcm_token=p_token;
  get diagnostics affected = row_count;
  return jsonb_build_object('invalidated',affected=1);
end $$;

revoke all on function public.lanu_message_wake_register(text,text),
  public.lanu_message_wake_claim(text,text,uuid),
  public.lanu_message_wake_invalidate(text,text) from public,anon,authenticated;
grant execute on function public.lanu_message_wake_register(text,text),
  public.lanu_message_wake_claim(text,text,uuid),
  public.lanu_message_wake_invalidate(text,text) to service_role;
