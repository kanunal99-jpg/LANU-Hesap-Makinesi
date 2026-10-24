-- Additive private media metadata. Keys, names, MIME and plaintext never enter this table.
alter table public.lanu_identities add column private_media_version integer not null default 0
  check (private_media_version in (0,1));
alter table public.lanu_messages add column private_media boolean not null default false;

create table public.lanu_media_objects (
  file_id text primary key check (file_id ~ '^[a-f0-9]{32}$'),
  message_id uuid not null unique,
  sender_id text not null references public.lanu_identities(id),
  recipient_id text not null references public.lanu_identities(id),
  cipher_bytes bigint not null check (cipher_bytes between 17 and 41953280),
  cipher_sha256 text not null check (cipher_sha256 ~ '^[a-f0-9]{64}$'),
  state text not null default 'pending' check (state in ('pending','ready','cancelled','purged')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '7 days',
  check (sender_id <> recipient_id)
);
alter table public.lanu_media_objects enable row level security;
revoke all on public.lanu_media_objects from public, anon, authenticated;
grant select,insert,update,delete on public.lanu_media_objects to service_role;
create index lanu_media_expiry on public.lanu_media_objects(expires_at);
create index lanu_media_sender_created on public.lanu_media_objects(sender_id,created_at);

create function public.lanu_reserve_private_media(p_owner text, p_peer text, p_file text, p_message uuid, p_bytes bigint, p_sha text)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare existing public.lanu_media_objects; total_bytes bigint; owner_bytes bigint; owner_daily integer;
begin
  if p_file !~ '^[a-f0-9]{32}$' or p_sha !~ '^[a-f0-9]{64}$' or p_bytes not between 17 and 41953280 or p_owner = p_peer then
    raise exception 'invalid private media reservation';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('lanu-private-media-quota-v1',0));
  select * into existing from public.lanu_media_objects where file_id=p_file;
  if found then
    if existing.sender_id<>p_owner or existing.recipient_id<>p_peer or existing.message_id<>p_message
       or existing.cipher_bytes<>p_bytes or existing.cipher_sha256<>p_sha or existing.state='cancelled' or existing.expires_at<=now() then
      raise exception 'private media reservation conflict';
    end if;
    return to_jsonb(existing);
  end if;
  if not exists (select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
     or not exists (select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    raise exception 'mutual contact required';
  end if;
  if not exists (select 1 from public.lanu_identities where id=p_peer and private_media_version=1) then
    raise exception 'recipient media upgrade required';
  end if;
  -- Expired objects still consume quota until actual Storage API cleanup succeeds.
  select coalesce(sum(cipher_bytes) filter (where state<>'purged'),0) into total_bytes from public.lanu_media_objects;
  select coalesce(sum(cipher_bytes) filter (where state<>'purged'),0), count(*) filter (where created_at>=now()-interval '1 day')
    into owner_bytes,owner_daily from public.lanu_media_objects where sender_id=p_owner;
  if total_bytes+p_bytes>536870912 or owner_bytes+p_bytes>268435456 or owner_daily>=4 then
    return jsonb_build_object('quotaExceeded',true);
  end if;
  insert into public.lanu_media_objects(file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256)
    values(p_file,p_message,p_owner,p_peer,p_bytes,p_sha) returning * into existing;
  return to_jsonb(existing);
end $$;
revoke all on function public.lanu_reserve_private_media(text,text,text,uuid,bigint,text) from public,anon,authenticated;
grant execute on function public.lanu_reserve_private_media(text,text,text,uuid,bigint,text) to service_role;

create function public.lanu_mark_private_media_message() returns trigger
language plpgsql security invoker set search_path = '' as $$
declare media public.lanu_media_objects;
begin
  select * into media from public.lanu_media_objects where message_id=new.id;
  new.private_media := false;
  if found then
    if media.sender_id<>new.sender_id or media.recipient_id<>new.recipient_id or media.state<>'ready' or media.expires_at<=now() then
      raise exception 'private media message not authorized';
    end if;
    new.private_media := true;
  end if;
  return new;
end $$;
revoke all on function public.lanu_mark_private_media_message() from public,anon,authenticated;
grant execute on function public.lanu_mark_private_media_message() to service_role;
create trigger lanu_private_media_message before insert on public.lanu_messages
for each row execute function public.lanu_mark_private_media_message();

-- Older clients cannot render file-key envelopes. Keep those events queued until upgrade.
create function public.lanu_media_aware_events(p_owner text,p_media_version integer)
returns setof public.lanu_events language sql security invoker set search_path = '' as $$
  select e.* from public.lanu_events e
  join public.lanu_messages m on m.id=e.message_id
  where e.owner_id=p_owner and not e.acked and (p_media_version=1 or not m.private_media)
  order by e.created_at,e.id limit 200;
$$;
revoke all on function public.lanu_media_aware_events(text,integer) from public,anon,authenticated;
grant execute on function public.lanu_media_aware_events(text,integer) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values ('lanu-private-media-v1','lanu-private-media-v1',false,44040192,array['application/octet-stream'])
on conflict(id) do nothing;
-- No anon/authenticated Storage policy is created. Signed grants are issued only by the relay.
