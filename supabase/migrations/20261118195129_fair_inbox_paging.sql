-- Additive keyset traversal. Retain rejected ciphertext and existing ACK semantics.
create function public.lanu_message_event_page(p_owner text,p_media_version integer,p_after uuid default null)
returns setof public.lanu_events language sql security invoker set search_path='' as $$
  with cursor_row as (
    select created_at,id from public.lanu_events where owner_id=p_owner and id=p_after
  )
  select e.* from public.lanu_events e join public.lanu_messages m on m.id=e.message_id
  where e.owner_id=p_owner and not e.acked and (p_media_version=1 or not m.private_media)
    and (not exists(select 1 from cursor_row) or (e.created_at,e.id)>
      (select created_at,id from cursor_row))
  order by e.created_at,e.id limit 200;
$$;
revoke all on function public.lanu_message_event_page(text,integer,uuid) from public,anon,authenticated;
grant execute on function public.lanu_message_event_page(text,integer,uuid) to service_role;

notify pgrst,'reload schema';
