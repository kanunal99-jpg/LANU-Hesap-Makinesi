-- Additive, private, short-lived signaling. No changes to existing message/Room data.
alter table public.lanu_identities add column if not exists call_version smallint not null default 0;
alter table public.lanu_identities add column if not exists call_seen_at timestamptz;

create table public.lanu_call_signals (
  id uuid primary key,
  call_id uuid not null,
  sender_id text not null references public.lanu_identities(id) on delete cascade,
  recipient_id text not null references public.lanu_identities(id) on delete cascade,
  content text not null check (length(content) between 44 and 32768 and content ~ '^lanu2:[A-Za-z0-9_-]+$'),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '90 seconds'),
  acked boolean not null default false,
  check (sender_id <> recipient_id)
);
create index lanu_call_inbox on public.lanu_call_signals(recipient_id, created_at, id) where not acked;
create index lanu_call_expiry on public.lanu_call_signals(expires_at);
create index lanu_call_sender on public.lanu_call_signals(sender_id, expires_at);
create index lanu_call_recipient on public.lanu_call_signals(recipient_id, expires_at);
alter table public.lanu_call_signals enable row level security;
revoke all on public.lanu_call_signals from public, anon, authenticated;
grant select, insert, update, delete on public.lanu_call_signals to service_role;

create function public.lanu_call_poll(p_owner text) returns jsonb
language plpgsql security invoker set search_path = public, pg_temp as $$
declare result jsonb;
begin
  -- Indexed and bounded global expiry cleanup; no live signal is deleted.
  delete from public.lanu_call_signals where id in
    (select id from public.lanu_call_signals where expires_at <= now() order by expires_at limit 256);
  update public.lanu_identities set call_version=1, call_seen_at=now() where id=p_owner;
  select coalesce(jsonb_agg(s), '[]'::jsonb) into result from (
    select id, call_id, sender_id, recipient_id, content, created_at, expires_at
    from public.lanu_call_signals x
    where recipient_id=p_owner and not acked and expires_at>now()
      and exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=x.sender_id)
      and exists(select 1 from public.lanu_contacts where owner_id=x.sender_id and peer_id=p_owner)
    order by created_at,id limit 24
  ) s;
  return result;
end $$;

create function public.lanu_call_send(p_owner text, p_peer text, p_id uuid, p_call uuid, p_content text)
returns jsonb language plpgsql security invoker set search_path = public, pg_temp as $$
declare old public.lanu_call_signals%rowtype;
begin
  perform pg_advisory_xact_lock(hashtext('lanu-call:' || p_owner));
  if p_owner=p_peer or not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    return jsonb_build_object('unavailable',true);
  end if;
  select * into old from public.lanu_call_signals where id=p_id;
  if found then
    return jsonb_build_object('accepted',old.sender_id=p_owner and old.recipient_id=p_peer
      and old.call_id=p_call and old.content=p_content, 'conflict', not(old.sender_id=p_owner
      and old.recipient_id=p_peer and old.call_id=p_call and old.content=p_content));
  end if;
  if not exists(select 1 from public.lanu_identities where id=p_peer and call_version=1
    and call_seen_at>now()-interval '20 seconds') then return jsonb_build_object('unavailable',true); end if;
  if (select count(*) from public.lanu_call_signals where sender_id=p_owner and expires_at>now())>=24
    or (select count(*) from public.lanu_call_signals where recipient_id=p_peer and expires_at>now())>=64
    then return jsonb_build_object('limited',true); end if;
  insert into public.lanu_call_signals(id,call_id,sender_id,recipient_id,content)
    values(p_id,p_call,p_owner,p_peer,p_content) on conflict(id) do nothing;
  select * into old from public.lanu_call_signals where id=p_id;
  return jsonb_build_object('accepted',old.sender_id=p_owner and old.recipient_id=p_peer
    and old.call_id=p_call and old.content=p_content);
end $$;

create function public.lanu_call_ack(p_owner text,p_ids uuid[]) returns void
language sql security invoker set search_path=public,pg_temp as $$
  update public.lanu_call_signals set acked=true where recipient_id=p_owner and id=any(p_ids);
$$;
revoke all on function public.lanu_call_poll(text) from public, anon, authenticated;
revoke all on function public.lanu_call_send(text,text,uuid,uuid,text) from public, anon, authenticated;
revoke all on function public.lanu_call_ack(text,uuid[]) from public, anon, authenticated;
grant execute on function public.lanu_call_poll(text) to service_role;
grant execute on function public.lanu_call_send(text,text,uuid,uuid,text) to service_role;
grant execute on function public.lanu_call_ack(text,uuid[]) to service_role;
