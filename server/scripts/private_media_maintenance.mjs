/**
 * Service-role-only operator maintenance. Never exposed via public Edge routes.
 *
 * The default is COUNTS-ONLY preview. An explicit, two-gate environment
 * authorization is required to delete expired encrypted Storage objects.
 * Existing message envelopes, identity, Room and E2EE keys are never touched.
 */
import { pathToFileURL } from 'node:url';
import { RestStore } from '../../supabase/functions/lanu-relay/core.mjs';
import { createPrivateMediaStorage } from '../../supabase/functions/lanu-relay/media-storage.mjs';
import { cleanExpiredPrivateMedia } from '../../supabase/functions/lanu-relay/media-retention.mjs';

export async function runPrivateMediaMaintenance({
  store, storage, mode = 'preview', nowMs = Date.now(), limit = 10,
  approved = false
}) {
  if (!['preview', 'apply'].includes(mode) ||
      !Number.isSafeInteger(nowMs) || nowMs < 1 ||
      !Number.isSafeInteger(limit) || limit < 1 || limit > 25 ||
      typeof store?.expiredMedia !== 'function') {
    throw new Error('Private media maintenance configuration rejected');
  }
  if (mode === 'preview') {
    // Read-only: do not emit file IDs, identity IDs, tokens or timestamps.
    const rows = await store.expiredMedia(nowMs, limit);
    if (!Array.isArray(rows) || rows.length > limit) throw new Error('Invalid maintenance preview');
    return Object.freeze({ mode: 'preview', eligible: rows.length, deleted: 0 });
  }
  if (approved !== true ||
      typeof storage?.removeExpiredCiphertext !== 'function' ||
      typeof store.markMediaPurged !== 'function') {
    throw new Error('Private media deletion requires independent approval');
  }
  const result = await cleanExpiredPrivateMedia({ store, storage, nowMs, limit });
  return Object.freeze({ mode: 'apply', eligible: result.scanned,
    deleted: result.purged, refused: result.refused });
}

async function main() {
  // No arguments, flags, credentials or private data are printed.
  const mode = process.env.LANU_MEDIA_MAINTENANCE_MODE || 'preview';
  const project = process.env.SUPABASE_URL;
  const role = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!project || !role) throw new Error('Operator credentials not configured');
  const store = new RestStore(project, role);
  const approved = process.env.LANU_MEDIA_RETENTION_APPLY_APPROVED === 'YES_I_APPROVE_PURGE';
  const result = await runPrivateMediaMaintenance({
    store, mode, approved, limit: 10,
    storage: mode === 'apply' && approved
      ? createPrivateMediaStorage(project, role) : null
  });
  console.info(JSON.stringify(result));
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  main().catch(() => {
    // Credentials and object IDs must never enter CI or operator logs.
    console.error('LANU media maintenance unavailable; no deletion success claimed');
    process.exitCode = 1;
  });
}
