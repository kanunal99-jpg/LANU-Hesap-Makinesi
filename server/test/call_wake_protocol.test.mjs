import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, generateKeyPairSync, webcrypto } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { createFcmWakeSender, fetchWithDeadline, fcmRetryDelay, FcmUnregisteredTokenError } from '../../supabase/functions/lanu-relay/call-wake-fcm.mjs';
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
  assert.equal(createFcmWakeSender('null'),null);
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
    assert.equal(message.android.ttl,'45s');
    assert.equal(message.notification,undefined,'No caller information can leak into a notification payload');
    sent.push(message);
    return {ok:true};
  };
  const sender=createFcmWakeSender(credentials,mockFetch,webcrypto,undefined,{clock:()=>1700000000000});
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
    type:'service_account',
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
    e=>e.name==='AbortError'||/mock aborted as expected/.test(e.message));
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

test('only explicit FCM UNREGISTERED permits compare-and-delete and never rejects encrypted INVITE',async()=>{
  const f=await fixture();await link(f);
  let invalidations=[];
  const token='X'.repeat(44);
  f.store.callSendWake=async()=>({accepted:true,created:true,wake_token:token});
  f.store.callWakeInvalidate=async(owner,oldToken)=>{
    invalidations.push({owner,oldToken});
    return {invalidated:true};
  };
  let mode='unregistered';
  const relay=createRelay(f.store,Date.now,{calls:true,callWakeEnabled:true,
    sendCallWake:async()=>{
      if(mode==='unregistered') throw new FcmUnregisteredTokenError();
      if(mode==='transient') throw new Error('FCM dispatch unavailable');
      return true;
    }});
  async function call(){
    const id=randomUUID(),callId=randomUUID();
    return relay(envelope(f.alice,'callSend',{
      id,callId,peerId:f.bob.id,
      content:encrypted(f.alice,f.bob,'LANU-CALL-v1:'+id,'sample'),wake:true
    }));
  }
  assert.deepEqual(await call(),{accepted:true});
  assert.deepEqual(invalidations,[{owner:f.bob.id,oldToken:token}]);
  mode='transient';
  assert.deepEqual(await call(),{accepted:true});
  assert.equal(invalidations.length,1,'Transient errors never remove a reachable registration');
  f.store.callWakeInvalidate=async()=>{ throw Error('Postgres temporarily unavailable'); };
  mode='unregistered';
  assert.deepEqual(await call(),{accepted:true},'Cleanup failure must not discard encrypted call INVITE');
});

test('FCM response classification refuses unrelated 404 and 429 as invalid token',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,
    privateKeyEncoding:{format:'pem',type:'pkcs8'},
    publicKeyEncoding:{format:'pem',type:'spki'}
  });
  const credentials=JSON.stringify({
    type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'lanu-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  let responseKind='unregistered';
  const fetcher=async(url)=>{
    if(url.includes('oauth2.googleapis.com'))
      return {ok:true,json:async()=>({access_token:'x'.repeat(36),expires_in:3600})};
    if(responseKind==='unregistered')return {ok:false,status:404,json:async()=>({
      error:{status:'NOT_FOUND',details:[{'@type':'type.googleapis.com/google.firebase.fcm.v1.FcmError',errorCode:'UNREGISTERED'}]}
    })};
    if(responseKind==='not_found')return {ok:false,status:404,json:async()=>({error:{status:'NOT_FOUND'}})};
    if(responseKind==='rate_limited')return {ok:false,status:429,json:async()=>({error:{status:'RESOURCE_EXHAUSTED'}})};
  };
  const sender=createFcmWakeSender(credentials,fetcher,webcrypto);
  assert.ok(sender);
  await assert.rejects(sender('A'.repeat(40)),e=>e instanceof FcmUnregisteredTokenError&&
    !String(e).includes('A'.repeat(10)));
  responseKind='not_found';
  await assert.rejects(sender('A'.repeat(40)),e=>!(e instanceof FcmUnregisteredTokenError)&&
    e.message==='FCM dispatch unavailable');
  responseKind='rate_limited';
  assert.equal(await sender('A'.repeat(40)),false,'Backpressure exceeds live call budget');
  assert.equal(createFcmWakeSender(JSON.stringify({
    type:'service_account',project_id:'another-firebase-project',
    client_email:'lanu-test@another-firebase-project.iam.gserviceaccount.com',
    private_key:privateKey
  }),fetcher,webcrypto),null,'Sender must refuse a different Firebase project');
});

