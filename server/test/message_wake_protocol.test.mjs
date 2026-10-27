import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, generateKeyPairSync, webcrypto } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { createFcmWakeSender, FcmUnregisteredTokenError } from '../../supabase/functions/lanu-relay/call-wake-fcm.mjs';
import { fixture, link, envelope, encrypted } from './fixtures.mjs';

test('message push is disabled without server opt-in, and old clients still deliver ciphertext', async () => {
  const f = await fixture(); await link(f);
  let claims = 0, pushes = 0;
  f.store.messageWakeClaim = async () => { claims++; return { claimed: true, wake_token: 'A'.repeat(50) }; };
  const relay = createRelay(f.store, Date.now, { sendMessageWake: async () => { pushes++; } });
  const id = randomUUID();
  const result = await relay(envelope(f.alice, 'send', {
    messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'encrypted')
  }));
  assert.deepEqual(result, { status: 'SENT' });
  assert.equal(claims, 0);
  assert.equal(pushes, 0);
});

test('signed owner registration and consent revoke are separate from call alerts', async () => {
  const f = await fixture();
  let registrations = [];
  f.store.messageWakeRegister = async (owner, token) => {
    registrations.push({ owner, token }); return { registered: true };
  };
  const enabled = createRelay(f.store, Date.now, { messageWakeEnabled: true });
  assert.deepEqual(await enabled(envelope(f.bob, 'messageWakeToken', { token: 'A'.repeat(42) })), { registered: true });
  assert.deepEqual(await enabled(envelope(f.bob, 'messageWakeToken', { token: null })), { registered: true });
  assert.deepEqual(registrations, [
    { owner: f.bob.id, token: 'A'.repeat(42) }, { owner: f.bob.id, token: null }
  ]);
  const disabled = createRelay(f.store);
  await assert.rejects(disabled(envelope(f.bob, 'messageWakeToken', { token: 'A'.repeat(42) })), e => e.status === 503);
  assert.deepEqual(await disabled(envelope(f.bob, 'messageWakeToken', { token: null })), { registered: true });
  await assert.rejects(enabled(envelope(f.bob, 'messageWakeToken', { token: 'invalid token' })), e => e.status === 400);
  await assert.rejects(enabled(envelope(f.bob, 'messageWakeToken', { token: null, sender: 'leak' })), e => e.status === 400);
  const forged = envelope(f.bob, 'messageWakeToken', { token: null });
  forged.signature = 'bogus';
  await assert.rejects(enabled(forged), e => e.status === 401);
});

test('one private hint after durable send; duplicates, spoofed sender and push failures cannot leak or reject E2EE', async () => {
  const f = await fixture(); await link(f);
  const token = 'B'.repeat(42), claims = new Set(), pushes = [];
  f.store.messageWakeClaim = async (_sender, _recipient, id) => {
    if (claims.has(id)) return { claimed: false };
    claims.add(id); return { claimed: true, wake_token: token };
  };
  const relay = createRelay(f.store, Date.now, {
    messageWakeEnabled: true,
    sendMessageWake: async (t, at) => { pushes.push({ t, at }); throw new Error('offline FCM'); }
  });
  const id = randomUUID(), cipher = encrypted(f.alice, f.bob, id, 'never plaintext in push');
  assert.deepEqual(await relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: cipher })), { status: 'SENT' });
  assert.equal(pushes.length, 1); assert.equal(pushes[0].t, token);
  assert.equal(f.store.messages.get(id).content, cipher);
  assert.deepEqual(await relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: cipher })), { status: 'SENT' });
  assert.equal(pushes.length, 1);
  await assert.rejects(relay(envelope(f.alice, 'send', { messageId: id, peerId: f.bob.id, content: cipher+'tampered' })), e => e.status === 409);
  assert.equal(pushes.length, 1);
  await assert.rejects(relay(envelope(f.mallory, 'send', { messageId: randomUUID(), peerId: f.bob.id, content: cipher })), e => e.status === 409);
  assert.equal(pushes.length, 1);
});

test('only UNREGISTERED invalidates matching message push token; transport error never drops message', async () => {
  const f = await fixture(); await link(f);
  const invalidated = [];
  f.store.messageWakeClaim = async () => ({ claimed: true, wake_token: 'C'.repeat(42) });
  f.store.messageWakeInvalidate = async (owner, token) => { invalidated.push({ owner, token }); };
  let failure = new FcmUnregisteredTokenError();
  const relay = createRelay(f.store, Date.now, {
    messageWakeEnabled: true, sendMessageWake: async () => { throw failure; }
  });
  async function send() {
    const id = randomUUID();
    return relay(envelope(f.alice,'send',{messageId:id,peerId:f.bob.id,content:encrypted(f.alice,f.bob,id,'secret')}));
  }
  assert.deepEqual(await send(),{status:'SENT'});
  assert.deepEqual(invalidated,[{owner:f.bob.id,token:'C'.repeat(42)}]);
  failure = new Error('rate limited');
  assert.deepEqual(await send(),{status:'SENT'});
  assert.equal(invalidated.length,1);
});

test('message FCM data contains only opaque type + timestamp and never a notification body', async () => {
  const {privateKey} = generateKeyPairSync('rsa', {
    modulusLength: 2048,
    privateKeyEncoding: {format:'pem',type:'pkcs8'},
    publicKeyEncoding: {format:'pem',type:'spki'}
  });
  const credentials = JSON.stringify({
    type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'lanu-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  let messages = [];
  const fetcher = async (url, init) => {
    if (url.includes('oauth2.googleapis.com')) return {ok:true,json:async()=>({access_token:'X'.repeat(40),expires_in:3600})};
    messages.push(JSON.parse(init.body).message);
    return {ok:true};
  };
  const sender = createFcmWakeSender(credentials, fetcher, webcrypto);
  assert.equal(await sender('D'.repeat(40),1700000000000,'message'),true);
  const msg = messages[0];
  assert.deepEqual(msg.data, {type:'lanu_message_wake_v1',at:'1700000000000'});
  assert.equal(msg.notification,undefined);
  assert.equal(msg.android.ttl,'120s');
  assert.equal(msg.android.collapse_key,'lanu_message_v1');
  assert.equal(await sender('D'.repeat(40),1700000000000,'marketing'),false);
  assert.equal(messages.length,1);
});
