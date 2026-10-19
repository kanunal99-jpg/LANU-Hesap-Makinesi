// Run explicitly against a deployed relay. Creates three disposable test identities.
// Private keys live only in process memory; output contains no recovery material.
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { identity, envelope, encrypted, decrypted } from './test/fixtures.mjs';
import { publicCode } from '../supabase/functions/lanu-relay/core.mjs';

const endpoint = process.env.LANU_RELAY_URL;
assert.ok(endpoint?.startsWith('https://'), 'Set LANU_RELAY_URL to the HTTPS relay endpoint');
const alice = await identity(), bob = await identity(), outsider = await identity();
const testIdentities = [alice.id, bob.id, outsider.id];
async function raw(value, expected = 200) {
  const response = await fetch(endpoint, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(value), signal: AbortSignal.timeout(30000) });
  const result = await response.json();
  assert.equal(response.status, expected, JSON.stringify(result));
  return result;
}
const call = (user, action, data = {}, expected = 200) => raw(envelope(user, action, data), expected);
try {
  for (const user of [alice, bob, outsider]) {
    const registered = await call(user, 'register', { code: user.code, nonce: user.nonce, publicKey: user.publicKey });
    assert.equal(registered.code, user.code);
  }
  const savedCode = alice.code;
  assert.equal((await call(alice, 'register', { code: alice.code, nonce: alice.nonce, publicKey: alice.publicKey })).code, savedCode);
  await call(alice, 'addContact', { code: bob.code });
  const id = randomUUID(), text = 'LANU canlı HTTPS testi: telefon yok, kod sabit 🔒';
  const content = encrypted(alice, bob, id, text);
  await call(alice, 'send', { messageId: id, peerId: bob.id, content }, 409);
  await call(bob, 'addContact', { code: alice.code });
  assert.equal((await call(alice, 'send', { messageId: id, peerId: bob.id, content })).status, 'SENT');
  await call(alice, 'send', { messageId: id, peerId: bob.id, content });
  const received = await call(bob, 'sync');
  assert.equal(decrypted(bob, alice, id, received.events.find(e => e.message.id === id).message.content), text);
  await call(bob, 'ack', { eventIds: received.events.map(e => e.id), readIds: [] });
  assert.equal((await call(alice, 'sync')).events.at(-1).message.status, 'DELIVERED');
  await call(bob, 'ack', { readIds: [id] });
  assert.equal((await call(alice, 'sync')).events.at(-1).message.status, 'READ');
  const replyId = randomUUID(), reply = 'Karşılıklı yanıt alındı ✅';
  await call(bob, 'send', { messageId: replyId, peerId: alice.id, content: encrypted(bob, alice, replyId, reply) });
  assert.equal(decrypted(alice, bob, replyId, (await call(alice, 'sync')).events.find(e => e.message.id === replyId).message.content), reply);
  assert.equal((await call(outsider, 'sync')).events.length, 0);
  await call(outsider, 'edit', { messageId: id, content }, 404);
  const forged = envelope(outsider, 'sync', {}, { identityId: alice.id });
  await raw(forged, 401);
  const replay = envelope(alice, 'sync');
  await raw(replay); await raw(replay, 409);
  const nonce = randomBytes(16).toString('base64url'), code = await publicCode(alice.publicKey, nonce);
  assert.equal((await call(alice, 'rotate', { nonce, expectedCode: alice.code })).code, code);
  Object.assign(alice, { code, nonce });
  await call(outsider, 'addContact', { code: savedCode }, 404);
  assert.equal((await call(bob, 'sync')).contacts[0].code, code);
  await call(bob, 'react', { messageId: id, reaction: '👍' });
  const edited = encrypted(alice, bob, id, 'Düzenlenmiş şifreli mesaj');
  await call(alice, 'edit', { messageId: id, content: edited });
  assert.equal(decrypted(bob, alice, id, (await call(bob, 'sync')).events.filter(e => e.message.id === id).at(-1).message.content), 'Düzenlenmiş şifreli mesaj');
  await call(alice, 'delete', { messageId: id });
  assert.equal((await call(bob, 'sync')).events.filter(e => e.message.id === id).at(-1).message.deleted, true);
  console.log(JSON.stringify({ status: 'PASS', transport: 'deployed HTTPS relay + Postgres', testIdentities, checks: ['registration-without-phone', 'stable-code', 'bilateral-contacts', 'ciphertext-only', 'bidirectional-decryption', 'delivery', 'read', 'outsider-denial', 'signature', 'replay', 'explicit-rotation', 'edit-reaction-delete'] }));
} catch (error) {
  console.error(JSON.stringify({ status: 'FAIL', testIdentities, error: error.message }));
  process.exitCode = 1;
}
