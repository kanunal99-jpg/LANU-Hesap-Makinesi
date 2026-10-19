import { createRelay, RestStore, RelayError } from './core.mjs';

const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
Deno.serve(async (request: Request) => {
  if (request.method !== 'POST') return new Response(JSON.stringify({ error: 'Yalnızca POST desteklenir.' }), { status: 405, headers });
  try {
    const raw = await request.text();
    if (raw.length > 100000) return new Response(JSON.stringify({ error: 'İstek çok büyük.' }), { status: 413, headers });
    const url = Deno.env.get('SUPABASE_URL');
    const modernKeys = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') ?? '{}');
    const key = modernKeys.default ?? Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!url || !key) return new Response(JSON.stringify({ error: 'Sunucu yapılandırması eksik.' }), { status: 503, headers });
    let envelope;
    try { envelope = JSON.parse(raw); } catch { return new Response(JSON.stringify({ error: 'Geçersiz JSON.' }), { status: 400, headers }); }
    const result = await createRelay(new RestStore(url, key))(envelope);
    return new Response(JSON.stringify(result), { status: 200, headers });
  } catch (error) {
    const known = error instanceof RelayError;
    return new Response(JSON.stringify({ error: known ? error.message : 'Sunucu işlemi tamamlayamadı.' }), { status: known ? error.status : 503, headers });
  }
});
