import test from 'node:test';
import assert from 'node:assert/strict';
import { RestStore } from '../../supabase/functions/lanu-relay/core.mjs';
const row = { id: 'test-message', sender_id: 'sender', recipient_id: 'recipient', content: 'cipher', created_at: '2026-11-08T00:00:00Z', revision: 1 };
test('new send returns the committed database row in one REST roundtrip', async () => {
  const calls = [];
  const store = new RestStore('https://example.invalid', 'nonproduction', async (url, options) => {
    calls.push({ url, options }); return new Response(JSON.stringify([row]));
  });
  const result = await store.send({ id: row.id, senderId: row.sender_id, recipientId: row.recipient_id, content: row.content });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.headers.Prefer, 'resolution=ignore-duplicates,return=representation');
  assert.equal(result.status, 'SENT'); assert.equal(result.content, row.content);
});
test('concurrent duplicate or legacy empty insert response reads the authoritative row', async () => {
  for (const insert of [[], null]) {
    const calls = [];
    const store = new RestStore('https://example.invalid', 'nonproduction', async (url, options) => {
      calls.push(options.method);
      return new Response(JSON.stringify(calls.length === 1 ? insert : [row]));
    });
    const result = await store.send({ id: row.id, senderId: row.sender_id, recipientId: row.recipient_id, content: row.content });
    assert.deepEqual(calls, ['POST', 'GET']); assert.equal(result.id, row.id);
  }
});
