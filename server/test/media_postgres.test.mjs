import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

const a = 'a'.repeat(64), b = 'b'.repeat(64), outsider = 'c'.repeat(64), digest = 'd'.repeat(64);
let db;
async function reserve(owner, peer, id, bytes, sha = digest) {
  const result = await db.query('select public.lanu_media_reserve($1,$2,$3,$4,$5) as allowed',
    [owner, peer, id, bytes, sha]);
  return result.rows[0].allowed;
}
before(async () => {
  db = new PGlite();
  await db.exec('create role anon; create role authenticated; create role service_role bypassrls;');
  await db.exec(await readFile(new URL('../../supabase/migrations/20261019073855_persistent_user_code_messaging.sql', import.meta.url), 'utf8'));
  // The in-memory PostgreSQL test fixture has no Supabase Storage extension.
  await db.exec('create schema storage; create table storage.buckets (id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);');
  await db.exec(await readFile(new URL('../sql/lanu_media_v1.sql', import.meta.url), 'utf8'));
  for (const id of [a, b, outsider]) {
    await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',
      [id, 'LANU-' + ['AAAA','AAAA','AAAA','AAAA','AAAA'].join('-'), 'A'.repeat(22), 'dummy'])
      .catch(async () => {
        // Contact-code uniqueness in production; use unique values for test identities.
        const n = { [a]: 'A', [b]: 'B', [outsider]: 'C' }[id];
        await db.query('insert into public.lanu_identities(id,code,nonce,public_key) values($1,$2,$3,$4)',
          [id, 'LANU-' + Array(5).fill(n.repeat(4)).join('-'), 'A'.repeat(22), 'dummy']);
      });
  }
});
after(async () => { if (db) await db.close(); });

test('media table has RLS, client roles have no read/write or quota RPC rights', async () => {
  const policy = (await db.query("select relrowsecurity from pg_class where relname = 'lanu_media'")).rows;
  assert.deepEqual(policy, [{ relrowsecurity: true }]);
  for (const role of ['anon','authenticated']) {
    assert.equal((await db.query("select has_table_privilege($1,'public.lanu_media','SELECT,INSERT,UPDATE,DELETE') as allowed", [role])).rows[0].allowed, false);
    assert.equal((await db.query("select has_function_privilege($1,'public.lanu_media_reserve(text,text,uuid,integer,text)','EXECUTE') as allowed", [role])).rows[0].allowed, false);
  }
  const bucket = (await db.query("select public,file_size_limit,allowed_mime_types from storage.buckets where id='lanu-private-media'")).rows[0];
  assert.equal(bucket.public, false);
  assert.equal(Number(bucket.file_size_limit), 2097152);
  assert.deepEqual(bucket.allowed_mime_types, ['application/octet-stream']);
});

test('reserved file metadata is immutable, bounded and idempotent', async () => {
  const id = randomUUID();
  assert.equal(await reserve(a,b,id,4096), true);
  assert.equal(await reserve(a,b,id,4096), true);
  assert.equal(await reserve(a,b,id,4097), false);
  assert.equal(await reserve(a,outsider,id,4096), false);
  assert.equal(await reserve(a,b,id,4096,'f'.repeat(64)), false);
  assert.equal(await reserve(a,b,randomUUID(),28), false);
  assert.equal(await reserve(a,b,randomUUID(),2097153), false);
  assert.equal((await db.query('select count(*)::integer as n from public.lanu_media where message_id=$1',[id])).rows[0].n, 1);
});

test('at most 12 files per owner over a rolling day', async () => {
  for (let i = 0; i < 11; i++) assert.equal(await reserve(a,b,randomUUID(),1024), true);
  assert.equal(await reserve(a,b,randomUUID(),1024), false);
  assert.equal(await reserve(outsider,b,randomUUID(),1024), true, 'quota is per verified owner');
});
