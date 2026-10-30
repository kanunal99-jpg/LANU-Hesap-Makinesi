import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

/**
 * ALL rows below are disposable synthetic PGlite fixtures.
 * This never contacts the user's live Supabase database, deletes a
 * production user or enables the staged Edge deletion feature flag.
 */
const alice = 'a'.repeat(64), bob = 'b'.repeat(64), carol = 'c'.repeat(64);
const code = x => 'LANU-' + Array(5).fill(x.repeat(4)).join('-');
const migrations = [
  '20261019073855_persistent_user_code_messaging.sql',
  '20261024105720_private_media_grants_and_quota.sql',
  '20261025123907_private_call_signaling.sql',
  '20261026091500_call_only_push_opt_in.sql',
  '20261026134500_invalidate_expired_fcm_token.sql',
  '20261027174242_private_message_wake_opt_in_20261027.sql'
];
const relations = [
  'lanu_identities','lanu_contacts','lanu_messages','lanu_events',
  'lanu_nonces','lanu_media_objects','lanu_call_signals',
  'lanu_call_wake_devices','lanu_message_wake_devices',
  'lanu_message_wake_claims'
];

async function createFixture() {
  const db = new PGlite();
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
    for (const name of migrations) {
      await db.exec(await readFile(
        new URL('../../supabase/migrations/' + name, import.meta.url), 'utf8'
      ));
    }
    await db.exec(await readFile(
      new URL('../staged/lanu_account_erasure_v1.sql', import.meta.url), 'utf8'
    ));
    await db.query(`
      insert into public.lanu_identities(id,code,nonce,public_key)
      values($1,$2,$7,$8),($3,$4,$7,$8),($5,$6,$7,$8)
    `, [alice, code('A'), bob, code('B'), carol, code('C'), 'A'.repeat(22), 'synthetic-public-key']);
    await db.query(`
      insert into public.lanu_contacts(owner_id,peer_id,code_at_add)
      values($1,$2,$3),($2,$1,$4),($2,$5,$6),($5,$2,$7)
    `, [alice,bob,code('B'),code('A'),carol,code('C'),code('B')]);
    const shared = randomUUID(), unrelated = randomUUID();
    await db.query(`
      insert into public.lanu_messages(id,sender_id,recipient_id,content)
      values($1,$2,$3,$4),($5,$3,$6,$7)
    `, [shared,alice,bob,'lanu2:'+'A'.repeat(64),unrelated,carol,'lanu2:'+'B'.repeat(64)]);
    await db.query('insert into public.lanu_nonces(owner_id,request_id) values($1,$2)',
      [alice,randomUUID()]);
    await db.query(`
      insert into public.lanu_call_signals(id,call_id,sender_id,recipient_id,content)
      values($1,$2,$3,$4,$5),($6,$7,$4,$8,$9)
    `, [randomUUID(),randomUUID(),alice,bob,'lanu2:'+'C'.repeat(64),
         randomUUID(),randomUUID(),carol,'lanu2:'+'D'.repeat(64)]);
    await db.query(`
      insert into public.lanu_call_wake_devices(owner_id,fcm_token)
      values($1,$2),($3,$4)
    `, [alice,'A'.repeat(48),bob,'B'.repeat(48)]);
    await db.query(`
      insert into public.lanu_message_wake_devices(owner_id,fcm_token)
      values($1,$2),($3,$4)
    `, [alice,'M'.repeat(48),bob,'N'.repeat(48)]);
    await db.query(`
      insert into public.lanu_message_wake_claims(message_id,sender_id,recipient_id)
      values($1,$2,$3),($4,$3,$5)
    `, [shared,alice,bob,unrelated,carol]);
    return {db,shared,unrelated};
  } catch (e) { await db.close(); throw e; }
}

async function count(db,table,where='',params=[]) {
  const r = await db.query(
    'select count(*)::int as n from public.'+table+(where ? ' where '+where : ''), params);
  return r.rows[0].n;
}
async function fingerprint(db) {
  return Object.fromEntries(await Promise.all(relations.map(
    async name => [name, await count(db,name)]
  )));
}

