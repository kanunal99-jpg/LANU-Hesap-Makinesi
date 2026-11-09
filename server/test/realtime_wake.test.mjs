import test from 'node:test';
import assert from 'node:assert/strict';
import { createRelay } from '../../supabase/functions/lanu-relay/core.mjs';
import { bridgeWake, createWakePublisher, wakeTopic } from '../../supabase/functions/lanu-relay/realtime-wake.mjs';
import { fixture, envelope, link, encrypted } from './fixtures.mjs';
import { randomUUID } from 'node:crypto';

test('realtime authorization uses a signed existing owner, rejects replay and cannot select another inbox', async () => {
  const f = await fixture();
  const relay = createRelay(f.store, Date.now, { realtimeEnabled: true });
  const auth = envelope(f.alice, 'realtimeOpen');
  assert.deepEqual(await relay(auth), { owner: f.alice.id, version: 1 });
  await assert.rejects(relay(auth), { status: 409 });
  await assert.rejects(relay(envelope(f.alice, 'realtimeOpen', { owner: f.bob.id })), { status: 503 });
  await assert.rejects(relay(envelope(f.alice, 'realtimeOpen', {}, { identityId: f.bob.id })), { status: 401 });
  await assert.rejects(f.relay(envelope(f.alice, 'realtimeOpen')), { status: 503 });
});

test('wake runs only after durable accepted ciphertext; failures do not reject or duplicate the message', async () => {
  const f = await fixture(); await link(f);
  let events = []; let diagnostics = 0;
  const relay = createRelay(f.store, Date.now, { onWakeUnavailable: () => { diagnostics++; throw Error("logger unavailable"); }, publishWake: async (owner, kind) => {
    assert.equal(f.store.messages.size, 1); events.push([owner, kind]); throw new Error('offline');
  } });
  const id = randomUUID(), data = { messageId: id, peerId: f.bob.id, content: encrypted(f.alice, f.bob, id, 'private') };
  assert.equal((await relay(envelope(f.alice, 'send', data))).status, 'SENT');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(events, [[f.bob.id, 'message']]);
  assert.equal(diagnostics, 1);
  assert.equal((await relay(envelope(f.alice, 'send', data))).status, 'SENT');
  assert.equal(f.store.messages.size, 1);
  await assert.rejects(relay(envelope(f.mallory, 'send', { ...data, peerId: f.bob.id })), { status: 409 });
});

test('publisher uses a private owner-selected channel with only a fixed content-free hint', async () => {
  let request;
  const owner = 'a'.repeat(64);
  await createWakePublisher('https://example.com', 'server-only', async (url, options) => {
    request = { url, options }; return new Response('{}');
  })(owner, 'call');
  assert.deepEqual(JSON.parse(request.options.body), { messages: [{ topic: wakeTopic(owner), event: 'changed', payload: { kind: 'call' }, private: true }] });
  await assert.rejects(createWakePublisher('https://example.com', 'key')('invalid', 'call'));
});

class FakeSocket {
  static created = [];
  readyState = 1; sent = []; closed = false;
  constructor(url) { this.url = url; FakeSocket.created.push(this); }
  send(value) { this.sent.push(JSON.parse(value)); }
  close() { this.closed = true; }
}
test('bridge rejects cross-owner and unjoined events and cleans both sockets on close', async () => {
  const downstream = new FakeSocket('client'), owner = 'a'.repeat(64);
  const bridge = bridgeWake(downstream, owner, 'https://example.com', 'server-key', FakeSocket);
  downstream.onopen(); const upstream = FakeSocket.created.at(-1); upstream.onopen();
  const topic = 'realtime:' + wakeTopic(owner);
  const emit = value => upstream.onmessage({ data: JSON.stringify(value) });
  emit({ topic, event: 'broadcast', payload: { event: 'changed', payload: { kind: 'call' } } });
  assert.equal(downstream.sent.length, 0);
  emit({ topic, event: 'phx_reply', ref: '1', payload: { status: 'ok' } });
  emit({ topic: 'other', event: 'broadcast', payload: { event: 'changed', payload: { kind: 'message' } } });
  emit({ topic, event: 'broadcast', payload: { event: 'changed', payload: { kind: 'call', secret: 'discarded' } } });
  assert.deepEqual(downstream.sent, [{ ready: true }, { changed: 'call' }]);
  downstream.onclose(); await bridge.done;
  assert.equal(upstream.closed, true); assert.equal(downstream.closed, true);
});

