import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

const first = 'd'.repeat(64);
const second = 'e'.repeat(64);
const content = 'lanu2:' + 'A'.repeat(40);

async function fixture() {
  const db = new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  const base = await readFile(new URL('../../supabase/migrations/20261019073855_persistent_user_code_messaging.sql', import.meta.url), 'utf8');
  const migration = await readFile(new URL('../../supabase/migrations/20261021160108_safe_relay_retention_20261021.sql', import.meta.url), 'utf8');
  await db.exec(base);
  await db.exec(migration);
  for (const [id, letter] of [[first, 'D'], [second, 'E']]) {
    await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values ($1,$2,$3,$4)',
      [id, 'LANU-' + Array(5).fill(letter.repeat(4)).join('-'), 'A'.repeat(22), 'test']);
  }
  return db;
}

async function makeMessage(db, { age = '100 days', deleted = false, eventAcknowledged = true } = {}) {
  const id = randomUUID();
  await db.query("insert into public.lanu_messages(id,sender_id,recipient_id,content,created_at) values ($1,$2,$3,$4,now()-$5::interval)",
    [id, first, second, content, age]);
  if (deleted) await db.query('update public.lanu_messages set deleted=true where id=$1', [id]);
  if (eventAcknowledged) await db.query(
    "update public.lanu_events set acked=true,created_at=now()-interval '40 days' where message_id=$1", [id]);
  return id;
}

test('retention removes old ACKed events and stale nonce but never pending events', async () => {
  const db = await fixture();
  try {
    const pending = await makeMessage(db, {eventAcknowledged: false});
    const acked = await makeMessage(db);
    const staleNonce = randomUUID(), recentNonce = randomUUID();
    await db.query("insert into public.lanu_nonces(owner_id,request_id,created_at) values ($1,$2,now()-interval '2 days')",
      [first, staleNonce]);
    await db.query('insert into public.lanu_nonces(owner_id,request_id) values ($1,$2)', [first, recentNonce]);
    await db.exec('set role service_role');
    const result = await db.query('select public.lanu_prune_retained_data(500) as result');
    await db.exec('reset role');
    assert.deepEqual(result.rows[0].result, {events: 2, nonces: 1, deleted_messages: 0});
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_events where message_id=$1', [pending])).rows[0].n), 2);
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_events where message_id=$1', [acked])).rows[0].n), 0);
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_nonces')).rows[0].n), 1);
  } finally { await db.close(); }
});

test('retention never erases live messages, pending receipt events, or recent tombstones', async () => {
  const db = await fixture();
  try {
    const live = await makeMessage(db, { deleted: false });
    const pendingDeleted = await makeMessage(db, { deleted: true, eventAcknowledged: false });
    const recentDeleted = await makeMessage(db, { age: '7 days', deleted: true });
    await db.exec('set role service_role');
    await db.query('select public.lanu_prune_retained_data(500)');
    await db.exec('reset role');
    for (const id of [live, pendingDeleted, recentDeleted]) {
      assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_messages where id=$1', [id])).rows[0].n), 1);
    }
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_events where message_id=$1', [pendingDeleted])).rows[0].n), 4);
  } finally { await db.close(); }
});

test('explicit old deleted messages expire only once all snapshots were acknowledged and aged', async () => {
  const db = await fixture();
  try {
    const id = await makeMessage(db, {deleted: true});
    await db.exec('set role service_role');
    const result = (await db.query('select public.lanu_prune_retained_data(500) as result')).rows[0].result;
    assert.equal(result.deleted_messages, 1);
    assert.equal(result.events, 4);
    await db.exec('reset role');
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_messages where id=$1', [id])).rows[0].n), 0);
    assert.equal(Number((await db.query('select count(*)::int as n from public.lanu_events where message_id=$1', [id])).rows[0].n), 0);
  } finally { await db.close(); }
});

test('bounded batches work incrementally and invalid retention requests are refused', async () => {
  const db = await fixture();
  try {
    await makeMessage(db);
    await makeMessage(db);
    await db.exec('set role service_role');
    assert.equal((await db.query('select public.lanu_prune_retained_data(1) as result')).rows[0].result.events, 1);
    assert.equal((await db.query('select public.lanu_prune_retained_data(1) as result')).rows[0].result.events, 1);
    for (const invalid of [0, 5001, -1]) {
      await assert.rejects(db.query('select public.lanu_prune_retained_data($1)', [invalid]));
    }
    await db.exec('reset role');
  } finally { await db.close(); }
});

test('anonymous and authenticated roles have no right to invoke retention', async () => {
  const db = await fixture();
  try {
    for (const role of ['anon', 'authenticated']) {
      const r = await db.query("select has_function_privilege($1,'public.lanu_prune_retained_data(integer)','EXECUTE') as granted", [role]);
      assert.equal(r.rows[0].granted, false);
    }
    const r = await db.query("select has_function_privilege('service_role','public.lanu_prune_retained_data(integer)','EXECUTE') as granted");
    assert.equal(r.rows[0].granted, true);
  } finally { await db.close(); }
});
