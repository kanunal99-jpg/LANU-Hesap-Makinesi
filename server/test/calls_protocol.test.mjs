import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, link, envelope, encrypted } from './fixtures.mjs';

test('call requests require the same real ECDSA authentication and mutual peer trust',async()=>{
 const f=await fixture();let writes=0;
 f.store.callSend=async()=>{writes++;return {accepted:true}};
 f.store.callPoll=async()=>[];
 const relay=createRelay(f.store,Date.now,{calls:true});
 const id=randomUUID(),callId=randomUUID(),data={id,callId,peerId:f.bob.id,content:encrypted(f.alice,f.bob,'LANU-CALL-v1:'+id,'test')};
 await assert.rejects(relay(envelope(f.mallory,'callSend',data)),e=>e.status===409);
 await link(f);
 const forged=envelope(f.alice,'callSend',data);forged.signature=forged.signature.slice(0,-3)+'AAA';
 await assert.rejects(relay(forged),e=>e.status===401);
 assert.equal(writes,0);
 assert.deepEqual(await relay(envelope(f.alice,'callSend',data)),{accepted:true});
 assert.equal(writes,1);
 const oversized={...data,id:randomUUID(),content:'lanu2:'+'A'.repeat(32768)};
 await assert.rejects(relay(envelope(f.alice,'callSend',oversized)),e=>e.status===400);
 await assert.rejects(relay(envelope(f.alice,'callSend',{...data,token:'must-not-accept-extra-secret-fields'})),e=>e.status===400);
 assert.equal(writes,1);
});
test('old clients keep exact chat responses; new foreground sync piggybacks invites',async()=>{
 const f=await fixture(); let polls=0;
 f.store.callPoll=async()=>{polls++;return []};
 const relay=createRelay(f.store,Date.now,{calls:true});
 const legacy=await relay(envelope(f.alice,'sync'));
 assert.deepEqual(Object.keys(legacy).sort(),['contacts','events']);assert.equal(polls,0);
 const current=await relay(envelope(f.alice,'sync',{callVersion:1}));
 assert.deepEqual(current.calls,[]);assert.equal(polls,1);
 const disabled=createRelay(f.store);
 await assert.rejects(disabled(envelope(f.alice,'callSync')),e=>e.status===503);
 assert.equal(polls,1);
});
