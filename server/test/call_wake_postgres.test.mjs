import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { randomUUID } from 'node:crypto';

let db;
const alice='a'.repeat(64), bob='b'.repeat(64), stranger='c'.repeat(64);
const cipher='lanu2:'+'A'.repeat(60), token='ABC_-:abcdef123456789012345678901234567890';

before(async()=>{
  db=new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  for(const name of ['20261019073855_persistent_user_code_messaging.sql',
    '20261025123907_private_call_signaling.sql',
    '20261026091500_call_only_push_opt_in.sql']){
    await db.exec(await readFile(new URL('../../supabase/migrations/'+name,import.meta.url),'utf8'));
  }
  for(const [id,letter] of [[alice,'A'],[bob,'B'],[stranger,'C']]){
    await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',
      [id,'LANU-'+Array(5).fill(letter.repeat(4)).join('-'),'A'.repeat(22),'test']);
  }
  await db.query('insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,$3),($2,$1,$4)',
    [alice,bob,'LANU-'+Array(5).fill('BBBB').join('-'),'LANU-'+Array(5).fill('AAAA').join('-')]);
});
after(async()=>db?.close());

test('FCM tokens are restricted to service role; public client cannot call wake RPCs',async()=>{
  assert.equal((await db.query("select relrowsecurity from pg_class where relname='lanu_call_wake_devices'")).rows[0].relrowsecurity,true);
  for(const role of ['anon','authenticated']){
    assert.equal((await db.query("select has_table_privilege($1,'public.lanu_call_wake_devices','SELECT,INSERT,UPDATE,DELETE') as allowed",[role])).rows[0].allowed,false);
    for(const fn of ['lanu_call_wake_register(text,text)','lanu_call_send_wake(text,text,uuid,uuid,text)']){
      assert.equal((await db.query('select has_function_privilege($1,$2,\'EXECUTE\') as allowed',
        [role,'public.'+fn])).rows[0].allowed,false);
    }
  }
});
test('offline only when neither foreground present nor explicitly registered push',async()=>{
  let response=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [alice,bob,randomUUID(),randomUUID(),cipher]);
  assert.equal(response.rows[0].v.unavailable,true);
  response=await db.query('select lanu_call_wake_register($1,$2) as v',[bob,token]);
  assert.equal(response.rows[0].v.registered,true);
  response=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [stranger,bob,randomUUID(),randomUUID(),cipher]);
  assert.equal(response.rows[0].v.unavailable,true,'Untrusted peer cannot wake');
});
test('registered recipient gets single short-lived encrypted INVITE with idempotent wake',async()=>{
  const args=[alice,bob,randomUUID(),randomUUID(),cipher];
  let response=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',args);
  assert.equal(response.rows[0].v.accepted,true);
  assert.equal(response.rows[0].v.created,true);
  assert.equal(response.rows[0].v.wake_token,token);
  response=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',args);
  assert.equal(response.rows[0].v.accepted,true);
  assert.equal(response.rows[0].v.created,false);
  assert.equal(response.rows[0].v.wake_token,undefined);
  response=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [alice,bob,args[2],args[3],cipher+'B']);
  assert.equal(response.rows[0].v.conflict,true);
  response=await db.query('select count(*) as n from public.lanu_call_signals where call_id=$1',[args[3]]);
  assert.equal(Number(response.rows[0].n),1);
});
test('opted-in recipient in private area remains wake-eligible but client suppresses duplicate UI',async()=>{
  await db.query('select public.lanu_call_poll($1)',[alice]);
  const r=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [bob,alice,randomUUID(),randomUUID(),cipher]);
  assert.equal(r.rows[0].v.accepted,true,'Existing active calls continue without remote wake');
});
test('rate limiting and remote revoke prevent push abuse and preserve old data',async()=>{
  const r=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [alice,bob,randomUUID(),randomUUID(),cipher]);
  assert.equal(r.rows[0].v.limited,true,'Repeated wakes to the same peer must be throttled');
  assert.equal((await db.query('select lanu_call_wake_register($1,$2) as v',[bob,null])).rows[0].v.registered,true);
  assert.equal((await db.query('select count(*) as n from public.lanu_call_wake_devices where owner_id=$1',[bob])).rows[0].n,0);
  await db.query("update public.lanu_identities set call_seen_at=now()-interval '5 minutes' where id=$1",[bob]);
  const after=await db.query('select lanu_call_send_wake($1,$2,$3,$4,$5) as v',
    [alice,bob,randomUUID(),randomUUID(),cipher]);
  assert.equal(after.rows[0].v.unavailable,true);
  assert.equal((await db.query('select count(*) as n from public.lanu_messages')).rows[0].n,0);
});
