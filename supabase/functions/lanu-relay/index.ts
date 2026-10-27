import { createRelay, RestStore, RelayError, readBoundedRequest } from './core.mjs';
import { createFcmWakeSender } from './call-wake-fcm.mjs';

const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
// Warm Edge instances reuse OAuth access tokens; no secret is exposed to clients.
// Classify configuration *shape* using fixed status labels only. Never log
// raw environment values, field contents, user IDs, project IDs or key bytes.
function serviceAccountStatus(raw: string | undefined): string {
  if (!raw) return 'missing';
  let config: Record<string, unknown>;
  try { config = JSON.parse(raw); } catch { return 'invalid_json'; }
  if (!config || typeof config !== 'object' || Array.isArray(config)) return 'not_object';
  if (config.type !== 'service_account') return 'wrong_document_type';
  if (config.project_id !== 'gen-lang-client-0658022317') return 'project_mismatch';
  if (typeof config.client_email !== 'string' || !config.client_email.endsWith('.gserviceaccount.com')) return 'client_email_invalid';
  if (typeof config.private_key !== 'string' || !config.private_key.includes('BEGIN PRIVATE KEY')) return 'key_format_invalid';
  return 'shape_valid';
}
const fcmCredentials = Deno.env.get('LANU_FCM_SERVICE_ACCOUNT_JSON');
const sendCallWake = createFcmWakeSender(fcmCredentials);
const sendMessageWake = sendCallWake
  ? (token: string, at: number) => sendCallWake(token, at, 'message')
  : null;
console.info('LANU call notification sender: ' + (sendCallWake ? 'configured' : 'not_configured'));
console.info('LANU FCM credentials shape: ' + serviceAccountStatus(fcmCredentials));
Deno.serve(async (request: Request) => {
  if (request.method !== 'POST') return new Response(JSON.stringify({ error: 'Yalnızca POST desteklenir.' }), { status: 405, headers });
  try {
    // Reject excess bytes while streaming, never after request.text() buffers the full body.
    const raw = await readBoundedRequest(request);
    const url = Deno.env.get('SUPABASE_URL');
    const modernKeys = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') ?? '{}');
    const key = modernKeys.default ?? Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!url || !key) return new Response(JSON.stringify({ error: 'Sunucu yapılandırması eksik.' }), { status: 503, headers });
    let envelope;
    try { envelope = JSON.parse(raw); } catch { return new Response(JSON.stringify({ error: 'Geçersiz JSON.' }), { status: 400, headers }); }
    // Feature gate: no secret -> no registration and no wakeups. No extra
    // network service, billing, or background polling is started by default.
    const result = await createRelay(new RestStore(url, key), Date.now,
      { calls: true, callWakeEnabled: Boolean(sendCallWake), sendCallWake,
        messageWakeEnabled: Boolean(sendMessageWake), sendMessageWake })(envelope);
    return new Response(JSON.stringify(result), { status: 200, headers });
  } catch (error) {
    const known = error instanceof RelayError;
    // Only fixed, non-sensitive error categories: no payloads, tokens or owner IDs.
    if (known && error.status === 503) {
      const category = error.message === 'Çağrı bildirimi sunucuda etkin değil.'
        ? 'notification_sender_disabled'
        : error.message === 'Cihaz bildirim kaydı tamamlanamadı.'
          ? 'registration_rejected'
          : error.message === 'Mesajlaşma sunucusu işlemi tamamlayamadı.'
            ? 'registration_store_unavailable'
            : 'other_service_unavailable';
      console.warn('LANU relay unavailable: ' + category);
    }
    return new Response(JSON.stringify({ error: known ? error.message : 'Sunucu işlemi tamamlayamadı.' }), { status: known ? error.status : 503, headers });
  }
});

