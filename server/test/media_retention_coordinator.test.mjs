import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanExpiredPrivateMedia } from '../../supabase/functions/lanu-relay/media-retention.mjs';

const now=Date.now(), first='a'.repeat(32), second='b'.repeat(32);
const expired=new Date(now-8_000_000).toISOString();
function fixture(changed={}) {
  const rows=[
    {file_id:first,state:'ready',expires_at:expired},
    {file_id:second,state:'pending',expires_at:expired}
  ];
  let remove=[],changedRows=[];
  const storage={removeExpiredCiphertext: async(bucket,path)=>{
    remove.push([bucket,path]);if(changed.failStorage&&path.includes(first))throw Error('outage');
    return changed.noDelete?false:true;
  }};
  const store={
    expiredMedia:async()=>changed.rows||rows,
    markMediaPurged:async(file,state,stamp)=>{
      changedRows.push({file,state,stamp});
      return !changed.failCommit;
    }
  };
  return {store,storage,remove,changedRows};
}

test('expired private Storage objects are removed before metadata is freed', async()=>{
 const f=fixture();
 const result=await cleanExpiredPrivateMedia({store:f.store,storage:f.storage,nowMs:now});
 assert.deepEqual(result,{scanned:2,purged:2,refused:0});
 assert.deepEqual(f.remove.map(x=>x[1]),['v1/'+first+'.cipher','v1/'+second+'.cipher']);
 assert.equal(f.changedRows.length,2);
});
test('storage or metadata failure cannot be counted as successful quota cleanup',async()=>{
 for(const options of [{failStorage:true},{failCommit:true},{noDelete:true}]){
  const f=fixture(options);
  const value=await cleanExpiredPrivateMedia({store:f.store,storage:f.storage,nowMs:now});
  assert.equal(value.scanned,2);
  assert.ok(value.refused>=1);
  if(options.noDelete)assert.equal(f.changedRows.length,0);
 }
});
test('no path traversal, duplicates, not expired, purged or unknown states',async()=>{
 const f=fixture({rows:[
  {file_id:'../escape',state:'ready',expires_at:expired},
  {file_id:first,state:'ready',expires_at:new Date(now+60_000).toISOString()},
  {file_id:first,state:'ready',expires_at:expired},
  {file_id:first,state:'ready',expires_at:expired},
  {file_id:second,state:'purged',expires_at:expired},
  {file_id:'c'.repeat(32),state:'untrusted',expires_at:expired}
 ]});
 const value=await cleanExpiredPrivateMedia({
   store:f.store,storage:f.storage,nowMs:now,limit:6
 });
 assert.deepEqual(value,{scanned:6,purged:1,refused:5});
 assert.deepEqual(f.remove.map(x=>x[1]),['v1/'+first+'.cipher']);
});
test('recently expired files survive any signed upload grant still in flight',async()=>{
 const recent = new Date(now - 60_000).toISOString();
 const f = fixture({ rows: [
   {file_id:first,state:'pending',expires_at:recent},
   {file_id:second,state:'ready',expires_at:expired}
 ]});
 const result = await cleanExpiredPrivateMedia({
   store:f.store,storage:f.storage,nowMs:now,limit:2
 });
 assert.deepEqual(result,{scanned:2,purged:1,refused:1});
 assert.deepEqual(f.remove.map(x=>x[1]),['v1/'+second+'.cipher']);
});
test('oversized batch and invalid service-only adapter are refused before deletions',async()=>{
 const f=fixture();
 for(const limit of [0,-1,26,1.5])
  await assert.rejects(cleanExpiredPrivateMedia({store:f.store,storage:f.storage,nowMs:now,limit}));
 await assert.rejects(cleanExpiredPrivateMedia({store:f.store,storage:f.storage,nowMs:-1}));
 const oversized=fixture({rows:Array.from({length:11},(_,i)=>({
   file_id:i.toString(16).padStart(32,'0'),state:'ready',expires_at:expired
 }))});
 await assert.rejects(cleanExpiredPrivateMedia({
   store:oversized.store,storage:oversized.storage,nowMs:now,limit:10
 }));
 assert.equal(oversized.remove.length,0);
});
