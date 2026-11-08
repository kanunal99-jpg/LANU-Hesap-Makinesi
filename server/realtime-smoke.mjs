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
let lastMessageAt = 0;
socket.on('message', value => { const event=JSON.parse(value.toString()); received.push(event); if(event.changed==='message') lastMessageAt=performance.now(); });
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
  const wakeMillis=Math.round(lastMessageAt-started);
  const sync=await request(bob,'sync');
  assert.ok(sync.events.some(v=>v.message.id===id), 'Durable message remains available after wake');
  await request(alice,'callSync'); await request(bob,'callSync');
  const signalId=randomUUID(), callId=randomUUID();
  await request(alice,'callSend',{id:signalId,callId,peerId:bob.id,content:encrypted(alice,bob,signalId,'synthetic encrypted signal')});
  await until(()=>received.some(v=>v.changed==='call'),'Call wake');
  const calls=await request(bob,'callSync');
  assert.ok(calls.signals.some(v=>v.id===signalId));
  await request(bob,'callAck',{ids:[signalId]});
  for (let pass=0;pass<2;pass++) for (const user of [bob,alice]) {
    const pending=await request(user,'sync');
    if(pending.events.length) await request(user,'ack',{eventIds:pending.events.map(v=>v.id),readIds:[]});
  }
  await request(alice,'removeContact',{peerId:bob.id}); await request(bob,'removeContact',{peerId:alice.id});
  console.log(JSON.stringify({signedChannel:true,opaqueCrossInstanceWake:true,callWake:true,durableHttpFallback:true,wakeMillis,physicalAndroidAcceptance:false}));
} finally { socket.close(); proxyAgent?.destroy(); }