test('signed-owner server erasure is atomic and never removes unrelated peer identity, calls or FCM', async () => {
  const {db,shared,unrelated} = await createFixture();
  try {
    assert.equal(await count(db,'lanu_identities'),3);
    assert.equal(await count(db,'lanu_events'),4);
    assert.equal(await count(db,'lanu_message_wake_claims'),2);
    const res = await db.query(
      'select public.lanu_delete_account_v1($1,$2) as result', [alice,code('A')]);
    assert.equal(res.rows[0].result.deleted,true);

    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_identities'),2);
    assert.equal(await count(db,'lanu_contacts','owner_id=$1 or peer_id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_messages','sender_id=$1 or recipient_id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_events','message_id=$1',[shared]),0);
    assert.equal(await count(db,'lanu_message_wake_claims','message_id=$1',[shared]),0);
    assert.equal(await count(db,'lanu_call_wake_devices','owner_id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_message_wake_devices','owner_id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_nonces','owner_id=$1',[alice]),0);
    assert.equal(await count(db,'lanu_call_signals','sender_id=$1 or recipient_id=$1',[alice]),0);

    // Bob/Carol retain their own identity, mutual contact, call signal,
    // message notification consent, unrelated encrypted message and events.
    assert.equal(await count(db,'lanu_contacts'),2);
    assert.equal(await count(db,'lanu_messages','id=$1',[unrelated]),1);
    assert.equal(await count(db,'lanu_events','message_id=$1',[unrelated]),2);
    assert.equal(await count(db,'lanu_message_wake_claims','message_id=$1',[unrelated]),1);
    assert.equal(await count(db,'lanu_call_wake_devices','owner_id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_message_wake_devices','owner_id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_call_signals'),1);

    const retry = await db.query(
      'select public.lanu_delete_account_v1($1,$2) as result', [alice,code('A')]);
    assert.equal(retry.rows[0].result.deleted,false,
      'A nonexistent user must not be reported as successfully deleted');
  } finally { await db.close(); }
});

