-- LANU private encrypted attachment quotas / isolated from existing chat tables.
-- No plaintext media, public bucket, anonymous storage policy or destructive migrations.
create table if not exists public.lanu_media (
    message_id uuid primary key,
    sender_id text not null references public.lanu_identities(id),
    recipient_id text not null references public.lanu_identities(id),
    cipher_bytes integer not null check (cipher_bytes between 29 and 2097152),
    cipher_sha256 text not null check (cipher_sha256 ~ '^[a-f0-9]{64}$'),
    reserved_at timestamptz not null default now(),
    constraint media_two_distinct_users check (sender_id <> recipient_id)
);
create index if not exists lanu_media_daily_quota on public.lanu_media(sender_id, reserved_at);

alter table public.lanu_media enable row level security;
revoke all on public.lanu_media from public, anon, authenticated;
grant select, insert on public.lanu_media to service_role;

-- Private bucket only accepts encrypted octet-stream and enforces the 2 MiB
-- upper bound at the Storage server, not merely in Android JavaScript.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('lanu-private-media', 'lanu-private-media', false, 2097152, array['application/octet-stream'])
on conflict (id) do update
set public = false, file_size_limit = 2097152,
    allowed_mime_types = array['application/octet-stream'];

-- A single serialized reservation prevents concurrency bypass of both
-- per-owner daily and account-wide storage guards. Duplicate same-ID retry
-- succeeds only if original ownership/bytes/digest are identical.
create or replace function public.lanu_media_reserve(
    p_sender text,
    p_recipient text,
    p_message_id uuid,
    p_cipher_bytes integer,
    p_cipher_sha256 text
) returns boolean
language plpgsql security invoker set search_path = '' as $$
declare
    existing public.lanu_media%rowtype;
    per_day_count integer;
    per_day_bytes bigint;
    total_bytes bigint;
begin
    if p_sender !~ '^[a-f0-9]{64}$' or p_recipient !~ '^[a-f0-9]{64}$'
       or p_sender = p_recipient or p_cipher_bytes not between 29 and 2097152
       or p_cipher_sha256 !~ '^[a-f0-9]{64}$' then
        return false;
    end if;

    -- Serialize across senders to also enforce the total account quota.
    perform pg_advisory_xact_lock(hashtextextended('lanu-private-media-v1', 0));

    select * into existing from public.lanu_media where message_id = p_message_id;
    if found then
        return existing.sender_id = p_sender and
               existing.recipient_id = p_recipient and
               existing.cipher_bytes = p_cipher_bytes and
               existing.cipher_sha256 = p_cipher_sha256;
    end if;

    select count(*), coalesce(sum(cipher_bytes), 0) into per_day_count, per_day_bytes
    from public.lanu_media
    where sender_id = p_sender and reserved_at >= now() - interval '24 hours';

    select coalesce(sum(cipher_bytes), 0) into total_bytes from public.lanu_media;

    -- Never turn on paid overages; reserve max 128 MiB of encrypted media in
    -- this initial pilot, 18 MiB and 12 objects per sender per rolling day.
    if per_day_count >= 12 or per_day_bytes + p_cipher_bytes > 18874368
       or total_bytes + p_cipher_bytes > 134217728 then
        return false;
    end if;

    insert into public.lanu_media
      (message_id, sender_id, recipient_id, cipher_bytes, cipher_sha256)
    values (p_message_id, p_sender, p_recipient, p_cipher_bytes, p_cipher_sha256);
    return true;
end $$;

revoke all on function public.lanu_media_reserve(text,text,uuid,integer,text) from public, anon, authenticated;
grant execute on function public.lanu_media_reserve(text,text,uuid,integer,text) to service_role;
