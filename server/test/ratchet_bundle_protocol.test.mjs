import test from 'node:test';
import assert from 'node:assert/strict';
import {randomBytes,randomUUID,sign} from 'node:crypto';
import {ratchetTranscript} from '../../supabase/functions/lanu-relay/ratchet-bundle.mjs';
import {createRelay,RestStore} from '../../supabase/functions/lanu-relay/core.mjs';
import {fixture,link,envelope} from './fixtures.mjs';
function signed(own,peer) {
 const b={owner:own.id,peer:peer.id,generation:randomUUID(),identity:randomBytes(32).toString('base64').replace(/=+$/,''),oneTime:randomBytes(32).toString('base64').replace(/=+$/,''),signature:'A'.repeat(96)};
 b.signature=sign('sha256',Buffer.from(ratchetTranscript(b)),own.privateKey).toString('base64url');return b;
}
test('public bundle must bind both participants and be signed by current P256 identity',async()=>{
 const f=await fixture();await link(f);let writes=0;
 f.store.publishRatchetBundle=async()=>{writes++;return {accepted:true,ready:false,peerBundle:null};};
 const relay=createRelay(f.store,Date.now,{ratchet:true});const b=signed(f.alice,f.bob);
 assert.equal((await relay(envelope(f.alice,'publishRatchetBundle',{peerId:f.bob.id,bundle:b}))).accepted,true);
 for(const bad of [{...b,peer:f.mallory.id},{...b,owner:f.bob.id},{...b,generation:randomUUID()},{...b,identity:'A'.repeat(43)},{...b,unknown:'ignored?'}])
   await assert.rejects(relay(envelope(f.alice,'publishRatchetBundle',{peerId:f.bob.id,bundle:bad})));
 await assert.rejects(createRelay(f.store)(envelope(f.alice,'publishRatchetBundle',{peerId:f.bob.id,bundle:b})),e=>e.status===503);
 assert.equal(writes,1);
});
test('new sync advertises ratchet explicitly while old sync uses old RPC',async()=>{
 const rest=new RestStore('https://example.invalid','TEST');const seen=[];
 rest.query=async(...args)=>{seen.push(args);return [];};
 await rest.events('a'.repeat(64),0);await rest.events('a'.repeat(64),1,3);
 assert.equal(seen[0][0],'rpc/lanu_media_aware_events');assert.equal(seen[1][0],'rpc/lanu_ratchet_aware_events');
});
