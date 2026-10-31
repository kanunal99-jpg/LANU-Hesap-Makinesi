import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, envelope, link } from './fixtures.mjs';

test('old client must not receive large-media envelopes even after peer upgrades', async () => {
  const f = await fixture();
  await link(f);
  const observations = [];
  const regularEvents = f.store.events.bind(f.store);
  f.store.mediaVersion = async id => id === f.alice.id ? 1 : 0;
  f.store.events = async (owner, mediaVersion) => {
    observations.push(mediaVersion);
    // Simulates SQL lanu_media_aware_events: legacy clients only see ordinary
    // encrypted message events; no raw file-key envelope is delivered.
    const events = await regularEvents(owner);
    return mediaVersion === 1 ? events : events.filter(e => !e.message.privateMedia);
  };
  const relay = createRelay(f.store, Date.now, { privateMediaTransfers: true });
  assert.equal((await relay(envelope(f.alice, 'sync', {}))).events.length, 0);
  assert.equal((await relay(envelope(f.alice, 'sync', { mediaVersion: 0 }))).events.length, 0);
  await relay(envelope(f.alice, 'sync', { mediaVersion: 1 }));
  await relay(envelope(f.bob, 'sync', { mediaVersion: 1 }));
  assert.deepEqual(observations, [0, 0, 1, 0],
    'Both signed request protocol and DB capability are required');
});

test('RestStore media-aware read calls restricted database function', async () => {
  const { RestStore } = await import('../../supabase/functions/lanu-relay/core.mjs');
  const rest = new RestStore('https://example.invalid', 'sb_secret_TEST');
  const seen = [];
  rest.query = async (path, method, body) => {
    seen.push({ path, method, body });
    return [{ id: 'a', snapshot: { content: 'encrypted' } }];
  };
  assert.equal((await rest.events('a'.repeat(64), 0))[0].message.content, 'encrypted');
  await rest.events('a'.repeat(64), 1);
  assert.deepEqual(seen.map(x => x.body.p_media_version), [0,1]);
  assert.ok(seen.every(x => x.path === 'rpc/lanu_media_aware_events' && x.method === 'POST'));
});
