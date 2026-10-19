import { generateKeyPairSync, sign, randomBytes, randomUUID, createECDH, createHash, createCipheriv, createDecipheriv, createPrivateKey, createPublicKey, diffieHellman } from 'node:crypto';
import assert from 'node:assert/strict';
import { createRelay, publicId, publicCode } from '../../supabase/functions/lanu-relay/core.mjs';

export class MemoryStore {
  users = new Map(); edges = new Map(); messages = new Map(); pending = new Map(); nonces = new Set();
  async identity(id) { return this.users.get(id) ?? null; }
  async identityByCode(code) { return [...this.users.values()].find(v => v.code === code) ?? null; }
  async register(value) { if (!this.users.has(value.id)) this.users.set(value.id, structuredClone(value)); return structuredClone(this.users.get(value.id)); }
  async consumeNonce(owner, id) { const key = owner + ':' + id; if (this.nonces.has(key)) return false; this.nonces.add(key); return true; }
  async rotate(id, expected, code, nonce) { const user = this.users.get(id); if (user.code !== expected) return false; Object.assign(user, { code, nonce }); return true; }
  async addContact(owner, peer, code) { const key = owner + ':' + peer; if (!this.edges.has(key)) this.edges.set(key, { owner, peer, code }); }
  async removeContact(owner, peer) { this.edges.delete(owner + ':' + peer); }
  async existingPeerByCode(owner, code) { const edge = [...this.edges.values()].find(v => v.owner === owner && v.code === code); return edge ? this.identity(edge.peer) : null; }
  async mutual(owner, peer) { return this.edges.has(owner + ':' + peer) && this.edges.has(peer + ':' + owner); }
  async contacts(owner) { return Promise.all([...this.edges.values()].filter(v => v.owner === owner).map(async e => ({ ...await this.identity(e.peer), codeAtAdd: e.code, mutual: await this.mutual(owner, e.peer) }))); }
  async message(id) { return structuredClone(this.messages.get(id)) ?? null; }
  emit(message) { for (const owner of [message.senderId, message.recipientId]) { const id = randomUUID(); this.pending.set(id, { id, owner, message: structuredClone(message), acked: false }); } }
  async send(value) { const message = { ...value, createdAt: Date.now(), status: 'SENT', revision: 1, deleted: false, reaction: '' }; this.messages.set(value.id, message); this.emit(message); return structuredClone(message); }
  async events(owner) { return [...this.pending.values()].filter(v => v.owner === owner && !v.acked).slice(0, 200).map(({ id, message }) => ({ id, message: structuredClone(message) })); }
  async ack(owner, ids, readIds) {
    const messages = new Set(readIds);
    for (const id of ids) { const event = this.pending.get(id); if (event?.owner === owner) { event.acked = true; messages.add(event.message.id); } }
    for (const id of messages) {
      const message = this.messages.get(id);
      if (!message || message.recipientId !== owner || message.deleted) continue;
      const status = readIds.includes(id) ? 'READ' : message.status === 'SENT' ? 'DELIVERED' : message.status;
      if (status !== message.status) { message.status = status; message.revision++; this.emit(message); }
    }
  }
  async updateMessage(id, changes) { const message = this.messages.get(id); Object.assign(message, changes); message.revision++; this.emit(message); }
}
export async function identity() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKey = pair.publicKey.export({ format: 'der', type: 'spki' }).toString('base64url');
  const nonce = randomBytes(16).toString('base64url');
  return { id: await publicId(publicKey), code: await publicCode(publicKey, nonce), nonce, publicKey, privateKey: pair.privateKey };
}
export function envelope(user, action, data = {}, changes = {}) {
  const payload = JSON.stringify({ action, identityId: user.id, ts: Date.now(), requestId: randomUUID(), data, ...changes });
  return { payload, signature: sign('sha256', Buffer.from(payload), user.privateKey).toString('base64url') };
}
export async function fixture() {
  const store = new MemoryStore(), relay = createRelay(store), alice = await identity(), bob = await identity(), mallory = await identity();
  for (const user of [alice, bob, mallory]) await relay(envelope(user, 'register', { code: user.code, nonce: user.nonce, publicKey: user.publicKey }));
  return { store, relay, alice, bob, mallory };
}
export async function link(f) { await f.relay(envelope(f.alice, 'addContact', { code: f.bob.code })); await f.relay(envelope(f.bob, 'addContact', { code: f.alice.code })); }
export function encrypted(from, to, messageId, text) {
  const shared = diffieHellman({ privateKey: from.privateKey, publicKey: createPublicKey({ key: Buffer.from(to.publicKey, 'base64url'), format: 'der', type: 'spki' }) });
  const conversation = createHash('sha256').update([from.id, to.id].sort().join(':')).digest('hex');
  const key = createHash('sha256').update(shared).update('LANU-v1:' + conversation).digest();
  const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from(messageId + ':' + from.id + ':' + to.id));
  return 'lanu2:' + Buffer.concat([iv, cipher.update(text, 'utf8'), cipher.final(), cipher.getAuthTag()]).toString('base64url');
}
export function decrypted(to, from, id, content) {
  const shared = diffieHellman({ privateKey: to.privateKey, publicKey: createPublicKey({ key: Buffer.from(from.publicKey, 'base64url'), format: 'der', type: 'spki' }) });
  const conversation = createHash('sha256').update([from.id, to.id].sort().join(':')).digest('hex');
  const key = createHash('sha256').update(shared).update('LANU-v1:' + conversation).digest();
  const bytes = Buffer.from(content.slice(6), 'base64url'), decipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
  decipher.setAuthTag(bytes.subarray(-16)); decipher.setAAD(Buffer.from(id + ':' + from.id + ':' + to.id));
  return Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]).toString();
}

