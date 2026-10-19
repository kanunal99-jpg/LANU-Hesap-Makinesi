import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { fixture, envelope, encrypted, decrypted } from './fixtures.mjs';

test('two independent signed HTTP clients exchange ciphertext and delivery receipts over a real socket', async () => {
  const f = await fixture();
  const server = createServer(async (request, response) => {
    let raw = '';
    for await (const chunk of request) raw += chunk;
    try { response.setHeader('content-type', 'application/json'); response.end(JSON.stringify(await f.relay(JSON.parse(raw)))); }
    catch (error) { response.statusCode = error.status ?? 500; response.end(JSON.stringify({ error: error.message })); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/relay`;
  async function call(user, action, data) {
    const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(envelope(user, action, data)) });
    const result = await response.json();
    assert.equal(response.status, 200, JSON.stringify(result)); return result;
  }
  try {
    await call(f.alice, 'addContact', { code: f.bob.code });
    await call(f.bob, 'addContact', { code: f.alice.code });
    const id = randomUUID(), text = 'HTTP üzerinde iki istemci: telefon yok 📬';
    await call(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, text) });
    const bob = await call(f.bob, 'sync');
    assert.equal(decrypted(f.bob, f.alice, id, bob.events[0].message.content), text);
    await call(f.bob, 'ack', { eventIds: bob.events.map(e => e.id), readIds: [id] });
    assert.equal((await call(f.alice, 'sync')).events.at(-1).message.status, 'READ');
  } finally { await new Promise(resolve => server.close(resolve)); }
});
