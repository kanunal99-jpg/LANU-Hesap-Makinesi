import { FcmUnregisteredTokenError } from './call-wake-fcm.mjs';
// Shared by the deployed edge function and the local protocol/security tests.
const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const encoder = new TextEncoder();
const hex64 = /^[a-f0-9]{64}$/;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const codePattern = /^LANU(?:-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}){5}$/;
const noncePattern = /^[A-Za-z0-9_-]{22}$/;
export class RelayError extends Error { constructor(status, message) { super(message); this.status = status; } }
const insist = (condition, status, message) => { if (!condition) throw new RelayError(status, message); };

/**
 * Stream and count raw bytes BEFORE decoding a request. request.text() reads the
 * entire attacker-controlled body, so checking its length afterwards is too late.
 * Enforce the cap even if Content-Length is missing or deliberately understated.
 */
export async function readBoundedRequest(request, maxBytes = 100000) {
  const declared = request.headers.get('content-length');
  if (declared !== null) {
    insist(/^[0-9]+$/.test(declared), 400, 'Geçersiz istek boyutu.');
    insist(Number(declared) <= maxBytes, 413, 'İstek çok büyük.');
  }
  const reader = request.body?.getReader();
  if (!reader) return '';
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let bytes = 0, complete = false;
  const parts = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      insist(value instanceof Uint8Array, 400, 'Geçersiz istek verisi.');
      bytes += value.byteLength;
      insist(bytes <= maxBytes, 413, 'İstek çok büyük.');
      try { parts.push(decoder.decode(value, { stream: true })); }
      catch { throw new RelayError(400, 'İstek UTF-8 metni geçersiz.'); }
    }
    try { parts.push(decoder.decode()); }
    catch { throw new RelayError(400, 'İstek UTF-8 metni geçersiz.'); }
    complete = true;
    return parts.join('');
  } finally {
    if (!complete) {
      try { await reader.cancel(); } catch { /* connection already terminated */ }
    }
    reader.releaseLock();
  }
}
/**
 * PostgREST results can contain up to 200 inbox events. Stream with a hard
 * decompressed-byte ceiling; response.text() otherwise buffers arbitrary
 * server data before JSON parsing and can exhaust the Edge Function.
 */
