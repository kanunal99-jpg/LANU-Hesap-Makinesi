import { authorizePrivateMediaGrant } from './media-grants.mjs';
import { verifyPrivateCiphertextResponse } from './media-verify.mjs';

const FILE_ID = /^[a-f0-9]{32}$/;
const BAD_REQUEST = 'Güvenli dosya isteği geçersiz.';
const UNAVAILABLE = 'Güvenli dosya aktarımı kullanılamıyor.';
const MAX_SIGNED_DOWNLOAD_SECONDS = 90;
const reject = (status, message) => Object.assign(new Error(message), { status });

/**
 * The owner is already verified by createRelay's ECDSA signature, registered
 * identity, anti-replay nonce and mutual peer check (when reserving).
 *
 * Only server-owned reservation rows may decide the Storage path. No client
 * supplied URL, MIME, bucket, secret, key, filename, or object path is accepted.
 * External Storage operations and DB state transitions are deliberately
 * injected for deterministic failure/authorization tests.
 */
export function createPrivateMediaTransfer({ store, storage, now = Date.now }) {
  if (!store || !storage ||
      typeof store.mediaReservation !== 'function' ||
      typeof store.markMediaReady !== 'function' ||
      typeof storage.signUpload !== 'function' ||
      typeof storage.signDownload !== 'function' ||
      typeof storage.readForVerification !== 'function') throw new Error('media transfer adapter missing');

  async function authorized(owner, fileId, mode) {
    if (typeof owner !== 'string' || !/^[a-f0-9]{64}$/.test(owner) ||
        typeof fileId !== 'string' || !FILE_ID.test(fileId)) throw reject(400, BAD_REQUEST);
    const row = await store.mediaReservation(fileId);
    if (!row) throw reject(404, 'Dosya bulunamadı veya süresi doldu.');
    let plan;
    try { plan = authorizePrivateMediaGrant(owner, mode, row, now()); }
    catch { throw reject(403, 'Dosyaya erişim izni bulunamadı.'); }
    return { row, plan };
  }

  function signedValue(value, field) {
    if (!value || typeof value[field] !== 'string' ||
        value[field].length < 8 || value[field].length > 4096) throw reject(503, UNAVAILABLE);
    // Never return arbitrary URLs (malicious adapter/config/relay reflection).
    const text = value[field];
    if (!text.startsWith('https://') || /[\\r\\n\\s]/.test(text)) throw reject(503, UNAVAILABLE);
    return text;
  }

  async function uploadGrant(owner, fileId) {
    const { plan } = await authorized(owner, fileId, 'upload');
    let signed;
    try { signed = await storage.signUpload(plan.bucket, plan.path); }
    catch { throw reject(503, UNAVAILABLE); }
    const url = signedValue(signed, 'url');
    if (typeof signed.token !== 'string' ||
        signed.token.length < 8 || signed.token.length > 4096 ||
        /[\\r\\n\\s]/.test(signed.token)) throw reject(503, UNAVAILABLE);
    return { fileId, url, token: signed.token, cipherBytes: plan.cipherBytes };
  }

  async function finalize(owner, fileId) {
    const { row, plan } = await authorized(owner, fileId, 'upload');
    let response;
    try { response = await storage.readForVerification(plan.bucket, plan.path); }
    catch { throw reject(503, 'Şifreli dosya doğrulanamadı.'); }
    const reservation = {
      fileId, cipherBytes: plan.cipherBytes, cipherSha256: plan.cipherSha256,
      state: row.state, expiresAt: row.expires_at
    };
    // A complete SHA256 of the opaque ciphertext is a PRECONDITION of ready.
    // A failed or timed-out Storage response leaves the DB row pending.
    let checked;
    try { checked = await verifyPrivateCiphertextResponse(response, reservation, now); }
    catch { throw reject(409, 'Şifreli dosya eksik veya bütünlüğü geçersiz.'); }
    let committed;
    try {
      committed = await store.markMediaReady({
        owner, fileId: checked.fileId, cipherBytes: checked.cipherBytes,
        cipherSha256: checked.cipherSha256
      });
    } catch { throw reject(503, UNAVAILABLE); }
    if (!committed || committed.file_id !== fileId ||
        committed.sender_id !== owner || committed.state !== 'ready' ||
        Number(committed.cipher_bytes) !== checked.cipherBytes ||
        committed.cipher_sha256 !== checked.cipherSha256 ||
        Date.parse(committed.expires_at) <= now()) throw reject(409, 'Dosya onaylanamadı.');
    return { fileId, ready: true, cipherBytes: checked.cipherBytes };
  }

  async function downloadGrant(owner, fileId) {
    const { plan } = await authorized(owner, fileId, 'download');
    let signed;
    try { signed = await storage.signDownload(plan.bucket, plan.path, MAX_SIGNED_DOWNLOAD_SECONDS); }
    catch { throw reject(503, UNAVAILABLE); }
    const url = signedValue(signed, 'url');
    return { fileId, url, cipherBytes: plan.cipherBytes,
      cipherSha256: plan.cipherSha256,
      expiresAt: Math.min(plan.expireAt, now() + MAX_SIGNED_DOWNLOAD_SECONDS * 1000) };
  }

  return Object.freeze({ uploadGrant, finalize, downloadGrant });
}
