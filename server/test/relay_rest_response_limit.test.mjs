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

test('RestStore abort deadline fails closed on stalled PostgREST fetch', async () => {
  let aborted = false;
  const fetcher = async (_url, init) => new Promise((_resolve, reject) => {
    init.signal.addEventListener('abort', () => {
      aborted = true;
      reject(new Error('remote secret must never leave server'));
    }, { once: true });
  });
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key', fetcher, 30);
  await assert.rejects(store.query('lanu_events?limit=1'), error =>
    error.status === 503 && error.message === 'Mesajlaşma sunucusu bağlantısı kullanılamıyor.' &&
    !String(error).includes('remote secret'));
  assert.equal(aborted, true);
});

test('RestStore keeps deadline across stalled streaming response bytes', async () => {
  let aborted = false;
  const fetcher = async (_url, init) => {
    const stream = new ReadableStream({
      start(controller) {
        init.signal.addEventListener('abort', () => {
          aborted = true;
          controller.error(new Error('PostgREST stalled'));
        }, { once: true });
      }
    });
    return new Response(stream, { status: 200 });
  };
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key', fetcher, 30);
  await assert.rejects(store.query('lanu_events?limit=1'),
    { status: 503, message: 'Mesajlaşma sunucusu yanıtı güvenli sınırları aştı.' });
  assert.equal(aborted, true, 'Timeout must cover body reading, not only response headers');
});

test('RestStore completed response releases abort timer without changing 409 semantics', async () => {
  let signal;
  const store = new RestStore('https://example.invalid', 'nonproduction-test-key',
    async (_url, init) => {
      signal = init.signal;
      return new Response(JSON.stringify({ detail: 'private server failure' }), { status: 409 });
    }, 20);
  await assert.rejects(store.query('rpc/lanu_call_send', 'POST', {}),
    { status: 409, message: 'Kayıt çakıştı; tekrar deneyin.' });
  await new Promise(resolve => setTimeout(resolve, 45));
  assert.equal(signal.aborted, false, 'Completed request must clear the deadline timer');
});