export async function readBoundedResponse(response, maxBytes = 16 * 1024 * 1024) {
  try {
    return await readBoundedRequest(response, maxBytes);
  } catch {
    // Never expose PostgREST/transport internals or raw data to the client.
    throw new RelayError(503, 'Mesajlaşma sunucusu yanıtı güvenli sınırları aştı.');
  }
}
function decode(value) { return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=')), c => c.charCodeAt(0)); }
async function digest(value) { return new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value))); }
export async function publicId(publicKey) { return [...await digest(publicKey)].map(v => v.toString(16).padStart(2, '0')).join(''); }
export async function publicCode(publicKey, nonce) {
  let buffer = 0, bits = 0, value = '';
  for (const byte of await digest(publicKey + ':' + nonce)) {
    buffer = (buffer << 8) | byte; bits += 8;
    while (bits >= 5 && value.length < 20) { bits -= 5; value += alphabet[(buffer >>> bits) & 31]; }
    if (value.length === 20) break;
  }
  return 'LANU-' + value.match(/.{4}/g).join('-');
}
// Java's SHA256withECDSA returns DER; WebCrypto requires a fixed 64-byte P-1363 signature.
function signatureBytes(value) {
  const bytes = decode(value);
  insist(bytes.length >= 8 && bytes.length <= 72 && bytes[0] === 0x30 && bytes[1] === bytes.length - 2, 401, 'Geçersiz istek imzası.');
  let offset = 2;
  const result = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    insist(bytes[offset++] === 2, 401, 'Geçersiz istek imzası.');
    let length = bytes[offset++];
    insist(length > 0 && length <= 33 && offset + length <= bytes.length, 401, 'Geçersiz istek imzası.');
    if (length === 33) { insist(bytes[offset] === 0, 401, 'Geçersiz istek imzası.'); offset++; length--; }
    result.set(bytes.slice(offset, offset + length), part * 32 + (32 - length)); offset += length;
  }
  insist(offset === bytes.length, 401, 'Geçersiz istek imzası.');
  return result;
}
async function verify(publicKey, payload, signature) {
  try {
    const key = await crypto.subtle.importKey('spki', decode(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, signatureBytes(signature), encoder.encode(payload));
  } catch { return false; }
}
const messageIds = values => Array.isArray(values) && values.length <= 200 && values.every(value => typeof value === 'string' && uuid.test(value));
const ciphertext = value => typeof value === 'string' && value.length <= 50000 && /^lanu2:[A-Za-z0-9_-]{38,}$/.test(value);
const mediaFileId = /^[a-f0-9]{32}$/;
const MEDIA_MAX_CIPHER_BYTES = 41953280;
/**
 * Reservation-only protocol. Production callers MUST explicitly pass
 * { privateMediaReservation: true } in a reviewed deployment *after* full
 * private Storage grant/upload/download/cleanup implementation is ready.
 * The currently deployed Edge wrapper does not pass this option, so a LANU
 * client cannot activate uploads or metadata reservations by guessing actions.
 */
export function createRelay(store, clock = Date.now, options = {}) {
  return async envelope => {
    insist(envelope && typeof envelope.payload === 'string' && envelope.payload.length <= 70000 && typeof envelope.signature === 'string', 400, 'Geçersiz istek.');
    let input;
    try { input = JSON.parse(envelope.payload); } catch { throw new RelayError(400, 'Geçersiz istek.'); }
    const { action, identityId: owner, ts, requestId } = input;
    const data = input.data ?? {};
    insist(hex64.test(owner ?? '') && uuid.test(requestId ?? '') && typeof action === 'string' && typeof data === 'object' && data !== null && !Array.isArray(data), 400, 'Geçersiz kimlik veya istek.');
    insist(Number.isSafeInteger(ts) && Math.abs(clock() - ts) <= 300000, 401, 'Cihaz saatini kontrol edin; istek süresi geçersiz.');
    let identity = await store.identity(owner);
    const publicKey = action === 'register' ? data.publicKey : identity?.publicKey;
    insist(typeof publicKey === 'string' && publicKey.length <= 256 && await publicId(publicKey) === owner, 401, 'Kimlik doğrulanamadı.');
    insist(await verify(publicKey, envelope.payload, envelope.signature), 401, 'İstek imzası doğrulanamadı.');
    if (action === 'register') {
      insist(noncePattern.test(data.nonce ?? '') && codePattern.test(data.code ?? '') && await publicCode(publicKey, data.nonce) === data.code, 400, 'Kullanıcı kodu anahtarla eşleşmiyor.');
      identity = await store.register({ id: owner, code: data.code, nonce: data.nonce, publicKey });
      insist(identity.publicKey === publicKey, 409, 'Kimlik kaydı çakıştı.');
    }
    insist(identity, 401, 'Kimlik bulunamadı.');
    insist(await store.consumeNonce(owner, requestId, clock()), 409, 'Bu istek daha önce işlendi.');
    switch (action) {
      case 'callSync': {
        insist(options.calls === true, 503, 'Arama sunucusu henüz etkin değil.');
        return { version: 1, signals: await store.callPoll(owner) };
      }
      case 'messageWakeToken': {
        insist(typeof store.messageWakeRegister === 'function',
          503, 'Mesaj bildirimi sunucu kaydı kullanılamıyor.');
        insist(Object.keys(data).sort().join(',') === 'token', 400,
          'Geçersiz mesaj bildirimi tercihi.');
        insist(data.token === null ||
          (typeof data.token === 'string' && data.token.length >= 20 &&
            data.token.length <= 4096 && /^[A-Za-z0-9_:.-]+$/.test(data.token)),
          400, 'Geçersiz bildirim cihaz kaydı.');
        // Revocation must work even if the push transport has been disabled.
        if (data.token !== null) insist(options.messageWakeEnabled === true,
          503, 'Mesaj bildirimi sunucuda etkin değil.');
        const result = await store.messageWakeRegister(owner, data.token);
        insist(result?.registered === true, 503, 'Mesaj cihaz bildirimi kaydedilemedi.');
        return { registered: true };
      }
      case 'callWakeToken': {
        insist(typeof store.callWakeRegister === 'function',
          503, 'Çağrı bildirimi sunucu kaydı kullanılamıyor.');
        insist(Object.keys(data).sort().join(',') === 'token', 400,
          'Geçersiz çağrı bildirimi tercihi.');
        insist(data.token === null ||
          (typeof data.token === 'string' && data.token.length >= 20 &&
            data.token.length <= 4096 && /^[A-Za-z0-9_:.-]+$/.test(data.token)),
          400, 'Geçersiz bildirim cihaz kaydı.');
        // Opt-out must remain possible even if FCM is unavailable or its
        // service-account secret is revoked. Only positive registrations
        // require an enabled push transport; never leave a device stuck
        // opted out with a server-side token that cannot be revoked.
        if (data.token !== null) {
          insist(options.callWakeEnabled === true, 503, 'Çağrı bildirimi sunucuda etkin değil.');
        }
        const result = await store.callWakeRegister(owner, data.token);
        insist(result?.registered === true, 503, 'Cihaz bildirim kaydı tamamlanamadı.');
        return { registered: true };
      }
      case 'callSend': {
        insist(options.calls === true, 503, 'Arama sunucusu henüz etkin değil.');
        insist(uuid.test(data.id ?? '') && uuid.test(data.callId ?? '') &&
          hex64.test(data.peerId ?? '') && data.peerId !== owner &&
          ciphertext(data.content) && data.content.length <= 32768 &&
          (Object.keys(data).sort().join(',') === 'callId,content,id,peerId' ||
            (Object.keys(data).sort().join(',') === 'callId,content,id,peerId,wake' && data.wake === true)),
          400, 'Geçersiz arama sinyali.');
        insist(await store.mutual(owner, data.peerId), 409, 'Arama için karşılıklı kişi kaydı gerekir.');
        // Wake is a request hint, never trusted plaintext call classification.
        // Only an authenticated/mutual signed sender may request it, and only
        // a registered recipient gets a wake. Old clients work unchanged.
        const awake = data.wake === true && options.callWakeEnabled === true &&
          typeof store.callSendWake === 'function';
        const result = awake
          ? await store.callSendWake(owner, data.peerId, data.id, data.callId, data.content)
          : await store.callSend(owner, data.peerId, data.id, data.callId, data.content);
        insist(!result.unavailable, 409, 'İki telefonda da güncel LANU güvenli alanı açık olmalıdır.');
        insist(!result.limited, 429, 'Arama sınırına ulaşıldı; biraz bekleyin.');
        insist(result.accepted === true, 409, 'Arama sinyali kimliği çakıştı.');
        // Never return token to a client, even after push transport fails.
        // If FCM fails, the accepted encrypted invite remains on relay.
        if (awake && result.created && typeof result.wake_token === 'string' &&
          typeof options.sendCallWake === 'function') {
          try { await options.sendCallWake(result.wake_token, clock()); }
          catch (error) {
            // FCM explicitly declared this old device token unregistered.
            // Delete only that *same* stored token, never a newer rotation,
            // and never disclose tokens or errors to the caller/logs.
            if (error instanceof FcmUnregisteredTokenError &&
              typeof store.callWakeInvalidate === 'function') {
              try { await store.callWakeInvalidate(data.peerId, result.wake_token); }
              catch { /* Encrypted INVITE remains accepted and retained. */ }
            }
          }
        }
        return { accepted: true };
      }
      case 'callAck': {
        insist(options.calls === true, 503, 'Arama sunucusu henüz etkin değil.');
        insist(messageIds(data.ids) && data.ids.length <= 24, 400, 'Geçersiz arama bildirimi.');
        await store.callAck(owner, data.ids); return { acknowledged: true };
      }
      case 'register': return { code: identity.code, nonce: identity.nonce };
      case 'rotate': {
        insist(noncePattern.test(data.nonce ?? '') && data.expectedCode === identity.code, 409, 'Kod değişti; kimliği yenileyip tekrar deneyin.');
        const code = await publicCode(publicKey, data.nonce);
        const changed = await store.rotate(owner, identity.code, code, data.nonce);
        insist(changed, 409, 'Kod değişti; tekrar deneyin.');
        return { code, nonce: data.nonce };
      }
      case 'addContact': {
        insist(codePattern.test(data.code ?? ''), 400, 'Geçerli bir LANU kodu girin.');
        const peer = await store.identityByCode(data.code) ?? await store.existingPeerByCode(owner, data.code);
        insist(peer, 404, 'Bu kodla bir kullanıcı bulunamadı.');
        insist(peer.id !== owner, 400, 'Kendi kodunuzu ekleyemezsiniz.');
        await store.addContact(owner, peer.id, data.code);
        return { peer: { ...peer, mutual: await store.mutual(owner, peer.id) } };
      }
      case 'removeContact': {
        insist(hex64.test(data.peerId ?? ''), 400, 'Geçersiz kişi.');
        await store.removeContact(owner, data.peerId); return { removed: true };
      }
      case 'send': {
        insist(uuid.test(data.messageId ?? '') && hex64.test(data.peerId ?? '') && data.peerId !== owner && ciphertext(data.content), 400, 'Geçersiz şifreli mesaj.');
        insist(await store.mutual(owner, data.peerId), 409, 'Yazışmak için karşı tarafın da kodunuzu kaydetmesi gerekir.');
        const existing = await store.message(data.messageId);
        if (existing) insist(existing.senderId === owner && existing.recipientId === data.peerId && existing.content === data.content, 409, 'Mesaj kimliği farklı içerikle kullanılmış.');
        const message = existing ?? await store.send({ id: data.messageId, senderId: owner, recipientId: data.peerId, content: data.content });
        insist(message.senderId === owner && message.recipientId === data.peerId && message.content === data.content, 409, 'Mesaj kimliği çakıştı.');
        // Never dispatch before the encrypted message is durable and validated.
        // Old relays/clients remain compatible. A wake is not a delivery ACK.
        if (!existing && options.messageWakeEnabled === true &&
          typeof options.sendMessageWake === 'function' &&
          typeof store.messageWakeClaim === 'function') {
          try {
            const claimed = await store.messageWakeClaim(owner, data.peerId, data.messageId);
            if (claimed?.claimed === true && typeof claimed.wake_token === 'string') {
              try { await options.sendMessageWake(claimed.wake_token, clock()); }
              catch (error) {
                if (error instanceof FcmUnregisteredTokenError &&
                    typeof store.messageWakeInvalidate === 'function') {
                  try { await store.messageWakeInvalidate(data.peerId, claimed.wake_token); }
                  catch { /* Durable E2EE message must survive notification failure. */ }
                }
              }
            }
          } catch { /* Optional push must never reject an accepted message. */ }
        }
        return { status: message.status };
      }
      case 'reservePrivateMedia': {
        // Disabled by default: NO signed upload URL, object grant or plaintext
        // ever leaves this handler, including when a test enables reservations.
        insist(options.privateMediaReservation === true, 503, 'Güvenli medya aktarımı henüz etkin değil.');
        insist(
          mediaFileId.test(data.fileId ?? '') && uuid.test(data.messageId ?? '') &&
          hex64.test(data.peerId ?? '') && data.peerId !== owner &&
          hex64.test(data.cipherSha256 ?? '') &&
          Number.isSafeInteger(data.cipherBytes) &&
          data.cipherBytes >= 17 && data.cipherBytes <= MEDIA_MAX_CIPHER_BYTES &&
          Object.keys(data).sort().join(',') ===
            'cipherBytes,cipherSha256,fileId,messageId,peerId',
          400, 'Geçersiz şifreli medya rezervasyonu.'
        );
        insist(await store.mutual(owner, data.peerId), 409,
          'Medya paylaşımı için karşılıklı kişi kaydı gerekir.');
        insist(await store.mediaVersion(owner) === 1 &&
          await store.mediaVersion(data.peerId) === 1, 409,
          'Medya gönderimi için iki kullanıcının desteklenen sürüme geçmesi gerekir.');
        const reserved = await store.reserveMedia({
          owner, peer: data.peerId, fileId: data.fileId,
          messageId: data.messageId, cipherBytes: data.cipherBytes,
          cipherSha256: data.cipherSha256
        });
        insist(!reserved?.quotaExceeded, 429, 'Özel dosya kotası doldu.');
        insist(reserved &&
          reserved.file_id === data.fileId &&
          reserved.message_id === data.messageId &&
          reserved.sender_id === owner &&
          reserved.recipient_id === data.peerId &&
          Number(reserved.cipher_bytes) === data.cipherBytes &&
          reserved.cipher_sha256 === data.cipherSha256 &&
          (reserved.state === 'pending' || reserved.state === 'ready') &&
          Number.isFinite(Date.parse(reserved.expires_at)) &&
          Date.parse(reserved.expires_at) > clock(),
          503, 'Medya rezervasyonunun güvenliği doğrulanamadı.'
        );
        // Zero secret-bearing fields: no object path, bucket permission,
        // signed link, encryption key or contact public key in the response.
        return { fileId: data.fileId, state: reserved.state,
          cipherBytes: data.cipherBytes, expiresAt: reserved.expires_at };
      }
      case 'sync': {
        // Parallel independent private read operations. No extra mobile poll,
        // no background service, same authenticated response and ack semantics.
        // Call controls remain part of the existing foreground chat sync only.
        const includeCalls = options.calls === true && data.callVersion === 1;
        const [contacts, events, calls] = await Promise.all([
          store.contacts(owner), store.events(owner),
          includeCalls ? store.callPoll(owner) : Promise.resolve(null)
        ]);
        const result = { contacts, events };
        if (includeCalls) result.calls = calls;
        return result;
      }
      case 'ack': {
        const eventIds = data.eventIds ?? [], readIds = data.readIds ?? [];
        insist(messageIds(eventIds) && messageIds(readIds), 400, 'Geçersiz teslim bildirimi.');
        await store.ack(owner, eventIds, readIds); return { acknowledged: true };
      }
      case 'edit': case 'delete': case 'react': {
        insist(uuid.test(data.messageId ?? ''), 400, 'Geçersiz mesaj.');
        const message = await store.message(data.messageId);
        insist(message && (message.senderId === owner || message.recipientId === owner), 404, 'Mesaj bulunamadı.');
        if (action !== 'react') insist(message.senderId === owner, 403, 'Bu işlem yalnızca kendi mesajınızda yapılabilir.');
        insist(!message.deleted, 409, 'Mesaj silinmiş.');
        if (action === 'edit') insist(ciphertext(data.content), 400, 'Geçersiz şifreli mesaj.');
        if (action === 'react') insist(typeof data.reaction === 'string' && data.reaction.length <= 16, 400, 'Geçersiz tepki.');
        await store.updateMessage(data.messageId, action === 'delete' ? { deleted: true } : action === 'edit' ? { content: data.content } : { reaction: data.reaction });
        return { updated: true };
      }
      default: throw new RelayError(400, 'Bilinmeyen işlem.');
    }
  };
}

/** Only used inside the trusted edge function. Never ship its service key in Android. */
export class RestStore {
  constructor(url, serviceKey, fetcher = fetch, requestDeadlineMs = 12000) {
    this.url = url.replace(/\/$/, '') + '/rest/v1/';
    this.serviceKey = serviceKey;
    this.fetcher = fetcher;
    // Bound both fetch AND streaming response body; the deadline is not reset
    // for each chunk. Shorter test deadlines use the same production path.
    this.requestDeadlineMs = Number.isFinite(requestDeadlineMs)
      ? Math.min(12000, Math.max(20, requestDeadlineMs)) : 12000;
  }
  async query(path, method = 'GET', data, prefer) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.requestDeadlineMs);
    try {
      let response;
      try {
        response = await this.fetcher(this.url + path, {
          method,
          headers: {
            apikey: this.serviceKey,
            ...(this.serviceKey.startsWith('sb_secret_') ? {} : { Authorization: 'Bearer ' + this.serviceKey }),
            'Content-Type': 'application/json',
            ...(prefer ? { Prefer: prefer } : {})
          },
          ...(data === undefined ? {} : { body: JSON.stringify(data) }),
          signal: controller.signal
        });
      } catch {
        // Never return PostgREST diagnostics or headers that could contain
        // server credentials or sensitive identity information.
        throw new RelayError(503, 'Mesajlaşma sunucusu bağlantısı kullanılamıyor.');
      }
      // Keep the deadline active until response bytes are fully consumed.
      const raw = await readBoundedResponse(response);
      let parsed; try { parsed = raw ? JSON.parse(raw) : null; } catch { throw new RelayError(503, 'Sunucu yanıtı okunamadı.'); }
      if (!response.ok) {
        if (response.status === 409) throw new RelayError(409, 'Kayıt çakıştı; tekrar deneyin.');
        throw new RelayError(503, 'Mesajlaşma sunucusu işlemi tamamlayamadı.');
      }
      return parsed;
    } finally {
      // Do not leave an abort timer active in a warm, reused Edge isolate.
      clearTimeout(timer);
    }
  }
  mapIdentity(row) { return row ? { id: row.id, code: row.code, nonce: row.nonce, publicKey: row.public_key } : null; }
  async identity(id) { return this.mapIdentity((await this.query('lanu_identities?id=eq.' + id + '&limit=1'))[0]); }
  async identityByCode(code) { return this.mapIdentity((await this.query('lanu_identities?code=eq.' + encodeURIComponent(code) + '&limit=1'))[0]); }
  async register(value) {
    await this.query('lanu_identities?on_conflict=id', 'POST', { id: value.id, code: value.code, nonce: value.nonce, public_key: value.publicKey }, 'resolution=ignore-duplicates');
    return this.identity(value.id);
  }
  async consumeNonce(owner, requestId) {
    const result = await this.query('rpc/lanu_consume_nonce', 'POST', { p_owner: owner, p_request_id: requestId });
    insist(!result.limited, 429, 'Çok fazla istek. Biraz bekleyip tekrar deneyin.');
    return result.allowed;
  }
  async rotate(owner, expected, code, nonce) { return (await this.query('lanu_identities?id=eq.' + owner + '&code=eq.' + encodeURIComponent(expected), 'PATCH', { code, nonce }, 'return=representation')).length === 1; }
  async addContact(owner, peer, code) { await this.query('lanu_contacts?on_conflict=owner_id,peer_id', 'POST', { owner_id: owner, peer_id: peer, code_at_add: code }, 'resolution=ignore-duplicates'); }
  async removeContact(owner, peer) { await this.query('lanu_contacts?owner_id=eq.' + owner + '&peer_id=eq.' + peer, 'DELETE'); }
  async existingPeerByCode(owner, code) { const row = (await this.query('lanu_contacts?owner_id=eq.' + owner + '&code_at_add=eq.' + encodeURIComponent(code) + '&limit=1'))[0]; return row ? this.identity(row.peer_id) : null; }
  async mutual(owner, peer) { return (await this.query('lanu_contacts?or=(and(owner_id.eq.' + owner + ',peer_id.eq.' + peer + '),and(owner_id.eq.' + peer + ',peer_id.eq.' + owner + '))&select=owner_id')).length === 2; }
  async mediaVersion(owner) {
    const rows = await this.query('lanu_identities?id=eq.' + owner + '&select=private_media_version&limit=1');
    return rows.length === 1 ? rows[0].private_media_version : 0;
  }
  async reserveMedia({ owner, peer, fileId, messageId, cipherBytes, cipherSha256 }) {
    return this.query('rpc/lanu_reserve_private_media', 'POST', {
      p_owner: owner, p_peer: peer, p_file: fileId,
      p_message: messageId, p_bytes: cipherBytes, p_sha: cipherSha256
    });
  }
  async contacts(owner) {
    const edges = await this.query('lanu_contacts?owner_id=eq.' + owner);
    return Promise.all(edges.map(async edge => ({ ...await this.identity(edge.peer_id), codeAtAdd: edge.code_at_add, mutual: await this.mutual(owner, edge.peer_id) })));
  }
  mapMessage(row) { return row ? { id: row.id, senderId: row.sender_id, recipientId: row.recipient_id, content: row.content, createdAt: Date.parse(row.created_at), status: row.read_at ? 'READ' : row.delivered_at ? 'DELIVERED' : 'SENT', deleted: row.deleted, reaction: row.reaction ?? '', revision: row.revision } : null; }
  async message(id) { return this.mapMessage((await this.query('lanu_messages?id=eq.' + id + '&limit=1'))[0]); }
  async send(value) {
    await this.query('lanu_messages?on_conflict=id', 'POST', { id: value.id, sender_id: value.senderId, recipient_id: value.recipientId, content: value.content }, 'resolution=ignore-duplicates');
    return this.message(value.id);
  }
  async events(owner) { return (await this.query('lanu_events?owner_id=eq.' + owner + '&acked=eq.false&order=created_at.asc,id.asc&limit=200')).map(row => ({ id: row.id, message: row.snapshot })); }
  async ack(owner, eventIds, readIds) { await this.query('rpc/lanu_ack', 'POST', { p_owner: owner, p_event_ids: eventIds, p_read_ids: readIds }); }
  async updateMessage(id, changes) { await this.query('lanu_messages?id=eq.' + id, 'PATCH', changes); }
  async callPoll(owner) {
    const rows = await this.query('rpc/lanu_call_poll', 'POST', { p_owner: owner });
    return rows.map(row => ({ id: row.id, callId: row.call_id, senderId: row.sender_id,
      recipientId: row.recipient_id, content: row.content, createdAt: Date.parse(row.created_at),
      expiresAt: Date.parse(row.expires_at) }));
  }
  async callSend(owner, peer, id, callId, content) {
    return this.query('rpc/lanu_call_send', 'POST', { p_owner: owner, p_peer: peer,
      p_id: id, p_call: callId, p_content: content });
  }
  async callAck(owner, ids) { await this.query('rpc/lanu_call_ack', 'POST', { p_owner: owner, p_ids: ids }); }
  async messageWakeRegister(owner, token) {
    return this.query('rpc/lanu_message_wake_register', 'POST',
      { p_owner: owner, p_token: token });
  }
  async messageWakeClaim(owner, peer, messageId) {
    return this.query('rpc/lanu_message_wake_claim', 'POST',
      { p_sender: owner, p_recipient: peer, p_message: messageId });
  }
  async messageWakeInvalidate(owner, token) {
    return this.query('rpc/lanu_message_wake_invalidate', 'POST',
      { p_owner: owner, p_token: token });
  }
  async callWakeRegister(owner, token) {
    return this.query('rpc/lanu_call_wake_register', 'POST', { p_owner: owner, p_token: token });
  }
  async callSendWake(owner, peer, id, callId, content) {
    return this.query('rpc/lanu_call_send_wake', 'POST',
      { p_owner: owner, p_peer: peer, p_id: id, p_call: callId, p_content: content });
  }
  async callWakeInvalidate(owner, token) {
    return this.query('rpc/lanu_call_wake_invalidate', 'POST',
      { p_owner: owner, p_token: token });
  }
}

