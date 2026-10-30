import test from 'node:test';
import assert from 'node:assert/strict';
import { authorizePrivateMediaGrant } from '../../supabase/functions/lanu-relay/media-grants.mjs';

const alice = 'a'.repeat(64), bob = 'b'.repeat(64), outsider = 'c'.repeat(64);
const now = Date.now();
const reservation = (changed = {}) => ({
  file_id: 'd'.repeat(32),
  sender_id: alice,
  recipient_id: bob,
  cipher_sha256: 'f'.repeat(64),
  cipher_bytes: 65552,
  state: 'pending',
  expires_at: new Date(now + 60_000).toISOString(),
  ...changed
});

test('only verified sender can plan upload while reservation is pending', () => {
  const r = reservation();
  const planned = authorizePrivateMediaGrant(alice, 'upload', r, now);
  assert.deepEqual(planned, {
    bucket: 'lanu-private-media-v1',
    path: 'v1/' + r.file_id + '.cipher',
    cipherBytes: r.cipher_bytes,
    cipherSha256: r.cipher_sha256,
    expireAt: Date.parse(r.expires_at)
  });
  assert.ok(Object.isFrozen(planned));
  assert.ok(!JSON.stringify(planned).includes(alice));
  assert.ok(!JSON.stringify(planned).includes(bob));
  assert.ok(!JSON.stringify(planned).includes('key'));
});

test('recipient cannot overwrite sender and outsiders cannot fetch private ciphertext', () => {
  const pending = reservation();
  for (const who of [bob, outsider])
    assert.throws(() => authorizePrivateMediaGrant(who, 'upload', pending, now));
  assert.throws(() => authorizePrivateMediaGrant(alice, 'download', pending, now));
  assert.throws(() => authorizePrivateMediaGrant(bob, 'download', pending, now));
  const ready = reservation({ state: 'ready' });
  for (const who of [alice, bob])
    assert.equal(authorizePrivateMediaGrant(who, 'download', ready, now).cipherBytes, 65552);
  assert.throws(() => authorizePrivateMediaGrant(outsider, 'download', ready, now));
  assert.throws(() => authorizePrivateMediaGrant(alice, 'upload', ready, now));
});

test('purged, cancelled, expired or malformed reservations cannot mint grants', () => {
  const bad = [
    { state: 'purged' }, { state: 'cancelled' },
    { expires_at: new Date(now).toISOString() },
    { expires_at: 'not-a-timestamp' },
    { file_id: '../escape' }, { file_id: 'D'.repeat(32) },
    { cipher_sha256: '0'.repeat(63) },
    { cipher_bytes: 0 }, { cipher_bytes: 41_953_281 },
    { cipher_bytes: 19.5 }, { sender_id: bob, recipient_id: bob },
    { recipient_id: 'not-authorized' },
  ];
  for (const invalid of bad) {
    assert.throws(() => authorizePrivateMediaGrant(
      alice, 'upload', reservation(invalid), now
    ), /not authorized/);
  }
  for (const value of ['', null, outsider])
    assert.throws(() => authorizePrivateMediaGrant(value, 'upload', reservation(), now));
  assert.throws(() => authorizePrivateMediaGrant(alice, '../upload', reservation(), now));
  assert.throws(() => authorizePrivateMediaGrant(alice, 'upload', reservation(), -1));
  assert.throws(() => authorizePrivateMediaGrant(alice, 'upload', null, now));
});

test('verified ciphertext path is derived exclusively from server reservation id', () => {
  const a = reservation({ path: '../../evil', signedUrl: 'http://evil', key: 'secret' });
  const result = authorizePrivateMediaGrant(alice, 'upload', a, now);
  assert.equal(result.path, 'v1/' + 'd'.repeat(32) + '.cipher');
  assert.ok(!JSON.stringify(result).includes('evil'));
  assert.ok(!JSON.stringify(result).includes('secret'));
});
