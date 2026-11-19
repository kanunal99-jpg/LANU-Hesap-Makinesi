import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {fixture,envelope} from './fixtures.mjs';
import {createRelay} from '../../supabase/functions/lanu-relay/core.mjs';

test('only opted-in event paging forwards an owner cursor; legacy response stays unchanged',async()=>{
 const f=await fixture(), seen=[];
 const events=Array.from({length:200},()=>({id:randomUUID(),message:{}}));
 f.store.events=async(owner,media,after)=>{seen.push({owner,after});return events;};
 const relay=createRelay(f.store,Date.now,{eventPaging:true});
 const legacy=await relay(envelope(f.alice,'sync'));
 assert.equal('eventPaging' in legacy,false);assert.equal(seen.at(-1).after,undefined);
 const first=await relay(envelope(f.alice,'sync',{eventPaging:1}));
 assert.equal(first.nextEventCursor,events.at(-1).id);assert.equal(seen.at(-1).after,null);
 await relay(envelope(f.alice,'sync',{eventPaging:1,eventAfter:first.nextEventCursor}));
 assert.deepEqual(seen.at(-1),{owner:f.alice.id,after:first.nextEventCursor});
 f.store.events=async()=>[];
 assert.equal((await relay(envelope(f.alice,'sync',{eventPaging:1}))).nextEventCursor,null);
});
test('malformed or array cursors fail before querying the event page',async()=>{
 const f=await fixture();let reads=0;f.store.events=async()=>{reads++;return [];};
 const relay=createRelay(f.store,Date.now,{eventPaging:true});
 for(const cursor of ['invalid',[randomUUID()],42,{}])
  await assert.rejects(relay(envelope(f.alice,'sync',{eventPaging:1,eventAfter:cursor})),e=>e.status===400);
 assert.equal(reads,0);
});
