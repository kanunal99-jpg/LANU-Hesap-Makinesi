import { createRelay, RestStore, RelayError, readBoundedRequest } from './core.mjs';
import { createFcmWakeSender } from './call-wake-fcm.mjs';

const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
// Warm Edge instances reuse OAuth access tokens; no secret is exposed to clients.
const sendCallWake = createFcmWakeSender(Deno.env.get('LANU_FCM_SERVICE_ACCOUNT_JSON'));
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
      { calls: true, callWakeEnabled: Boolean(sendCallWake), sendCallWake })(envelope);
    return new Response(JSON.stringify(result), { status: 200, headers });
  } catch (error) {
    const known = error instanceof RelayError;
    return new Response(JSON.stringify({ error: known ? error.message : 'Sunucu işlemi tamamlayamadı.' }), { status: known ? error.status : 503, headers });
  }
});

