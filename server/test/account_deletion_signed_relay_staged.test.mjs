import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { fixture, envelope } from './fixtures.mjs';

/**
 * Only P-256 ECDSA signed requests may select their OWN server identity.
 * Activation is deliberately absent from the production Edge wrapper.
 */
test('live/default relay refuses every account erasure request', async () => {
  const f = await fixture();
  let calls = 0;
  f.store.accountDelete = async () => { calls++; return { deleted:true }; };
  await assert.rejects(f.relay(envelope(f.alice,'deleteAccount',{
    confirmation:'DELETE_ACCOUNT',expectedCode:f.alice.code
  })),{status:503});
  assert.equal(calls,0);
  assert.equal(f.store.users.size,3);
});

test('staged account deletion requires a signed owner and unambiguous exact confirmation', async () => {
  const f = await fixture();
  const requests = [];
  f.store.accountDelete = async (owner,expectedCode) => {
    requests.push({owner,expectedCode});
    return {deleted:true};
  };
  const relay = createRelay(f.store, Date.now, {accountDeletionEnabled:true});
  const good = { confirmation:'DELETE_ACCOUNT',expectedCode:f.alice.code };
  const denied = [
    {},
    {confirmation:'DELETE_ACCOUNT'},
    {confirmation:'delete_account',expectedCode:f.alice.code},
    {confirmation:'DELETE_ACCOUNT',expectedCode:f.bob.code},
    {...good, peerId:f.bob.id},
    {...good, accountId:f.bob.id},
    {...good, force:true}
  ];
  for (const data of denied) {
    await assert.rejects(relay(envelope(f.alice,'deleteAccount',data)),{status:409});
  }
  await assert.rejects(relay(envelope(f.bob,'deleteAccount',good)),{status:409});
  assert.equal(requests.length,0);

  // Replacing identityId with another owner's ID does NOT give the caller
  // that owner's signing private key. Signature verification must reject it.
  const forged = envelope(f.bob,'deleteAccount',good,{identityId:f.alice.id});
  await assert.rejects(relay(forged),{status:401});
  assert.equal(requests.length,0);
  const valid = envelope(f.alice,'deleteAccount',good);
  assert.deepEqual(await relay(valid),{deleted:true});
  assert.deepEqual(requests,[{owner:f.alice.id,expectedCode:f.alice.code}]);
  await assert.rejects(relay(valid),{status:409}); // nonce replay
  assert.equal(requests.length,1);
});

test('transaction failure or zero deleted rows never becomes a false success response', async () => {
  const f = await fixture();
  let called = 0;
  f.store.accountDelete = async () => { called++; return {deleted:false}; };
  const relay = createRelay(f.store, Date.now, {accountDeletionEnabled:true});
  await assert.rejects(relay(envelope(f.alice,'deleteAccount',{
    confirmation:'DELETE_ACCOUNT',expectedCode:f.alice.code
  })),{status:503});
  f.store.accountDelete = async () => { called++; throw new Error('synthetic rollback'); };
  await assert.rejects(relay(envelope(f.alice,'deleteAccount',{
    confirmation:'DELETE_ACCOUNT',expectedCode:f.alice.code
  })),/synthetic rollback/);
  assert.equal(called,2);
  // No client or local database state is destroyed by this server gate.
  assert.equal(f.store.users.has(f.alice.id),true);
});