test('transient FCM call delivery retries twice with the same privacy-safe hint and bounded waits',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,publicKeyEncoding:{format:'pem',type:'spki'},
    privateKeyEncoding:{format:'pem',type:'pkcs8'}
  });
  const credentials=JSON.stringify({
    type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'lanu-retry-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  const attempts=[],waits=[];
  const fetcher=async(url,init)=>{
    if(url.includes('oauth2.googleapis.com'))
      return {ok:true,json:async()=>({access_token:'x'.repeat(36),expires_in:3600})};
    const call=JSON.parse(init.body).message;
    attempts.push(call);
    if(attempts.length===1)return {ok:false,status:503};
    if(attempts.length===2)return {ok:false,status:503};
    return {ok:true,status:200};
  };
  const sender=createFcmWakeSender(credentials,fetcher,webcrypto,async ms=>{waits.push(ms)}, {clock:()=>1710000000000,random:()=>0});
  assert.equal(await sender('A'.repeat(44),1710000000000,'call'),true);
  assert.deepEqual(waits,[10000,20000]);
  assert.equal(attempts.length,3);
  for(const message of attempts) {
    assert.deepEqual(Object.keys(message.data).sort(),['at','type']);
    assert.deepEqual(message.data,{type:'lanu_call_wake_v1',at:'1710000000000'});
    assert.equal(message.notification,undefined);
    assert.equal(message.android.priority,'HIGH');
    assert.equal(message.android.ttl,'45s');
  }
});

test('permanent FCM responses never get auto-retried for either alert',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,publicKeyEncoding:{format:'pem',type:'spki'},
    privateKeyEncoding:{format:'pem',type:'pkcs8'}
  });
  const credentials=JSON.stringify({
    type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'lanu-permanent-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  const seen=[];const waits=[];
  const fetcher=async(url,init)=>{
    if(url.includes('oauth2.googleapis.com'))
      return {ok:true,json:async()=>({access_token:'x'.repeat(36),expires_in:3600})};
    seen.push(JSON.parse(init.body).message.data.type);
    return {ok:false,status:401};
  };
  const sender=createFcmWakeSender(credentials,fetcher,webcrypto,async ms=>{waits.push(ms)}, {clock:()=>1710000000000,random:()=>0});
  await assert.rejects(sender('A'.repeat(44),1710000000000,'call'),/FCM dispatch unavailable/);
  await assert.rejects(sender('A'.repeat(44),1710000000000,'message'),/FCM dispatch unavailable/);
  assert.deepEqual(seen,['lanu_call_wake_v1','lanu_message_wake_v1']);
  assert.deepEqual(waits,[]);
});

test('transient FCM message delivery retries twice with the same privacy-safe hint and bounded waits',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{
    modulusLength:2048,publicKeyEncoding:{format:'pem',type:'spki'},
    privateKeyEncoding:{format:'pem',type:'pkcs8'}
  });
  const credentials=JSON.stringify({
    type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'lanu-retry-test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:privateKey
  });
  const attempts=[],waits=[];
  const fetcher=async(url,init)=>{
    if(url.includes('oauth2.googleapis.com'))
      return {ok:true,json:async()=>({access_token:'x'.repeat(36),expires_in:3600})};
    const call=JSON.parse(init.body).message;
    attempts.push(call);
    if(attempts.length===1)return {ok:false,status:503};
    if(attempts.length===2)return {ok:false,status:503};
    return {ok:true,status:200};
  };
  const sender=createFcmWakeSender(credentials,fetcher,webcrypto,async ms=>{waits.push(ms)}, {clock:()=>1710000000000,random:()=>0});
  assert.equal(await sender('A'.repeat(44),1710000000000,'message'),true);
  assert.deepEqual(waits,[10000,20000]);
  assert.equal(attempts.length,3);
  for(const message of attempts) {
    assert.deepEqual(Object.keys(message.data).sort(),['at','type']);
    assert.deepEqual(message.data,{type:'lanu_message_wake_v1',at:'1710000000000'});
    assert.equal(message.notification,undefined);
    assert.equal(message.android.priority,'HIGH');
    assert.equal(message.android.ttl,'86400s');
  }
});

test('deadline covers stalled response body and cancels its stream',async()=>{
  let cancelled=false;
  const fetcher=async()=>new Response(new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('{')); },
    cancel() { cancelled=true; }
  }));
  await assert.rejects(fetchWithDeadline(fetcher,'https://example.invalid',{},15),e=>e.name==='AbortError');
  assert.equal(cancelled,true);
});

