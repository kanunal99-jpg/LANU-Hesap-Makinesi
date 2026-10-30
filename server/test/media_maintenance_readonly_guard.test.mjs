import test from 'node:test';
import assert from 'node:assert/strict';
import { runPrivateMediaMaintenance } from '../scripts/private_media_maintenance.mjs';

const id='a'.repeat(32), now=Date.now();
const date=new Date(now-8_000_000).toISOString();
function fixture() {
  const count={read:0,storage:0,db:0};
  const store={
    expiredMedia: async()=>{count.read++;return [{file_id:id,state:'ready',expires_at:date}]},
    markMediaPurged: async()=>{count.db++;return true}
  };
  const storage={removeExpiredCiphertext: async()=>{count.storage++;return true}};
  return {count,store,storage};
}

test('default mode is read-only and prints no ID, path or secrets',async()=>{
  const f=fixture();
  const response=await runPrivateMediaMaintenance({store:f.store,storage:f.storage,nowMs:now});
  assert.deepEqual(response,{mode:'preview',eligible:1,deleted:0});
  assert.deepEqual(f.count,{read:1,storage:0,db:0});
  assert.ok(!JSON.stringify(response).includes(id));
});

test('preview filters objects younger than the two-hour signed-grant grace',async()=>{
 const f=fixture();
 let cutoff;
 f.store.expiredMedia=async at=>{
   cutoff=at;
   return [];
 };
 const result=await runPrivateMediaMaintenance({
   store:f.store,mode:'preview',nowMs:now
 });
 assert.deepEqual(result,{mode:'preview',eligible:0,deleted:0});
 assert.equal(cutoff,now-7_500_000);
 assert.deepEqual(f.count,{read:0,storage:0,db:0});
});
test('apply mode fails closed without explicit separate authorization',async()=>{
 const f=fixture();
 await assert.rejects(runPrivateMediaMaintenance({
   store:f.store,storage:f.storage,mode:'apply',approved:false,nowMs:now
 }));
 assert.deepEqual(f.count,{read:0,storage:0,db:0});
});

test('authorized apply deletes object before marking database purged',async()=>{
 const f=fixture();
 const result=await runPrivateMediaMaintenance({
   store:f.store,storage:f.storage,mode:'apply',approved:true,nowMs:now
 });
 assert.deepEqual(result,{mode:'apply',eligible:1,deleted:1,refused:0});
 assert.deepEqual(f.count,{read:1,storage:1,db:1});
});

test('storage failure reserves quota and does not claim success',async()=>{
 const f=fixture();
 f.storage.removeExpiredCiphertext=async()=>{f.count.storage++;throw Error('offline storage')};
 const r=await runPrivateMediaMaintenance({
   store:f.store,storage:f.storage,mode:'apply',approved:true,nowMs:now
 });
 assert.deepEqual(r,{mode:'apply',eligible:1,deleted:0,refused:1});
 assert.equal(f.count.db,0);
});

test('invalid batch, clock and action are rejected before storage access',async()=>{
 const f=fixture();
 for(const changed of [{mode:'delete'},{limit:26},{limit:0},{nowMs:-1},{nowMs:0}]) {
  await assert.rejects(runPrivateMediaMaintenance({
    store:f.store,storage:f.storage,mode:'preview',nowMs:now,...changed
  }));
 }
 assert.deepEqual(f.count,{read:0,storage:0,db:0});
});

test('malformed preview response is not accepted as eligibility',async()=>{
 const f=fixture();
 f.store.expiredMedia=async()=>[{},{}];
 await assert.rejects(runPrivateMediaMaintenance({
   store:f.store,storage:f.storage,mode:'preview',nowMs:now,limit:1
 }));
 assert.equal(f.count.storage,0);
});
