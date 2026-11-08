-- Every v3 message is bound to an authenticated pair generation. Old ciphertext
-- and events remain untouched across explicit, two-owner session renewal.
alter table public.lanu_messages add column ratchet_epoch text;
alter table public.lanu_messages add constraint lanu_ratchet_message_epoch
  check ((protocol_version=1 and ratchet_epoch is null) or
         (protocol_version=3 and ratchet_epoch is not null and ratchet_epoch ~ '^[a-f0-9]{64}$'));
create index lanu_ratchet_recipient_epoch on public.lanu_messages(recipient_id,ratchet_epoch)
  where protocol_version=3 and not deleted;
create index lanu_ratchet_sender_epoch on public.lanu_messages(sender_id,ratchet_epoch)
  where protocol_version=3 and not deleted;

create function public.lanu_ratchet_bundle_text(p_bundle jsonb) returns text
language sql immutable security invoker set search_path='' as $$
  select E'LANU-OLM2-BUNDLE-v1\n'||(p_bundle->>'owner')||E'\n'||(p_bundle->>'peer')||E'\n'||
    (p_bundle->>'generation')||E'\n'||(p_bundle->>'identity')||E'\n'||(p_bundle->>'oneTime');
$$;
create function public.lanu_ratchet_epoch(p_first jsonb,p_second jsonb) returns text
language sql immutable security invoker set search_path='' as $$
  select case when p_first is null or p_second is null or p_first->>'owner'<>p_second->>'peer'
    or p_first->>'peer'<>p_second->>'owner' then null else
    encode(sha256(convert_to(E'LANU-OLM2-PAIR-v1\n'||
      case when p_first->>'owner'<p_second->>'owner'
        then public.lanu_ratchet_bundle_text(p_first)||E'\n'||public.lanu_ratchet_bundle_text(p_second)
        else public.lanu_ratchet_bundle_text(p_second)||E'\n'||public.lanu_ratchet_bundle_text(p_first) end,'UTF8')),'hex') end;
$$;
create function public.lanu_ratchet_pair_epoch(p_owner text,p_peer text) returns text
language sql stable security invoker set search_path='' as $$
  select public.lanu_ratchet_epoch(
    (select bundle from public.lanu_ratchet_bundles where owner_id=p_owner and peer_id=p_peer),
    (select bundle from public.lanu_ratchet_bundles where owner_id=p_peer and peer_id=p_owner));
$$;

create table public.lanu_ratchet_renewals (
  id uuid primary key,
  first_id text not null references public.lanu_identities(id),
  second_id text not null references public.lanu_identities(id),
  previous_epoch text not null check(previous_epoch ~ '^[a-f0-9]{64}$'),
  votes jsonb not null check(octet_length(votes::text)<=8192 and jsonb_typeof(votes)='object'),
  state text not null default 'pending' check(state in ('pending','active','cancelled')),
  new_epoch text check(new_epoch ~ '^[a-f0-9]{64}$'),
  created_at timestamptz not null default now(),
  activated_at timestamptz,
  check(first_id<second_id),
  check((state='active' and new_epoch is not null and activated_at is not null) or
        (state<>'active' and new_epoch is null and activated_at is null))
);
create unique index lanu_ratchet_one_pending on public.lanu_ratchet_renewals(first_id,second_id) where state='pending';
create index lanu_ratchet_renewal_second on public.lanu_ratchet_renewals(second_id,first_id);
alter table public.lanu_ratchet_renewals enable row level security;
revoke all on public.lanu_ratchet_renewals from public,anon,authenticated;
grant select,insert,update on public.lanu_ratchet_renewals to service_role;
grant update,delete on public.lanu_ratchet_bundles to service_role;

