import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { createFcmWakeSender } from '../../supabase/functions/lanu-relay/call-wake-fcm.mjs';
import { fixture, link, envelope, encrypted } from './fixtures.mjs';

test('call wake opt-in requires owner ECDSA and rejects unrelated payloads',async()=>{
  const f=await fixture();
  let registrations=[];
  f.store.callWakeRegister=async(owner,token)=>{registrations.push({owner,token});return {registered:true}};
  const relay=createRelay(f.store,Date.now,{calls:true,callWakeEnabled:true});
  await assert.rejects(relay(envelope(f.alice,'callWakeToken',{token:'malformed token'})),e=>e.status===400);
  await assert.rejects(relay(envelope(f.alice,'callWakeToken',{token:'A'.repeat(50),caller:'leak'})),e=>e.status===400);
  const invalid=envelope(f.mallory,'callWakeToken',{token:'A'.repeat(50)});invalid.signature='bad';
  await assert.rejects(relay(invalid),e=>e.status===401);
  assert.deepEqual(await relay(envelope(f.alice,'callWakeToken',{token:'A'.repeat(50)})),{registered:true});
  assert.deepEqual(await relay(envelope(f.alice,'callWakeToken',{token:null})),{registered:true});
  assert.equal(registrations.length,2);
  assert.equal(registrations[0].owner,f.alice.id);
  assert.equal(registrations[1].token,null);
  const disabled=createRelay(f.store,Date.now,{calls:true});
  await assert.rejects(disabled(envelope(f.alice,'callWakeToken',{token:'A'.repeat(50)})),e=>e.status===503);
});

test('only authenticated mutual signed call send with wake gets one nonsecret push',async()=>{
  const f=await fixture();let pushes=[],stored=0;
  f.store.callSend=async()=>{stored++;return {accepted:true}};
  f.store.callSendWake=async()=>{stored++;return {accepted:true,created:true,wake_token:'A'.repeat(50)}};
  const relay=createRelay(f.store,Date.now,{
    calls:true,callWakeEnabled:true,
    sendCallWake:async(token,at)=>{pushes.push({token,at});return true}
  });
  const id=randomUUID(),callId=randomUUID();
  const data={id,callId,peerId:f.bob.id,content:encrypted(f.alice,f.bob,'LANU-CALL-v1:'+id,'sample'),wake:true};
  await assert.rejects(relay(envelope(f.alice,'callSend',data)),e=>e.status===409);
  assert.equal(stored,0);
  await link(f);
  const result=await relay(envelope(f.alice,'callSend',data));
  assert.deepEqual(result,{accepted:true});
  assert.equal(stored,1);assert.equal(pushes.length,1);
  assert.equal(pushes[0].token,'A'.repeat(50));
  assert.equal(typeof pushes[0].at,'number');
  const forged=envelope(f.alice,'callSend',{...data,id:randomUUID(),wake:'yes'});
  await assert.rejects(relay(forged),e=>e.status===400);
  assert.equal(pushes.length,1);
  f.store.callSendWake=async()=>({accepted:true,created:false,wake_token:'A'.repeat(50)});
  assert.deepEqual(await relay(envelope(f.alice,'callSend',data)),{accepted:true});
  assert.equal(pushes.length,1,'Retry must not re-send push');
  f.store.callSendWake=async()=>({accepted:true,created:true});
  assert.deepEqual(await relay(envelope(f.alice,'callSend',{...data,id:randomUUID()})),{accepted:true});
  assert.equal(pushes.length,1,'Foreground-only call has no token');
  f.store.callSendWake=async()=>({accepted:true,created:true,wake_token:'A'.repeat(50)});
  const failing=createRelay(f.store,Date.now,{calls:true,callWakeEnabled:true,
    sendCallWake:async()=>{throw new Error('transport unavailable')}});
  assert.deepEqual(await failing(envelope(f.alice,'callSend',{...data,id:randomUUID()})),{accepted:true},
    'FCM failure must not discard the encrypted INVITE');
});

test('ordinary chat, background legacy calls and unrelated messages cannot trigger push',async()=>{
  const f=await fixture();await link(f);
  let pushes=0;let regular=0;
  f.store.callSend=async()=>{regular++;return {accepted:true}};
  f.store.callSendWake=async()=>{throw new Error('should not be called')};
  const relay=createRelay(f.store,Date.now,{calls:true,callWakeEnabled:false,sendCallWake:()=>{pushes++}});
  const id=randomUUID(),callId=randomUUID(),data={id,callId,peerId:f.bob.id,
    content:encrypted(f.alice,f.bob,'LANU-CALL-v1:'+id,'sample'),wake:true};
  assert.deepEqual(await relay(envelope(f.alice,'callSend',data)),{accepted:true});
  assert.equal(regular,1);assert.equal(pushes,0);
  await relay(envelope(f.alice,'sync'));
  assert.equal(pushes,0);
});

test('FCM sender remains disabled without secret; invalid secret cannot send',()=>{
  assert.equal(createFcmWakeSender(null),null);
  assert.equal(createFcmWakeSender('{bad'),null);
  assert.equal(createFcmWakeSender(JSON.stringify({project_id:'fake-project'})),null);
});
