import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { identity, envelope, encrypted } from './test/fixtures.mjs';
const require = createRequire(import.meta.url);
const WebSocket = require('ws');
const { HttpsProxyAgent } = require('https-proxy-agent');
const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
const endpoint = process.env.LANU_RELAY_URL;
const proxyAgent = proxy ? new HttpsProxyAgent(proxy) : undefined;
assert.ok(endpoint?.startsWith('https://'));
async function request(user, action, data = {}) {
  const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(envelope(user, action, data)), signal: AbortSignal.timeout(20000) });
  const value = await response.json();
  assert.equal(response.status, 200, 'Relay operation failed: ' + action + ' HTTP ' + response.status);
  return value;
}
const alice = await identity(), bob = await identity();
for (const user of [alice,bob]) await request(user,'register',{publicKey:user.publicKey,nonce:user.nonce,code:user.code});
await request(alice,'addContact',{code:bob.code}); await request(bob,'addContact',{code:alice.code});
const socket = new WebSocket(endpoint.replace(/^https:/,'wss:'), { agent: proxyAgent, headers: { 'X-Lanu-Authorization': JSON.stringify(envelope(bob,'realtimeOpen')) }, handshakeTimeout: 20000 });
const received = [];
socket.on('message', value => { received.push(JSON.parse(value.toString())); });
async function until(predicate, label) {
  const deadline = Date.now()+22000;
  while (!predicate()) { if (Date.now()>deadline) throw Error(label+' timed out'); await new Promise(r=>setTimeout(r,50)); }
}
socket.on('error', error => { console.log('Socket transport failure: '+(error.code ?? 'handshake')); });
socket.on('unexpected-response', (_, response) => { console.log('Socket handshake HTTP '+response.statusCode); response.resume(); socket.terminate(); });
socket.on('close', code => { console.log('Socket close '+code); });
try {
  await until(()=>received.some(v=>v.ready===true), 'Signed private channel');
  const id=randomUUID(), started=performance.now();
  await request(alice,'send',{messageId:id,peerId:bob.id,content:encrypted(alice,bob,id,'synthetic realtime acceptance')});
  await until(()=>received.some(v=>v.changed==='message'), 'Cross-instance message wake');
  const wakeMillis=Math.round(performance.now()-started);
  const sync=await request(bob,'sync');
  assert.ok(sync.events.some(v=>v.message.id===id), 'Durable message remains available after wake');
  console.log(JSON.stringify({signedChannel:true,opaqueCrossInstanceWake:true,durableHttpFallback:true,wakeMillis,physicalAndroidAcceptance:false}));
} finally { socket.close(); proxyAgent?.destroy(); }