test('response body is bounded even without Content-Length and normal bodies survive deadline cleanup',async()=>{
  let cancelled=false;
  await assert.rejects(fetchWithDeadline(async()=>new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(65_537)); },
    cancel() { cancelled=true; }
  })),'https://example.invalid',{}),/response too large/);
  assert.equal(cancelled,true);
  const response=await fetchWithDeadline(async()=>new Response('{"ok":true}'),'',{},50);
  assert.deepEqual(await response.json(),{ok:true});
});

test('Retry-After seconds, dates, quota minimum, invalid values and jitter are lower bounded',()=>{
  const now=Date.parse('2026-11-18T12:00:00Z');
  assert.equal(fcmRetryDelay(503,new Headers({'Retry-After':'120'}),0,now,()=>0),120000);
  assert.equal(fcmRetryDelay(503,new Headers({'Retry-After':'Wed, 18 Nov 2026 12:01:00 GMT'}),0,now,()=>0),60000);
  assert.equal(fcmRetryDelay(429,new Headers(),0,now,()=>0),60000);
  for(const value of ['invalid','-2','0']) {
    assert.ok(fcmRetryDelay(503,new Headers({'Retry-After':value}),0,now,()=>0)>=10000);
  }
  assert.equal(fcmRetryDelay(503,null,1,now,()=>1),25000);
  assert.equal(fcmRetryDelay(503,new Headers({'Retry-After':'9'.repeat(400)}),0,now,()=>0),Infinity);
});

test('backpressure respects live expiry, stale hints never send, retries shrink TTL, diagnostics are fixed labels',async()=>{
  const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048,
    privateKeyEncoding:{format:'pem',type:'pkcs8'},publicKeyEncoding:{format:'pem',type:'spki'}});
  const credentials=JSON.stringify({type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'test@gen-lang-client-0658022317.iam.gserviceaccount.com',private_key:privateKey});
  let time=1800000000000, status=503, header='120', attempts=[], waits=[], outcomes=[];
  const sender=createFcmWakeSender(credentials,async(url,init)=>{
    if(url.includes('oauth2')) return new Response(JSON.stringify({access_token:'x'.repeat(40),expires_in:3600}));
    attempts.push(JSON.parse(init.body).message);
    return new Response('{}',{status:attempts.length===1?status:200,headers:{'Retry-After':header}});
  },webcrypto,async ms=>{waits.push(ms);time+=ms;},{clock:()=>time,random:()=>0,
    onOutcome:(kind,outcome)=>outcomes.push([kind,outcome])});
  assert.equal(await sender('T'.repeat(40),time,'call'),false);
  assert.equal(attempts.length,1);assert.deepEqual(waits,[]);
  assert.deepEqual(outcomes.pop(),['call','backpressure']);
  assert.equal(await sender('T'.repeat(40),time-120001,'message'),false);
  assert.equal(attempts.length,1);
  attempts=[]; status=429; header='1';
  const original=time;
  assert.equal(await sender('T'.repeat(40),time,'message'),true);
  assert.deepEqual(waits,[60000]);
  assert.equal(attempts[0].android.ttl,'86400s');
  assert.equal(attempts[1].android.ttl,'86400s');
  assert.ok(attempts.every(x=>x.data.at===String(original)));
  assert.deepEqual(outcomes.pop(),['message','accepted']);
});

test('stalled shared OAuth body times out for both waiters and a later wake recovers',async()=>{
  let auth=0, sends=0, cancelled=false;
  const credentials=JSON.stringify({type:'service_account',project_id:'gen-lang-client-0658022317',
    client_email:'test@gen-lang-client-0658022317.iam.gserviceaccount.com',
    private_key:'-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----'});
  const cryptoApi={subtle:{importKey:async()=>({}),sign:async()=>new Uint8Array(32)}};
  const sender=createFcmWakeSender(credentials,async url=>{
    if(url.includes('oauth2')) {
      auth++;
      if(auth===1) return new Response(new ReadableStream({cancel(){cancelled=true;}}));
      return new Response(JSON.stringify({access_token:'x'.repeat(40),expires_in:3600}));
    }
    sends++;
    return new Response('{}');
  },cryptoApi);
  const outcomes=await Promise.allSettled([sender('A'.repeat(40)),sender('B'.repeat(40))]);
  assert.ok(outcomes.every(x=>x.status==='rejected'&&x.reason.name==='AbortError'));
  assert.equal(auth,1);assert.equal(sends,0);assert.equal(cancelled,true);
  assert.equal(await sender('C'.repeat(40)),true);
  assert.equal(auth,2);assert.equal(sends,1);
});
