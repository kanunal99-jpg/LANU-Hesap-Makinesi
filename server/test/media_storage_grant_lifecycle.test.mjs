import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { createPrivateMediaStorage } from '../../supabase/functions/lanu-relay/media-storage.mjs';
import { fixture, envelope, link } from './fixtures.mjs';

const A = 'a'.repeat(32);
const ID = 'a'.repeat(32);
const SHA = 'b'.repeat(64);
const rows = (f, changed = {}) => ({
  file_id: ID, message_id: randomUUID(), sender_id: f.alice.id,
  recipient_id: f.bob.id, cipher_bytes: 65552, cipher_sha256: SHA,
  state: 'pending', expires_at: new Date(Date.now() + 60_000).toISOString(),
  ...changed
});

async function configured() {
  const f = await fixture();
  await link(f);
  let row = rows(f);
  let signed = 0, verified = 0, finalized = 0;
  const storage = {
    signedUpload: async () => { signed++; return { url: 'https://example.supabase.co/storage/v1/object/upload/sign/private', expiresInSeconds: 7200 }; },
    signedDownload: async () => { signed++; return { url: 'https://example.supabase.co/storage/v1/object/sign/private', expiresInSeconds: 60 }; },
    verifyUploaded: async () => { verified++; },
  };
  f.store.mediaByFile = async id => row?.file_id === id ? { ...row } : null;
  f.store.markMediaReady = async original => {
    finalized++;
    if (row.state !== 'pending' || row.file_id !== original.file_id) return false;
    row = { ...row, state: 'ready' }; return true;
  };
  const relay = createRelay(f.store, Date.now,
    { privateMediaTransfers: true, mediaStorage: storage });
  return { ...f, relay, getRow: () => row,
    setRow: x => { row = x; }, counts: () => ({ signed, verified, finalized }) };
}

test('disabled deployment cannot mint signed media capabilities', async () => {
  const f = await configured();
  const disabled = createRelay(f.store);
  for (const action of ['mediaUploadGrant', 'mediaDownloadGrant', 'mediaComplete', 'enablePrivateMedia'])
    await assert.rejects(disabled(envelope(f.alice, action,
      action === 'enablePrivateMedia' ? { version: 1 } : { fileId: ID })), { status: 503 });
  assert.deepEqual(f.counts(), { signed: 0, verified: 0, finalized: 0 });
});

test('sender uploads only pending media, receiver downloads only after server verified ready', async () => {
  const f = await configured();
  const upload = await f.relay(envelope(f.alice, 'mediaUploadGrant', { fileId: ID }));
  assert.equal(upload.fileId, ID);
  assert.equal(upload.expiresInSeconds, 7200);
  await assert.rejects(f.relay(envelope(f.bob, 'mediaUploadGrant', { fileId: ID })), { status: 403 });
  await assert.rejects(f.relay(envelope(f.bob, 'mediaDownloadGrant', { fileId: ID })), { status: 403 });
  await assert.rejects(f.relay(envelope(f.mallory, 'mediaComplete', { fileId: ID })), { status: 403 });
  assert.equal((await f.relay(envelope(f.alice, 'mediaComplete', { fileId: ID }))).state, 'ready');
  assert.equal(f.getRow().state, 'ready');
  for (const who of [f.alice, f.bob]) {
    const grant = await f.relay(envelope(who, 'mediaDownloadGrant', { fileId: ID }));
    assert.equal(grant.expiresInSeconds, 60);
    assert.ok(!JSON.stringify(grant).includes(f.alice.id));
  }
  await assert.rejects(f.relay(envelope(f.alice, 'mediaUploadGrant', { fileId: ID })), { status: 403 });
  assert.deepEqual(f.counts(), { signed: 3, verified: 1, finalized: 1 });
});

