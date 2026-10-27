import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

/**
 * FAIL-CLOSED prerequisite for LANU account-deletion design (#224).
 * Every statement below runs against disposable IN-MEMORY PGlite ONLY.
 * Never imports production rows, keys, device tokens or owner identities.
 * This does NOT implement account deletion or grant public DELETE.
 */
let db;
const alice = 'a'.repeat(64);
const bob = 'b'.repeat(64);
const msgId = randomUUID();

before(async () => {
  db = new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  const migration = await readFile(
    new URL('../../supabase/migrations/20261019073855_persistent_user_code_messaging.sql', import.meta.url),
    'utf8'
  );
  await db.exec(migration);
  for (const [id, prefix] of [[alice, 'A'], [bob, 'B']]) {
    await db.query(
      'insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',
      [id, 'LANU-' + Array(5).fill(prefix.repeat(4)).join('-'), 'A'.repeat(22), 'synthetic-key']
    );
  }
  await db.query(
    'insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,$3),($2,$1,$3)',
    [alice, bob, 'LANU-AAAA-AAAA-AAAA-AAAA-AAAA']
  );
  await db.query(
    'insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',
    [msgId, alice, bob, 'lanu2:' + 'A'.repeat(48)]
  );
  await db.query(
    'insert into public.lanu_nonces(owner_id,request_id) values($1,$2)',
    [alice, randomUUID()]
  );
});

after(async () => { if (db) await db.close(); });

async function counts() {
  const tables = ['lanu_identities', 'lanu_contacts', 'lanu_messages', 'lanu_events', 'lanu_nonces'];
  return Object.fromEntries(await Promise.all(tables.map(async table => {
    const result = await db.query('select count(*)::int as n from public.' + table);
    return [table, result.rows[0].n];
  })));
}

test('directly deleting a LANU account cannot silently orphan peer data', async () => {
  const beforeDelete = await counts();
  assert.equal(beforeDelete.lanu_identities, 2);
  assert.equal(beforeDelete.lanu_contacts, 2);
  assert.equal(beforeDelete.lanu_messages, 1);
  assert.equal(beforeDelete.lanu_events, 2);
  assert.equal(beforeDelete.lanu_nonces, 1);

  // A naive DELETE must raise FK error, NOT cascade into the peer's records.
  await assert.rejects(db.query('delete from public.lanu_identities where id=$1', [alice]));
  assert.deepEqual(await counts(), beforeDelete);
  assert.equal((await db.query('select recipient_id from public.lanu_messages where id=$1', [msgId])).rows[0].recipient_id, bob);
});

test('core messaging identity dependencies are NO ACTION, not implicit CASCADE', async () => {
  const response = await db.query(`
    select conrelid::regclass::text as child, confdeltype as delete_action
    from pg_constraint
    where contype='f' and confrelid='public.lanu_identities'::regclass
  `);
  const references = response.rows;
  assert.equal(references.length, 6);
  assert.ok(references.every(row => row.delete_action === 'a'),
    'Deleting an identity cannot automatically erase contacts, messages, events or nonces');
  const names = new Set(references.map(row => row.child.split('.').at(-1)));
  assert.deepEqual([...names].sort(),
    ['lanu_contacts', 'lanu_events', 'lanu_messages', 'lanu_nonces']);
  const messageEvents = await db.query(`
    select confdeltype as delete_action from pg_constraint
    where contype='f' and conrelid='public.lanu_events'::regclass
      and confrelid='public.lanu_messages'::regclass
  `);
  assert.equal(messageEvents.rows.length, 1);
  assert.equal(messageEvents.rows[0].delete_action, 'a');
});

test('public client roles cannot turn deletion requests into direct database deletes', async () => {
  for (const role of ['anon', 'authenticated']) {
    const grants = await db.query(
      "select has_table_privilege($1,'public.lanu_identities','DELETE') as can_delete",
      [role]
    );
    assert.equal(grants.rows[0].can_delete, false);
    await db.exec('set role ' + role);
    try {
      await assert.rejects(db.query('delete from public.lanu_identities where id=$1', [alice]));
    } finally {
      await db.exec('reset role');
    }
  }
  assert.equal((await counts()).lanu_identities, 2);
});