class FakeTimers {
  now = 0; id = 0; jobs = new Map();
  setTimeout = (fn, ms) => { const id=++this.id; this.jobs.set(id,{fn,at:this.now+ms,period:0});return id; };
  clearTimeout = id => this.jobs.delete(id);
  setInterval = (fn, ms) => { const id=++this.id; this.jobs.set(id,{fn,at:this.now+ms,period:ms});return id; };
  clearInterval = id => this.jobs.delete(id);
  tick(ms) {
    const end=this.now+ms;
    while(true) {
      const next=[...this.jobs].filter(([,j])=>j.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];
      if(!next) break;
      const [id,job]=next;this.now=job.at;
      if(job.period) job.at+=job.period;else this.jobs.delete(id);
      job.fn();
    }
    this.now=end;
  }
}
test('unconfirmed join expires in ten seconds and late callbacks cannot reopen it', async () => {
  const socket=new FakeSocket('client'),timers=new FakeTimers();
  const bridge=bridgeWake(socket,'a'.repeat(64),'https://example.com','key',FakeSocket,timers);
  socket.onopen();const upstream=FakeSocket.created.at(-1);upstream.onopen();
  timers.tick(10000);await bridge.done;
  upstream.onmessage({data:JSON.stringify({topic:'realtime:'+wakeTopic('a'.repeat(64)),event:'phx_reply',ref:'1',payload:{status:'ok'}})});
  assert.equal(socket.sent.length,0);assert.equal(timers.jobs.size,0);
});
test('heartbeat acknowledgement keeps bridge alive; missing acknowledgement closes both ends', async () => {
  const socket=new FakeSocket('client'),timers=new FakeTimers();
  const bridge=bridgeWake(socket,'a'.repeat(64),'https://example.com','key',FakeSocket,timers);
  socket.onopen();const upstream=FakeSocket.created.at(-1);upstream.onopen();
  upstream.onmessage({data:JSON.stringify({topic:'realtime:'+wakeTopic('a'.repeat(64)),event:'phx_reply',ref:'1',payload:{status:'ok'}})});
  timers.tick(15000);const heartbeat=upstream.sent.at(-1);
  assert.equal(heartbeat.topic,'phoenix');
  upstream.onmessage({data:JSON.stringify({topic:'phoenix',event:'phx_reply',ref:heartbeat.ref,payload:{status:'ok'}})});
  timers.tick(15000);assert.equal(socket.closed,false);
  timers.tick(15000);await bridge.done;
  assert.equal(socket.closed,true);assert.equal(upstream.closed,true);assert.equal(timers.jobs.size,0);
});
test('wake publisher retries only transient errors with the same hint and closes response bodies', async () => {
  let calls=0;const bodies=[];const pauses=[];
  const publisher=createWakePublisher('https://example.com','key',async (_,options)=>{
    calls++;bodies.push(options.body);return new Response('{}',{status:calls===1?503:200});
  },async ms=>{pauses.push(ms);});
  await publisher('a'.repeat(64),'message');assert.equal(calls,2);assert.equal(bodies[0],bodies[1]);assert.deepEqual(pauses,[150]);
  calls=0;
  await assert.rejects(createWakePublisher('https://example.com','key',async ()=>{calls++;return new Response('{}',{status:403});},async()=>{})('a'.repeat(64),'call'));
  assert.equal(calls,1);
  calls=0;
  await assert.rejects(createWakePublisher('https://example.com','key',async ()=>{calls++;throw Error('offline');},async()=>{})('a'.repeat(64),'call'));
  assert.equal(calls,2);
});

test('ninety-second lifetime closes healthy channels and rate-limit failures are not retried', async () => {
  const socket=new FakeSocket('client'),timers=new FakeTimers();
  const bridge=bridgeWake(socket,'a'.repeat(64),'https://example.com','key',FakeSocket,timers);
  socket.onopen();const upstream=FakeSocket.created.at(-1);upstream.onopen();
  upstream.onmessage({data:JSON.stringify({topic:'realtime:'+wakeTopic('a'.repeat(64)),event:'phx_reply',ref:'1',payload:{status:'ok'}})});
  for(let i=0;i<5;i++) {
    timers.tick(15000);const beat=upstream.sent.at(-1);
    upstream.onmessage({data:JSON.stringify({topic:'phoenix',event:'phx_reply',ref:beat.ref,payload:{status:'ok'}})});
  }
  assert.equal(socket.closed,false);timers.tick(15000);await bridge.done;
  assert.equal(socket.closed,true);assert.equal(timers.jobs.size,0);
  let calls=0;
  await assert.rejects(createWakePublisher('https://example.com','key',async()=>{calls++;return new Response('{}',{status:429,headers:{'Retry-After':'60'}});},async()=>{})('a'.repeat(64),'message'));
  assert.equal(calls,1);
});

test('broken response cleanup cannot turn an authorization rejection into a retry', async () => {
  let attempts=0;
  const publish=createWakePublisher('https://example.com','key',async()=>{
    attempts++;return {ok:false,status:403,body:{cancel:async()=>{throw Error('stream closed');}}};
  },async()=>{});
  await assert.rejects(publish('a'.repeat(64),'call'));assert.equal(attempts,1);
});