create function public.lanu_ratchet_state(p_owner text,p_peer text) returns jsonb
language plpgsql stable security invoker set search_path='' as $$
declare own_bundle jsonb; peer_bundle jsonb; epoch text; pending jsonb; completed jsonb; incoming bigint; outgoing bigint;
begin
  if p_owner=p_peer or not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    raise exception using errcode='42501',message='Mutual contact required';
  end if;
  select bundle into own_bundle from public.lanu_ratchet_bundles where owner_id=p_owner and peer_id=p_peer;
  select bundle into peer_bundle from public.lanu_ratchet_bundles where owner_id=p_peer and peer_id=p_owner;
  epoch:=public.lanu_ratchet_epoch(own_bundle,peer_bundle);
  select to_jsonb(r) into pending from public.lanu_ratchet_renewals r
    where first_id=least(p_owner,p_peer) and second_id=greatest(p_owner,p_peer) and state='pending';
  select to_jsonb(r) into completed from public.lanu_ratchet_renewals r
    where first_id=least(p_owner,p_peer) and second_id=greatest(p_owner,p_peer) and state='active' and new_epoch=epoch
    order by activated_at desc,id desc limit 1;
  select count(*) into incoming from public.lanu_messages where recipient_id=p_owner and sender_id=p_peer
    and protocol_version=3 and not deleted and delivered_at is null and ratchet_epoch<>epoch;
  select count(*) into outgoing from public.lanu_messages where sender_id=p_owner and recipient_id=p_peer
    and protocol_version=3 and not deleted and delivered_at is null and ratchet_epoch<>epoch;
  return jsonb_build_object('ready',epoch is not null,'epoch',epoch,'ownBundle',own_bundle,'peerBundle',peer_bundle,
    'pending',pending,'completed',completed,'retiredIncoming',incoming,'retiredOutgoing',outgoing,
    'hasProtectedHistory',exists(select 1 from public.lanu_messages where protocol_version=3 and
       ((sender_id=p_owner and recipient_id=p_peer) or(sender_id=p_peer and recipient_id=p_owner))));
end $$;

create function public.lanu_ratchet_cancel_initial(p_owner text,p_peer text) returns jsonb
language plpgsql security invoker set search_path='' as $$
begin
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(p_owner,p_peer)||'|'||greatest(p_owner,p_peer),0));
  if public.lanu_ratchet_pair_epoch(p_owner,p_peer) is not null or exists(select 1 from public.lanu_messages
    where protocol_version=3 and ((sender_id=p_owner and recipient_id=p_peer) or(sender_id=p_peer and recipient_id=p_owner))) then
    return jsonb_build_object('cancelled',false,'reason','already_active');
  end if;
  delete from public.lanu_ratchet_bundles where owner_id=p_owner and peer_id=p_peer;
  return jsonb_build_object('cancelled',true);
end $$;

