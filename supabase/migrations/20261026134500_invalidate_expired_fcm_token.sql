-- P1 call-wake hygiene. Service-role only, compare-and-delete.
-- A delayed Google UNREGISTERED for token A must never remove fresh token B.
-- No existing user/profile/message/PIN state is changed.
create or replace function public.lanu_call_wake_invalidate(
  p_owner text, p_token text
) returns jsonb
language plpgsql security invoker set search_path=public,pg_temp as $$
declare affected integer;
begin
  if p_token is null or length(p_token) not between 20 and 4096
    or p_token !~ '^[A-Za-z0-9_:.-]+$' then
    return jsonb_build_object('invalidated', false);
  end if;
  delete from public.lanu_call_wake_devices
    where owner_id=p_owner and fcm_token=p_token;
  get diagnostics affected = row_count;
  return jsonb_build_object('invalidated', affected=1);
end $$;

revoke all on function public.lanu_call_wake_invalidate(text,text) from public,anon,authenticated;
grant execute on function public.lanu_call_wake_invalidate(text,text) to service_role;
