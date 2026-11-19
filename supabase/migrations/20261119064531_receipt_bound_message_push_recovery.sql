-- Delivery ACK is authoritative. Google acceptance only schedules receipt recovery.
alter table public.lanu_push_jobs add column last_accepted_at timestamptz;
alter table public.lanu_push_jobs alter column expires_at set default (now()+interval '24 hours');
-- Resume only recent, still-undelivered, currently consented/mutual jobs. Never touch message data.
update public.lanu_push_jobs j set expires_at=m.created_at+interval '24 hours',
  state=case when j.state='leased' and j.lease_until>now() then 'leased' else 'pending' end,
  attempts=case when j.state='leased' and j.lease_until>now() then j.attempts else 0 end,
  next_attempt_at=now(),
  lease_id=case when j.state='leased' and j.lease_until>now() then j.lease_id else null end,
  lease_until=case when j.state='leased' and j.lease_until>now() then j.lease_until else null end
from public.lanu_messages m where m.id=j.message_id and m.delivered_at is null and not m.deleted
  and m.created_at>now()-interval '24 hours'
  and exists(select 1 from public.lanu_message_wake_devices where owner_id=m.recipient_id)
  and exists(select 1 from public.lanu_contacts where owner_id=m.sender_id and peer_id=m.recipient_id)
  and exists(select 1 from public.lanu_contacts where owner_id=m.recipient_id and peer_id=m.sender_id);

create or replace function public.lanu_push_lease() returns jsonb
language plpgsql security invoker set search_path='' as $$
declare j public.lanu_push_jobs; declare m public.lanu_messages; declare token text;
declare recent integer; declare retry_at timestamptz; declare lease uuid;
begin
  update public.lanu_push_jobs set state='expired',lease_id=null,lease_until=null
    where state in ('pending','leased') and (expires_at<=now() or (attempts>=4 and last_accepted_at is null))
      and (lease_until is null or lease_until<=now());
  -- A lost fourth transport completion must not keep minting scheduler tickets.
  update public.lanu_push_jobs set state='pending',next_attempt_at=expires_at,lease_id=null,lease_until=null
    where state in ('pending','leased') and attempts>=4 and last_accepted_at is not null and expires_at>now()
      and (lease_until is null or lease_until<=now());
  for j in select * from public.lanu_push_jobs
      where state in ('pending','leased') and next_attempt_at<=now()
        and expires_at>now() and attempts<4 and (lease_until is null or lease_until<=now())
      order by next_attempt_at,message_id for update skip locked limit 8 loop
    select * into m from public.lanu_messages where id=j.message_id;
    select fcm_token into token from public.lanu_message_wake_devices where owner_id=m.recipient_id;
    if m.delivered_at is not null then
      update public.lanu_push_jobs set state='done',lease_id=null,lease_until=null where message_id=j.message_id;
      continue;
    end if;
    if m.id is null or m.deleted or token is null
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
      'token',token,'at',floor(extract(epoch from now())*1000)::bigint);
  end loop;
  return null;
end $$;
revoke all on function public.lanu_push_lease() from public,anon,authenticated;
grant execute on function public.lanu_push_lease() to service_role;

create or replace function public.lanu_push_finish(p_message uuid,p_lease uuid,p_outcome text,p_delay_ms bigint default 60000)
returns boolean language plpgsql security invoker set search_path='' as $$
declare j public.lanu_push_jobs; declare m public.lanu_messages; declare seconds double precision;
begin
  if p_outcome not in ('accepted','done','retry','expired') then raise exception 'Invalid job result'; end if;
  select * into j from public.lanu_push_jobs where message_id=p_message and lease_id=p_lease
    and state='leased' and lease_until>now() for update;
  if not found then return false; end if;
  select * into m from public.lanu_messages where id=p_message;
  if m.delivered_at is not null then
    update public.lanu_push_jobs set state='done',lease_id=null,lease_until=null where message_id=p_message;
  elsif m.id is null or m.deleted or j.expires_at<=now() or p_outcome='expired' then
    update public.lanu_push_jobs set state='expired',lease_id=null,lease_until=null where message_id=p_message;
  elsif p_outcome in ('accepted','done') then
    -- 'done' is accepted from the previous relay during rolling deployment;
    -- it NEVER substitutes for the actual recipient's delivered_at.
    seconds:=case j.attempts when 1 then 120 when 2 then 600 else 3600 end;
    update public.lanu_push_jobs set state='pending',last_accepted_at=now(),lease_id=null,lease_until=null,
      next_attempt_at=case when attempts>=4 then expires_at else least(expires_at,now()+make_interval(secs=>seconds)) end
      where message_id=p_message;
  else
    seconds:=greatest(10,least(86400,ceil(p_delay_ms/1000.0)));
    update public.lanu_push_jobs set state=case when attempts<4 and now()+make_interval(secs=>seconds)<expires_at then 'pending'
        when last_accepted_at is not null then 'pending' else 'expired' end,
      next_attempt_at=case when attempts>=4 or now()+make_interval(secs=>seconds)>=expires_at then expires_at
        else now()+make_interval(secs=>seconds) end,lease_id=null,lease_until=null where message_id=p_message;
  end if;
  return true;
end $$;
revoke all on function public.lanu_push_finish(uuid,uuid,text,bigint) from public,anon,authenticated;
grant execute on function public.lanu_push_finish(uuid,uuid,text,bigint) to service_role;

create function public.lanu_push_delivery_receipt() returns trigger
language plpgsql security invoker set search_path='' as $$
begin
  if new.delivered_at is not null or new.deleted then
    update public.lanu_push_jobs set state=case when new.delivered_at is not null then 'done' else 'expired' end,
      lease_id=null,lease_until=null where message_id=new.id;
  end if;
  return new;
end $$;
revoke all on function public.lanu_push_delivery_receipt() from public,anon,authenticated;
grant execute on function public.lanu_push_delivery_receipt() to service_role;
create trigger lanu_push_delivery_receipt after update of delivered_at,deleted on public.lanu_messages
for each row execute function public.lanu_push_delivery_receipt();

create or replace function public.lanu_schedule_message_push() returns void
language plpgsql security invoker set search_path='' as $$
declare ticket text;
begin
  update public.lanu_push_jobs set state='expired',lease_id=null,lease_until=null
    where state in ('pending','leased') and expires_at<=now() and (lease_until is null or lease_until<=now());
  delete from public.lanu_push_tickets where expires_at<=now();
  delete from public.lanu_push_jobs where expires_at<now()-interval '1 day';
  if not exists(select 1 from public.lanu_push_jobs where state in ('pending','leased')
      and attempts<4 and next_attempt_at<=now() and expires_at>now() and (lease_until is null or lease_until<=now()))
    or exists(select 1 from public.lanu_push_tickets where expires_at>now()) then return; end if;
  ticket:=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-','');
  insert into public.lanu_push_tickets(hash,expires_at)
    values(encode(sha256(convert_to(ticket,'UTF8')),'hex'),now()+interval '30 seconds');
  perform net.http_post(
    url:='https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay',
    headers:='{"Content-Type":"application/json"}'::jsonb,
    body:=jsonb_build_object('workerTicket',ticket),timeout_milliseconds:=20000);
end $$;
revoke all on function public.lanu_schedule_message_push() from public,anon,authenticated,service_role;

notify pgrst,'reload schema';