create function public.lanu_ratchet_renew(p_owner text,p_peer text,p_reset uuid,p_previous text,p_bundle jsonb,p_proof text) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare r public.lanu_ratchet_renewals; existing jsonb; vote jsonb; epoch text; v_new_epoch text; next_votes jsonb;
begin
  if p_owner !~ '^[a-f0-9]{64}$' or p_peer !~ '^[a-f0-9]{64}$' or p_owner=p_peer or
    p_previous !~ '^[a-f0-9]{64}$' or jsonb_typeof(p_bundle)<>'object' or octet_length(p_bundle::text)>2048 or
    p_bundle->>'owner' is distinct from p_owner or p_bundle->>'peer' is distinct from p_peer or
    p_proof is null or length(p_proof) not between 80 and 100 then
    raise exception using errcode='22023',message='Invalid renewal';
  end if;
  if not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    raise exception using errcode='42501',message='Mutual contact required';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(p_owner,p_peer)||'|'||greatest(p_owner,p_peer),0));
  vote:=jsonb_build_object('bundle',p_bundle,'proof',p_proof);
  select * into r from public.lanu_ratchet_renewals where id=p_reset;
  if found then
    if r.first_id<>least(p_owner,p_peer) or r.second_id<>greatest(p_owner,p_peer) or r.previous_epoch<>p_previous then
      return jsonb_build_object('accepted',false,'reason','renewal_conflict');
    end if;
    existing:=r.votes->p_owner;
    if existing is not null and existing<>vote then return jsonb_build_object('accepted',false,'reason','vote_conflict'); end if;
    if r.state='active' and existing=vote then
      return jsonb_build_object('accepted',true,'ready',true,'state',public.lanu_ratchet_state(p_owner,p_peer),'receipt',to_jsonb(r));
    end if;
    if r.state<>'pending' then return jsonb_build_object('accepted',false,'reason','renewal_closed'); end if;
  else
    if exists(select 1 from public.lanu_ratchet_renewals where first_id=least(p_owner,p_peer) and second_id=greatest(p_owner,p_peer) and state='pending') then
      return jsonb_build_object('accepted',false,'reason','another_renewal_pending');
    end if;
    if (select count(*) from public.lanu_ratchet_renewals where first_id=least(p_owner,p_peer) and second_id=greatest(p_owner,p_peer))>=256 then
      return jsonb_build_object('accepted',false,'reason','renewal_budget');
    end if;
    r.id:=p_reset;r.first_id:=least(p_owner,p_peer);r.second_id:=greatest(p_owner,p_peer);
    r.previous_epoch:=p_previous;r.votes:='{}'::jsonb;r.state:='pending';
  end if;
  epoch:=public.lanu_ratchet_pair_epoch(p_owner,p_peer);
  if epoch is null or epoch<>p_previous then return jsonb_build_object('accepted',false,'reason','epoch_changed'); end if;
  if p_bundle=(select bundle from public.lanu_ratchet_bundles where owner_id=p_owner and peer_id=p_peer) then
    return jsonb_build_object('accepted',false,'reason','fresh_bundle_required');
  end if;
  next_votes:=r.votes||jsonb_build_object(p_owner,vote);
  insert into public.lanu_ratchet_renewals(id,first_id,second_id,previous_epoch,votes)
    values(p_reset,r.first_id,r.second_id,p_previous,next_votes)
    on conflict(id) do update set votes=excluded.votes;
  if next_votes ? p_owner and next_votes ? p_peer then
    v_new_epoch:=public.lanu_ratchet_epoch(next_votes->p_owner->'bundle',next_votes->p_peer->'bundle');
    if v_new_epoch is null or v_new_epoch=epoch then raise exception using errcode='22023',message='Fresh generation required'; end if;
    update public.lanu_ratchet_bundles set bundle=next_votes->p_owner->'bundle' where owner_id=p_owner and peer_id=p_peer;
    update public.lanu_ratchet_bundles set bundle=next_votes->p_peer->'bundle' where owner_id=p_peer and peer_id=p_owner;
    update public.lanu_ratchet_renewals set state='active',new_epoch=v_new_epoch,activated_at=now() where id=p_reset;
  end if;
  select * into r from public.lanu_ratchet_renewals where id=p_reset;
  return jsonb_build_object('accepted',true,'ready',r.state='active','state',public.lanu_ratchet_state(p_owner,p_peer),'receipt',to_jsonb(r));
end $$;

-- Same pair lock for reset and send. Existing immutable retries retain their
-- original generation; no message content, history or delivery event is erased.
create or replace function public.lanu_ratchet_admit_message() returns trigger
language plpgsql security invoker set search_path='' as $$
declare current_epoch text;
begin
  if tg_op='UPDATE' then
    if new.protocol_version<>old.protocol_version or new.ratchet_epoch is distinct from old.ratchet_epoch then
      raise exception using errcode='23514',message='Message protocol is immutable';
    end if;return new;
  end if;
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(new.sender_id,new.recipient_id)||'|'||greatest(new.sender_id,new.recipient_id),0));
  current_epoch:=public.lanu_ratchet_pair_epoch(new.sender_id,new.recipient_id);
  if (current_epoch is null and new.protocol_version<>1) or
     (current_epoch is not null and (new.protocol_version<>3 or new.ratchet_epoch is distinct from current_epoch)) then
    raise exception using errcode='23514',message='Conversation protocol barrier mismatch';
  end if;return new;
end $$;
drop trigger lanu_ratchet_admission on public.lanu_messages;
create trigger lanu_ratchet_admission before insert or update of protocol_version,ratchet_epoch on public.lanu_messages
for each row execute function public.lanu_ratchet_admit_message();

create or replace function public.lanu_ratchet_aware_events(p_owner text,p_media_version integer)
returns table(id uuid,snapshot jsonb)
language sql security invoker set search_path='' as $$
  select e.id,e.snapshot||jsonb_build_object('protocolVersion',m.protocol_version,'ratchetEpoch',m.ratchet_epoch)
  from public.lanu_events e join public.lanu_messages m on m.id=e.message_id
  where e.owner_id=p_owner and not e.acked and (p_media_version=1 or not m.private_media) and
    (m.protocol_version=1 or
      m.ratchet_epoch=public.lanu_ratchet_pair_epoch(m.sender_id,m.recipient_id))
  order by e.created_at,e.id limit 200;
