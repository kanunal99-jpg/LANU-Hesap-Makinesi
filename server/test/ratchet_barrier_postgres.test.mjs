import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
const A='a'.repeat(64), B='b'.repeat(64), C='c'.repeat(64);
const bundle=(owner,peer)=>({owner,peer,generation:randomUUID(),identity:'public-fixture',oneTime:'public-fixture',signature:'public-fixture'});
test('SQL ratchet barrier preserves legacy queues, rejects downgrade, and filters before inbox limit',async()=>{
 const db=new PGlite();
 try {
  await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;
    create schema storage;create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);`);
  for(const file of ['20261019073855_persistent_user_code_messaging.sql','20261024105720_private_media_grants_and_quota.sql','20261108060100_ratchet_peer_barrier.sql'])
    await db.exec(await readFile(new URL('../../supabase/migrations/'+file,import.meta.url),'utf8'));
  for(const [id,letter] of [[A,'A'],[B,'B'],[C,'C']]) await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',[id,'LANU-'+Array(5).fill(letter.repeat(4)).join('-'),'A'.repeat(22),'fixture']);
  await db.query("insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'fixture'),($2,$1,'fixture'),($3,$1,'fixture'),($1,$3,'fixture')",[A,B,C]);
  const ab=bundle(A,B),ba=bundle(B,A);
  const publish=async(o,p,b)=>(await db.query('select public.lanu_ratchet_publish($1,$2,$3) as result',[o,p,b])).rows[0].result;
  const old=randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[old,A,B,'lanu2:'+'A'.repeat(40)]);
  assert.equal((await publish(A,B,ab)).reason,'drain_legacy_queue');
  await db.query('update public.lanu_messages set delivered_at=now() where id=$1',[old]);
  // Delivery alone is insufficient: both clients must persist/ACK old events.
  assert.equal((await publish(A,B,ab)).accepted,false);
  await db.exec('update public.lanu_events set acked=true');
  assert.equal((await publish(A,B,ab)).ready,false);
  await assert.rejects(db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version) values($1,$2,$3,$4,3)',[randomUUID(),A,B,'lanu2:'+'A'.repeat(40)]));
  assert.equal((await publish(B,A,ba)).ready,true);
  assert.equal((await publish(A,B,ab)).ready,true);
  assert.equal((await publish(A,B,{...ab,generation:randomUUID()})).reason,'explicit_reset_required');
  await assert.rejects(db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[randomUUID(),A,B,'lanu2:'+'A'.repeat(40)]));
  await db.query("insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version) select gen_random_uuid(),$1,$2,$3,3 from generate_series(1,210)",[B,A,'lanu2:'+'A'.repeat(40)]);
  const legacy=randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[legacy,C,A,'lanu2:'+'A'.repeat(40)]);
  const oldPoll=(await db.query('select * from public.lanu_media_aware_events($1,0)',[A])).rows;
  assert.equal(oldPoll.length,1);assert.equal(oldPoll[0].snapshot.id,legacy,'210 ratchet events must not starve legacy peer');
  const upgraded=(await db.query('select * from public.lanu_ratchet_aware_events($1,0)',[A])).rows;
  assert.equal(upgraded.length,200);assert.equal(upgraded[0].snapshot.protocolVersion,3);
  for(const role of ['anon','authenticated']) {
    assert.equal((await db.query("select has_table_privilege($1,'public.lanu_ratchet_bundles','select,insert,update,delete') as allowed",[role])).rows[0].allowed,false);
    assert.equal((await db.query("select has_function_privilege($1,'public.lanu_ratchet_publish(text,text,jsonb)','execute') as allowed",[role])).rows[0].allowed,false);
    assert.equal((await db.query("select has_function_privilege($1,'public.lanu_ratchet_aware_events(text,integer)','execute') as allowed",[role])).rows[0].allowed,false);
  }
  assert.equal((await db.query("select relrowsecurity as enabled from pg_class where oid='public.lanu_ratchet_bundles'::regclass")).rows[0].enabled,true);
 } finally {await db.close();}
});
