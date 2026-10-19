import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { publicCode } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, identity, envelope, link, encrypted, decrypted } from './fixtures.mjs';

test('independent signed clients register without phone, code stays stable on register', async () => {
  const f = await fixture();
  const nonce = randomBytes(16).toString('base64url');
  const result = await f.relay(envelope(f.alice, 'register', { code: await publicCode(f.alice.publicKey, nonce), nonce, publicKey: f.alice.publicKey }));
  assert.equal(result.code, f.alice.code);
  assert.equal(f.store.users.size, 3);
});
test('wrong signer, tampered payload, expired timestamp and replay are rejected', async () => {
  const f = await fixture();
  await assert.rejects(f.relay(envelope(f.mallory, 'sync', {}, { identityId: f.alice.id })), { status: 401 });
  const request = envelope(f.alice, 'sync'); request.payload += ' ';
  await assert.rejects(f.relay(request), { status: 401 });
  await assert.rejects(f.relay(envelope(f.alice, 'sync', {}, { ts: Date.now() - 600000 })), { status: 401 });
  const once = envelope(f.alice, 'sync'); await f.relay(once);
  await assert.rejects(f.relay(once), { status: 409 });
});
test('shareable public code alone cannot authenticate or claim another key', async () => {
  const f = await fixture();
  await assert.rejects(f.relay({ payload: JSON.stringify({ identityId: f.alice.id, action: 'sync', ts: Date.now(), requestId: randomUUID() }), signature: f.alice.code }), { status: 401 });
  const evil = await identity();
  await assert.rejects(f.relay(envelope(evil, 'register', { code: f.alice.code, nonce: evil.nonce, publicKey: evil.publicKey })), { status: 400 });
});
test('bilateral contact registration is required, own/unknown codes are rejected', async () => {
  const f = await fixture();
  await assert.rejects(f.relay(envelope(f.alice, 'addContact', { code: f.alice.code })), { status: 400 });
  const stranger = await identity();
  await assert.rejects(f.relay(envelope(f.alice, 'addContact', { code: stranger.code })), { status: 404 });
  await f.relay(envelope(f.alice, 'addContact', { code: f.bob.code }));
  const id = randomUUID(), content = encrypted(f.alice, f.bob, id, 'hello');
  await assert.rejects(f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content })), { status: 409 });
  await f.relay(envelope(f.bob, 'addContact', { code: f.alice.code }));
  assert.equal((await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content }))).status, 'SENT');
});
test('two-client encrypted send, durable inbox ack and real read status', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID(), text = 'Gerçek iki istemci: merhaba 🌍', content = encrypted(f.alice, f.bob, id, text);
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content }));
  const incoming = await f.relay(envelope(f.bob, 'sync'));
  assert.equal(incoming.events.length, 1);
  assert.equal(decrypted(f.bob, f.alice, id, incoming.events[0].message.content), text);
  assert.equal(f.store.messages.get(id).status, 'SENT');
  assert.deepEqual((await f.relay(envelope(f.bob, 'sync'))).events, incoming.events, 'events stay pending until the receiving device saves and acknowledges them');
  await f.relay(envelope(f.bob, 'ack', { eventIds: incoming.events.map(e => e.id), readIds: [] }));
  assert.equal(f.store.messages.get(id).status, 'DELIVERED');
  await f.relay(envelope(f.bob, 'ack', { eventIds: [], readIds: [id] }));
  assert.equal(f.store.messages.get(id).status, 'READ');
  const sender = await f.relay(envelope(f.alice, 'sync'));
  assert.equal(sender.events.at(-1).message.status, 'READ');
  assert.ok(!JSON.stringify([...f.store.messages.values()]).includes(text));
});
test('outsider cannot read inbox, acknowledge, edit, react or delete another conversation', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID(), content = encrypted(f.alice, f.bob, id, 'secret');
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content }));
  const events = (await f.relay(envelope(f.bob, 'sync'))).events;
  assert.equal((await f.relay(envelope(f.mallory, 'sync'))).events.length, 0);
  await f.relay(envelope(f.mallory, 'ack', { eventIds: events.map(e => e.id), readIds: [id] }));
  assert.equal(f.store.messages.get(id).status, 'SENT');
  for (const action of ['edit', 'delete', 'react']) await assert.rejects(f.relay(envelope(f.mallory, action, { messageId: id, content, reaction: '👍' })), { status: 404 });
  await assert.rejects(f.relay(envelope(f.bob, 'edit', { messageId: id, content })), { status: 403 });
});
test('retry is idempotent; reused message id with another payload fails', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID(), content = encrypted(f.alice, f.bob, id, 'once');
  const data = { messageId: id, peerId: f.bob.id, content };
  await f.relay(envelope(f.alice, 'send', data));
  await f.relay(envelope(f.alice, 'send', data));
  assert.equal(f.store.messages.size, 1); assert.equal(f.store.pending.size, 2);
  await assert.rejects(f.relay(envelope(f.alice, 'send', { ...data, content: encrypted(f.alice, f.bob, id, 'changed') })), { status: 409 });
});
test('rotation is explicit, preserves account/contacts/history and invalidates old code for new people', async () => {
  const f = await fixture(); await link(f);
  const before = f.alice.code, nonce = randomBytes(16).toString('base64url');
  const result = await f.relay(envelope(f.alice, 'rotate', { expectedCode: before, nonce }));
  assert.notEqual(result.code, before); assert.equal(f.store.users.get(f.alice.id).publicKey, f.alice.publicKey);
  await assert.rejects(f.relay(envelope(f.mallory, 'addContact', { code: before })), { status: 404 });
  assert.equal((await f.relay(envelope(f.bob, 'addContact', { code: before }))).peer.id, f.alice.id);
  const id = randomUUID(), content = encrypted(f.alice, f.bob, id, 'still same account');
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content }));
  assert.equal(decrypted(f.bob, f.alice, id, f.store.messages.get(id).content), 'still same account');
});
test('removing a contact revokes further message sending', async () => {
  const f = await fixture(); await link(f);
  await f.relay(envelope(f.bob, 'removeContact', { peerId: f.alice.id }));
  const id = randomUUID();
  await assert.rejects(f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'blocked') })), { status: 409 });
});
test('edits, reactions and deletions create new remote events', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID();
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'first') }));
  await f.relay(envelope(f.alice, 'edit', { messageId: id, content: encrypted(f.alice, f.bob, id, 'edited') }));
  await f.relay(envelope(f.bob, 'react', { messageId: id, reaction: '👍' }));
  await f.relay(envelope(f.alice, 'delete', { messageId: id }));
  const latest = (await f.relay(envelope(f.bob, 'sync'))).events.at(-1).message;
  assert.equal(latest.revision, 4); assert.equal(latest.deleted, true); assert.equal(latest.reaction, '👍');
});
