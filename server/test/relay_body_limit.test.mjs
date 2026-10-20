import test from 'node:test';
import assert from 'node:assert/strict';
import { readBoundedRequest } from '../../supabase/functions/lanu-relay/core.mjs';

function request(bytes, headers = {}) {
  return new Request('https://example.invalid/functions/v1/lanu-relay', {
    method: 'POST',
    headers,
    body: bytes,
    duplex: 'half'
  });
}

test('valid signed-envelope JSON passes the streaming request reader unchanged', async () => {
  const payload = JSON.stringify({ payload: '{"action":"sync"}', signature: 'test' });
  assert.equal(await readBoundedRequest(request(payload)), payload);
});

test('a Content-Length above the cap fails before reading body bytes', async () => {
  await assert.rejects(
    readBoundedRequest(request('small-body', { 'content-length': '100001' })),
    { status: 413 }
  );
});

test('malformed Content-Length fails closed', async () => {
  await assert.rejects(
    readBoundedRequest(request('small-body', { 'content-length': 'very-big' })),
    { status: 400 }
  );
});

test('exactly 100000 bytes is accepted; next byte is rejected with 413', async () => {
  assert.equal((await readBoundedRequest(request('x'.repeat(100000)))).length, 100000);
  await assert.rejects(readBoundedRequest(request('x'.repeat(100001))), { status: 413 });
});

test('chunked oversized requests are cancelled before full body can be retained', async () => {
  let cancelled = false, chunks = 0;
  const stream = new ReadableStream({
    pull(controller) {
      chunks++;
      controller.enqueue(new Uint8Array(1024).fill(65));
    },
    cancel() { cancelled = true; }
  });
  await assert.rejects(
    readBoundedRequest(request(stream), 2_000),
    { status: 413 }
  );
  assert.equal(cancelled, true);
  assert.ok(chunks <= 5, 'Body should not be read to completion');
});

test('UTF-8 byte length, not JavaScript character count, is enforced', async () => {
  assert.equal(await readBoundedRequest(request('ğğ'), 4), 'ğğ');
  await assert.rejects(readBoundedRequest(request('ğğğ'), 5), { status: 413 });
});

test('invalid UTF-8 input is rejected before any relay action executes', async () => {
  await assert.rejects(
    readBoundedRequest(request(new Uint8Array([0xc3]))),
    { status: 400 }
  );
});
