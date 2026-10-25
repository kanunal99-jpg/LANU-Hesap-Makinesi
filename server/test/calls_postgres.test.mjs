import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { randomUUID } from 'node:crypto';
let db;
const a='a'.repeat(64), b='b'.repeat(64), c='c'.repeat(64), cipher='lanu2:'+'A'.repeat(60);
before(async()=>{
 db=new PGlite();
 await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
 await db.exec(await readFile(new URL('../../supabase/migrations/20261019073855_persistent_user_code_messaging.sql',import.meta.url),'utf8'));
 await db.exec(await readFile(new URL('../../supabase/migrations/20261025123907_private_call_signaling.sql',import.meta.url),'utf8'));
 for(const [id,k] of [[a,'A'],[b,'B'],[c,'C']]) await db.query('insert into lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',[id,'LANU-'+Array(5).fill(k.repeat(4)).join('-'),'A'.repeat(22),'test']);
 await db.query('insert into lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,$3),($2,$1,$4)',[a,b,'LANU-'+Array(5).fill('BBBB').join('-'),'LANU-'+Array(5).fill('AAAA').join('-')]);
});
after(async()=>db.close());
test('call table and RPCs expose no anonymous/authenticated access',async()=>{
 assert.equal((await db.query("select relrowsecurity from pg_class where relname='lanu_call_signals'")).rows[0].relrowsecurity,true);
 for(const role of ['anon','authenticated']){
  assert.equal((await db.query("select has_table_privilege($1,'public.lanu_call_signals','SELECT,INSERT,UPDATE,DELETE') as ok",[role])).rows[0].ok,false);
  for(const fn of ['lanu_call_poll(text)','lanu_call_send(text,text,uuid,uuid,text)','lanu_call_ack(text,uuid[])'])
   assert.equal((await db.query('select has_function_privilege($1,$2,\'EXECUTE\') as ok',[role,'public.'+fn])).rows[0].ok,false);
 }
});
test('new call requires mutual contacts and recently active supported peer',async()=>{
 let res=await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',[a,b,randomUUID(),randomUUID(),cipher]);
 assert.equal(res.rows[0].v.unavailable,true);
 await db.query('select lanu_call_poll($1)',[b]);
 res=await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',[c,b,randomUUID(),randomUUID(),cipher]);
 assert.equal(res.rows[0].v.unavailable,true);
});
test('signals are isolated from chat, idempotent, recipient-scoped and short lived',async()=>{
 await db.query('select lanu_call_poll($1)',[b]);
 const id=randomUUID(),call=randomUUID(),args=[a,b,id,call,cipher];
 assert.equal((await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',args)).rows[0].v.accepted,true);
 assert.equal((await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',args)).rows[0].v.accepted,true);
 assert.equal((await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',[a,b,id,call,cipher+'B'])).rows[0].v.conflict,true);
 assert.equal((await db.query('select lanu_call_poll($1) as v',[c])).rows[0].v.length,0);
 assert.equal((await db.query('select count(*) from lanu_messages')).rows[0].count,0);
 await db.query('select lanu_call_ack($1,$2::uuid[])',[c,[id]]);
 assert.equal((await db.query('select lanu_call_poll($1) as v',[b])).rows[0].v.length,1);
 await db.query('select lanu_call_ack($1,$2::uuid[])',[b,[id]]);
 assert.equal((await db.query('select lanu_call_poll($1) as v',[b])).rows[0].v.length,0);
 await db.query("update lanu_call_signals set expires_at=now()-interval '1 second' where id=$1",[id]);
 await db.query('select lanu_call_poll($1)',[b]);
 assert.equal((await db.query('select count(*) from lanu_call_signals where id=$1',[id])).rows[0].count,0);
});
test('server-side sender budget prevents unbounded signaling growth',async()=>{
 await db.query('select lanu_call_poll($1)',[b]);
 for(let n=0;n<24;n++) assert.equal((await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',[a,b,randomUUID(),randomUUID(),cipher])).rows[0].v.accepted,true);
 assert.equal((await db.query('select lanu_call_send($1,$2,$3,$4,$5) as v',[a,b,randomUUID(),randomUUID(),cipher])).rows[0].v.limited,true);
});
