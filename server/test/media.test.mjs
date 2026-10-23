import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { fixture, link, encrypted, envelope } from './fixtures.mjs';

const digest = 'b'.repeat(64);
function upload(id, peerId, overrides = {}) {
  return { messageId: id, peerId, cipherBytes: 1200, cipherSha256: digest, ...overrides };
}

test('private media requires mutual verified contacts and strict digest and payload bounds', async () => {
  const f = await fixture();
  const id = randomUUID();
  await assert.rejects(f.relay(envelope(f.alice, 'mediaUpload', upload(id, f.bob.id))), { status: 409 });
  await link(f);
  for (const bad of [
    upload(id, f.bob.id, { cipherBytes: 0 }),
    upload(id, f.bob.id, { cipherBytes: 2 * 1024 * 1024 + 1 }),
    upload(id, f.bob.id, { cipherSha256: 'not-hash' }),
    upload(id, f.bob.id, { peerId: f.alice.id }),
    upload('not-uuid', f.bob.id)
  ]) {
    await assert.rejects(f.relay(envelope(f.alice, 'mediaUpload', bad)), { status: 400 });
  }
  assert.equal(f.store.mediaFiles.size, 0);
});

test('upload reservation never grants unregistered outsiders media access', async () => {
  const f = await fixture();
  await link(f);
  const id = randomUUID();
  const url = await f.relay(envelope(f.alice, 'mediaUpload', upload(id, f.bob.id)));
  assert.match(url.uploadUrl, /\/upload\//);
  // Not yet a message; even authorized recipient cannot obtain download URL.
  await assert.rejects(f.relay(envelope(f.bob, 'mediaDownload', { messageId: id })), { status: 404 });
  const content = encrypted(f.alice, f.bob, id, 'LANU_MEDIA_V1:encrypted metadata');
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content }));
  for (const person of [f.alice, f.bob]) {
    const result = await f.relay(envelope(person, 'mediaDownload', { messageId: id }));
    assert.equal(result.cipherBytes, 1200);
    assert.equal(result.cipherSha256, digest);
    assert.match(result.downloadUrl, /\/download\//);
  }
  await assert.rejects(f.relay(envelope(f.mallory, 'mediaDownload', { messageId: id })), { status: 404 });
  await assert.rejects(f.relay(envelope(f.mallory, 'mediaUpload', upload(randomUUID(), f.bob.id))), { status: 409 });
});

test('replay with modified media hash, recipient or size is rejected', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID(), request = upload(id, f.bob.id);
  await f.relay(envelope(f.alice, 'mediaUpload', request));
  assert.match((await f.relay(envelope(f.alice, 'mediaUpload', request))).uploadUrl, /upload/);
  for (const change of [{ cipherSha256: 'a'.repeat(64) }, { cipherBytes: 1300 }, { peerId: f.mallory.id }]) {
    await assert.rejects(f.relay(envelope(f.alice, 'mediaUpload', { ...request, ...change })), { status: change.peerId ? 409 : 429 });
  }
  assert.equal(f.store.mediaFiles.size, 1);
});

test('deleted messages cannot obtain newly signed media download URLs', async () => {
  const f = await fixture(); await link(f);
  const id = randomUUID();
  await f.relay(envelope(f.alice, 'mediaUpload', upload(id, f.bob.id)));
  await f.relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'metadata') }));
  await f.relay(envelope(f.alice, 'delete', { messageId: id }));
  await assert.rejects(f.relay(envelope(f.bob, 'mediaDownload', { messageId: id })), { status: 404 });
});