test('incomplete private-media Storage cleanup blocks ALL account deletion and rolls back', async () => {
  const {db} = await createFixture();
  try {
    await db.query(`
      insert into public.lanu_media_objects(
        file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256,state
      ) values($1,$2,$3,$4,$5,$6,$7)
    `, ['f'.repeat(32),randomUUID(),alice,bob,512,'e'.repeat(64),'ready']);
    const before = await fingerprint(db);
    await assert.rejects(db.query(
      'select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]),
      /media cleanup not verified/i);
    assert.deepEqual(await fingerprint(db),before);
    assert.equal(await count(db,'lanu_call_wake_devices','owner_id=$1',[alice]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
    // Even an altered/expired owner code cannot delete a different user.
    const wrong = await db.query(
      'select public.lanu_delete_account_v1($1,$2) as result',[alice,code('B')]);
    assert.equal(wrong.rows[0].result.deleted,false);
    assert.deepEqual(await fingerprint(db),before);
  } finally { await db.close(); }
});

test('unknown private-media cleanup state fails closed even under future nullable schema drift', async () => {
  const {db} = await createFixture();
  try {
    // Current schema has NOT NULL. Simulate a later migration or import that
    // unexpectedly allows SQL NULL. SQL three-valued logic must not treat
    // UNKNOWN as verified Storage deletion.
    await db.exec('alter table public.lanu_media_objects alter column state drop not null');
    const fileId = 'a'.repeat(32);
    await db.query(`
      insert into public.lanu_media_objects(
        file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256,state
      ) values($1,$2,$3,$4,$5,$6,$7)
    `, [fileId,randomUUID(),alice,bob,512,'e'.repeat(64),null]);
    const before = await fingerprint(db);
    await assert.rejects(
      db.query('select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]),
      /media cleanup not verified/i
    );
    assert.deepEqual(await fingerprint(db),before);
    assert.equal(await count(db,'lanu_media_objects','file_id=$1',[fileId]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
  } finally { await db.close(); }
});

test('account erasure RPC is denied to anonymous or authenticated mobile clients', async () => {
  const {db} = await createFixture();
  try {
    for (const role of ['anon','authenticated']) {
      const grant = await db.query(
        "select has_function_privilege($1,'public.lanu_delete_account_v1(text,text)','EXECUTE') as allowed",
        [role]
      );
      assert.equal(grant.rows[0].allowed,false);
      await db.exec('set role '+role);
      try {
        await assert.rejects(db.query(
          'select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]));
      } finally { await db.exec('reset role'); }
    }
    const service = await db.query(
      "select has_function_privilege('service_role','public.lanu_delete_account_v1(text,text)','EXECUTE') as allowed");
    assert.equal(service.rows[0].allowed,true);
    assert.equal(await count(db,'lanu_identities'),3);
  } finally { await db.close(); }
});


test('malformed or ambiguous owner identifiers and codes fail closed without mutating any account', async () => {
  const {db} = await createFixture();
  try {
    const before = await fingerprint(db);
    const invalid = [
      [null, code('A')],
      ['', code('A')],
      [alice.toUpperCase(), code('A')],
      [alice + "' OR TRUE --", code('A')],
      [alice, null],
      [alice, ''],
      [alice, code('A').toLowerCase()],
      [alice, code('A') + ' ']
    ];
    for (const [owner, expectedCode] of invalid) {
      await assert.rejects(
        db.query('select public.lanu_delete_account_v1($1,$2)', [owner, expectedCode]),
        /Invalid account deletion request/i
      );
      assert.deepEqual(await fingerprint(db), before,
        'invalid identity/code must not mutate even a peer-owned row');
    }
    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
  } finally { await db.close(); }
});

test('same-count FK column replacement cannot bypass reviewed erasure schema', async () => {
  const {db} = await createFixture();
  try {
    // Old per-table FK counting would miss this change: the same child,
    // parent and NO ACTION mode still have exactly two constraints, but
    // one now references a DIFFERENT identity column.
    await db.exec(`
      alter table public.lanu_contacts
        drop constraint lanu_contacts_peer_id_fkey;
      alter table public.lanu_contacts
        add constraint lanu_contacts_code_at_add_fkey
        foreign key (code_at_add) references public.lanu_identities(code);
    `);
    const before = await fingerprint(db);
    await assert.rejects(
      db.query('select public.lanu_delete_account_v1($1,$2)', [alice,code('A')]),
      /Account schema review required before deletion/i
    );
    assert.deepEqual(await fingerprint(db),before,
      'swapping FK columns must not delete identity, contacts or peer rows');
    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_contacts'),4);
  } finally { await db.close(); }
});

test('future FK schema drift rejects erasure and preserves every existing row', async () => {
  const {db} = await createFixture();
  try {
    // Simulate a later migration introducing account-linked records that
    // the reviewed deletion function has not yet learned to clean up.
    await db.exec(`
      create table public.lanu_future_account_data (
        owner_id text not null references public.lanu_identities(id),
        payload text not null
      );
    `);
    await db.query(
      'insert into public.lanu_future_account_data(owner_id,payload) values($1,$2)',
      [alice,'synthetic-future-record']);
    const before = await fingerprint(db);
    await assert.rejects(
      db.query('select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]),
      /Account schema review required before deletion/i
    );
    assert.deepEqual(await fingerprint(db),before,
      'new account-linked FK must abort every part of the deletion transaction');
    assert.equal(await count(db,'lanu_future_account_data','owner_id=$1',[alice]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
  } finally { await db.close(); }
});

test('unreviewed CASCADE foreign keys cannot silently erase future peer data', async () => {
  // A new CASCADE child would NOT cause the final parent DELETE to fail.
  // The staged RPC must reject changed FK inventories before touching rows.
  for (const parent of ['identity', 'message']) {
    const {db,shared} = await createFixture();
    try {
      if (parent === 'identity') {
        await db.exec(`
          create table public.lanu_future_peer_data(
            owner_id text not null references public.lanu_identities(id)
              on delete cascade, payload text not null
          )
        `);
        await db.query(
          'insert into public.lanu_future_peer_data(owner_id,payload) values($1,$2)',
          [alice,'synthetic-peer-owned-record']
        );
      } else {
        await db.exec(`
          create table public.lanu_future_peer_data(
            message_id uuid not null references public.lanu_messages(id)
              on delete cascade, payload text not null
          )
        `);
        await db.query(
          'insert into public.lanu_future_peer_data(message_id,payload) values($1,$2)',
          [shared,'synthetic-peer-inbox-record']
        );
      }
      const before = await fingerprint(db);
      await assert.rejects(
        db.query('select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]),
        /Account schema review required/i,
        'A newly cascading ' + parent + ' FK must require independent review'
      );
      assert.deepEqual(await fingerprint(db),before);
      assert.equal(await count(db,'lanu_future_peer_data'),1);
      assert.equal(await count(db,'lanu_identities','id=$1',[alice]),1);
      assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
    } finally { await db.close(); }
  }
});

test('another pair\'s unpurged media does not authorize or block unrelated account deletion', async () => {
  const {db} = await createFixture();
  try {
    const fileId = 'd'.repeat(32);
    await db.query(`
      insert into public.lanu_media_objects(
        file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256,state
      ) values($1,$2,$3,$4,$5,$6,$7)
    `, [fileId,randomUUID(),carol,bob,512,'e'.repeat(64),'ready']);
    const result = await db.query(
      'select public.lanu_delete_account_v1($1,$2) as result',[alice,code('A')]);
    assert.equal(result.rows[0].result.deleted,true);
    assert.equal(await count(db,'lanu_media_objects','file_id=$1',[fileId]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[carol]),1);
  } finally { await db.close(); }
});

test('verified purged synthetic media permits atomic metadata cleanup without affecting peers', async () => {
  const {db} = await createFixture();
  try {
    const fileId = 'f'.repeat(32);
    await db.query(`
      insert into public.lanu_media_objects(
        file_id,message_id,sender_id,recipient_id,cipher_bytes,cipher_sha256,state
      ) values($1,$2,$3,$4,$5,$6,$7)
    `, [fileId,randomUUID(),alice,bob,512,'e'.repeat(64),'purged']);
    const result = await db.query(
      'select public.lanu_delete_account_v1($1,$2) as result',[alice,code('A')]);
    assert.equal(result.rows[0].result.deleted,true);
    assert.equal(await count(db,'lanu_media_objects','file_id=$1',[fileId]),0);
    assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[carol]),1);
    assert.equal(await count(db,'lanu_call_wake_devices','owner_id=$1',[bob]),1);
    assert.equal(await count(db,'lanu_message_wake_devices','owner_id=$1',[bob]),1);
  } finally { await db.close(); }
});


test('Storage metadata is row-locked BEFORE cleanup verification and deletion', async () => {
  // This is a structural concurrency regression: PGlite uses one local
  // connection and cannot prove a two-connection PostgreSQL race. Real
  // multi-session/Storage worker acceptance remains mandatory before rollout.
  const sql = await readFile(
    new URL('../staged/lanu_account_erasure_v1.sql', import.meta.url), 'utf8'
  );
  const lock = sql.indexOf('perform 1 from public.lanu_media_objects');
  const stateCheck = sql.indexOf("and state is distinct from 'purged'");
  const metadataDelete = sql.indexOf('delete from public.lanu_media_objects');
  assert.ok(lock >= 0 && stateCheck > lock && metadataDelete > stateCheck,
    'All owner media rows must be locked before checking or deleting cleanup metadata');
  assert.match(sql.slice(lock, stateCheck),
    /where sender_id = p_owner or recipient_id = p_owner\s+for update\s*;/i,
    'Lock must cover every existing sender/recipient row, including purged rows');
});

test('all ten erased parent tables are covered by the schema drift guard', async () => {
  const sql = await readFile(
    new URL('../staged/lanu_account_erasure_v1.sql', import.meta.url), 'utf8'
  );
  const inventory = sql.slice(sql.indexOf('where c.contype ='), sql.indexOf('group by c.confrelid'));
  for (const name of relations) {
    assert.ok(inventory.includes("'public."+name+"'::regclass"),
      'Missing schema drift guard for '+name);
  }
});

test('new cascading call-signal child requires review and preserves peer rows', async () => {
  const {db} = await createFixture();
  try {
    await db.exec(`create table public.lanu_future_receipts(
      signal_id uuid references public.lanu_call_signals(id) on delete cascade,
      receipt text not null)`);
    const signal = await db.query(
      'select id from public.lanu_call_signals where sender_id=$1', [alice]);
    await db.query('insert into public.lanu_future_receipts values($1,$2)',
      [signal.rows[0].id,'synthetic-receipt']);
    const before = await fingerprint(db);
    await assert.rejects(
      db.query('select public.lanu_delete_account_v1($1,$2)',[alice,code('A')]),
      /Account schema review required/i);
    assert.deepEqual(await fingerprint(db),before);
    assert.equal(await count(db,'lanu_future_receipts'),1);
    assert.equal(await count(db,'lanu_identities','id=$1',[bob]),1);
  } finally { await db.close(); }
});
