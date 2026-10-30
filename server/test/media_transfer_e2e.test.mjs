import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { createPrivateMediaTransfer } from '../../supabase/functions/lanu-relay/media-transfer.mjs';

const alice = 'a'.repeat(64), bob = 'b'.repeat(64), outsider = 'c'.repeat(64);
const id = 'd'.repeat(32), expires = () => new Date(Date.now() + 100000).toISOString();
const bytes = randomBytes(65_552);
const digest = createHash('sha256').update(bytes).digest('hex');
function setup(opts = {}) {
  let state = opts.state || 'pending', writes = 0, uploadCalls = 0, downloadCalls = 0, reads = 0;
  const row = () => ({
    file_id:id,sender_id:alice,recipient_id:bob,cipher_bytes:bytes.length,
    cipher_sha256:digest,expires_at:opts.expired ? new Date(1).toISOString() : expires(),
    state
  });
  const store = {
    mediaReservation: async (fileId) => fileId === id ? row() : null,
    markMediaReady: async ({owner,fileId,cipherBytes,cipherSha256}) => {
      writes++;
      if (owner !== alice || fileId !== id || state !== 'pending' ||
          cipherBytes !== bytes.length || cipherSha256 !== digest) return null;
      state = 'ready';
      return row();
    }
  };
  const storage = {
    signUpload: async (bucket, path) => {
      uploadCalls++;
      assert.equal(bucket, 'lanu-private-media-v1');
      assert.equal(path, 'v1/'+id+'.cipher');
      return {url:'https://example.supabase.co/storage/v1/object/upload/sign/'+id,token:'t'.repeat(48)};
    },
    readForVerification: async () => {
      reads++;
      return new Response(opts.corrupt ? Buffer.from(bytes).fill(0) : bytes,{
        headers:{'content-type':'application/octet-stream'}
      });
    },
    signDownload: async (bucket, path, seconds) => {
      downloadCalls++;
      assert.equal(seconds, 90);
      assert.equal(path,'v1/'+id+'.cipher');
      return {url:'https://example.supabase.co/storage/v1/object/sign/'+id+'?token=opaque'};
    }
  };
  return { media:createPrivateMediaTransfer({store,storage}), get state(){return state},
    get writes(){return writes},get uploadCalls(){return uploadCalls},
    get downloadCalls(){return downloadCalls},get reads(){return reads} };
}

test('authenticated owner gets signed upload with opaque keyless metadata', async () => {
  const f=setup();
  const grant=await f.media.uploadGrant(alice,id);
  assert.deepEqual(Object.keys(grant).sort(),['cipherBytes','fileId','token','url']);
  assert.equal(grant.cipherBytes,bytes.length);
  assert.ok(!JSON.stringify(grant).includes(alice));
  assert.ok(!JSON.stringify(grant).includes(digest));
  assert.equal(f.uploadCalls,1);
});
test('receiver, outsider, malformed file or expired pending grant fail before Storage', async () => {
  const f=setup();
  for(const who of [bob,outsider,'X'.repeat(64)]){
    await assert.rejects(f.media.uploadGrant(who,id));
  }
  await assert.rejects(f.media.uploadGrant(alice,'../'+id));
  await assert.rejects(f.media.uploadGrant(alice,'0'.repeat(32)));
  assert.equal(f.uploadCalls,0);
  const expired=setup({expired:true});
  await assert.rejects(expired.media.uploadGrant(alice,id));
  assert.equal(expired.uploadCalls,0);
});
test('no downloads until complete server-side SHA check and exact atomic ready transition', async () => {
  const f=setup();
  await assert.rejects(f.media.downloadGrant(bob,id));
  assert.equal(f.downloadCalls,0);
  assert.equal(f.writes,0);
  const committed=await f.media.finalize(alice,id);
  assert.deepEqual(committed,{fileId:id,ready:true,cipherBytes:bytes.length});
  assert.equal(f.reads,1);
  assert.equal(f.writes,1);
  assert.equal(f.state,'ready');
  const receipt=await f.media.downloadGrant(bob,id);
  assert.equal(receipt.fileId,id);
  assert.equal(receipt.cipherSha256,digest);
  assert.ok(receipt.expiresAt > Date.now());
  assert.ok(receipt.expiresAt <= Date.now()+90000);
  await assert.rejects(f.media.downloadGrant(outsider,id));
  await assert.rejects(f.media.uploadGrant(alice,id));
  assert.equal(f.downloadCalls,1);
});
test('tampered ciphertext never commits and never signs a receiver URL', async () => {
  const f=setup({corrupt:true});
  await assert.rejects(f.media.finalize(alice,id));
  await assert.rejects(f.media.downloadGrant(bob,id));
  assert.equal(f.writes,0);
  assert.equal(f.downloadCalls,0);
  assert.equal(f.state,'pending');
});
test('receiver cannot finalize other party object; replay finalization cannot rewrite ready object', async () => {
  const f=setup();
  await assert.rejects(f.media.finalize(bob,id));
  assert.equal(f.reads,0);
  await f.media.finalize(alice,id);
  await assert.rejects(f.media.finalize(alice,id));
  assert.equal(f.writes,1);
});
test('signed grant adapter failure, malformed URL or token fail closed', async () => {
  const row=()=>({file_id:id,sender_id:alice,recipient_id:bob,cipher_bytes:bytes.length,
    cipher_sha256:digest,expires_at:expires(),state:'pending'});
  const base={mediaReservation:async()=>row(),markMediaReady:async()=>null};
  const fixture=(returnValue)=>createPrivateMediaTransfer({
    store:base,storage:{
      signUpload:async()=>returnValue,
      signDownload:async()=>returnValue,
      readForVerification:async()=>new Response(bytes)
    }
  });
  for(const v of [null,{}, {url:'http://not-tls',token:'t'.repeat(48)},
    {url:'https://evil.invalid/\r\ninjection',token:'t'.repeat(48)},
    {url:'https://storage.example.com',token:'bad'}])
    await assert.rejects(fixture(v).uploadGrant(alice,id));
});
