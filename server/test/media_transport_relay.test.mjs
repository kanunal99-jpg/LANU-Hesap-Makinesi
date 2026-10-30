import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, link, envelope } from './fixtures.mjs';

const fileId = 'a'.repeat(32);
async function setup(enabled) {
  const f = await fixture();
  await link(f);
  let calls = [];
  const mediaTransfer = {
    uploadGrant: async (owner,id) => { calls.push(['upload',owner,id]); return {fileId:id,url:'https://storage.example/sign',token:'secret',cipherBytes:17}; },
    finalize: async (owner,id) => { calls.push(['finish',owner,id]); return {fileId:id,ready:true}; },
    downloadGrant: async (owner,id) => { calls.push(['download',owner,id]); return {fileId:id,url:'https://storage.example/shortlink'}; }
  };
  const relay = createRelay(f.store, Date.now, {
    privateMediaReservation: enabled, mediaTransfer
  });
  return { ...f, relay, calls };
}
test('all media actions remain disabled by default and never invoke signed storage', async () => {
  const f=await setup(false);
  for(const action of ['privateMediaUploadGrant','privateMediaFinalize','privateMediaDownloadGrant']) {
    await assert.rejects(f.relay(envelope(f.alice,action,{fileId})),{status:503});
  }
  assert.equal(f.calls.length,0);
});
test('signed, registered relay identities are required before any enabled grant call', async () => {
  const f=await setup(true);
  const result=await f.relay(envelope(f.alice,'privateMediaUploadGrant',{fileId}));
  assert.equal(result.cipherBytes,17);
  assert.deepEqual(f.calls,[['upload',f.alice.id,fileId]]);
  await f.relay(envelope(f.bob,'privateMediaDownloadGrant',{fileId}));
  await f.relay(envelope(f.alice,'privateMediaFinalize',{fileId}));
  assert.equal(f.calls.length,3);
  await assert.rejects(f.relay(envelope(f.bob,'privateMediaUploadGrant',{fileId},{
    identityId:f.alice.id
  })),{status:401});
  assert.equal(f.calls.length,3);
});
test('strict file-ID and payload shape; malicious URL/path/key never reaches coordinator', async () => {
  const f=await setup(true);
  for(const data of [
    {fileId:'../secret'}, {fileId:'Z'.repeat(32)}, {fileId:'a'.repeat(31)},
    {fileId,token:'bypass'}, {fileId,path:'../secret'},
    {fileId,privateKey:'SHOULD_NOT_LEAK'},
    {fileId, bucket:'public'}, {fileId,signedUrl:'https://example.com'}
  ]) await assert.rejects(f.relay(envelope(f.alice,'privateMediaUploadGrant',data)),{status:400});
  assert.equal(f.calls.length,0);
});
test('exact signed envelope request replay is rejected before calling file grant', async () => {
  const f=await setup(true), e=envelope(f.alice,'privateMediaDownloadGrant',{fileId});
  await f.relay(e);
  await assert.rejects(f.relay(e),{status:409});
  assert.equal(f.calls.length,1);
});
