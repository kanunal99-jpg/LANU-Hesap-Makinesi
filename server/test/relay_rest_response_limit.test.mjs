import test from 'node:test';
import assert from 'node:assert/strict';
import { RestStore, readBoundedResponse } from '../../supabase/functions/lanu-relay/core.mjs';

test('normal PostgREST JSON is readable under its size limit', async () => {
  const body = JSON.stringify([{ id: 1, text: 'Merhaba, dünya 🌍' }]);
  assert.equal(await readBoundedResponse(new Response(body), 4096), body);
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key', async () =>
    new Response(body, { status: 200 }));
  assert.deepEqual(await store.query('lanu_events?limit=1'), JSON.parse(body));
});

test('declared oversized PostgREST response is rejected before reading stream', async () => {
  let pulled = 0;
  const stream = new ReadableStream({
    pull(controller) { pulled++; controller.enqueue(new Uint8Array([123, 125])); },
    cancel() {}
  });
  const response = new Response(stream, { headers: { 'content-length': '1000000' } });
  await assert.rejects(readBoundedResponse(response, 1024), { status: 503 });
  assert.equal(pulled <= 1, true);
});

test('chunked oversized PostgREST response gets cancelled within a bounded number of chunks', async () => {
  let cancelled = false, chunks = 0;
  const stream = new ReadableStream({
    pull(controller) {
      chunks++;
      controller.enqueue(new Uint8Array(1024).fill(65));
    },
    cancel() { cancelled = true; }
  });
  await assert.rejects(readBoundedResponse(new Response(stream), 2048), { status: 503 });
  assert.equal(cancelled, true);
  assert.ok(chunks <= 5, 'Must not read the full malicious response');
});

test('invalid UTF-8 response fails closed and does not echo backend payload', async () => {
  const response = new Response(new Uint8Array([0xC3]));
  await assert.rejects(readBoundedResponse(response, 2048), { status: 503 });
});

test('RestStore maps overlarge response to generic error even on upstream HTTP 200', async () => {
  const response = () => new Response('A'.repeat(4096), {
    status: 200,
    headers: { 'content-length': String(20 * 1024 * 1024) }
  });
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key',
    async () => response());
  await assert.rejects(store.query('lanu_events?limit=200'), {
    status: 503, message: 'Mesajlaşma sunucusu yanıtı güvenli sınırları aştı.'
  });
});

test('upstream PostgREST errors remain generalized after bounded valid response', async () => {
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key',
    async () => new Response(JSON.stringify({ detail: 'private-backend-secret' }), { status: 403 }));
  await assert.rejects(store.query('lanu_events?limit=200'), {
    status: 503, message: 'Mesajlaşma sunucusu işlemi tamamlayamadı.'
  });
});
