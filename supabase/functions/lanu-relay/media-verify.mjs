// The trusted relay can use this after a private Storage upload and before
// ANY status transition to ready. This module intentionally does no I/O
// except reading a caller-supplied response and does not issue signed URLs,
// write to Postgres, or expose private file material.
import { createHash, timingSafeEqual } from 'node:crypto';

const FILE_ID = /^[a-f0-9]{32}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const MAX_CIPHER_BYTES = 41_953_280;
const fail = () => new Error('Private ciphertext object verification failed.');

function fromHex(hex) {
  const data = new Uint8Array(hex.length / 2);
  for (let i = 0; i < data.length; i++) data[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return data;
}

/**
 * Verify a just-uploaded, PRIVATE object against the authenticated reservation.
 *
 * Security assumptions:
 * - Caller fetched an exact, server-owned Storage object path via privileged
 *   credentials; clients must never control a fetch URL.
 * - Reservation identity, recipient, object path and ownership were checked.
 * - Caller owns the final, atomic state transition and rechecks pending+TTL.
 *
 * Reject size mismatches, truncated data, extra bytes, wrong content-encoding,
 * partial responses and same-size tampering. Never buffer the entire 40 MiB
 * ciphertext; SHA-256 is incremental and the stream is cancelled on failure.
 *
 * Not exposed through the deployed Edge handler. NO upload/download is enabled.
 */
export async function verifyPrivateCiphertextResponse(response, reservation, clock = Date.now) {
  if (!reservation || !FILE_ID.test(reservation.fileId ?? '') ||
      !SHA256.test(reservation.cipherSha256 ?? '') ||
      !Number.isSafeInteger(reservation.cipherBytes) ||
      reservation.cipherBytes < 17 || reservation.cipherBytes > MAX_CIPHER_BYTES ||
      reservation.state !== 'pending' ||
      typeof reservation.expiresAt !== 'string' ||
      !Number.isFinite(Date.parse(reservation.expiresAt)) ||
      Date.parse(reservation.expiresAt) <= clock()) throw fail();

  if (!response || response.status !== 200 || !response.body ||
      !/^application\/octet-stream(?:\s*;|$)/i.test(
        response.headers?.get?.('content-type') ?? '') ||
      !['', 'identity'].includes(
        (response.headers?.get?.('content-encoding') ?? '').toLowerCase()
      )) throw fail();

  const advertised = response.headers.get('content-length');
  if (advertised !== null &&
      (!/^(0|[1-9][0-9]*)$/.test(advertised) ||
       Number(advertised) !== reservation.cipherBytes)) throw fail();

  const reader = response.body.getReader();
  const hash = createHash('sha256');
  let consumed = 0;
  let verified = false;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!(value instanceof Uint8Array) || value.length === 0) throw fail();
      consumed += value.byteLength;
      if (consumed > reservation.cipherBytes ||
          consumed > MAX_CIPHER_BYTES) throw fail();
      hash.update(value);
    }
    if (consumed !== reservation.cipherBytes) throw fail();
    const actualDigest = new Uint8Array(hash.digest());
    if (!timingSafeEqual(actualDigest, fromHex(reservation.cipherSha256))) throw fail();
    verified = true;
    return Object.freeze({
      fileId: reservation.fileId,
      cipherBytes: consumed,
      cipherSha256: reservation.cipherSha256
    });
  } finally {
    if (!verified) {
      try { await reader.cancel(); } catch { /* already closed */ }
    }
    reader.releaseLock();
  }
}
