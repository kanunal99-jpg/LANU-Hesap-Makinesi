/**
 * Optional FCM HTTP v1 transport for opt-in voice/video wake hints.
 * No private peer data, caller ID, chat text, SDP or call ID is sent to Google.
 * Use a restricted service account stored ONLY in Supabase Edge secrets.
 */
const encode = obj => btoa(JSON.stringify(obj)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const bytes64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)))
  .replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');

export function createFcmWakeSender(rawCredentials, fetcher = fetch, cryptoApi = crypto) {
  if (!rawCredentials) return null; // Safe default: disabled without credentials.
  let config;
  try { config = JSON.parse(rawCredentials); } catch { return null; }
  if (typeof config.project_id !== 'string' ||
    !/^[a-z][a-z0-9-]{4,45}$/.test(config.project_id) ||
    typeof config.client_email !== 'string' ||
    !config.client_email.endsWith('.gserviceaccount.com') ||
    typeof config.private_key !== 'string' ||
    !config.private_key.includes('BEGIN PRIVATE KEY')) return null;

  let cached = null;
  let expires = 0;
  // Several simultaneous incoming calls should share one OAuth refresh.
  // Never serialize distinct FCM sends, and never cache a failed refresh.
  let refreshing = null;
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
    const response = await fetcher('https://oauth2.googleapis.com/token',{
      method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion}).toString(),
      signal:AbortSignal.timeout(6000)
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
  return async (deviceToken,now=Date.now()) => {
    if (typeof deviceToken !== 'string' || deviceToken.length < 20 ||
      deviceToken.length > 4096 || !/^[A-Za-z0-9_:.-]+$/.test(deviceToken)) return false;
    const bearer = await accessToken();
    const response = await fetcher('https://fcm.googleapis.com/v1/projects/'+config.project_id+'/messages:send',{
      method:'POST',headers:{Authorization:'Bearer '+bearer,'Content-Type':'application/json'},
      body:JSON.stringify({message:{
        token:deviceToken,
        // Only two nonsensitive fields. No notification section: Android app
        // creates its own privacy-redacted opt-in notification.
        data:{type:'lanu_call_wake_v1',at:String(now)},
        android:{priority:'HIGH',ttl:'70s',collapse_key:'lanu_call_v1'}
      }}),
      // A slow Google endpoint must never indefinitely hold the encrypted
      // call invitation relay. Do not retry rate-limited FCM synchronously.
      signal:AbortSignal.timeout(6000)
    });
    if (!response.ok) throw new Error('FCM dispatch unavailable');
    return true;
  };
}
