package com.example

import com.example.core.messaging.MessageWakeSyncPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageWakeSyncPolicyTest {
    @Test fun noConsentOrInvalidHintNeverSchedulesBackgroundSync() {
        assertFalse(MessageWakeSyncPolicy.shouldSchedule(false, true))
        assertFalse(MessageWakeSyncPolicy.shouldSchedule(true, false))
        assertFalse(MessageWakeSyncPolicy.shouldSchedule(false, false))
        assertTrue(MessageWakeSyncPolicy.shouldSchedule(true, true))
    }

    @Test fun onlyShortTransientFailuresRetryAndOptOutStopsRetries() {
        for (status in listOf(0, 408, 429, 500, 503)) {
            assertTrue(MessageWakeSyncPolicy.shouldRetry(true, status, 0))
            assertTrue(MessageWakeSyncPolicy.shouldRetry(true, status, 1))
            assertFalse(MessageWakeSyncPolicy.shouldRetry(true, status, 2))
            assertFalse(MessageWakeSyncPolicy.shouldRetry(false, status, 0))
        }
        for (status in listOf(400, 401, 403, 409, 413, 422)) {
            assertFalse(MessageWakeSyncPolicy.shouldRetry(true, status, 0))
        }
    }
}
