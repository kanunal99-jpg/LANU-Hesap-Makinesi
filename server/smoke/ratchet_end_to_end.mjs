// Actual JNI + signed relay + encrypted wire + receipt + two-owner renewal.
// Test keys stay in process/private scratch. Never print envelope, key or state.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {randomBytes,randomUUID,sign,createPrivateKey} from 'node:crypto';
import {readFile,writeFile,unlink} from 'node:fs/promises';
import {resolve} from 'node:path';
import {identity,envelope,encrypted,decrypted,MemoryStore} from '../test/fixtures.mjs';
import {createRelay} from '../../supabase/functions/lanu-relay/core.mjs';
import {ratchetTranscript,ratchetResetTranscript,ratchetEpoch} from '../../supabase/functions/lanu-relay/ratchet-bundle.mjs';
const fixturePath=process.env.LANU_RATCHET_SMOKE_FIXTURE;
let step='initialization';
let native;
try {
 if(process.argv.includes('--prepare')) {
  assert(fixturePath);
  const users=await Promise.all([identity(),identity()]);
  await writeFile(fixturePath,JSON.stringify(users.map(u=>({...u,privateKey:u.privateKey.export({type:'pkcs8',format:'pem'})}))),{mode:0o600,flag:'wx'});
  console.log(JSON.stringify({owners:users.map(u=>u.id)}));
 } else {
  const endpoint=process.env.LANU_RATCHET_SMOKE_ENDPOINT;
  const users=fixturePath ? JSON.parse(await readFile(fixturePath,'utf8')).map(u=>({...u,privateKey:createPrivateKey(u.privateKey)})) : await Promise.all([identity(),identity()]);
  const [a,b]=users;
  if(endpoint) assert(/^https:\/\/talhknjcjaijqiqwdyhf\.supabase\.co\/functions\/v1\/lanu-relay(?:-ratchet-acceptance)?$/.test(endpoint));
  const memory=new MemoryStore();const bundles=new Map();let renewal;
  const state=(owner,peer)=>{
   const ownBundle=bundles.get(owner),peerBundle=bundles.get(peer);
   return ratchetEpoch(ownBundle,peerBundle).then(epoch=>({ready:!!epoch,epoch,ownBundle,peerBundle,
    pending:renewal?.state==='pending'?renewal:null,completed:renewal?.state==='active'?renewal:null,retiredIncoming:0,retiredOutgoing:0,hasProtectedHistory:memory.messages.size>0}));
  };
  memory.publishRatchetBundle=async(owner,peer,bundle)=>{bundles.set(owner,bundle);return {accepted:true,ready:bundles.has(peer),peerBundle:bundles.get(peer)??null};};
  memory.ratchetState=state;
  memory.renewRatchet=async(owner,peer,id,previous,bundle,proof)=>{
   renewal??={id,previous_epoch:previous,first_id:[owner,peer].sort()[0],second_id:[owner,peer].sort()[1],votes:{},state:'pending'};
   assert(renewal.id===id && renewal.previous_epoch===previous);
   renewal.votes[owner]={bundle,proof};
   if(Object.keys(renewal.votes).length===2) {
    for(const u of users) bundles.set(u.id,renewal.votes[u.id].bundle);
    renewal.state='active';renewal.new_epoch=await ratchetEpoch(bundles.get(owner),bundles.get(peer));
   }
   return {accepted:true,ready:renewal.state==='active',receipt:renewal,state:await state(owner,peer)};
  };
  const local=createRelay(memory,Date.now,{ratchet:true});
  const request=async(user,action,data={})=>{
   const signed=envelope(user,action,data);
   if(!endpoint) return local(signed);
   const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(signed),signal:AbortSignal.timeout(35000)});
   if(!response.ok) {const e=new Error('Synthetic relay request rejected');e.status=response.status;throw e;}
   return response.json();
  };
  const library=resolve(process.env.LANU_RATCHET_HOST_LIBRARY??'native/ratchet/target/release/liblanu_ratchet.so');
  native=spawn('java',['--source','17','server/smoke/java/com/example/core/messaging/OlmRatchetNative.java',library],{stdio:['pipe','pipe','ignore']});
  const lines=createInterface({input:native.stdout})[Symbol.asyncIterator]();
  const engine=async(key,command)=>{
   native.stdin.write(key.toString('base64')+' '+Buffer.from(JSON.stringify(command)).toString('base64')+'\n');
   const line=await Promise.race([lines.next(),new Promise((_,reject)=>{const t=setTimeout(()=>reject(new Error('JNI deadline')),15000);t.unref();})]);
   assert(!line.done && line.value!=='ERROR');return JSON.parse(Buffer.from(line.value,'base64').toString());
  };
  const fresh=async(own,peer)=>{
   const key=randomBytes(32),account=await engine(key,{op:'create'});
   const bundle={owner:own.id,peer:peer.id,generation:randomUUID(),identity:account.identity,oneTime:account.oneTime,signature:'A'.repeat(96)};
   bundle.signature=sign('sha256',Buffer.from(ratchetTranscript(bundle)),own.privateKey).toString('base64url');
   return {key,account,bundle};
  };
  step='identity and mutual contact';
  for(const u of users) await request(u,'register',{code:u.code,nonce:u.nonce,publicKey:u.publicKey});
  await request(a,'addContact',{code:b.code});await request(b,'addContact',{code:a.code});
  let ax=await fresh(a,b),bx=await fresh(b,a);
  step='signed initial barrier';
  assert.equal((await request(a,'publishRatchetBundle',{peerId:b.id,bundle:ax.bundle})).ready,false);
  assert.equal((await request(b,'publishRatchetBundle',{peerId:a.id,bundle:bx.bundle})).ready,true);
  const previous=await ratchetEpoch(ax.bundle,bx.bundle);
  assert.equal((await request(a,'ratchetState',{peerId:b.id})).epoch,previous);
  const exchange=async(generation,from,to,sender,recipient,text)=>{
   const id=randomUUID();const plaintext=JSON.stringify({version:3,epoch:generation,id,from:from.id,to:to.id,text});
   const sent=await engine(sender.key,sender.session ? {op:'send',session:sender.session,sessionId:sender.sid,plaintext}:
    {op:'start',account:sender.account.account,peerIdentity:recipient.bundle.identity,peerOneTime:recipient.bundle.oneTime,plaintext});
   sender.session=sent.session;sender.sid=sent.sessionId;
   const frame='LANU-DR3:'+JSON.stringify({version:3,epoch:generation,sessionId:sent.sessionId,message:sent.message});
   const wire=encrypted(from,to,id,frame);
   const data={messageId:id,peerId:to.id,protocolVersion:3,ratchetEpoch:generation,content:wire};
   assert.equal((await request(from,'send',data)).status,'SENT');
   assert.equal((await request(from,'send',data)).status,'SENT');
   const inbox=await request(to,'sync',{ratchetVersion:3});
   const event=inbox.events.find(e=>e.message.id===id);assert(event);assert.equal(event.message.ratchetEpoch,generation);
   const actual=JSON.parse(decrypted(to,from,id,event.message.content).slice(9));
   const opened=await engine(recipient.key,recipient.session ? {op:'receive',session:recipient.session,sessionId:actual.sessionId,message:actual.message}:
    {op:'accept',account:recipient.account.account,peerIdentity:sender.bundle.identity,message:actual.message});
   recipient.session=opened.session;recipient.sid=opened.sessionId;if(opened.account) recipient.account.account=opened.account;
   assert.deepEqual(JSON.parse(opened.plaintext),JSON.parse(plaintext));
   await request(to,'ack',{eventIds:[event.id],readIds:[]});
   return {id,data};
  };
  step='bidirectional native ciphertext and immutable retry';
  await exchange(previous,a,b,ax,bx,'synthetic JNI message');
  await exchange(previous,b,a,bx,ax,'synthetic JNI response');
  step='two separate reset approvals';
  const nextA=await fresh(a,b),nextB=await fresh(b,a),round=randomUUID();
  const vote=(owner,peer,x)=>({peerId:peer.id,resetId:round,previousEpoch:previous,bundle:x.bundle,
   proof:sign('sha256',Buffer.from(ratchetResetTranscript(owner.id,peer.id,round,previous,x.bundle)),owner.privateKey).toString('base64url')});
  assert.equal((await request(a,'renewRatchet',vote(a,b,nextA))).ready,false);
  assert.equal((await request(b,'ratchetState',{peerId:a.id})).epoch,previous);
  const result=await request(b,'renewRatchet',vote(b,a,nextB));assert(result.ready);
  const next=await ratchetEpoch(nextA.bundle,nextB.bundle);assert.equal(result.state.epoch,next);
  assert.equal((await request(a,'renewRatchet',vote(a,b,nextA))).ready,true);
  for(const user of users) {
   const other=user===a?b:a;const receipt=(await request(user,'ratchetState',{peerId:other.id})).completed;
   assert(receipt?.votes[a.id] && receipt?.votes[b.id]);assert.equal(receipt.new_epoch,next);
  }
  step='new generation native exchange';
  await exchange(next,a,b,nextA,nextB,'synthetic recovered JNI message');
  await exchange(next,b,a,nextB,nextA,'synthetic recovered JNI reply');
  ax.key.fill(0);bx.key.fill(0);nextA.key.fill(0);nextB.key.fill(0);
  if(fixturePath) await unlink(fixturePath);
  console.log('PASS: actual JNI, signed relay, bidirectional delivery, immutable retry and two-owner renewal'+(endpoint?' against acceptance Edge':' locally'));
 }
} catch(error) {
 console.error('FAIL: ratchet synthetic smoke at '+step+(Number.isInteger(error?.status)?' (HTTP '+error.status+')':''));
 process.exitCode=1;
} finally {native?.kill();}
