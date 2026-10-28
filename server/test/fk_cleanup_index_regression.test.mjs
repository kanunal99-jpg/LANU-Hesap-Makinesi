import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';

/**
 * Reproduces the LIVE Supabase advisory fixes on a disposable PGlite schema.
 * Never queries real identities, deletes a row, or changes authorization.
 */
test('account cleanup FK indexes are additive, idempotent and do not open raw table access', async () => {
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
    for (const migration of [
      '20261019073855_persistent_user_code_messaging.sql',
      '20261024105720_private_media_grants_and_quota.sql',
      '20261025123907_private_call_signaling.sql',
      '20261026091500_call_only_push_opt_in.sql',
      '20261026134500_invalidate_expired_fcm_token.sql',
      '20261027174242_private_message_wake_opt_in_20261027.sql'
    ]) {
      await db.exec(await readFile(new URL('../../supabase/migrations/'+migration,import.meta.url),'utf8'));
    }
    const script = await readFile(
      new URL('../../supabase/migrations/20261028070614_lanu_fk_cleanup_query_indexes_20261028.sql',
        import.meta.url), 'utf8'
    );
    await db.exec(script);
    await db.exec(script); // repeat deployments must not error or rebuild data
    const indexes = (await db.query(`
      select tablename,indexname,indexdef from pg_indexes
      where schemaname='public'
        and indexname in (
          'lanu_media_recipient_fk_lookup',
          'lanu_message_wake_claim_sender_fk_lookup')
      order by indexname
    `)).rows;
    assert.equal(indexes.length,2);
    assert.equal(indexes.find(x=>x.indexname==='lanu_media_recipient_fk_lookup')?.tablename,
      'lanu_media_objects');
    assert.match(indexes.find(x=>x.indexname==='lanu_media_recipient_fk_lookup').indexdef,
      /\(recipient_id\)/);
    assert.equal(indexes.find(x=>x.indexname==='lanu_message_wake_claim_sender_fk_lookup')?.tablename,
      'lanu_message_wake_claims');
    assert.match(indexes.find(x=>x.indexname==='lanu_message_wake_claim_sender_fk_lookup').indexdef,
      /\(sender_id\)/);

    for (const table of ['lanu_media_objects','lanu_message_wake_claims']) {
      assert.equal((await db.query('select count(*)::int as n from public.'+table)).rows[0].n,0);
      const rls = await db.query(
        'select relrowsecurity as enabled from pg_class where oid=$1::regclass',
        ['public.'+table]
      );
      assert.equal(rls.rows[0].enabled,true);
      for (const role of ['anon','authenticated']) {
        for (const permission of ['SELECT','INSERT','UPDATE','DELETE']) {
          const grant = await db.query(
            'select has_table_privilege($1,$2,$3) as allowed',
            [role,'public.'+table,permission]
          );
          assert.equal(grant.rows[0].allowed,false,role+' '+permission+' on '+table);
        }
      }
    }
  } finally { await db.close(); }
});
