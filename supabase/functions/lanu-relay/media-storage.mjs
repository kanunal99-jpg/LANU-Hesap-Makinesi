import { verifyPrivateCiphertextResponse } from './media-verify.mjs';

/**
 * Server-only Supabase Storage adapter. It never receives plaintext, media
 * keys, contact names, client-selected paths or arbitrary network URLs.
 *
 * Signed upload grants are bearer capabilities: only the sender receives one;
 * upsert is always false, so an upload cannot replace a committed object.
 * Neither Storage credentials nor bearer tokens may enter app databases/logs.
 */
const BUCKET = 'lanu-private-media-v1';
const FILE_ID = /^[a-f0-9]{32}$/;
const MAX_BYTES = 41_953_280;
const fail = () => new Error('Private media storage operation unavailable');

function storageObject(fileId) {
  if (!FILE_ID.test(fileId ?? '')) throw fail();
  return BUCKET + '/v1/' + fileId + '.cipher';
}

async function smallJson(response) {
  // A remote Storage error body must not exhaust the Edge isolate.
  const reader = response.body?.getReader();
  if (!reader) throw fail();
  const chunks = [];
  let size = 0, success = false;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!(value instanceof Uint8Array) || (size += value.byteLength) > 8192) throw fail();
      chunks.push(value);
    }
    const joined = new Uint8Array(size);
    let offset = 0;
    for (const part of chunks) { joined.set(part, offset); offset += part.length; }
    let data;
    try { data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(joined)); }
    catch { throw fail(); }
    if (!response.ok || !data || typeof data !== 'object' || Array.isArray(data)) throw fail();
    success = true;
    return data;
  } finally {
    if (!success) try { await reader.cancel(); } catch { /* closed */ }
    reader.releaseLock();
  }
}

export function createPrivateMediaStorage(projectUrl, serviceKey, fetcher = fetch) {
  const origin = new URL(projectUrl);
  if (origin.protocol !== 'https:' || origin.username || origin.password ||
      origin.pathname !== '/' && origin.pathname !== '' ||
      origin.search || origin.hash ||
      !/^[a-z0-9-]+\.supabase\.co$/.test(origin.hostname) ||
      typeof serviceKey !== 'string' || serviceKey.length < 20) throw fail();

  const base = origin.origin + '/storage/v1/';
  const keyHeaders = {
    apikey: serviceKey,
    ...(serviceKey.startsWith('sb_secret_') ? {} : { Authorization: 'Bearer ' + serviceKey })
  };

  async function request(relativePath, method, body, timeout = 15000) {
    try {
      // An AbortSignal timeout remains active for the complete streamed body.
      // Clearing a timer after response HEADERS alone would allow a stuck
      // private 40MiB GET to run indefinitely.
      return await fetcher(base + relativePath, {
        method,
        redirect: 'error',
        headers: {
          ...keyHeaders,
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          Accept: 'application/octet-stream, application/json'
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(timeout)
      });
    } catch {
      throw fail();
    }
  }

  async function signedUpload(plan) {
    const path = storageObject(plan.fileId);
    if (!Number.isSafeInteger(plan.cipherBytes) || plan.cipherBytes < 17 ||
        plan.cipherBytes > MAX_BYTES) throw fail();
    const r = await request('object/upload/sign/' + path, 'POST', { upsert: false });
    const data = await smallJson(r);
    if (typeof data.token !== 'string' || data.token.length < 16 ||
        data.token.length > 4096 || !/^[A-Za-z0-9._~-]+$/.test(data.token)) throw fail();
    // Never trust Storage JSON's "url" as an arbitrary redirect destination.
    // Construct only the immutable private object route from verified fileId.
    return Object.freeze({
      url: base + 'object/upload/sign/' + path + '?token=' + encodeURIComponent(data.token),
      // Supabase signed upload tokens are valid for up to 2 hours.
      expiresInSeconds: 7200
    });
  }

  async function signedDownload(plan) {
    const path = storageObject(plan.fileId);
    const r = await request('object/sign/' + path, 'POST', { expiresIn: 60 });
    const data = await smallJson(r);
    const signed = data.signedURL;
    if (typeof signed !== 'string' || signed.length > 6000) throw fail();
    let url;
    try {
      // Storage may return either /object/sign/... (relative to /storage/v1)
      // or the fully qualified /storage/v1/object/sign/... route.
      const canonical = signed.startsWith('/object/sign/')
        ? base.slice(0, -1) + signed
        : signed.startsWith('/storage/v1/object/sign/')
          ? origin.origin + signed : signed;
      url = new URL(canonical, base);
    } catch { throw fail(); }
    const required = '/storage/v1/object/sign/' + path;
    // A signed link must stay on THIS project's private Storage hostname and
    // exact validated path. No client-selected URLs, offsite redirects or fragments.
    const token = url.searchParams.get('token');
    if (url.origin !== origin.origin || url.pathname !== required || url.hash ||
        [...url.searchParams.keys()].join(',') !== 'token' ||
        typeof token !== 'string' || token.length < 16 || token.length > 4096 ||
        !/^[A-Za-z0-9._~-]+$/.test(token)) throw fail();
    return Object.freeze({ url: url.href, expiresInSeconds: 60 });
  }

  async function verifyUploaded(plan, clock = Date.now) {
    const path = storageObject(plan.fileId);
    const r = await request('object/authenticated/' + path, 'GET', undefined, 90000);
    // verifyPrivateCiphertextResponse uses constant-space SHA256 streaming,
    // strict content-length, state, TTL and type checks.
    return verifyPrivateCiphertextResponse(r, {
      fileId: plan.fileId,
      cipherBytes: plan.cipherBytes,
      cipherSha256: plan.cipherSha256,
      state: 'pending',
      expiresAt: new Date(plan.expireAt).toISOString()
    }, clock);
  }

  async function removeExpiredCiphertext(bucket, path) {
    if (bucket !== BUCKET || typeof path !== 'string' ||
        !/^v1\/[a-f0-9]{32}\.cipher$/.test(path)) throw fail();
    // Supabase Storage REST remove() uses DELETE /object/{bucket} with
    // { prefixes: [path] }; delete via Storage, never storage.objects SQL.
    // The response is bounded and must explicitly identify this sole key.
    const response = await request('object/' + BUCKET, 'DELETE',
      { prefixes: [path] }, 15_000);
    if (!response.ok) return false;
    const reader = response.body?.getReader();
    if (!reader) return false;
    let size = 0;
    const chunks = [];
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!(value instanceof Uint8Array) || (size += value.byteLength) > 8192)
          return false;
        chunks.push(value);
      }
    } finally { reader.releaseLock(); }
    let deleted;
    try {
      const joined = new Uint8Array(size);
      let offset = 0;
      for (const part of chunks) { joined.set(part, offset); offset += part.length; }
      deleted = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(joined));
    } catch { return false; }
    // If Storage reports nothing deleted, keep metadata quota reserved until
    // an independently verified absence check exists. No optimistic purge.
    return Array.isArray(deleted) && deleted.length === 1 &&
      (deleted[0]?.name === path || deleted[0]?.path === path);
  }

  return Object.freeze({ signedUpload, signedDownload, verifyUploaded,
    removeExpiredCiphertext });
}
