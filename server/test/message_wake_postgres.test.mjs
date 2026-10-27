import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

let db;
const alice='a'.repeat(64), bob='b'.repeat(64), stranger='c'.repeat(64);
const oldToken='T'.repeat(40), nextToken='U'.repeat(40);
const ciphertext='lanu2:'+'A'.repeat(60);

before(async()=>{
  db = new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  for (const name of [
    '20261019073855_persistent_user_code_messaging.sql',
    '20261027195900_private_message_wake_opt_in.sql'
  ]) await db.exec(await readFile(new URL('../../supabase/migrations/'+name, import.meta.url),'utf8'));
  for(const id of [alice,bob,stranger]) {
    await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',
      [id,'LANU-AAAA-AAAA-AAAA-AAAA-AAAA','A'.repeat(22),'test']);
  }
  await db.query('insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values ($1,$2,$3),($2,$1,$3)',
    [alice,bob,'LANU-AAAA-AAAA-AAAA-AAAA-AAAA']);
});
after(async()=>db?.close());

async function save(sender=alice,recipient=bob) {
  const id=randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',
    [id,sender,recipient,ciphertext]);
  return id;
}
async function claim(sender,recipient,id) {
  return (await db.query('select public.lanu_message_wake_claim($1,$2,$3) as v',
    [sender,recipient,id])).rows[0].v;
}

test('message registry and claim records are RLS protected, restricted to service role',async()=>{
  for (const table of ['lanu_message_wake_devices','lanu_message_wake_claims']) {
    assert.equal((await db.query('select relrowsecurity from pg_class where relname=$1',[table])).rows[0].relrowsecurity,true);
    for(const role of ['anon','authenticated']) {
      const result=await db.query('select has_table_privilege($1,$2,\'SELECT,INSERT,UPDATE,DELETE\') as allowed',
        [role,'public.'+table]);
      assert.equal(result.rows[0].allowed,false);
    }
  }
  for(const role of ['anon','authenticated']) for(const signature of [
    'lanu_message_wake_register(text,text)',
    'lanu_message_wake_claim(text,text,uuid)',
    'lanu_message_wake_invalidate(text,text)'
  ]) {
    const r=await db.query('select has_function_privilege($1,$2,\'EXECUTE\') as allowed',
      [role,'public.'+signature]);
    assert.equal(r.rows[0].allowed,false);
  }
});

test('not opted in: no remote wake even for accepted encrypted message',async()=>{
  const id=await save();
  assert.deepEqual(await claim(alice,bob,id),{claimed:false});
  const count=await db.query('select count(*)::int as n from public.lanu_message_wake_claims');
  assert.equal(count.rows[0].n,0);
});

test('consented message wakes are once-only and cannot cross owners',async()=>{
  assert.equal((await db.query('select public.lanu_message_wake_register($1,$2) as v',[bob,oldToken])).rows[0].v.registered,true);
  const id=await save();
  assert.deepEqual(await claim(stranger,bob,id),{claimed:false});
  assert.deepEqual(await claim(alice,stranger,id),{claimed:false});
  assert.deepEqual(await claim(alice,bob,id),{claimed:true,wake_token:oldToken});
  assert.deepEqual(await claim(alice,bob,id),{claimed:false});
  const wrongId=randomUUID();
  assert.deepEqual(await claim(alice,bob,wrongId),{claimed:false});
  assert.equal((await db.query('select count(*)::int as n from public.lanu_messages where id=$1',[id])).rows[0].n,1);
});

test('rate cap bounds audible notifications without breaking accepted encrypted messages',async()=>{
  // One alert was used by the previous test, so at most five more are allowed.
  let allowed=0;
  for(let i=0;i<9;i++) {
    const id=await save();
    const r=await claim(alice,bob,id);
    if(r.claimed) allowed++;
  }
  assert.ok(allowed<=5);
  const r=await db.query('select count(*)::int as n from public.lanu_messages');
  assert.ok(r.rows[0].n>=11,'All messages remain durable even if notification is suppressed');
});

test('opt-out and token rotation prevent stale Google response from unregistering fresh device',async()=>{
  await db.query('select public.lanu_message_wake_register($1,$2)',[bob,nextToken]);
  assert.deepEqual((await db.query('select public.lanu_message_wake_invalidate($1,$2) as v',
    [bob,oldToken])).rows[0].v,{invalidated:false});
  const token=await db.query('select fcm_token from public.lanu_message_wake_devices where owner_id=$1',[bob]);
  assert.equal(token.rows[0].fcm_token,nextToken);
  assert.deepEqual((await db.query('select public.lanu_message_wake_invalidate($1,$2) as v',
    [stranger,nextToken])).rows[0].v,{invalidated:false});
  assert.equal((await db.query('select public.lanu_message_wake_register($1,$2) as v',[bob,null])).rows[0].v.registered,true);
  assert.equal((await db.query('select count(*)::int as n from public.lanu_message_wake_devices where owner_id=$1',[bob])).rows[0].n,0);
  const id=await save();
  assert.deepEqual(await claim(alice,bob,id),{claimed:false});
});
