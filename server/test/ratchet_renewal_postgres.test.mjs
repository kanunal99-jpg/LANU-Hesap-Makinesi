import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
import {ratchetEpoch} from '../../supabase/functions/lanu-relay/ratchet-bundle.mjs';
const A='a'.repeat(64),B='b'.repeat(64),C='c'.repeat(64);
const bundle=(owner,peer)=>({owner,peer,generation:randomUUID(),identity:randomBytes(32).toString('base64').replace(/=+$/,''),oneTime:randomBytes(32).toString('base64').replace(/=+$/,''),signature:'S'.repeat(96)});
async function fixture() {
 const db=new PGlite();
 await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;
 create schema storage;create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);`);
 const migrations=await readdir(new URL('../../supabase/migrations/',import.meta.url));
 for(const file of ['20261019073855_persistent_user_code_messaging.sql','20261024105720_private_media_grants_and_quota.sql','20261108060100_ratchet_peer_barrier.sql',migrations.find(x=>x.endsWith('_ratchet_generation_renewal.sql'))])
   await db.exec(await readFile(new URL('../../supabase/migrations/'+file,import.meta.url),'utf8'));
 for(const [id,char] of [[A,'A'],[B,'B'],[C,'C']]) await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',[id,'LANU-'+Array(5).fill(char.repeat(4)).join('-'),'A'.repeat(22),'fixture']);
 await db.query("insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'fixture'),($2,$1,'fixture')",[A,B]);
 const ab=bundle(A,B),ba=bundle(B,A);
 await db.query('select public.lanu_ratchet_publish($1,$2,$3)',[A,B,ab]);
 await db.query('select public.lanu_ratchet_publish($1,$2,$3)',[B,A,ba]);
 const epoch=await ratchetEpoch(ab,ba);
 const state=async(o=A,p=B)=>(await db.query('select public.lanu_ratchet_state($1,$2) as s',[o,p])).rows[0].s;
 const renew=async(o,p,id,previous,b)=>(await db.query('select public.lanu_ratchet_renew($1,$2,$3,$4,$5,$6) as r',[o,p,id,previous,b,'P'.repeat(96)])).rows[0].r;
 return {db,ab,ba,epoch,state,renew};
}
test('both owners must approve the SAME reset and old ciphertext survives while new traffic advances',async()=>{
 const f=await fixture();const {db,epoch,state,renew}=f;
 try {
  assert.equal((await state()).epoch,epoch,'SQL/Kotlin-compatible canonical hash');
  const old=randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version,ratchet_epoch) values($1,$2,$3,$4,3,$5)',[old,A,B,'lanu2:'+'A'.repeat(40),epoch]);
  const id=randomUUID(),aNew=bundle(A,B),bNew=bundle(B,A);
  const first=await renew(A,B,id,epoch,aNew);assert.equal(first.accepted,true);assert.equal(first.ready,false);
  assert.equal((await state()).epoch,epoch);
  const other=await renew(B,A,randomUUID(),epoch,bNew);assert.equal(other.accepted,false);
  const bad=await renew(B,A,id,'f'.repeat(64),bNew);assert.equal(bad.accepted,false);
  const done=await renew(B,A,id,epoch,bNew);assert.equal(done.ready,true);
  const next=await ratchetEpoch(aNew,bNew);assert.equal(done.state.epoch,next);assert.notEqual(next,epoch);
  assert.equal((await state(B,A)).retiredIncoming,1);
  assert.equal((await state()).retiredOutgoing,1);
  assert.equal((await renew(A,B,id,epoch,aNew)).ready,true,'lost response is idempotent');
  assert.equal((await renew(A,B,id,epoch,bundle(A,B))).accepted,false,'a committed vote is immutable');
  assert.equal(Number((await db.query('select count(*) as n from public.lanu_messages where id=$1',[old])).rows[0].n),1);
  await assert.rejects(db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version,ratchet_epoch) values($1,$2,$3,$4,3,$5)',[randomUUID(),A,B,'lanu2:'+'A'.repeat(40),epoch]));
  await assert.rejects(db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version) values($1,$2,$3,$4,3)',[randomUUID(),A,B,'lanu2:'+'A'.repeat(40)]));
  const fresh=randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content,protocol_version,ratchet_epoch) values($1,$2,$3,$4,3,$5)',[fresh,A,B,'lanu2:'+'B'.repeat(40),next]);
  const events=(await db.query('select * from public.lanu_ratchet_aware_events($1,0)',[B])).rows;
  assert.deepEqual(events.map(e=>e.snapshot.id),[fresh],'old generation is filtered BEFORE limit and is not falsely ACKed');
  assert.equal((await db.query('select acked from public.lanu_events where owner_id=$1 and message_id=$2',[B,old])).rows[0].acked,false);
  await assert.rejects(db.query('update public.lanu_messages set ratchet_epoch=$1 where id=$2',[epoch,fresh]));
  for(const role of ['anon','authenticated']) {
   assert.equal((await db.query("select has_table_privilege($1,'public.lanu_ratchet_renewals','select,insert,update,delete') as allowed",[role])).rows[0].allowed,false);
   assert.equal((await db.query("select has_function_privilege($1,'public.lanu_ratchet_renew(text,text,uuid,text,jsonb,text)','execute') as allowed",[role])).rows[0].allowed,false);
  }
 } finally {await db.close();}
});
test('only a not-yet-active initial upgrade can be cancelled without erasing any messages',async()=>{
 const f=await fixture();
 try {
  assert.equal((await f.db.query('select public.lanu_ratchet_cancel_initial($1,$2) as c',[A,B])).rows[0].c.cancelled,false);
  // A separate pending initial pair with NO v3 history.
  await f.db.query("insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,'fixture'),($2,$1,'fixture')",[A,C]);
  await f.db.query('select public.lanu_ratchet_publish($1,$2,$3)',[A,C,bundle(A,C)]);
  assert.equal((await f.db.query('select public.lanu_ratchet_cancel_initial($1,$2) as c',[A,C])).rows[0].c.cancelled,true);
  assert.equal((await f.db.query('select public.lanu_ratchet_cancel_initial($1,$2) as c',[A,C])).rows[0].c.cancelled,true);
  assert.equal(Number((await f.db.query('select count(*) n from public.lanu_ratchet_bundles where owner_id=$1 and peer_id=$2',[A,C])).rows[0].n),0);
 } finally {await f.db.close();}
});

test('atomic send receipts stay idempotent across renewal and cannot replace a generation or another pair UUID',async()=>{
 const f=await fixture();const {db,epoch,renew}=f;
 const send=async(owner,peer,id,content,generation)=>(await db.query('select public.lanu_ratchet_send($1,$2,$3,$4,$5) s',[owner,peer,id,content,generation])).rows[0].s;
 try {
  const id=randomUUID(),cipher='lanu2:'+'X'.repeat(42);
  assert.equal((await send(A,B,id,cipher,epoch)).accepted,true);
  assert.equal((await send(A,B,id,cipher,epoch)).accepted,true);
  assert.equal((await send(A,B,id,cipher+'Y',epoch)).accepted,false);
  const round=randomUUID();await renew(A,B,round,epoch,bundle(A,B));
  const next=await renew(B,A,round,epoch,bundle(B,A));
  assert.equal((await send(A,B,id,cipher,epoch)).accepted,true,'a lost old ACK is still truthful');
  assert.equal((await send(A,B,randomUUID(),cipher,epoch)).accepted,false,'new old-epoch row cannot race reset');
  assert.equal((await send(A,B,id,cipher,next.state.epoch)).accepted,false,'same UUID cannot be rekeyed');
  assert.equal((await send(A,B,randomUUID(),cipher,next.state.epoch)).accepted,true);
  const cancel=(await db.query('select public.lanu_ratchet_cancel_renewal($1,$2,$3) c',[A,B,round])).rows[0].c;
  assert.equal(cancel.cancelled,false,'active two-owner renewal cannot be cancelled');
  const round2=randomUUID();await renew(A,B,round2,next.state.epoch,bundle(A,B));
  assert.equal((await db.query('select public.lanu_ratchet_cancel_renewal($1,$2,$3) c',[B,A,round2])).rows[0].c.cancelled,false);
  assert.equal((await db.query('select public.lanu_ratchet_cancel_renewal($1,$2,$3) c',[A,B,round2])).rows[0].c.cancelled,true);
  assert.equal((await db.query('select public.lanu_ratchet_cancel_renewal($1,$2,$3) c',[A,B,round2])).rows[0].c.cancelled,true);
  assert.equal((await renew(B,A,round2,next.state.epoch,bundle(B,A))).accepted,false);
 } finally {await db.close();}
});
