import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, generateKeyPairSync, webcrypto } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { createFcmWakeSender, fetchWithDeadline } from '../../supabase/functions/lanu-relay/call-wake-fcm.mjs';
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
  // Owner must be able to revoke an existing token even if the FCM HTTP v1
  // service-account secret is missing, expired or intentionally removed.
  assert.deepEqual(await disabled(envelope(f.alice,'callWakeToken',{token:null})),{registered:true});
  assert.deepEqual(registrations.at(-1),{owner:f.alice.id,token:null});
  await assert.rejects(disabled(envelope(f.alice,'callWakeToken',{token:'bad token'})),e=>e.status===400);
  const forgedRevoke=envelope(f.mallory,'callWakeToken',{token:null});
  forgedRevoke.signature='bad';
  await assert.rejects(disabled(forgedRevoke),e=>e.status===401);
  const missingRegister=Object.create(f.store); // Preserve class prototype methods for identity validation.
  missingRegister.callWakeRegister=undefined;
  const noStore=createRelay(missingRegister,Date.now,{calls:true});
  await assert.rejects(noStore(envelope(f.alice,'callWakeToken',{token:null})),e=>e.status===503);
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

test('simultaneous FCM wakes share OAuth refresh while preserving isolated privacy-safe payloads',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,
    publicKeyEncoding:{format:'pem',type:'spki'},
    privateKeyEncoding:{format:'pem',type:'pkcs8'}
  });
  const credentials=JSON.stringify({
    type:'service_account',
    project_id:'gen-lang-client-0658022317',
    client_email:'lanu-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  let oauthRequests=0;
  const sent=[];
  const mockFetch=async(url,init)=>{
    assert.ok(init.signal instanceof AbortSignal,'Every outbound Google request needs an abort deadline');
    if (url==='https://oauth2.googleapis.com/token') {
      oauthRequests++;
      await Promise.resolve(); // allow both concurrent requests to reach the token promise
      return {ok:true,json:async()=>({access_token:'x'.repeat(32),expires_in:3600})};
    }
    assert.equal(url,'https://fcm.googleapis.com/v1/projects/gen-lang-client-0658022317/messages:send');
    const message=JSON.parse(init.body).message;
    assert.deepEqual(Object.keys(message.data).sort(),['at','type']);
    assert.equal(message.data.type,'lanu_call_wake_v1');
    assert.equal(message.android.priority,'HIGH');
    assert.equal(message.android.ttl,'70s');
    assert.equal(message.notification,undefined,'No caller information can leak into a notification payload');
    sent.push(message);
    return {ok:true};
  };
  const sender=createFcmWakeSender(credentials,mockFetch,webcrypto);
  assert.ok(sender);
  const [first,second]=await Promise.all([
    sender('A'.repeat(40),1700000000001),
    sender('B'.repeat(40),1700000000002)
  ]);
  assert.equal(first,true);
  assert.equal(second,true);
  assert.equal(oauthRequests,1,'Concurrent sends must refresh only once');
  assert.equal(sent.length,2,'Every distinct call needs its own wake');
  assert.deepEqual(new Set(sent.map(x=>x.data.at)),new Set(['1700000000001','1700000000002']));
  assert.equal(await sender('C'.repeat(40),1700000000003),true);
  assert.equal(oauthRequests,1,'Cached token must be reused on subsequent sends');
});

test('failed OAuth refresh is not cached and the next safe attempt can recover',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,
    publicKeyEncoding:{format:'pem',type:'spki'},
    privateKeyEncoding:{format:'pem',type:'pkcs8'}
  });
  const credentials=JSON.stringify({
    project_id:'gen-lang-client-0658022317',
    client_email:'lanu-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  let authAttempts=0;
  let sends=0;
  const fetcher=async(url,init)=>{
    assert.ok(init.signal instanceof AbortSignal);
    if(url.includes('oauth2.googleapis.com')){
      authAttempts++;
      if(authAttempts===1) return {ok:false};
      return {ok:true,json:async()=>({access_token:'y'.repeat(32),expires_in:3600})};
    }
    sends++;
    return {ok:true};
  };
  const sender=createFcmWakeSender(credentials,fetcher,webcrypto);
  await assert.rejects(sender('A'.repeat(40)),/FCM authentication unavailable/);
  assert.equal(await sender('A'.repeat(40)),true);
  assert.equal(authAttempts,2);
  assert.equal(sends,1,'Failed authentication must not send any call wake');
});

test('FCM fetch deadlines cancel slow Google calls and clear completed timers',async()=>{
  let aborted = false;
  const hanging=(_url,init)=>new Promise((_resolve,reject)=>{
    init.signal.addEventListener('abort',()=>{
      aborted=true;
      reject(new Error('mock aborted as expected'));
    },{once:true});
  });
  await assert.rejects(fetchWithDeadline(hanging,'https://example.invalid',{},15),
    /mock aborted as expected/);
  assert.equal(aborted,true,'Request must abort after its deadline');

  let completedSignal;
  const response=await fetchWithDeadline(async(_url,init)=>{
    completedSignal=init.signal;
    return {ok:true};
  },'https://example.invalid',{},15);
  assert.equal(response.ok,true);
  await new Promise(resolve=>setTimeout(resolve,30));
  assert.equal(completedSignal.aborted,false,'A completed fetch must not retain a live abort timer');
});
