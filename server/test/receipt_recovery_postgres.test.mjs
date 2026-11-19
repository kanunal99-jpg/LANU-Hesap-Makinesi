import {before,after,beforeEach,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
let db;
const A='a'.repeat(64),B='b'.repeat(64);
const migration=async name=>readFile(new URL('../../supabase/migrations/'+name,import.meta.url),'utf8');
before(async()=>{
 db=new PGlite();
 await db.exec('create role anon;create role authenticated;create role service_role bypassrls;create schema storage;create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);');
 for(const name of ['20261019073855_persistent_user_code_messaging.sql','20261024105720_private_media_grants_and_quota.sql','20261027174242_private_message_wake_opt_in_20261027.sql','20261118195129_fair_inbox_paging.sql','20261118195225_durable_message_push_outbox.sql']) await db.exec(await migration(name));
 for(const [id,letter] of [[A,'A'],[B,'B']])await db.query('insert into lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',[id,'LANU-'+Array(5).fill(letter.repeat(4)).join('-'),'A'.repeat(22),'test']);
 await db.query("insert into lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'test'),($2,$1,'test')",[A,B]);
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2)',[B,'T'.repeat(40)]);
 const legacy=await save();await db.query("update lanu_push_jobs set state='done' where message_id=$1",[legacy]);
 const delivered=await save();await db.query('update lanu_messages set delivered_at=now() where id=$1',[delivered]);
 await db.query("update lanu_push_jobs set state='done' where message_id=$1",[delivered]);
 const active=await save();const leaseId=randomUUID();
 await db.query("update lanu_push_jobs set state='leased',lease_id=$2,lease_until=now()+interval '60 seconds',attempts=1 where message_id=$1",[active,leaseId]);
 await db.exec(await migration('20261119064531_receipt_bound_message_push_recovery.sql'));
 assert.equal((await job(legacy)).state,'pending');assert.equal((await job(delivered)).state,'done');
 assert.equal((await job(active)).lease_id,leaseId);assert.equal((await job(active)).attempts,1);
});
after(async()=>db?.close());
beforeEach(async()=>{
 await db.exec('delete from lanu_push_jobs;delete from lanu_message_wake_claims;delete from lanu_events;delete from lanu_messages;delete from lanu_push_tickets;');
 await db.query("insert into lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'test'),($2,$1,'test') on conflict do nothing",[A,B]);
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2) on conflict(owner_id) do update set fcm_token=excluded.fcm_token',[B,'T'.repeat(40)]);
});
async function save(){const id=randomUUID();await db.query('insert into lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[id,A,B,'lanu2:'+'A'.repeat(60)]);return id;}
async function lease(){return (await db.query('select lanu_push_lease() v')).rows[0].v;}
async function finish(j,outcome,delay=60000){return (await db.query('select lanu_push_finish($1,$2,$3,$4) v',[j.messageId,j.leaseId,outcome,delay])).rows[0].v;}
async function job(id){return (await db.query('select * from lanu_push_jobs where message_id=$1',[id])).rows[0];}
async function ack(id,owner=B){const event=(await db.query('select id from lanu_events where message_id=$1 and owner_id=$2 order by created_at desc limit 1',[id,owner])).rows[0];await db.query('select lanu_ack($1,$2::uuid[],array[]::uuid[])',[owner,[event.id]]);}
async function due(id){await db.query('update lanu_push_jobs set next_attempt_at=now() where message_id=$1',[id]);}
test('Google acceptance stays pending, bounded retry refreshes hint, only recipient ACK completes',async()=>{
 const id=await save(),first=await lease();assert.equal(first.messageId,id);
 assert.equal(await finish(first,'accepted'),true);
 let row=await job(id);assert.equal(row.state,'pending');assert.ok(row.last_accepted_at);assert.equal(row.attempts,1);
 assert.ok(row.next_attempt_at.getTime()-Date.now()>115000);
 assert.ok(row.expires_at.getTime()-Date.now()>86_390_000);
 assert.equal((await db.query('select delivered_at from lanu_messages where id=$1',[id])).rows[0].delivered_at,null);
 assert.equal(await lease(),null,'No hot loop before delivery grace expires');
 await ack(id,A);assert.equal((await job(id)).state,'pending','Sender cannot impersonate recipient delivery');
 await due(id);const second=await lease();assert.equal(second.messageId,id);assert.notEqual(first.leaseId,second.leaseId);
 assert.ok(second.at>=first.at);assert.equal(await finish(first,'accepted'),false,'Stale completion is refused');
 assert.equal(await finish(second,'done'),true,'Rolling old relay result means accepted only');
 assert.equal((await job(id)).state,'pending');
 await ack(id);assert.equal((await job(id)).state,'done');assert.equal(await lease(),null);
});
test('unacknowledged quarantine retries at most four times and late authentic ACK still succeeds',async()=>{
 const id=await save();
 for(let n=1;n<=4;n++){
  await due(id);const j=await lease();assert.ok(j);assert.equal(await finish(j,'accepted'),true);
  assert.equal((await job(id)).attempts,n);
 }
 const row=await job(id);assert.equal(row.state,'pending');assert.equal(+row.next_attempt_at,+row.expires_at);
 await due(id);assert.equal(await lease(),null,'No fifth transport attempt even if due timestamp is changed');
 await ack(id);assert.equal((await job(id)).state,'done');
});
test('consent revoke, peer removal, deletion and expiry never deliver or erase ciphertext',async()=>{
 for(const action of ['revoke','unlink','delete','expire']){
  await db.query("insert into lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'test') on conflict do nothing",[B,A]);
  await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2) on conflict do nothing',[B,'T'.repeat(40)]);
  const id=await save(),j=await lease();await finish(j,'accepted');await due(id);
  if(action==='revoke')await db.query('delete from lanu_message_wake_devices where owner_id=$1',[B]);
  if(action==='unlink')await db.query('delete from lanu_contacts where owner_id=$1',[B]);
  if(action==='delete')await db.query('update lanu_messages set deleted=true where id=$1',[id]);
  if(action==='expire')await db.query("update lanu_push_jobs set expires_at=now()-interval '1 second' where message_id=$1",[id]);
  assert.equal(await lease(),null);assert.equal((await job(id)).state,'expired');
  const m=(await db.query('select content,delivered_at from lanu_messages where id=$1',[id])).rows[0];
  assert.equal(m.delivered_at,null);assert.equal(m.content,'lanu2:'+'A'.repeat(60));
 }
});
test('receipt closes active lease atomically, transport retry honours delay and role boundaries remain',async()=>{
 const id=await save(),j=await lease();await ack(id);
 assert.equal((await job(id)).state,'done');assert.equal(await finish(j,'retry'),false);
 const next=await save(),retry=await lease();assert.equal(retry.messageId,next);await finish(retry,'retry',61234);
 const row=await job(next);assert.equal(row.state,'pending');assert.ok(row.next_attempt_at.getTime()-Date.now()>61000);
 for(const role of ['anon','authenticated']){
  assert.equal((await db.query("select has_function_privilege($1,'lanu_push_lease()','execute') v",[role])).rows[0].v,false);
  assert.equal((await db.query("select has_table_privilege($1,'lanu_push_jobs','select') v",[role])).rows[0].v,false);
 }
});

test('lost fourth completion cannot cause scheduler ticket polling and expiry preserves pending message',async()=>{
 const id=await save();
 for(let n=1;n<=3;n++){await due(id);await finish(await lease(),'accepted');}
 await due(id);const fourth=await lease();assert.ok(fourth);
 await db.query("update lanu_push_jobs set lease_until=now()-interval '1 second' where message_id=$1",[id]);
 await db.exec("create schema net;create table net.requests(body jsonb);create function net.http_post(url text,headers jsonb,body jsonb,timeout_milliseconds integer) returns bigint language plpgsql as $$begin insert into net.requests values(body);return 1;end$$;");
 await db.exec('select lanu_schedule_message_push();select lanu_schedule_message_push()');
 assert.equal((await db.query('select count(*)::int n from net.requests')).rows[0].n,0);
 assert.equal(await lease(),null);const waiting=await job(id);
 assert.equal(waiting.state,'pending');assert.equal(waiting.lease_id,null);assert.equal(+waiting.next_attempt_at,+waiting.expires_at);
 assert.equal(await finish(fourth,'accepted'),false);
 await db.query("update lanu_push_jobs set expires_at=now()-interval '1 second' where message_id=$1",[id]);
 await db.exec('select lanu_schedule_message_push()');assert.equal((await job(id)).state,'expired');
 assert.equal((await db.query('select delivered_at from lanu_messages where id=$1',[id])).rows[0].delivered_at,null);
 await ack(id);assert.equal((await job(id)).state,'done','Late authentic receipt still completes retained job');
});
