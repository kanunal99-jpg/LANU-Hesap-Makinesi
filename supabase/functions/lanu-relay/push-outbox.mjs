import { FcmUnregisteredTokenError, FcmRetryableError, FcmPermanentDispatchError } from './call-wake-fcm.mjs';

/** Each lease has one bounded attempt. DB leases, not warm-instance RAM, own jobs. */
export function createMessagePushDispatcher(store, send, report = () => {}, clock = Date.now) {
  return async () => {
    if (!send) return;
    for (let i = 0; i < 4; i++) {
      let job;
      try { job = await store.leaseMessagePush(); }
      catch { return; } // Job remains durable for the scheduler.
      if (!job) return;
      let outcome = 'retry', delayMs = 60000;
      try { outcome = await send(job.token, clock(), 'message') ? 'accepted' : 'expired'; }
      catch (error) {
        if (error instanceof FcmUnregisteredTokenError) {
          outcome = 'expired';
          try { await store.messageWakeInvalidate(job.recipientId, job.token); } catch { /* compare-delete may retry on token health */ }
        } else if (error instanceof FcmPermanentDispatchError) outcome = 'expired';
        else if (error instanceof FcmRetryableError) {
          delayMs = Number.isFinite(error.retryAfterMillis) ? Math.ceil(error.retryAfterMillis) : 86400000;
        }
      }
      try { if (await store.finishMessagePush(job, outcome, delayMs) !== true) return; }
      catch { return; } // Lost completion response: lease expiry safely retries.
      try { report(outcome); } catch { /* No identifiers, token or exception text. */ }
    }
  };
}