$$;

revoke all on function public.lanu_ratchet_bundle_text(jsonb),public.lanu_ratchet_epoch(jsonb,jsonb),
  public.lanu_ratchet_pair_epoch(text,text),public.lanu_ratchet_state(text,text),
  public.lanu_ratchet_cancel_initial(text,text),public.lanu_ratchet_renew(text,text,uuid,text,jsonb,text)
  from public,anon,authenticated;
grant execute on function public.lanu_ratchet_bundle_text(jsonb),public.lanu_ratchet_epoch(jsonb,jsonb),
  public.lanu_ratchet_pair_epoch(text,text),public.lanu_ratchet_state(text,text),
  public.lanu_ratchet_cancel_initial(text,text),public.lanu_ratchet_renew(text,text,uuid,text,jsonb,text)
  to service_role;

create function public.lanu_ratchet_cancel_renewal(p_owner text,p_peer text,p_reset uuid) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare r public.lanu_ratchet_renewals;
begin
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(p_owner,p_peer)||'|'||greatest(p_owner,p_peer),0));
  select * into r from public.lanu_ratchet_renewals where id=p_reset;
  if not found or r.first_id<>least(p_owner,p_peer) or r.second_id<>greatest(p_owner,p_peer) then
    return jsonb_build_object('cancelled',false);
  end if;
  if r.state='cancelled' and r.votes ? p_owner then return jsonb_build_object('cancelled',true); end if;
  if r.state<>'pending' or not(r.votes ? p_owner) or (select count(*) from jsonb_object_keys(r.votes))<>1 then
    return jsonb_build_object('cancelled',false);
  end if;
  update public.lanu_ratchet_renewals set state='cancelled' where id=p_reset;
  return jsonb_build_object('cancelled',true);
end $$;

create function public.lanu_ratchet_send(p_owner text,p_peer text,p_message uuid,p_content text,p_epoch text) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare m public.lanu_messages;
begin
  if p_owner=p_peer or p_epoch is null or p_epoch !~ '^[a-f0-9]{64}$' or p_content is null or
    length(p_content)>50000 or p_content !~ '^lanu2:[A-Za-z0-9_-]{38,}$' then
    return jsonb_build_object('accepted',false,'reason','invalid_message');
  end if;
  perform pg_advisory_xact_lock(hashtextextended('LANU-DR3|'||least(p_owner,p_peer)||'|'||greatest(p_owner,p_peer),0));
  if not exists(select 1 from public.lanu_contacts where owner_id=p_owner and peer_id=p_peer)
    or not exists(select 1 from public.lanu_contacts where owner_id=p_peer and peer_id=p_owner) then
    return jsonb_build_object('accepted',false,'reason','mutual_required');
  end if;
  select * into m from public.lanu_messages where id=p_message;
  if found then
    if m.sender_id=p_owner and m.recipient_id=p_peer and m.content=p_content and m.ratchet_epoch=p_epoch and m.protocol_version=3 then
      return jsonb_build_object('accepted',true,'message',to_jsonb(m));
    end if;
    return jsonb_build_object('accepted',false,'reason','id_conflict');
  end if;
  if p_epoch is distinct from public.lanu_ratchet_pair_epoch(p_owner,p_peer) then
    return jsonb_build_object('accepted',false,'reason','epoch_changed');
  end if;
  insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version,ratchet_epoch)
    values(p_message,p_owner,p_peer,p_content,3,p_epoch) on conflict(id) do nothing;
  select * into m from public.lanu_messages where id=p_message;
  if m.sender_id<>p_owner or m.recipient_id<>p_peer or m.content<>p_content or m.ratchet_epoch<>p_epoch then
    return jsonb_build_object('accepted',false,'reason','id_conflict');
  end if;
  return jsonb_build_object('accepted',true,'message',to_jsonb(m));
end $$;
revoke all on function public.lanu_ratchet_send(text,text,uuid,text,text),public.lanu_ratchet_cancel_renewal(text,text,uuid)
  from public,anon,authenticated;
grant execute on function public.lanu_ratchet_send(text,text,uuid,text,text),public.lanu_ratchet_cancel_renewal(text,text,uuid)
  to service_role;
