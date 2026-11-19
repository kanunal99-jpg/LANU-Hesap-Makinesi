import {before,after,test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
let db;
const A='a'.repeat(64), B='b'.repeat(64), C='c'.repeat(64);
const migration=async name=>readFile(new URL('../../supabase/migrations/'+name,import.meta.url),'utf8');
before(async()=>{
 db=new PGlite();
 await db.exec('create role anon;create role authenticated;create role service_role bypassrls;create schema storage;create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);');
 for(const name of ['20261019073855_persistent_user_code_messaging.sql','20261024105720_private_media_grants_and_quota.sql','20261027174242_private_message_wake_opt_in_20261027.sql','20261118195129_fair_inbox_paging.sql','20261118195225_durable_message_push_outbox.sql']) await db.exec(await migration(name));
 for(const [id,letter] of [[A,'A'],[B,'B'],[C,'C']]) await db.query('insert into lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',[id,'LANU-'+Array(5).fill(letter.repeat(4)).join('-'),'A'.repeat(22),'test']);
 await db.query("insert into lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'test'),($2,$1,'test')",[A,B]);
});
after(async()=>db?.close());
async function save(){const id=randomUUID();await db.query('insert into lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[id,A,B,'lanu2:'+'A'.repeat(60)]);return id;}
async function lease(){return (await db.query('select lanu_push_lease() v')).rows[0].v;}
async function finish(j,result,delay=60000){return (await db.query('select lanu_push_finish($1,$2,$3,$4) v',[j.messageId,j.leaseId,result,delay])).rows[0].v;}
test('keyset page passes 200 unacknowledged events; foreign cursor cannot skip own data or mark delivery',async()=>{
 const ids=[];for(let i=0;i<201;i++)ids.push(await save());
 const first=(await db.query('select * from lanu_message_event_page($1,0,null)',[B])).rows;
 assert.equal(first.length,200);
 const second=(await db.query('select * from lanu_message_event_page($1,0,$2)',[B,first.at(-1).id])).rows;
 assert.equal(second.length,1);assert.ok(!first.some(e=>e.id===second[0].id));
 assert.deepEqual(new Set([...first,...second].map(e=>e.message_id)),new Set(ids));
 const foreign=(await db.query('select id from lanu_events where owner_id=$1 limit 1',[A])).rows[0].id;
 assert.equal((await db.query('select id from lanu_message_event_page($1,0,$2)',[B,foreign])).rows.length,200);
 assert.equal((await db.query('select count(*)::int n from lanu_messages where delivered_at is not null')).rows[0].n,0);
 assert.equal((await db.query('select count(*)::int n from lanu_push_jobs')).rows[0].n,0,'No opt-in means no push queue');
});
test('atomic outbox survives lease loss, refuses stale completion and preserves ciphertext',async()=>{
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2)',[B,'T'.repeat(40)]);
 const id=await save();const j=await lease();assert.equal(j.messageId,id);
 assert.equal(await lease(),null,'Active lease cannot be stolen');
 await db.query("update lanu_push_jobs set lease_until=now()-interval '1 second' where message_id=$1",[id]);
 const retry=await lease();assert.equal(retry.messageId,id);assert.notEqual(retry.leaseId,j.leaseId);
 assert.equal(await finish(j,'done'),false);
 assert.equal(await finish(retry,'retry',61234),true);
 const row=(await db.query('select state,extract(epoch from next_attempt_at-now()) delay from lanu_push_jobs where message_id=$1',[id])).rows[0];
 assert.equal(row.state,'pending');assert.ok(Number(row.delay)>61,'Do not round down Retry-After');
 assert.equal(await lease(),null);
 assert.equal((await db.query('select content from lanu_messages where id=$1',[id])).rows[0].content,'lanu2:'+'A'.repeat(60));
 await db.query("update lanu_push_jobs set expires_at=now()-interval '1 second' where message_id=$1",[id]);
 assert.equal(await lease(),null);
});
test('revoke and already-delivered messages expire jobs without dispatch; roles cannot lease or mint tickets',async()=>{
 const id=await save();await db.query('delete from lanu_message_wake_devices where owner_id=$1',[B]);
 assert.equal(await lease(),null);
 assert.equal((await db.query('select state from lanu_push_jobs where message_id=$1',[id])).rows[0].state,'expired');
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2)',[B,'T'.repeat(40)]);
 const delivered=await save();await db.query('update lanu_messages set delivered_at=now() where id=$1',[delivered]);
 assert.equal(await lease(),null);
 assert.equal((await db.query('select state from lanu_push_jobs where message_id=$1',[delivered])).rows[0].state,'expired');
 await db.query('delete from lanu_message_wake_devices where owner_id=$1',[B]);
 for(const role of ['anon','authenticated']){
  assert.equal((await db.query("select has_table_privilege($1,'lanu_push_jobs','select') v",[role])).rows[0].v,false);
  assert.equal((await db.query("select has_function_privilege($1,'lanu_push_lease()','execute') v",[role])).rows[0].v,false);
  assert.equal((await db.query("select has_function_privilege($1,'lanu_push_consume_ticket(text)','execute') v",[role])).rows[0].v,false);
 }
});
test('recipient quota defers the seventh job instead of losing it; rollback creates no orphan job',async()=>{
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2)',[B,'T'.repeat(40)]);
 await db.exec('delete from lanu_message_wake_claims');
 const id=randomUUID();
 await db.exec('begin');
 await db.query('insert into lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',[id,A,B,'lanu2:'+'A'.repeat(60)]);
 await db.exec('rollback');
 assert.equal((await db.query('select count(*)::int n from lanu_push_jobs where message_id=$1',[id])).rows[0].n,0);
 for(let i=0;i<7;i++)await save();
 for(let i=0;i<6;i++){const job=await lease();assert.ok(job);assert.equal(await finish(job,'done'),true);}
 assert.equal(await lease(),null);
 const pending=(await db.query("select * from lanu_push_jobs where state='pending' and expires_at>now()")).rows;
 assert.equal(pending.length,1);assert.ok(pending[0].next_attempt_at>new Date());
 await db.exec("update lanu_message_wake_claims set created_at=now()-interval '2 minutes';update lanu_push_jobs set next_attempt_at=now() where state='pending'");
 const recovered=await lease();assert.equal(recovered.messageId,pending[0].message_id);assert.equal(await finish(recovered,'done'),true);
 await db.query('delete from lanu_message_wake_devices where owner_id=$1',[B]);
});
test('scheduler only sends when due and uses expiring single-use tickets without a project secret',async()=>{
 await db.exec("create schema net;create table net.requests(body jsonb);create function net.http_post(url text,headers jsonb,body jsonb,timeout_milliseconds integer) returns bigint language plpgsql as $$begin insert into net.requests values(body);return 1;end$$;create schema cron;create function cron.schedule(text,text,text) returns bigint language sql as $$select 1::bigint$$;");
 const sql=(await migration('20261118195516_message_push_scheduler.sql')).replace('create extension if not exists pg_net with schema extensions;','');
 await db.exec(sql);
 await db.exec('select lanu_schedule_message_push()');assert.equal((await db.query('select count(*)::int n from net.requests')).rows[0].n,0);
 await db.query('insert into lanu_message_wake_devices(owner_id,fcm_token) values($1,$2)',[B,'T'.repeat(40)]);await save();
 await db.exec('select lanu_schedule_message_push();select lanu_schedule_message_push();');
 const requests=(await db.query('select body from net.requests')).rows;assert.equal(requests.length,1);
 const ticket=requests[0].body.workerTicket;assert.match(ticket,/^[a-f0-9]{64}$/);
 assert.equal((await db.query('select lanu_push_consume_ticket($1) v',[ticket])).rows[0].v,true);
 assert.equal((await db.query('select lanu_push_consume_ticket($1) v',[ticket])).rows[0].v,false);
 await db.query("insert into lanu_push_tickets values(encode(sha256(convert_to($1,'UTF8')),'hex'),now()-interval '1 second')",['a'.repeat(64)]);
 assert.equal((await db.query('select lanu_push_consume_ticket($1) v',['a'.repeat(64)])).rows[0].v,false);
 assert.equal((await db.query("select has_function_privilege('service_role','lanu_schedule_message_push()','execute') v")).rows[0].v,false);
});
