import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

/**
 * Account deletion safety against the COMPLETE currently deployed LANU
 * messaging/media/call/FCM schema. Disposable in-memory Postgres only.
 *
 * No Supabase URL/credentials, no remote DELETE, and no real user records.
 * This documents why deleting an identity directly is NOT a deletion feature.
 */
test('complete LANU schema rejects unsafe identity deletion without losing peer data', async () => {
  const db = new PGlite();
  const alice = 'a'.repeat(64), bob = 'b'.repeat(64);
  const messageId = randomUUID();
  const tables = [
    'lanu_identities', 'lanu_contacts', 'lanu_messages', 'lanu_events',
    'lanu_nonces', 'lanu_media_objects', 'lanu_call_signals',
    'lanu_call_wake_devices'
  ];
  try {
    await db.exec(`
      create role anon;
      create role authenticated;
      create role service_role bypassrls;
      create schema storage;
      create table storage.buckets(
        id text primary key, name text not null, public boolean not null,
        file_size_limit bigint, allowed_mime_types text[]
      );
    `);
    // Retention/pg_cron migrations intentionally excluded: they affect
    // scheduling, not these identity foreign keys, and aren't supported by PGlite.
    const migrations = [
      '20261019073855_persistent_user_code_messaging.sql',
      '20261024105720_private_media_grants_and_quota.sql',
      '20261025123907_private_call_signaling.sql',
      '20261026091500_call_only_push_opt_in.sql',
      '20261026134500_invalidate_expired_fcm_token.sql'
    ];
    for (const migration of migrations) {
      const sql = await readFile(
        new URL('../../supabase/migrations/' + migration, import.meta.url), 'utf8'
      );
      await db.exec(sql);
    }

    // Public data API users must not gain raw table access as a "fix" for
    // account deletion; it must stay behind signed, scoped relay operations.
    for (const table of tables) {
      const state = await db.query(
        'select relrowsecurity as rls from pg_class where oid=$1::regclass',
        ['public.' + table]
      );
      assert.equal(state.rows[0].rls, true, table + ' RLS');
      for (const role of ['anon', 'authenticated']) {
        for (const permission of ['SELECT', 'INSERT', 'DELETE']) {
          const result = await db.query(
            "select has_table_privilege($1,$2,$3) as allowed",
            [role, 'public.' + table, permission]
          );
          assert.equal(result.rows[0].allowed, false,
            table + ': unexpected ' + role + ' ' + permission + ' permission');
        }
      }
    }

    // The eight NO ACTION references and three CASCADE references are the
    // current, observable contract. A lone DELETE on identities is unsafe.
    const fks = (await db.query(`
      select conrelid::regclass::text as child, confdeltype as mode
      from pg_constraint
      where contype='f'
        and confrelid='public.lanu_identities'::regclass
    `)).rows.map(row => [
      row.child.replace(/^public\\./, ''), row.mode
    ]);
    const expected = [
      ['lanu_contacts', 'a'], ['lanu_contacts', 'a'],
      ['lanu_messages', 'a'], ['lanu_messages', 'a'],
      ['lanu_events', 'a'], ['lanu_nonces', 'a'],
      ['lanu_media_objects', 'a'], ['lanu_media_objects', 'a'],
      ['lanu_call_signals', 'c'], ['lanu_call_signals', 'c'],
      ['lanu_call_wake_devices', 'c']
    ];
    const canonical = values => values.map(v => v.join(':')).sort();
    assert.deepEqual(canonical(fks), canonical(expected),
      'Foreign-key deletion policy changed; redesign account deletion before release');
    const eventMessageFk = await db.query(`
      select confdeltype as mode from pg_constraint
      where contype='f'
        and conrelid='public.lanu_events'::regclass
        and confrelid='public.lanu_messages'::regclass
    `);
    assert.deepEqual(eventMessageFk.rows.map(x => x.mode), ['a']);

    await db.query(
      'insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4),($5,$6,$3,$4)',
      [alice, 'LANU-' + Array(5).fill('AAAA').join('-'), 'A'.repeat(22), 'fixture',
       bob, 'LANU-' + Array(5).fill('BBBB').join('-')]
    );
    await db.query(
      'insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values($1,$2,$3),($2,$1,$4)',
      [alice, bob, 'LANU-' + Array(5).fill('BBBB').join('-'),
       'LANU-' + Array(5).fill('AAAA').join('-')]
    );
    await db.query(
      'insert into public.lanu_messages(id,sender_id,recipient_id,content) values($1,$2,$3,$4)',
      [messageId, alice, bob, 'lanu2:' + 'A'.repeat(64)]
    );
    await db.query(
      'insert into public.lanu_nonces(owner_id,request_id) values($1,$2)',
      [alice, randomUUID()]
    );
    await db.query(
      'insert into public.lanu_media_objects(file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256) values($1,$2,$3,$4,$5,$6)',
      ['c'.repeat(32), randomUUID(), alice, bob, 1024, 'e'.repeat(64)]
    );
    await db.query(
      'insert into public.lanu_call_signals(id,call_id,sender_id,recipient_id,content) values($1,$2,$3,$4,$5)',
      [randomUUID(), randomUUID(), alice, bob, 'lanu2:' + 'B'.repeat(64)]
    );
    await db.query(
      'insert into public.lanu_call_wake_devices(owner_id,fcm_token) values($1,$2)',
      [bob, 'F'.repeat(48)]
    );

    async function counts() {
      return Object.fromEntries(await Promise.all(tables.map(async table => {
        const result = await db.query(
          'select count(*)::int as n from public.' + table
        );
        return [table, result.rows[0].n];
      })));
    }
    const before = await counts();
    assert.equal(before.lanu_identities, 2);
    assert.equal(before.lanu_contacts, 2);
    assert.equal(before.lanu_messages, 1);
    assert.ok(before.lanu_events > 0);
    assert.equal(before.lanu_media_objects, 1);
    assert.equal(before.lanu_call_signals, 1);
    assert.equal(before.lanu_call_wake_devices, 1);

    // Cascading child deletes must be rolled back with the rejected
    // parent deletion. Neither the peer's ciphertext nor its wake token
    // may be silently removed by a failed account-deletion attempt.
    await assert.rejects(db.query(
      'delete from public.lanu_identities where id=$1', [alice]
    ));
    assert.deepEqual(await counts(), before);
    const peerMessage = await db.query(
      'select recipient_id,content from public.lanu_messages where id=$1',
      [messageId]
    );
    assert.equal(peerMessage.rows[0].recipient_id, bob);
    assert.equal(peerMessage.rows[0].content, 'lanu2:' + 'A'.repeat(64));
    assert.equal((await db.query(
      'select count(*)::int as n from public.lanu_call_wake_devices where owner_id=$1',
      [bob]
    )).rows[0].n, 1);
  } finally {
    await db.close();
  }
});
