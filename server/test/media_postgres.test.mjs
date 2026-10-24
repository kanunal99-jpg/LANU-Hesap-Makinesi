import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { PGlite } from '@electric-sql/pglite';

const A = 'a'.repeat(64), B = 'b'.repeat(64);
const F = 'a'.repeat(32), SHA = 'e'.repeat(64);

test('real Postgres private-media migration: RLS, quota, idempotency and legacy event gating', async () => {
  const db = new PGlite();
  try {
    await db.exec(`
      create role anon; create role authenticated; create role service_role bypassrls;
      create schema storage;
      create table storage.buckets(
        id text primary key, name text not null, public boolean not null,
        file_size_limit bigint, allowed_mime_types text[]
      );
    `);
    const base = await readFile(new URL(
      '../../supabase/migrations/20261019073855_persistent_user_code_messaging.sql',
      import.meta.url), 'utf8');
    const media = await readFile(new URL(
      '../../supabase/migrations/20261024105720_private_media_grants_and_quota.sql',
      import.meta.url), 'utf8');
    await db.exec(base);
    await db.exec(media);
    const bucket = (await db.query(
      "select * from storage.buckets where id='lanu-private-media-v1'")).rows[0];
    assert.equal(bucket.public, false);
    assert.equal(Number(bucket.file_size_limit), 44_040_192);
    assert.deepEqual(bucket.allowed_mime_types, ['application/octet-stream']);

    const acl = (await db.query(
      "select relrowsecurity from pg_class where oid='public.lanu_media_objects'::regclass")).rows[0];
    assert.equal(acl.relrowsecurity, true);
    for (const role of ['anon', 'authenticated']) {
      assert.equal((await db.query(
        "select has_table_privilege($1,'public.lanu_media_objects','select,insert,update,delete') as allowed", [role]
      )).rows[0].allowed, false);
      assert.equal((await db.query(
        "select has_function_privilege($1,'public.lanu_reserve_private_media(text,text,text,uuid,bigint,text)','execute') as allowed", [role]
      )).rows[0].allowed, false);
    }

    await db.query(
      "insert into public.lanu_identities(id,code,nonce,public_key) values ($1,$2,$3,$4),($5,$6,$3,$4)",
      [A,'LANU-' + Array(5).fill('AAAA').join('-'),'A'.repeat(22),'dummy',
       B,'LANU-' + Array(5).fill('BBBB').join('-')]);
    await db.query(
      "insert into public.lanu_contacts(owner_id,peer_id,code_at_add) values ($1,$2,'dummy'),($2,$1,'dummy')",
      [A,B]);

    await db.exec('set role service_role');
    const rpc = async (fileId, messageId, bytes = 65552, hash = SHA, owner=A, peer=B) =>
      (await db.query(
        'select public.lanu_reserve_private_media($1,$2,$3,$4::uuid,$5,$6) as media',
        [owner,peer,fileId,messageId,bytes,hash])).rows[0].media;
    const msg = randomUUID();

    // Peer must have opted into media support before any metadata is reserved.
    await assert.rejects(rpc(F,msg), /recipient media upgrade required/);
    assert.equal((await db.query('select count(*) as n from public.lanu_media_objects')).rows[0].n, '0');
    await db.query('update public.lanu_identities set private_media_version=1 where id=$1', [B]);
    const first = await rpc(F,msg);
    assert.equal(first.state,'pending');
    assert.equal(first.message_id,msg);
    assert.deepEqual(await rpc(F,msg), first);
    await assert.rejects(rpc(F,randomUUID()), /reservation conflict/);
    await assert.rejects(rpc('INVALID_PATH',randomUUID()), /invalid private media reservation/);
    await assert.rejects(rpc('b'.repeat(32),randomUUID(),41953281), /invalid private media reservation/);

    // A reserved but unverified upload must NEVER be announced as a sent message.
    await assert.rejects(db.query(
      "insert into public.lanu_messages(id,sender_id,recipient_id,content) values ($1,$2,$3,$4)",
      [msg,A,B,'lanu2:'+'A'.repeat(40)]), /private media message not authorized/);
    assert.equal((await db.query('select count(*) as n from public.lanu_messages')).rows[0].n,'0');

    // Only a *trusted server-side completed upload verification* may set ready.
    await db.query("update public.lanu_media_objects set state='ready' where file_id=$1",[F]);
    await db.query(
      "insert into public.lanu_messages(id,sender_id,recipient_id,content) values ($1,$2,$3,$4)",
      [msg,A,B,'lanu2:'+'A'.repeat(40)]);
    const inserted = (await db.query('select private_media from public.lanu_messages where id=$1',[msg])).rows[0];
    assert.equal(inserted.private_media, true);
    assert.equal((await db.query('select count(*) as n from public.lanu_media_aware_events($1,0)',[B])).rows[0].n,'0');
    assert.equal((await db.query('select count(*) as n from public.lanu_media_aware_events($1,1)',[B])).rows[0].n,'1');

    // Four distinct daily reservations allowed; fifth must fail without insertion.
    for (let i=0;i<3;i++) {
      const row = await rpc((i+1).toString(16).repeat(32),randomUUID());
      assert.equal(row.state,'pending');
    }
    assert.deepEqual(await rpc('f'.repeat(32),randomUUID()), { quotaExceeded:true });
    assert.equal((await db.query('select count(*) as n from public.lanu_media_objects')).rows[0].n,'4');
  } finally { await db.close(); }
});
