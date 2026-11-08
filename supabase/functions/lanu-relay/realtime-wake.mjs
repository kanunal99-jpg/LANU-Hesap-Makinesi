// Content-free, server-authorized wake transport. Durable HTTP sync is authoritative.
export const wakeTopic = owner => {
  if (!/^[a-f0-9]{64}$/.test(owner)) throw new Error('Invalid owner');
  return 'lanu-wake:' + owner;
};
export function createWakePublisher(url, key, fetcher = fetch) {
  return async (owner, kind) => {
    if (!['message', 'call'].includes(kind)) throw new Error('Invalid kind');
    const response = await fetcher(url + '/realtime/v1/api/broadcast', {
      method: 'POST', headers: { apikey: key, Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ messages: [{ topic: wakeTopic(owner), event: 'changed', payload: { kind }, private: true }] }),
      signal: AbortSignal.timeout(4000)
    });
    if (!response.ok) throw new Error('Wake unavailable');
    await response.body?.cancel();
  };
}
// A bridge owns one private service-role subscription, never exposed to Android.
// Both the signed owner and private topic are selected on the server, not by the client.
export function bridgeWake(socket, owner, url, key, WebSocketType = WebSocket) {
  const topic = 'realtime:' + wakeTopic(owner);
  let upstream, heartbeat, lifetime, joined = false, finished = false, ref = 1;
  let resolve;
  const done = new Promise(r => { resolve = r; });
  const close = () => {
    if (finished) return;
    finished = true;
    clearInterval(heartbeat); clearTimeout(lifetime);
    try { upstream?.close(); } catch { /* already closed */ }
    try { socket.close(1000, 'Reconnect'); } catch { /* already closed */ }
    resolve();
  };
  const send = (event, payload, channel = topic) => {
    if (upstream?.readyState === 1) upstream.send(JSON.stringify({ topic: channel, event, payload, ref: String(ref++) }));
  };
  socket.onclose = close;
  socket.onerror = close;
  // No inbound application commands; each command still uses signed durable HTTP.
  socket.onmessage = close;
  socket.onopen = () => {
    try {
      upstream = new WebSocketType(url.replace(/^https:/, 'wss:') + '/realtime/v1/websocket?apikey=' + encodeURIComponent(key) + '&vsn=1.0.0');
      lifetime = setTimeout(close, 90000);
      upstream.onopen = () => {
        send('phx_join', { config: { broadcast: { ack: false, self: false }, presence: { enabled: false }, private: true }, access_token: key });
      };
      upstream.onmessage = event => {
        try {
          if (typeof event.data !== 'string' || event.data.length > 4096) return close();
          const value = JSON.parse(event.data);
          if (value.topic !== topic) return;
          if (value.event === 'phx_reply' && value.ref === '1') {
            if (value.payload?.status !== 'ok') return close();
            joined = true;
            socket.send('{"ready":true}');
          } else if (joined && value.event === 'broadcast' && value.payload?.event === 'changed') {
            const kind = value.payload.payload?.kind;
            if (kind === 'message' || kind === 'call') socket.send(JSON.stringify({ changed: kind }));
          } else if (value.event === 'phx_error' || value.event === 'phx_close') close();
        } catch { close(); }
      };
      upstream.onerror = close; upstream.onclose = close;
      heartbeat = setInterval(() => {
        if (!joined) return close();
        send('heartbeat', {}, 'phoenix');
      }, 15000);
    } catch { close(); }
  };
  return { done, close };
}
