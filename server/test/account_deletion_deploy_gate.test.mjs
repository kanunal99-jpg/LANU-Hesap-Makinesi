import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';

/**
 * CI deployment tripwire:
 * A safe prototype must not silently become a LIVE destructive feature
 * when the Edge module/migration directory is deployed automatically.
 *
 * Re-enabling requires a separate approved release which updates this test
 * only AFTER owner/legal approval, backup, Storage and physical-device proof.
 */
test('staged account erasure cannot be activated accidentally in production Edge', async () => {
  const edge = await readFile(
    new URL('../../supabase/functions/lanu-relay/index.ts',import.meta.url),'utf8');
  assert.doesNotMatch(edge,/accountDeletionEnabled\s*:/,
    'SECURITY: Edge wrapper attempted to enable unapproved account deletion');
  assert.doesNotMatch(edge,/accountDeletionPreflightEnabled\\s*:/,
    'SECURITY: Edge wrapper attempted to enable unapproved account deletion preflight');
  const stage = await readFile(
    new URL('../staged/lanu_account_erasure_v1.sql',import.meta.url),'utf8');
  assert.match(stage,/lanu_delete_account_v1/,
    'The staged function must remain independently reviewable');
  assert.match(stage,/lanu_account_erasure_preflight_v1/,
    'The staged read-only preflight must remain independently reviewable');
  const folder = new URL('../../supabase/migrations/',import.meta.url);
  const migrations = (await readdir(folder)).filter(name=>name.endsWith('.sql'));
  for(const migration of migrations){
    const sql = await readFile(new URL(migration,folder),'utf8');
    assert.doesNotMatch(sql,/\blanu_delete_account_v1\b/,
      'SECURITY: staged destructive erasure must not be included in '+migration);
    assert.doesNotMatch(sql,/\\blanu_account_erasure_preflight_v1\\b/,
      'SECURITY: staged preflight must not enter production migrations');
  }
});
