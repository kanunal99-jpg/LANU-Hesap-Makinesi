/**
 * Optional FCM HTTP v1 transport for opt-in voice/video wake hints.
 * No private peer data, caller ID, chat text, SDP or call ID is sent to Google.
 * Use a restricted service account stored ONLY in Supabase Edge secrets.
 */
const encode = obj => btoa(JSON.stringify(obj)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const bytes64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');

/** Bound each outbound Google request; never leave timers running in a Deno Edge isolate. */
export async function fetchWithDeadline(fetcher, url, init, timeoutMs = 10_000) {
  const controller = new AbortController();
  let response;
  let timer;
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new DOMException('FCM request deadline', 'AbortError'));
    }, timeoutMs);
  });
  try {
    return await Promise.race([deadline, (async () => {
      response = await fetcher(url, {...init, signal:controller.signal});
      if (controller.signal.aborted) {
        response.body?.cancel().catch(() => {});
        throw new DOMException('FCM request deadline', 'AbortError');
      }
      // Keep the deadline through the BODY, not only response headers. Bound
      // memory even for chunked responses without a Content-Length header.
      let payload = '';
      if (response.body) {
        const reader = response.body.getReader();
        const cancel = () => { reader.cancel().catch(() => {}); };
        controller.signal.addEventListener('abort', cancel, {once:true});
        const chunks = [];
        let size = 0;
        try {
          while (true) {
            const {done, value} = await reader.read();
            if (controller.signal.aborted) throw new DOMException('FCM request deadline', 'AbortError');
            if (done) break;
            size += value.byteLength;
            if (size > 65_536) throw new Error('FCM response too large');
            chunks.push(value);
          }
          const bytes = new Uint8Array(size);
          let offset = 0;
          for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
          payload = new TextDecoder().decode(bytes);
        } finally {
          controller.signal.removeEventListener('abort', cancel);
          // Do not await a hostile stream's cancellation promise.
          reader.cancel().catch(() => {});
          reader.releaseLock();
        }
      } else if (typeof response.json === 'function') {
        // Supports body-less transport adapters; production fetch uses streams.
        payload = JSON.stringify(await response.json());
        if (payload.length > 65_536) throw new Error('FCM response too large');
      }
      return {ok:response.ok, status:response.status, headers:response.headers,
        json:async () => JSON.parse(payload)};
    })()]);
  } catch (error) {
    controller.abort();
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

/** Google backpressure is a lower bound, never shortened to fit a call. */
export function fcmRetryDelay(status, headers, attempt, now, random = Math.random) {
  const raw = headers?.get?.('retry-after');
  let after = 0;
  if (raw && /^\d+(\.\d+)?$/.test(raw.trim())) after = Number(raw) * 1000;
  else if (raw) { const date = Date.parse(raw); if (Number.isFinite(date)) after = Math.max(0, date - now); }
  const base = Math.max(status === 429 ? 60_000 : 10_000, 10_000 * 2 ** attempt, after);
  if (!Number.isFinite(base)) return Infinity; // Unbounded header means no safe retry within a wake lifetime.
  return base + Math.floor(base * 0.25 * Math.min(1, Math.max(0, random())));
}

/** Permanent token invalidation, never includes token or Google response data. */
export class FcmUnregisteredTokenError extends Error {
  constructor() { super('FCM_UNREGISTERED'); this.name = 'FcmUnregisteredTokenError'; }
}

export function createFcmWakeSender(rawCredentials, fetcher = fetch, cryptoApi = crypto,
  retryWait = ms => new Promise(resolve => setTimeout(resolve, ms)), options = {}) {
  if (!rawCredentials) return null; // Safe default: disabled without credentials.
  let config;
  try { config = JSON.parse(rawCredentials); } catch { return null; }
  if (!config || typeof config !== 'object' || Array.isArray(config) || config.type !== 'service_account' ||
    config.project_id !== 'gen-lang-client-0658022317' ||
    typeof config.client_email !== 'string' ||
    !config.client_email.endsWith('.gserviceaccount.com') ||
    typeof config.private_key !== 'string' ||
    !config.private_key.includes('BEGIN PRIVATE KEY')) return null;

  let cached = null;
  let expires = 0;
  // Several simultaneous incoming calls should share one OAuth refresh.
  // Never serialize distinct FCM sends, and never cache a failed refresh.
  let refreshing = null;
  const clock = options.clock ?? Date.now;
  const report = (kind, outcome) => {
    // Fixed labels only; telemetry callback cannot break durable delivery.
    try { options.onOutcome?.(kind, outcome); } catch { /* Optional diagnostics. */ }
  };
  async function refreshAccessToken() {
    if (cached && Date.now() < expires - 120_000) return cached;
    const keyBytes = Uint8Array.from(atob(config.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,'')), c=>c.charCodeAt(0));
    const key = await cryptoApi.subtle.importKey('pkcs8', keyBytes,
      {name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
    const issued = Math.floor(Date.now()/1000);
    const claims = {iss:config.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',
      aud:'https://oauth2.googleapis.com/token',iat:issued,exp:issued+3600};
    const unsigned = encode({alg:'RS256',typ:'JWT'})+'.'+encode(claims);
    const signature = await cryptoApi.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(unsigned));
    const assertion = unsigned+'.'+bytes64(signature);
    const response = await fetchWithDeadline(fetcher, 'https://oauth2.googleapis.com/token',{
      method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion}).toString()
    });
    if (!response.ok) throw new Error('FCM authentication unavailable');
    const payload = await response.json();
    if (typeof payload.access_token !== 'string' || payload.access_token.length < 20) throw new Error('FCM token response invalid');
    cached = payload.access_token;
    expires = Date.now() + Math.min(3600,Math.max(120,Number(payload.expires_in)||3600))*1000;
    return cached;
  }
  async function accessToken() {
    if (cached && Date.now() < expires - 120_000) return cached;
    if (!refreshing) {
      refreshing = refreshAccessToken().finally(() => { refreshing = null; });
    }
    return refreshing;
  }
  return async (deviceToken,now=clock(),kind='call') => {
    if (kind !== 'call' && kind !== 'message') return false;
    if (typeof deviceToken !== 'string' || deviceToken.length < 20 ||
      deviceToken.length > 4096 || !/^[A-Za-z0-9_:.-]+$/.test(deviceToken)) return false;
    const deadline = now + (kind === 'message' ? 120_000 : 45_000);
    if (!Number.isSafeInteger(now) || now > clock() + 30_000 || clock() >= deadline) {
      report(kind, 'expired');
      return false;
    }
    let outcome = 'unavailable';
    try {
    const bearer = await accessToken();
    // Three total bounded attempts for a single call or message wake.
    // Reuse the EXACT opaque FCM hint/timestamp on retry so Android's
    // dedupe rejects a duplicate if Google accepted the first lost ACK.
    // Never retry auth, invalid-token,
    // protocol or permanent client-side failures.
    for (let attempt = 0; attempt < 3; attempt++) {
      const remaining = deadline - clock();
      if (remaining <= 0) { outcome = 'expired'; return false; }
      let response;
      try {
        response = await fetchWithDeadline(fetcher, 'https://fcm.googleapis.com/v1/projects/'+config.project_id+'/messages:send',{
      method:'POST',headers:{Authorization:'Bearer '+bearer,'Content-Type':'application/json'},
      body:JSON.stringify({message:{
        token:deviceToken,
        // Only two nonsensitive fields. No notification section: Android app
        // creates its own privacy-redacted opt-in notification.
        data:{type:kind === 'message' ? 'lanu_message_wake_v1' : 'lanu_call_wake_v1',at:String(now)},
        android:{priority:'HIGH',ttl:Math.min(kind === 'message' ? 120 : 45, Math.floor(remaining / 1000))+'s',
          collapse_key:kind === 'message' ? 'lanu_message_v1' : 'lanu_call_v1'}
      }}),
      // The signed relay ACK and encrypted INVITE are independent of FCM.
      // The Edge waitUntil task absorbs this bounded retry without blocking
      // the caller. Never loop past the original call's 45-second lifetime.
        }, Math.min(10_000, remaining));
      } catch (error) {
        if (attempt < 2 &&
            (error instanceof TypeError || error?.name === 'AbortError')) {
          const delay = fcmRetryDelay(0, null, attempt, clock(), options.random);
          if (clock() + delay + 10_000 >= deadline) { outcome = 'expired'; return false; }
          await retryWait(delay);
          continue;
        }
        throw error;
      }
      if (response.ok) { outcome = 'accepted'; return true; }
      if (!response.ok) {
      // FCM HTTP 404 alone can also mean a missing project or endpoint.
      // Only the explicit UNREGISTERED detail proves this *device token*
      // is expired. Never discard an active token on generic 400/401/404/429.
      if (response.status === 404) {
        try {
          const body = await response.json();
          if (Array.isArray(body?.error?.details) &&
            body.error.details.some(detail => detail?.errorCode === 'UNREGISTERED')) {
            throw new FcmUnregisteredTokenError();
          }
        } catch (error) {
          if (error instanceof FcmUnregisteredTokenError) throw error;
        }
      }
        if (attempt < 2 &&
            [408, 429, 500, 502, 503, 504].includes(response.status)) {
          const delay = fcmRetryDelay(response.status, response.headers, attempt, clock(), options.random);
          if (clock() + delay + 10_000 >= deadline) {
            outcome = 'backpressure';
            return false; // Durable ciphertext remains; never send an expired hint.
          }
          await retryWait(delay);
          continue;
        }
        throw new Error('FCM dispatch unavailable');
      }
    }
    throw new Error('FCM dispatch unavailable');
    } catch (error) {
      if (error instanceof FcmUnregisteredTokenError) outcome = 'unregistered';
      throw error;
    } finally { report(kind, outcome); }
  };
}