test('expired, forged, replaced and revoked grants fail closed before signing', async () => {
  const f = await configured();
  const initial = f.getRow();
  for (const changes of [
    { expires_at: new Date(Date.now() - 60_000).toISOString() },
    { state: 'purged' }, { state: 'cancelled' },
    { sender_id: f.bob.id }, { cipher_sha256: 'bad' },
    { cipher_bytes: 41_953_281 }
  ]) {
    f.setRow({ ...initial, ...changes });
    await assert.rejects(f.relay(envelope(f.alice, 'mediaUploadGrant', { fileId: ID })), { status: 403 });
  }
  f.setRow(initial);
  for (const bad of [{ fileId: 'other' }, { fileId: '../escape' },
    { fileId: ID, url: 'https://evil.invalid' }, { fileId: ID, secret: 's' }])
    await assert.rejects(f.relay(envelope(f.alice, 'mediaUploadGrant', bad)),
      { status: 400 });
  f.store.mutual = async () => false;
  await assert.rejects(f.relay(envelope(f.alice, 'mediaUploadGrant', { fileId: ID })), { status: 403 });
  assert.deepEqual(f.counts(), { signed: 0, verified: 0, finalized: 0 });
});

test('failed storage verification never marks a partially uploaded file ready', async () => {
  const f = await configured();
  let called = 0;
  const storage = {
    signedUpload: async () => ({ url: 'https://example.supabase.co/a', expiresInSeconds: 2 }),
    signedDownload: async () => ({ url: 'https://example.supabase.co/a', expiresInSeconds: 2 }),
    verifyUploaded: async () => { called++; throw Error('bad SHA'); }
  };
  const relay = createRelay(f.store, Date.now, { privateMediaTransfers: true, mediaStorage: storage });
  await assert.rejects(relay(envelope(f.alice, 'mediaComplete', { fileId: ID })), /bad SHA/);
  assert.equal(called, 1);
  assert.equal(f.getRow().state, 'pending');
  assert.equal(f.counts().finalized, 0);
});

test('Storage adapter signs private paths only, detects tampered ciphertext', async () => {
  const cipher = randomBytes(65_552);
  const hash = createHash('sha256').update(cipher).digest('hex');
  const calls = [];
  const token = 'a'.repeat(80);
  const fetcher = async (url, options) => {
    calls.push({ url, method: options.method, redirect: options.redirect });
    if (url.includes('/upload/sign/'))
      return Response.json({ token });
    if (url.includes('/object/sign/'))
      return Response.json({ signedURL: '/object/sign/lanu-private-media-v1/v1/' + ID + '.cipher?token=' + token });
    if (url.includes('/object/authenticated/'))
      return new Response(cipher, { status: 200, headers: { 'Content-Type': 'application/octet-stream' } });
    throw Error('unexpected route');
  };
  const service = createPrivateMediaStorage('https://example.supabase.co', 'sb_secret_' + 'x'.repeat(40), fetcher);
  const plan = { fileId: ID, cipherBytes: cipher.length, cipherSha256: hash, expireAt: Date.now() + 60_000 };
  const up = await service.signedUpload(plan);
  assert.match(up.url, /\/object\/upload\/sign\/lanu-private-media-v1\/v1\/a{32}\.cipher\?token=/);
  const down = await service.signedDownload(plan);
  assert.match(down.url, /\/object\/sign\/lanu-private-media-v1\/v1\/a{32}\.cipher\?token=/);
  assert.equal((await service.verifyUploaded(plan)).cipherSha256, hash);
  assert.ok(calls.every(x => x.url.startsWith('https://example.supabase.co/storage/v1/')));
  assert.ok(calls.every(x => x.redirect === 'error'));
  await assert.rejects(service.verifyUploaded({ ...plan, cipherSha256: SHA }));
  await assert.rejects(service.signedUpload({ ...plan, fileId: '../escape' }));
});

test('Storage download rejects offsite or unrelated signed URL', async () => {
  const token = 'x'.repeat(60);
  for (const signedURL of ['https://evil.invalid/steal?token=' + token,
    '/object/sign/other/path?token=' + token,
    '/object/sign/lanu-private-media-v1/v1/' + ID + '.cipher']) {
    const service = createPrivateMediaStorage('https://example.supabase.co', 'sb_secret_' + token,
      async () => Response.json({ signedURL }));
    await assert.rejects(service.signedDownload({ fileId: ID }));
  }
});
