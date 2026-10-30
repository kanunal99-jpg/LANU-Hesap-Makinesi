const BUCKET = 'lanu-private-media-v1';
const FILE = /^v1\/[a-f0-9]{32}\.cipher$/;
const FAIL = 'Özel dosya depolaması kullanılamıyor.';
const MAX_JSON = 8192;

function checkLocation(bucket, path) {
  if (bucket !== BUCKET || typeof path !== 'string' || !FILE.test(path))
    throw new Error('Invalid private media storage location');
}

/**
 * Service-role-only Supabase Storage adapter. The caller cannot supply URL,
 * host, bucket or storage path. The Edge function may use this adapter only
 * after identity-signature/nonce authorization in createRelay().
 *
 * Signed upload: upsert is ALWAYS false. Supabase will reject overwrite;
 * the object's ciphertext hash must be verified before the DB marks ready.
 * No plaintext, file key, MIME or user-selected filename reaches Storage.
 */
export function createPrivateMediaStorage(url, serviceKey, fetcher = fetch) {
  if (typeof url !== 'string' || !/^https:\/\/[a-z0-9.-]+\.supabase\.co\/?$/.test(url) ||
      typeof serviceKey !== 'string' || serviceKey.length < 20)
    throw new Error('Private Storage configuration missing');
  const base = url.replace(/\/$/, '') + '/storage/v1';
  const auth = {
    apikey: serviceKey,
    ...(serviceKey.startsWith('sb_secret_') ? {} : { Authorization: 'Bearer ' + serviceKey })
  };
  async function signed(action, bucket, path, expiresIn) {
    checkLocation(bucket, path);
    const target = base + '/object/' + action + '/' + bucket + '/' + path;
    let response;
    try {
      response = await fetcher(target, {
        method: 'POST',
        headers: { ...auth, 'Content-Type': 'application/json' },
        body: JSON.stringify(action === 'sign' ? { expiresIn } : {}),
        signal: AbortSignal.timeout(12_000),
        redirect: 'error'
      });
    } catch { throw new Error(FAIL); }
    if (!response.ok || Number(response.headers.get('content-length') || 0) > MAX_JSON)
      throw new Error(FAIL);
    let data;
    try {
      const raw = await response.text();
      if (raw.length > MAX_JSON) throw new Error(FAIL);
      data = JSON.parse(raw);
    } catch { throw new Error(FAIL); }
    const segment = action === 'sign' ? data?.signedURL : data?.url;
    if (typeof segment !== 'string' || !segment.startsWith('/object/'+action+'/'))
      throw new Error(FAIL);
    const signedUrl = new URL(base + segment);
    const expected = '/storage/v1/object/' + action + '/' + bucket + '/' + path;
    if (signedUrl.origin !== new URL(base).origin ||
        signedUrl.pathname !== expected ||
        !signedUrl.searchParams.get('token')) throw new Error(FAIL);
    if (action === 'sign')
      return { url: signedUrl.toString() };
    return { url: signedUrl.toString(), token: signedUrl.searchParams.get('token') };
  }
  return Object.freeze({
    signUpload: (bucket, path) => signed('upload/sign', bucket, path),
    signDownload: (bucket, path, seconds) => {
      if (!Number.isInteger(seconds) || seconds < 30 || seconds > 90)
        throw new Error(FAIL);
      return signed('sign', bucket, path, seconds);
    },
    readForVerification: async (bucket, path) => {
      checkLocation(bucket, path);
      try {
        return await fetcher(base + '/object/authenticated/' + bucket + '/' + path, {
          method: 'GET', headers: auth,
          signal: AbortSignal.timeout(60_000), redirect: 'error'
        });
      } catch { throw new Error(FAIL); }
    }
  });
}
