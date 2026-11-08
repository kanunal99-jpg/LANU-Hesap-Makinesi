import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { bridgeWake, createWakePublisher, wakeTopic } from '../../supabase/functions/lanu-relay/realtime-wake.mjs';
import { fixture, envelope, link, encrypted } from './fixtures.mjs';
import { randomUUID } from 'node:crypto';

test('realtime authorization uses a signed existing owner, rejects replay and cannot select another inbox', async () => {
  const f = await fixture();
  const relay = createRelay(f.store, Date.now, { realtimeEnabled: true });
  const auth = envelope(f.alice, 'realtimeOpen');
  assert.deepEqual(await relay(auth), { owner: f.alice.id, version: 1 });
  await assert.rejects(relay(auth), { status: 409 });
  await assert.rejects(relay(envelope(f.alice, 'realtimeOpen', { owner: f.bob.id })), { status: 503 });
  await assert.rejects(relay(envelope(f.alice, 'realtimeOpen', {}, { identityId: f.bob.id })), { status: 401 });
  await assert.rejects(f.relay(envelope(f.alice, 'realtimeOpen')), { status: 503 });
});

test('wake runs only after durable accepted ciphertext; failures do not reject or duplicate the message', async () => {
  const f = await fixture(); await link(f);
  let events = [];
  const relay = createRelay(f.store, Date.now, { publishWake: async (owner, kind) => {
    assert.equal(f.store.messages.size, 1); events.push([owner, kind]); throw new Error('offline');
  } });
  const id = randomUUID(), data = { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'private') };
  assert.equal((await relay(envelope(f.alice, 'send', data))).status, 'SENT');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(events, [[f.bob.id, 'message']]);
  assert.equal((await relay(envelope(f.alice, 'send', data))).status, 'SENT');
  assert.equal(f.store.messages.size, 1);
  await assert.rejects(relay(envelope(f.mallory, 'send', { ...data, peerId: f.bob.id })), { status: 409 });
});

test('publisher uses a private owner-selected channel with only a fixed content-free hint', async () => {
  let request;
  const owner = 'a'.repeat(64);
  await createWakePublisher('https://example.com', 'server-only', async (url, options) => {
    request = { url, options }; return new Response('{}');
  })(owner, 'call');
  assert.deepEqual(JSON.parse(request.options.body), { messages: [{ topic: wakeTopic(owner), event: 'changed', payload: { kind: 'call' }, private: true }] });
  await assert.rejects(createWakePublisher('https://example.com', 'key')('invalid', 'call'));
});

class FakeSocket {
  static created = [];
  readyState = 1; sent = []; closed = false;
  constructor(url) { this.url = url; FakeSocket.created.push(this); }
  send(value) { this.sent.push(JSON.parse(value)); }
  close() { this.closed = true; }
}
test('bridge rejects cross-owner and unjoined events and cleans both sockets on close', async () => {
  const downstream = new FakeSocket('client'), owner = 'a'.repeat(64);
  const bridge = bridgeWake(downstream, owner, 'https://example.com', 'server-key', FakeSocket);
  downstream.onopen(); const upstream = FakeSocket.created.at(-1); upstream.onopen();
  const topic = 'realtime:' + wakeTopic(owner);
  const emit = value => upstream.onmessage({ data: JSON.stringify(value) });
  emit({ topic, event: 'broadcast', payload: { event: 'changed', payload: { kind: 'call' } } });
  assert.equal(downstream.sent.length, 0);
  emit({ topic, event: 'phx_reply', ref: '1', payload: { status: 'ok' } });
  emit({ topic: 'other', event: 'broadcast', payload: { event: 'changed', payload: { kind: 'message' } } });
  emit({ topic, event: 'broadcast', payload: { event: 'changed', payload: { kind: 'call', secret: 'discarded' } } });
  assert.deepEqual(downstream.sent, [{ ready: true }, { changed: 'call' }]);
  downstream.onclose(); await bridge.done;
  assert.equal(upstream.closed, true); assert.equal(downstream.closed, true);
});
