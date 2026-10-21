-- No phone numbers, SMS codes, or plaintext message content are stored.
create table public.lanu_identities (
  id text primary key check (id ~ '^[a-f0-9]{64}$'),
  code text not null unique check (code ~ '^LANU(-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}){5}$'),
  nonce text not null check (length(nonce) = 22),
  public_key text not null check (length(public_key) <= 256),
  created_at timestamptz not null default now()
);
create table public.lanu_contacts (
  owner_id text not null references public.lanu_identities(id),
  peer_id text not null references public.lanu_identities(id),
  code_at_add text not null,
  created_at timestamptz not null default now(),
  primary key (owner_id, peer_id),
  check (owner_id <> peer_id)
);
create index lanu_contacts_peer_idx on public.lanu_contacts(peer_id);
create table public.lanu_messages (
  id uuid primary key,
  sender_id text not null references public.lanu_identities(id),
  recipient_id text not null references public.lanu_identities(id),
  content text not null check (content like 'lanu2:%' and length(content) <= 50000),
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  read_at timestamptz,
  reaction text check (length(reaction) <= 16),
  deleted boolean not null default false,
  revision bigint not null default 1,
  check (sender_id <> recipient_id)
);
create index lanu_messages_sender_idx on public.lanu_messages(sender_id);
create index lanu_messages_recipient_idx on public.lanu_messages(recipient_id);
create table public.lanu_events (
  id uuid primary key default gen_random_uuid(),
  owner_id text not null references public.lanu_identities(id),
  message_id uuid not null references public.lanu_messages(id),
  snapshot jsonb not null,
  acked boolean not null default false,
  created_at timestamptz not null default clock_timestamp()
);
create index lanu_events_pending_idx on public.lanu_events(owner_id, created_at, id) where not acked;
create index lanu_events_message_idx on public.lanu_events(message_id);
create table public.lanu_nonces (
  owner_id text not null references public.lanu_identities(id),
  request_id uuid not null,
  created_at timestamptz not null default now(),
  primary key(owner_id, request_id)
);
create index lanu_nonces_time_idx on public.lanu_nonces(owner_id, created_at);

alter table public.lanu_identities enable row level security;
alter table public.lanu_contacts enable row level security;
alter table public.lanu_messages enable row level security;
alter table public.lanu_events enable row level security;
alter table public.lanu_nonces enable row level security;
-- Direct client access is denied; the edge verifies signatures and row ownership.
revoke all on public.lanu_identities, public.lanu_contacts, public.lanu_messages, public.lanu_events, public.lanu_nonces from public, anon, authenticated;
grant select, insert, update, delete on public.lanu_identities, public.lanu_contacts, public.lanu_messages, public.lanu_events, public.lanu_nonces to service_role;

create function public.lanu_message_revision() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
  new.revision := old.revision + 1;
  new.created_at := old.created_at;
  new.sender_id := old.sender_id;
  new.recipient_id := old.recipient_id;
  return new;
end $$;
create trigger lanu_revision before update on public.lanu_messages
for each row execute function public.lanu_message_revision();

create function public.lanu_message_event() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare value jsonb;
begin
  value := jsonb_build_object(
    'id', new.id, 'senderId', new.sender_id, 'recipientId', new.recipient_id,
    'content', new.content, 'createdAt', floor(extract(epoch from new.created_at) * 1000)::bigint,
    'status', case when new.read_at is not null then 'READ' when new.delivered_at is not null then 'DELIVERED' else 'SENT' end,
    'reaction', coalesce(new.reaction, ''), 'deleted', new.deleted, 'revision', new.revision);
  insert into public.lanu_events(owner_id, message_id, snapshot) values
    (new.sender_id, new.id, value), (new.recipient_id, new.id, value);
  return new;
end $$;
create trigger lanu_event after insert or update on public.lanu_messages
for each row execute function public.lanu_message_event();

-- Acknowledging events and recording real device delivery are one transaction.
create function public.lanu_ack(p_owner text, p_event_ids uuid[], p_read_ids uuid[]) returns void
language plpgsql security invoker set search_path = '' as $$
begin
  if coalesce(array_length(p_event_ids, 1), 0) > 200 or coalesce(array_length(p_read_ids, 1), 0) > 200 then
    raise exception 'Acknowledgement batch is too large';
  end if;
  update public.lanu_messages m
    set delivered_at = coalesce(m.delivered_at, now()),
        read_at = case when m.id = any(p_read_ids) then coalesce(m.read_at, now()) else m.read_at end
    where m.recipient_id = p_owner and not m.deleted
      and (m.id = any(p_read_ids) or m.id in (
        select e.message_id from public.lanu_events e where e.owner_id = p_owner and e.id = any(p_event_ids)))
      and (m.delivered_at is null or (m.id = any(p_read_ids) and m.read_at is null));
  update public.lanu_events set acked = true where owner_id = p_owner and id = any(p_event_ids);
  -- Expired nonces cannot be replayed because the signed request expires after five minutes.
  delete from public.lanu_nonces where owner_id = p_owner and created_at < now() - interval '1 day';
end $$;
revoke all on function public.lanu_ack(text, uuid[], uuid[]) from public, anon, authenticated;
grant execute on function public.lanu_ack(text, uuid[], uuid[]) to service_role;
revoke all on function public.lanu_message_revision() from public, anon, authenticated;
revoke all on function public.lanu_message_event() from public, anon, authenticated;
grant execute on function public.lanu_message_revision(), public.lanu_message_event() to service_role;


-- Serialize each account's rate-limit check and durable nonce insert.
create function public.lanu_consume_nonce(p_owner text, p_request_id uuid) returns jsonb
language plpgsql security invoker set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtextextended(p_owner, 0));
  delete from public.lanu_nonces where owner_id = p_owner and created_at < now() - interval '1 day';
  if exists (select 1 from public.lanu_nonces where owner_id = p_owner and request_id = p_request_id) then
    return jsonb_build_object('allowed', false, 'limited', false);
  end if;
  if (select count(*) from public.lanu_nonces where owner_id = p_owner and created_at >= now() - interval '1 minute') >= 120 then
    return jsonb_build_object('allowed', false, 'limited', true);
  end if;
  insert into public.lanu_nonces(owner_id, request_id) values (p_owner, p_request_id);
  return jsonb_build_object('allowed', true, 'limited', false);
end $$;
revoke all on function public.lanu_consume_nonce(text, uuid) from public, anon, authenticated;
grant execute on function public.lanu_consume_nonce(text, uuid) to service_role;
