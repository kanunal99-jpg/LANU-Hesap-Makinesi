// Explicit owner-run production smoke; never part of npm test/CI.
// Creates only disposable test identities; no user keys, codes or SDP are printed.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import { identity, envelope, encrypted, decrypted } from '../test/fixtures.mjs';

const endpoint = process.env.LANU_SMOKE_ENDPOINT;
assert.ok(endpoint?.startsWith('https://'), 'Set LANU_SMOKE_ENDPOINT explicitly.');
const users = await Promise.all([identity(), identity(), identity()]);
if (process.env.LANU_SMOKE_PUBLIC_IDS_FILE) await writeFile(process.env.LANU_SMOKE_PUBLIC_IDS_FILE,
  JSON.stringify(users.map(u => u.id)), { mode: 0o600 });
async function call(user, action, data = {}, status = 200, signed = null) {
  const response = await fetch(endpoint, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify(signed ?? envelope(user, action, data)), signal: AbortSignal.timeout(20_000) });
  assert.equal(response.status, status, `Unexpected status for ${action}`);
  return response.json();
}
const [a,b,outsider] = users;
for (const u of users) await call(u, 'register', { code: u.code, nonce: u.nonce, publicKey: u.publicKey });
await call(a, 'addContact', { code: b.code }); await call(b, 'addContact', { code: a.code });
assert.deepEqual(Object.keys(await call(a, 'sync')).sort(), ['contacts','events']);
await call(a, 'sync', { callVersion: 1 }); await call(b, 'sync', { callVersion: 1 });
const id = randomUUID(), callId = randomUUID();
const clear = JSON.stringify({ v: 1, id, call: callId, kind: 'INVITE', video: false, at: Date.now(), sdp: '' });
const content = encrypted(a,b,'LANU-CALL-v1:'+id,clear);
const signal = { id, callId, peerId: b.id, content };
await call(outsider, 'callSend', signal, 409);
const forged = envelope(a,'callSend',signal); forged.signature = 'A'.repeat(forged.signature.length);
await call(a,'callSend',signal,401,forged);
assert.equal((await call(a,'callSend',signal)).accepted,true);
assert.equal((await call(a,'callSend',signal)).accepted,true);
const inbox = await call(b,'sync',{callVersion:1});
assert.equal(inbox.calls.length,1); assert.equal(inbox.events.length,0);
assert.equal(decrypted(b,a,'LANU-CALL-v1:'+id,inbox.calls[0].content),clear);
assert.equal((await call(outsider,'callSync')).signals.length,0);
await call(outsider,'callAck',{ids:[id]});
assert.equal((await call(b,'callSync')).signals.length,1);
await call(b,'callAck',{ids:[id]});
assert.equal((await call(b,'callSync')).signals.length,0);
await call(b,'removeContact',{peerId:a.id});
await call(a,'callSend',{...signal,id:randomUUID()},409);
console.log('PASS: live ECDSA, legacy sync, E2EE invite, idempotency, recipient-only ACK, mutual revocation; no chat contamination.');
