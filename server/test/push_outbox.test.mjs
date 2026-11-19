import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createMessagePushDispatcher} from '../../supabase/functions/lanu-relay/push-outbox.mjs';
import {createFcmWakeSender,FcmRetryableError,FcmUnregisteredTokenError,FcmPermanentDispatchError} from '../../supabase/functions/lanu-relay/call-wake-fcm.mjs';
import {fixture,link,envelope,encrypted} from './fixtures.mjs';
import {createRelay} from '../../supabase/functions/lanu-relay/core.mjs';
import {randomUUID} from 'node:crypto';
test('dispatcher respects deferred quota, permanent errors, compare-delete and completion failure',async()=>{
 for(const [error,result,delay] of [[new FcmRetryableError(61234),'retry',61234],[new FcmPermanentDispatchError(),'expired',60000],[new FcmUnregisteredTokenError(),'expired',60000],[new Error('OAuth unavailable'),'retry',60000]]){
  let available=true, finished=[], invalidated=0;
  const job={messageId:'private',leaseId:'private',recipientId:'private',token:'private',at:123};
  const store={leaseMessagePush:async()=>available?(available=false,job):null,
   finishMessagePush:async(j,outcome,ms)=>finished.push([outcome,ms]),
   messageWakeInvalidate:async()=>{invalidated++}};
  await createMessagePushDispatcher(store,async()=>{throw error})();
  assert.deepEqual(finished,[[result,delay]]);
  assert.equal(invalidated,error instanceof FcmUnregisteredTokenError?1:0);
 }
 let claims=0;
 await createMessagePushDispatcher({leaseMessagePush:async()=>{claims++;return {};},finishMessagePush:async()=>{throw Error('lost response');}},async()=>true)();
 assert.equal(claims,1,'Failed completion is retried by durable lease expiry, not a hot loop');
});
test('durable path schedules retry on duplicate message without re-inserting ciphertext',async()=>{
 const f=await fixture();await link(f);let drains=0;
 const relay=createRelay(f.store,Date.now,{messageWakeEnabled:true,drainMessageWakes:async()=>{drains++;}});
 const id=randomUUID(),data={messageId:id,peerId:f.bob.id,content:encrypted(f.alice,f.bob,id,'synthetic')};
 await relay(envelope(f.alice,'send',data));await relay(envelope(f.alice,'send',data));
 assert.equal(f.store.messages.size,1);assert.equal(drains,2);
});
test('single-attempt FCM exposes Retry-After to durable worker without sleeping',async()=>{
 const credentials=JSON.stringify({type:'service_account',project_id:'gen-lang-client-0658022317',client_email:'test@gen-lang-client-0658022317.iam.gserviceaccount.com',private_key:'-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----'});
 let sends=0;
 const sender=createFcmWakeSender(credentials,async url=>{
  if(url.includes('oauth2'))return new Response(JSON.stringify({access_token:'x'.repeat(40),expires_in:3600}));
  sends++;return new Response('{}',{status:429,headers:{'Retry-After':'120'}});
 },{subtle:{importKey:async()=>({}),sign:async()=>new Uint8Array(32)}},async()=>assert.fail('No in-memory wait'),{singleAttempt:true,random:()=>0});
 await assert.rejects(sender('T'.repeat(40),Date.now(),'message'),e=>e instanceof FcmRetryableError&&e.retryAfterMillis===120000);
 assert.equal(sends,1);
});
