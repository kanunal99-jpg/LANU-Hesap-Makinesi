import test from 'node:test';
import assert from 'node:assert/strict';
import { createPrivateMediaStorage } from '../../supabase/functions/lanu-relay/media-storage.mjs';

const id='a'.repeat(32), name='v1/'+id+'.cipher';
const url='https://example-project.supabase.co';
function fixture(returned) {
  const calls=[];
  const api=createPrivateMediaStorage(url,'sb_secret_123456789012345678901234',
    async (target, init)=>{
      calls.push({target,method:init.method,body:init.body});
      return new Response(JSON.stringify(returned),{
        status:200,headers:{'content-type':'application/json'}
      });
    });
  return {api,calls};
}

test('Storage API removes only server-derived private ciphertext path',async()=>{
 const f=fixture([{name}]);
 assert.equal(await f.api.removeExpiredCiphertext('lanu-private-media-v1',name),true);
 assert.equal(f.calls.length,1);
 assert.equal(f.calls[0].target,url+'/storage/v1/object/lanu-private-media-v1');
 assert.equal(f.calls[0].method,'DELETE');
 assert.deepEqual(JSON.parse(f.calls[0].body),{prefixes:[name]});
});
test('nothing is purged after absent, wrong or ambiguous Storage deletion receipt',async()=>{
 for(const data of [[],[{}],[{name:'v1/'+ 'b'.repeat(32)+'.cipher'}],
   [{name},{name}],{deleted:true}]){
  const f=fixture(data);
  assert.equal(await f.api.removeExpiredCiphertext('lanu-private-media-v1',name),false);
 }
});
test('path traversal or wrong storage bucket is rejected before API request',async()=>{
 const f=fixture([{name}]);
 for(const bad of ['../'+name,'/object/'+name,'v1/%2e%2e','v1/'+'0'.repeat(32)+'.json']){
  await assert.rejects(f.api.removeExpiredCiphertext('lanu-private-media-v1',bad));
 }
 await assert.rejects(f.api.removeExpiredCiphertext('public',name));
 assert.equal(f.calls.length,0);
});
