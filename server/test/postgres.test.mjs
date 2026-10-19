import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { randomUUID } from 'node:crypto';

let db;
const alice = 'a'.repeat(64), bob = 'b'.repeat(64), outsider = 'c'.repeat(64);
before(async () => {
  db = new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  const migration = await readFile(new URL('../../supabase/migrations/20261019071736_persistent_user_code_messaging.sql', import.meta.url), 'utf8');
  await db.exec(migration);
  for (const [id, letter] of [[alice, 'A'], [bob, 'B'], [outsider, 'C']]) {
    await db.query('insert into public.lanu_identities(id, code, nonce, public_key) values ($1,$2,$3,$4)', [id, 'LANU-' + Array(5).fill(letter.repeat(4)).join('-'), 'A'.repeat(22), 'test-key']);
  }
});
after(async () => { await db.close(); });

test('all exposed messaging tables have RLS and no direct anonymous/authenticated grants', async () => {
  const { rows } = await db.query("select relname, relrowsecurity from pg_class where relname in ('lanu_identities','lanu_contacts','lanu_messages','lanu_events','lanu_nonces')");
  assert.equal(rows.length, 5);
  assert.ok(rows.every(row => row.relrowsecurity));
  for (const role of ['anon', 'authenticated']) {
    for (const table of rows.map(row => row.relname)) {
      const result = await db.query('select has_table_privilege($1,$2,$3) as allowed', [role, 'public.' + table, 'SELECT,INSERT,UPDATE,DELETE']);
      assert.equal(result.rows[0].allowed, false);
    }
    const result = await db.query("select has_function_privilege($1, 'public.lanu_ack(text,uuid[],uuid[])', 'EXECUTE') as allowed", [role]);
    assert.equal(result.rows[0].allowed, false);
  }
});
test('trigger snapshots are durable and delivery/read only change on the receiving device ack', async () => {
  const id = randomUUID();
  await db.exec('set role service_role');
  try {
    await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values ($1,$2,$3,$4)', [id, alice, bob, 'lanu2:' + 'A'.repeat(40)]);
    const events = (await db.query('select * from public.lanu_events where message_id=$1', [id])).rows;
    assert.equal(events.length, 2);
    assert.ok(events.every(event => event.snapshot.status === 'SENT'));
    assert.equal((await db.query('select delivered_at,read_at from public.lanu_messages where id=$1', [id])).rows[0].delivered_at, null);
    const receivingEvent = events.find(event => event.owner_id === bob);
    await db.query('select public.lanu_ack($1,$2::uuid[],$3::uuid[])', [outsider, [receivingEvent.id], [id]]);
    assert.equal((await db.query('select acked from public.lanu_events where id=$1', [receivingEvent.id])).rows[0].acked, false);
    assert.equal((await db.query('select delivered_at from public.lanu_messages where id=$1', [id])).rows[0].delivered_at, null);
    await db.query('select public.lanu_ack($1,$2::uuid[],$3::uuid[])', [bob, [receivingEvent.id], []]);
    const delivered = (await db.query('select * from public.lanu_messages where id=$1', [id])).rows[0];
    assert.ok(delivered.delivered_at); assert.equal(delivered.read_at, null); assert.equal(Number(delivered.revision), 2);
    await db.query('select public.lanu_ack($1,$2::uuid[],$3::uuid[])', [bob, [], [id]]);
    const read = (await db.query('select * from public.lanu_messages where id=$1', [id])).rows[0];
    assert.ok(read.read_at); assert.equal(Number(read.revision), 3);
    const latest = (await db.query('select snapshot from public.lanu_events where owner_id=$1 and message_id=$2 order by created_at desc limit 1', [alice, id])).rows[0].snapshot;
    assert.equal(latest.status, 'READ');
    await db.query('select public.lanu_ack($1,$2::uuid[],$3::uuid[])', [bob, [receivingEvent.id], [id]]);
    assert.equal(Number((await db.query('select revision from public.lanu_messages where id=$1', [id])).rows[0].revision), 3, 'duplicate acknowledgement must not produce endless status events');
  } finally { await db.exec('reset role'); }
});
test('invalid inserts create neither a message nor orphan event', async () => {
  const id = randomUUID();
  await assert.rejects(db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values ($1,$2,$2,$3)', [id, alice, 'lanu2:' + 'A'.repeat(40)]));
  assert.equal((await db.query('select id from public.lanu_events where message_id=$1', [id])).rows.length, 0);
});
test('editing immutable ownership fields cannot move a message to another account', async () => {
  const id = randomUUID();
  await db.query('insert into public.lanu_messages(id,sender_id,recipient_id,content) values ($1,$2,$3,$4)', [id, alice, bob, 'lanu2:' + 'A'.repeat(40)]);
  await db.query('update public.lanu_messages set sender_id=$1,recipient_id=$2 where id=$3', [outsider, alice, id]);
  const row = (await db.query('select sender_id,recipient_id from public.lanu_messages where id=$1', [id])).rows[0];
  assert.equal(row.sender_id, alice); assert.equal(row.recipient_id, bob);
});
test('nonce uniqueness survives separate executions and rejects replay durably', async () => {
  const nonce = randomUUID();
  await db.query('insert into public.lanu_nonces(owner_id,request_id) values ($1,$2)', [alice, nonce]);
  await assert.rejects(db.query('insert into public.lanu_nonces(owner_id,request_id) values ($1,$2)', [alice, nonce]));
  assert.equal((await db.query('select request_id from public.lanu_nonces where owner_id=$1 and request_id=$2', [alice, nonce])).rows.length, 1);
});


test('atomic nonce RPC blocks replay and enforces an account rate limit', async () => {
  await db.exec('set role service_role');
  try {
    const nonce = randomUUID();
    assert.equal((await db.query('select public.lanu_consume_nonce($1,$2) as value', [outsider, nonce])).rows[0].value.allowed, true);
    assert.equal((await db.query('select public.lanu_consume_nonce($1,$2) as value', [outsider, nonce])).rows[0].value.allowed, false);
    for (let i = 0; i < 119; i++) await db.query('select public.lanu_consume_nonce($1,$2)', [outsider, randomUUID()]);
    const result = (await db.query('select public.lanu_consume_nonce($1,$2) as value', [outsider, randomUUID()])).rows[0].value;
    assert.deepEqual(result, { allowed: false, limited: true });
  } finally { await db.exec('reset role'); }
});
