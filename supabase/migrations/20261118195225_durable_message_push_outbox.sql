-- Durable, service-role-only message wake jobs; no token or plaintext stored.
create table public.lanu_push_jobs (
  message_id uuid primary key references public.lanu_messages(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now()+interval '2 minutes'),
  next_attempt_at timestamptz not null default now(),
  lease_until timestamptz,
  lease_id uuid,
  attempts integer not null default 0 check(attempts between 0 and 4),
  state text not null default 'pending' check(state in ('pending','leased','done','expired'))
);
create index lanu_push_jobs_due on public.lanu_push_jobs(next_attempt_at) where state in ('pending','leased');
alter table public.lanu_push_jobs enable row level security;
revoke all on public.lanu_push_jobs from public,anon,authenticated;
grant select,insert,update,delete on public.lanu_push_jobs to service_role;

create function public.lanu_queue_message_push() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
  if exists(select 1 from public.lanu_message_wake_devices where owner_id=new.recipient_id)
    and exists(select 1 from public.lanu_contacts where owner_id=new.sender_id and peer_id=new.recipient_id)
    and exists(select 1 from public.lanu_contacts where owner_id=new.recipient_id and peer_id=new.sender_id) then
    insert into public.lanu_push_jobs(message_id) values(new.id) on conflict do nothing;
  end if;
  return new;
end $$;
revoke all on function public.lanu_queue_message_push() from public,anon,authenticated;
grant execute on function public.lanu_queue_message_push() to service_role;
create trigger lanu_queue_message_push after insert on public.lanu_messages
for each row execute function public.lanu_queue_message_push();

-- One short lease at a time: OAuth+FCM single-attempt deadline is <=20 seconds.
create function public.lanu_push_lease() returns jsonb
language plpgsql security invoker set search_path='' as $$
declare j public.lanu_push_jobs; declare m public.lanu_messages; declare token text;
declare recent integer; declare retry_at timestamptz; declare lease uuid;
begin
  update public.lanu_push_jobs set state='expired',lease_id=null,lease_until=null
    where state in ('pending','leased') and (expires_at<=now() or attempts>=4)
      and (lease_until is null or lease_until<=now());
  for j in select * from public.lanu_push_jobs
      where state in ('pending','leased') and next_attempt_at<=now()
        and expires_at>now() and attempts<4 and (lease_until is null or lease_until<=now())
      order by next_attempt_at,message_id for update skip locked limit 8 loop
    select * into m from public.lanu_messages where id=j.message_id;
    select fcm_token into token from public.lanu_message_wake_devices where owner_id=m.recipient_id;
    if m.id is null or m.deleted or m.delivered_at is not null or token is null
      or not exists(select 1 from public.lanu_contacts where owner_id=m.sender_id and peer_id=m.recipient_id)
      or not exists(select 1 from public.lanu_contacts where owner_id=m.recipient_id and peer_id=m.sender_id) then
      update public.lanu_push_jobs set state='expired',lease_id=null,lease_until=null where message_id=j.message_id;
      continue;
    end if;
    -- Match the legacy recipient throttle. A throttled job stays pending.
    if not pg_try_advisory_xact_lock(hashtext('lanu-msg-wake:'||m.recipient_id)) then continue; end if;
    select count(*),min(created_at)+interval '61 seconds' into recent,retry_at
      from public.lanu_message_wake_claims where recipient_id=m.recipient_id
        and message_id<>j.message_id and created_at>now()-interval '1 minute';
    if recent>=6 then
      update public.lanu_push_jobs set state='pending',next_attempt_at=retry_at where message_id=j.message_id;
      continue;
    end if;
    insert into public.lanu_message_wake_claims(message_id,sender_id,recipient_id)
      values(m.id,m.sender_id,m.recipient_id) on conflict(message_id) do update set created_at=now();
    lease:=gen_random_uuid();
    update public.lanu_push_jobs set state='leased',lease_id=lease,lease_until=now()+interval '60 seconds',attempts=attempts+1
      where message_id=j.message_id;
    return jsonb_build_object('messageId',j.message_id,'leaseId',lease,'recipientId',m.recipient_id,
      'token',token,'at',floor(extract(epoch from j.created_at)*1000)::bigint);
  end loop;
  return null;
end $$;
revoke all on function public.lanu_push_lease() from public,anon,authenticated;
grant execute on function public.lanu_push_lease() to service_role;

create function public.lanu_push_finish(p_message uuid,p_lease uuid,p_outcome text,p_delay_ms bigint default 60000)
returns boolean language plpgsql security invoker set search_path='' as $$
declare affected integer;
begin
  if p_outcome not in ('done','retry','expired') then raise exception 'Invalid job result'; end if;
  update public.lanu_push_jobs set
    state=case when p_outcome='retry' and attempts<4 and now()+make_interval(secs=>greatest(10,least(86400,ceil(p_delay_ms/1000.0))))<expires_at
      then 'pending' when p_outcome='done' then 'done' else 'expired' end,
    next_attempt_at=now()+make_interval(secs=>greatest(10,least(86400,ceil(p_delay_ms/1000.0)))),
    lease_id=null,lease_until=null
  where message_id=p_message and lease_id=p_lease and state='leased' and lease_until>now();
  get diagnostics affected=row_count;
  return affected=1;
end $$;
revoke all on function public.lanu_push_finish(uuid,uuid,text,bigint) from public,anon,authenticated;
grant execute on function public.lanu_push_finish(uuid,uuid,text,bigint) to service_role;

-- Short-lived single-use scheduler credentials. Only hashes remain in DB.
create table public.lanu_push_tickets(hash text primary key,expires_at timestamptz not null);
alter table public.lanu_push_tickets enable row level security;
revoke all on public.lanu_push_tickets from public,anon,authenticated;
grant select,insert,delete on public.lanu_push_tickets to service_role;
create function public.lanu_push_consume_ticket(p_ticket text) returns boolean
language sql security invoker set search_path='' as $$
  with consumed as (delete from public.lanu_push_tickets
    where hash=encode(sha256(convert_to(p_ticket,'UTF8')),'hex') and expires_at>now()
      and length(p_ticket)=64 returning hash)
  select exists(select 1 from consumed);
$$;
revoke all on function public.lanu_push_consume_ticket(text) from public,anon,authenticated;
grant execute on function public.lanu_push_consume_ticket(text) to service_role;
