import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRelay, RelayError, RestStore } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, envelope, link } from './fixtures.mjs';

const fileId = 'f'.repeat(32), cipherSha256 = 'a'.repeat(64);
function request(f, update = {}) {
  return {
    messageId: randomUUID(), fileId,
    peerId: f.bob.id,
    cipherBytes: 65552, cipherSha256,
    ...update
  };
}
async function reserveFixture() {
  const f = await fixture();
  await link(f);
  const versions = new Map([[f.alice.id, 1], [f.bob.id, 1]]);
  f.store.mediaVersion = async id => versions.get(id) ?? 0;
  const rows = new Map();
  let writes = 0;
  f.store.reserveMedia = async data => {
    writes++;
    const row = {
      file_id: data.fileId, message_id: data.messageId,
      sender_id: data.owner, recipient_id: data.peer,
      cipher_bytes: data.cipherBytes,
      cipher_sha256: data.cipherSha256, state: 'pending',
      expires_at: new Date(Date.now() + 86_400_000).toISOString()
    };
    const old = rows.get(data.fileId);
    if (old) {
      if (Object.keys(row).some(k => k !== 'expires_at' && row[k] !== old[k]))
        throw new RelayError(409, 'Rezervasyon kimliği zaten kullanımda.');
      return old;
    }
    rows.set(data.fileId, row);
    return row;
  };
  const relay = createRelay(f.store, Date.now, { privateMediaReservation: true });
  return { ...f, relay, versions, rows, getWrites: () => writes };
}

test('deployed default cannot reserve private media or issue storage grants', async () => {
  const f = await fixture();
  await link(f);
  const data = request(f);
  // Even if an attacker discovers the action, default Edge wrapper never sets
  // privateMediaReservation, so it never calls the service-role reservation RPC.
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia', data)), { status: 503 });
  assert.equal(typeof f.store.reserveMedia, 'undefined');
});

test('signed, mutually registered, upgraded peers may reserve metadata only', async () => {
  const f = await reserveFixture();
  const data = request(f);
  const result = await f.relay(envelope(f.alice, 'reservePrivateMedia', data));
  assert.deepEqual(Object.keys(result).sort(), ['cipherBytes', 'expiresAt', 'fileId', 'state']);
  assert.deepEqual({ fileId: result.fileId, cipherBytes: result.cipherBytes, state: result.state },
    { fileId: data.fileId, cipherBytes: data.cipherBytes, state: 'pending' });
  assert.equal(f.rows.size, 1);
  assert.ok(!JSON.stringify(result).includes('signedURL'));
  assert.ok(!JSON.stringify(result).includes('token'));
  assert.ok(!JSON.stringify(result).includes('secret'));
  // New signed request ID + identical reservation must not create a second row.
  assert.deepEqual(await f.relay(envelope(f.alice, 'reservePrivateMedia', data)), result);
  assert.equal(f.rows.size, 1);
  // Exact-same signed request is replayed and rejected BEFORE touching storage.
  const once = envelope(f.alice, 'reservePrivateMedia', { ...data, fileId: '0'.repeat(32), messageId: randomUUID() });
  await f.relay(once);
  const before = f.getWrites();
  await assert.rejects(f.relay(once), { status: 409 });
  assert.equal(f.getWrites(), before);
});

test('unknown/wrong ECDSA identity, untrusted peers and older peers are rejected', async () => {
  const f = await reserveFixture();
  const data = request(f);
  await assert.rejects(f.relay(envelope(f.mallory, 'reservePrivateMedia', data)), { status: 409 });
  await assert.rejects(f.relay(envelope(f.mallory, 'reservePrivateMedia', data,
    { identityId: f.alice.id })), { status: 401 });
  f.versions.set(f.bob.id, 0);
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia', data)), { status: 409 });
  f.versions.set(f.bob.id, 1);
  f.versions.set(f.alice.id, 0);
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia', data)), { status: 409 });
  assert.equal(f.getWrites(), 0);
});

test('rejects invalid manifest IDs, bounds, owner substitution and secret fields', async () => {
  const f = await reserveFixture();
  const base = request(f);
  const invalid = [
    { fileId: '../object' }, { fileId: 'A'.repeat(32) },
    { cipherSha256: 'g'.repeat(64) }, { cipherSha256: 'a'.repeat(63) },
    { messageId: 'bad' }, { peerId: f.alice.id },
    { peerId: f.mallory.id },
    { cipherBytes: 16 }, { cipherBytes: 41953281 },
    { cipherBytes: 12.3 }, { cipherBytes: 9007199254740991 },
    { secretKey: 'SHOULD_NEVER_BE_TRANSMITTED' }, { content: 'plaintext' }
  ];
  for (const item of invalid) {
    await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia', { ...base, ...item })),
      { status: item.peerId === f.mallory.id ? 409 : 400 });
  }
  assert.equal(f.getWrites(), 0);
});

test('reservation collisions, database mismatch/expiry and quota are fail-closed', async () => {
  const f = await reserveFixture();
  const data = request(f);
  await f.relay(envelope(f.alice, 'reservePrivateMedia', data));
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia',
    { ...data, messageId: randomUUID() })), { status: 409 });
  f.store.reserveMedia = async () => ({ quotaExceeded: true });
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia',
    { ...data, fileId: 'e'.repeat(32), messageId: randomUUID() })), { status: 429 });
  f.store.reserveMedia = async d => ({
    file_id: d.fileId, message_id: d.messageId, sender_id: f.mallory.id,
    recipient_id: d.peer, cipher_bytes: d.cipherBytes,
    cipher_sha256: d.cipherSha256, state: 'pending',
    expires_at: new Date(Date.now() + 1000).toISOString()
  });
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia',
    { ...data, fileId: 'd'.repeat(32), messageId: randomUUID() })), { status: 503 });
  f.store.reserveMedia = async d => ({
    file_id: d.fileId, message_id: d.messageId, sender_id: d.owner,
    recipient_id: d.peer, cipher_bytes: d.cipherBytes,
    cipher_sha256: d.cipherSha256, state: 'pending',
    expires_at: new Date(Date.now() - 1000).toISOString()
  });
  await assert.rejects(f.relay(envelope(f.alice, 'reservePrivateMedia',
    { ...data, fileId: 'c'.repeat(32), messageId: randomUUID() })), { status: 503 });
});

test('PostgREST adapter calls the already deployed server-only quota RPC', async () => {
  const rest = new RestStore('https://example.invalid', 'sb_secret_DUMMY');
  let invoked = 0;
  rest.query = async (path, method, body) => {
    invoked++;
    assert.equal(path, 'rpc/lanu_reserve_private_media');
    assert.equal(method, 'POST');
    assert.deepEqual(body, {
      p_owner: 'a'.repeat(64), p_peer: 'b'.repeat(64),
      p_file: fileId, p_message: '12b34567-89ab-4cde-8f01-23456789abcd',
      p_bytes: 65552, p_sha: cipherSha256
    });
    return { quotaExceeded: true };
  };
  assert.deepEqual(await rest.reserveMedia({
    owner: 'a'.repeat(64), peer: 'b'.repeat(64), fileId,
    messageId: '12b34567-89ab-4cde-8f01-23456789abcd',
    cipherBytes: 65552, cipherSha256
  }), { quotaExceeded: true });
  assert.equal(invoked, 1);
});
