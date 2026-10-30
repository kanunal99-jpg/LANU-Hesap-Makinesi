/**
 * Pure server-side authorization gate for a FUTURE private Storage grant.
 *
 * This module never signs URLs, uploads/downloads objects, changes database
 * state, or exposes a grant to clients. It must execute only AFTER the relay
 * has verified the owner's ECDSA envelope and replay nonce, with reservation
 * read through privileged Storage metadata access. Live grant issuance stays
 * disabled until atomic upload verification, full E2EE key delivery, TTL,
 * cleanup, and low-bandwidth device acceptance exist.
 */
const HEX_32 = /^[a-f0-9]{32}$/;
const HEX_64 = /^[a-f0-9]{64}$/;
const MIN_BYTES = 17;
const MAX_BYTES = 41_953_280;
const VALID_STATES = new Set(['pending', 'ready']);
const safeNow = x => Number.isSafeInteger(x) && x > 0;
function reject() { throw new Error('private storage grant not authorized'); }

export function authorizePrivateMediaGrant(owner, mode, reservation, nowMs) {
  if (!HEX_64.test(owner ?? '') || !['upload', 'download'].includes(mode) ||
      !safeNow(nowMs) || !reservation ||
      typeof reservation !== 'object' || Array.isArray(reservation))
    reject();
  const id = reservation.file_id;
  if (!HEX_32.test(id ?? '') ||
      !HEX_64.test(reservation.sender_id ?? '') ||
      !HEX_64.test(reservation.recipient_id ?? '') ||
      reservation.sender_id === reservation.recipient_id ||
      !HEX_64.test(reservation.cipher_sha256 ?? '') ||
      !Number.isSafeInteger(reservation.cipher_bytes) ||
      reservation.cipher_bytes < MIN_BYTES || reservation.cipher_bytes > MAX_BYTES ||
      !VALID_STATES.has(reservation.state) ||
      typeof reservation.expires_at !== 'string')
    reject();
  const expires = Date.parse(reservation.expires_at);
  if (!Number.isFinite(expires) || expires <= nowMs) reject();
  if (mode === 'upload') {
    // A receiver must NEVER be able to overwrite a sender's ciphertext.
    if (reservation.state !== 'pending' || reservation.sender_id !== owner) reject();
  } else {
    // Only a committed, server-hash-verified object can be downloaded.
    if (reservation.state !== 'ready' ||
        (reservation.sender_id !== owner && reservation.recipient_id !== owner))
      reject();
  }
  // Deterministic, server-derived path; client input never supplies folders
  // or URLs. This returned path is INTERNAL ONLY; no direct client exposure.
  return Object.freeze({
    bucket: 'lanu-private-media-v1',
    path: 'v1/' + id + '.cipher',
    cipherBytes: reservation.cipher_bytes,
    cipherSha256: reservation.cipher_sha256,
    expireAt: expires
  });
}
