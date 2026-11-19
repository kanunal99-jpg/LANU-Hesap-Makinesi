-- Supabase pg_net is free/open-source, existing project only. No Vault/API key is exposed.
create extension if not exists pg_net with schema extensions;
revoke usage on schema net from public,anon,authenticated;
revoke all on all functions in schema net from public,anon,authenticated;
revoke all on all tables in schema net from public,anon,authenticated;
revoke all on all sequences in schema net from public,anon,authenticated;
create function public.lanu_schedule_message_push() returns void
language plpgsql security invoker set search_path='' as $$
declare ticket text;
begin
  delete from public.lanu_push_tickets where expires_at<=now();
  delete from public.lanu_push_jobs where expires_at<now()-interval '1 day';
  if not exists(select 1 from public.lanu_push_jobs where state in ('pending','leased')
      and next_attempt_at<=now() and expires_at>now() and (lease_until is null or lease_until<=now()))
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
-- Only the database's postgres scheduler may mint tickets; replay/expiry is checked in the Edge RPC.
select cron.schedule('lanu-message-push-outbox','10 seconds','select public.lanu_schedule_message_push()');

-- Keep only this feature's execution metadata bounded; leave other cron logs untouched.
select cron.schedule('lanu-push-cron-log-retention','20 3 * * *',
  $job$delete from cron.job_run_details where jobid in
    (select jobid from cron.job where jobname in ('lanu-message-push-outbox','lanu-push-cron-log-retention'))
    and end_time<now()-interval '7 days'$job$);
