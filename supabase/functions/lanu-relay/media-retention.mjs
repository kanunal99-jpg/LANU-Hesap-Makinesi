/**
 * Service-only expired media cleanup coordinator.
 * NO HTTP public entrypoint, cron schedule, or automatic delete is installed.
 * A trusted maintenance worker must own the service role and call this after
 * an independently reviewed deployment. Existing chat E2EE messages are NEVER
 * deleted; metadata stays reserved until Storage deletion is confirmed.
 */
const FILE = /^[a-f0-9]{32}$/;
const MAX_BATCH = 25;
const BUCKET = 'lanu-private-media-v1';
// Signed Supabase upload capabilities may remain valid for two hours after
// issuance, even when our media reservation has expired. A premature purge
// could be followed by a late PUT, leaving an orphaned Storage object.
// Keep a separate five-minute allowance for clock skew and in-flight writes.
const SIGNED_UPLOAD_TTL_MS = 2 * 60 * 60 * 1000;
const CLOCK_SKEW_GRACE_MS = 5 * 60 * 1000;
export function mediaCleanupCutoff(nowMs) {
  const grace = SIGNED_UPLOAD_TTL_MS + CLOCK_SKEW_GRACE_MS;
  if (!Number.isSafeInteger(nowMs) || nowMs <= grace) {
    throw new Error('media cleanup clock unavailable');
  }
  return nowMs - grace;
}

export async function cleanExpiredPrivateMedia({ store, storage, nowMs, limit = 10 }) {
  if (!Number.isSafeInteger(nowMs) || nowMs < 1 ||
      !Number.isSafeInteger(limit) || limit < 1 || limit > MAX_BATCH ||
      typeof store?.expiredMedia !== 'function' ||
      typeof store?.markMediaPurged !== 'function' ||
      typeof storage?.removeExpiredCiphertext !== 'function') {
    throw new Error('media cleanup unavailable');
  }
  const safeCutoffMs = mediaCleanupCutoff(nowMs);
  const candidates = await store.expiredMedia(safeCutoffMs, limit);
  if (!Array.isArray(candidates) || candidates.length > limit)
    throw new Error('media cleanup evidence invalid');
  const seen = new Set();
  let purged = 0, refused = 0;
  for (const row of candidates) {
    // No user-chosen path, owner IDs or plaintext enter object storage.
    const id = row?.file_id;
    if (!FILE.test(id ?? '') || seen.has(id) ||
        row?.state === 'purged' ||
        !['pending', 'ready', 'cancelled'].includes(row?.state) ||
        !Number.isFinite(Date.parse(row?.expires_at)) ||
        Date.parse(row.expires_at) > safeCutoffMs) {
      refused++;
      continue;
    }
    seen.add(id);
    const path = 'v1/' + id + '.cipher';
    try {
      // Exactly one immutable object. A 404 from Storage may mean the object
      // never uploaded; the adapter must verify that no object exists before
      // reporting true. Transient errors never free quota.
      const removed = await storage.removeExpiredCiphertext(BUCKET, path);
      if (removed !== true) { refused++; continue; }
      // Recheck expiry + state under one DB UPDATE; never touch any message.
      if (await store.markMediaPurged(id, row.state, nowMs) === true) purged++;
      else refused++;
    } catch {
      // Failure is isolated per item; remaining objects are still attempted.
      refused++;
    }
  }
  // Privacy safe counts only. Never log IDs, paths, keys or client names.
  return Object.freeze({ scanned: candidates.length, purged, refused });
}
