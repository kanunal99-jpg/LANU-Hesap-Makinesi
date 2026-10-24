import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { verifyPrivateCiphertextResponse } from '../../supabase/functions/lanu-relay/media-verify.mjs';

function reservation(bytes) {
  return {
    fileId: 'a'.repeat(32),
    cipherBytes: bytes.length,
    cipherSha256: createHash('sha256').update(bytes).digest('hex'),
    state: 'pending',
    expiresAt: new Date(Date.now() + 60_000).toISOString()
  };
}
const headers = { 'content-type': 'application/octet-stream' };
function object(bytes, customHeaders = {}) {
  return new Response(bytes, { status: 200, headers: { ...headers, ...customHeaders } });
}

test('complete ciphertext passes with incremental digest and no key in result', async () => {
  for (const bytes of [randomBytes(17), randomBytes(65552), randomBytes(170_005)]) {
    const expected = reservation(bytes);
    const verified = await verifyPrivateCiphertextResponse(object(bytes), expected);
    assert.deepEqual(verified, {
      fileId: expected.fileId, cipherBytes: bytes.length,
      cipherSha256: expected.cipherSha256
    });
    assert.ok(Object.isFrozen(verified));
    assert.ok(!JSON.stringify(verified).includes('signedUrl'));
    assert.ok(!JSON.stringify(verified).includes('secret'));
  }
});

test('rejects tampered but same-size ciphertext, short content and excess bytes', async () => {
  const original = randomBytes(65552);
  const expected = reservation(original);
  const changed = Buffer.from(original);
  changed[23] ^= 1;
  await assert.rejects(verifyPrivateCiphertextResponse(object(changed), expected), /verification failed/);
  await assert.rejects(verifyPrivateCiphertextResponse(
    object(original.subarray(0, -1)), expected), /verification failed/);
  await assert.rejects(verifyPrivateCiphertextResponse(
    object(Buffer.concat([original, Buffer.from([1])])), expected), /verification failed/);
});

test('unknown length streaming succeeds with bounded SHA256, excessive stream is cancelled', async () => {
  const bytes = randomBytes(70_011);
  let position = 0;
  const stream = new ReadableStream({
    pull(controller) {
      if (position >= bytes.length) return controller.close();
      const chunk = bytes.subarray(position, Math.min(bytes.length, position + 3137));
      position += chunk.length;
      controller.enqueue(chunk);
    }
  });
  const verified = await verifyPrivateCiphertextResponse(
    new Response(stream, { headers }), reservation(bytes));
  assert.equal(verified.cipherBytes, bytes.length);
  let cancelled = false;
  const overlong = new ReadableStream({
    start(controller) {
      controller.enqueue(randomBytes(18));
    },
    cancel() { cancelled = true; }
  });
  await assert.rejects(verifyPrivateCiphertextResponse(
    new Response(overlong, { headers }), reservation(randomBytes(17))), /verification failed/);
  assert.equal(cancelled, true);
});

test('HTTP status, content-type, content-encoding and contradictory size fail closed', async () => {
  const bytes = randomBytes(17);
  const expected = reservation(bytes);
  const variants = [
    new Response(bytes, { status: 206, headers }),
    new Response(bytes, { status: 200, headers: { 'content-type': 'text/plain' } }),
    object(bytes, { 'content-encoding': 'gzip' }),
    object(bytes, { 'content-length': '18' }),
    object(bytes, { 'content-length': '00017' }),
    new Response(null, { status: 200, headers })
  ];
  for (const response of variants)
    await assert.rejects(verifyPrivateCiphertextResponse(response, expected), /verification failed/);
});

test('invalid reservation ownership-bound fields, sizes, state and expiry fail closed', async () => {
  const bytes = randomBytes(17), original = reservation(bytes);
  const expired = new Date(Date.now() - 60_000).toISOString();
  const variants = [
    { fileId: '../escape' }, { fileId: 'F'.repeat(32) },
    { cipherSha256: 'g'.repeat(64) }, { cipherSha256: '0'.repeat(63) },
    { cipherBytes: 16 }, { cipherBytes: 41_953_281 },
    { cipherBytes: 17.4 }, { cipherBytes: -1 },
    { state: 'ready' }, { state: 'cancelled' }, { expiresAt: expired },
    { expiresAt: 'invalid' }
  ];
  for (const overrides of variants)
    await assert.rejects(verifyPrivateCiphertextResponse(
      object(bytes), { ...original, ...overrides }), /verification failed/);
});

test('stream reader failure does not approve an object', async () => {
  const bytes = randomBytes(17);
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(bytes.subarray(0, 8));
      controller.error(new Error('network disconnect'));
    }
  });
  await assert.rejects(verifyPrivateCiphertextResponse(
    new Response(stream, { headers }), reservation(bytes)), /network disconnect/);
});
