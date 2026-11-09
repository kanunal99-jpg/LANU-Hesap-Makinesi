// Content-free, server-authorized wake transport. Durable HTTP sync is authoritative.
export const wakeTopic = owner => {
  if (!/^[a-f0-9]{64}$/.test(owner)) throw new Error('Invalid owner');
  return 'lanu-wake:' + owner;
};
export function createWakePublisher(url, key, fetcher = fetch, pause = ms => new Promise(r => setTimeout(r, ms))) {
  return async (owner, kind) => {
    if (!['message', 'call'].includes(kind)) throw new Error('Invalid kind');
    const body = JSON.stringify({ messages: [{ topic: wakeTopic(owner), event: 'changed', payload: { kind }, private: true }] });
    for (let attempt = 0; attempt < 2; attempt++) {
      let retry = false;
      try {
        const response = await fetcher(url + '/realtime/v1/api/broadcast', {
          method: 'POST', headers: { apikey: key, Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
          body, signal: AbortSignal.timeout(4000)
        });
        retry = response.status === 408 || response.status >= 500;
        // Cleanup failures must never reclassify an auth/quota rejection as retryable.
        try { await response.body?.cancel(); } catch { /* status remains authoritative */ }
        if (response.ok) return;
      } catch { retry = true; }
      // At most one retry; no auth retry, sensitive diagnostics or critical-path wait.
      if (!retry || attempt === 1) throw new Error('Wake unavailable');
      await pause(150);
    }
  };
}
// One private service-role subscription, selected only from the verified owner.
export function bridgeWake(socket, owner, url, key, WebSocketType = WebSocket, timers = globalThis) {
  const topic = 'realtime:' + wakeTopic(owner);
  let upstream, heartbeat, lifetime, joinDeadline, joined = false, finished = false, ref = 1;
  let pendingHeartbeat = null;
  let resolve;
  const done = new Promise(r => { resolve = r; });
  const close = () => {
    if (finished) return;
    finished = true;
    timers.clearInterval(heartbeat); timers.clearTimeout(lifetime); timers.clearTimeout(joinDeadline);
    try { upstream?.close(); } catch { /* already closed */ }
    try { socket.close(1000, 'Reconnect'); } catch { /* already closed */ }
    resolve();
  };
  const send = (event, payload, channel = topic) => {
    if (finished || upstream?.readyState !== 1) return null;
    const current = String(ref++);
    try { upstream.send(JSON.stringify({ topic: channel, event, payload, ref: current })); }
    catch { close(); return null; }
    return current;
  };
  socket.onclose = close;
  socket.onerror = close;
  // No inbound commands. Every durable operation still needs a signed HTTP request.
  socket.onmessage = close;
  socket.onopen = () => {
    if (finished) return;
    try {
      upstream = new WebSocketType(url.replace(/^https:/, 'wss:') + '/realtime/v1/websocket?apikey=' + encodeURIComponent(key) + '&vsn=1.0.0');
      lifetime = timers.setTimeout(close, 90000);
      joinDeadline = timers.setTimeout(close, 10000);
      upstream.onopen = () => {
        send('phx_join', { config: { broadcast: { ack: false, self: false }, presence: { enabled: false }, private: true }, access_token: key });
      };
      upstream.onmessage = event => {
        if (finished) return;
        try {
          if (typeof event.data !== 'string' || event.data.length > 4096) return close();
          const value = JSON.parse(event.data);
          // Heartbeat replies use phoenix, not the private owner channel.
          if (value.topic === 'phoenix' && value.event === 'phx_reply' &&
              value.ref === pendingHeartbeat && value.payload?.status === 'ok') {
            pendingHeartbeat = null; return;
          }
          if (value.topic !== topic) return;
          if (value.event === 'phx_reply' && value.ref === '1') {
            if (value.payload?.status !== 'ok') return close();
            if (joined) return;
            joined = true; timers.clearTimeout(joinDeadline);
            socket.send('{"ready":true}');
          } else if (joined && value.event === 'broadcast' && value.payload?.event === 'changed') {
            const kind = value.payload.payload?.kind;
            if (kind === 'message' || kind === 'call') socket.send(JSON.stringify({ changed: kind }));
          } else if (value.event === 'phx_error' || value.event === 'phx_close') close();
        } catch { close(); }
      };
      upstream.onerror = close; upstream.onclose = close;
      heartbeat = timers.setInterval(() => {
        if (!joined || pendingHeartbeat !== null) return close();
        pendingHeartbeat = send('heartbeat', {}, 'phoenix');
        if (pendingHeartbeat === null) close();
      }, 15000);
    } catch { close(); }
  };
  return { done, close };
}
