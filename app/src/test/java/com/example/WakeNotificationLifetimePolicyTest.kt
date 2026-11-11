package com.example

import com.example.core.notifications.WakeNotificationLifetimePolicy
import org.junit.Assert.*
import org.junit.Test

class WakeNotificationLifetimePolicyTest {
    private val now = 1_710_000_000_000L

    private fun call(at: Long, clock: Long = now): Long? =
        WakeNotificationLifetimePolicy.remainingMs(at, clock, 65_000L, 15_000L, 75_000L)

    private fun message(at: Long, clock: Long = now): Long? =
        WakeNotificationLifetimePolicy.remainingMs(at, clock, 120_000L, 15_000L, 120_000L)

    @Test fun lateCallDoesNotGetFreshRingtonePeriod() {
        assertEquals(65_000L, call(now))
        assertEquals(1_000L, call(now - 64_000L))
        assertEquals(1L, call(now - 64_999L))
        assertNull("Exactly expired call must not become infinite timeoutAfter(0)",
            call(now - 65_000L))
        assertNull(call(now - 65_001L))
        assertEquals(75_000L, call(now + 15_000L))
        assertNull(call(now + 15_001L))
    }

    @Test fun delayedMessageReminderExpiresWithoutDeletingEncryptedHistory() {
        assertEquals(120_000L, message(now))
        assertEquals(1L, message(now - 119_999L))
        assertNull(message(now - 120_000L))
        assertNull(message(now - 120_001L))
        assertEquals(120_000L, message(now + 15_000L))
        assertNull(message(now + 15_001L))
    }

    @Test fun timestampAndConfigurationOverflowAlwaysFailClosed() {
        assertNull(call(Long.MIN_VALUE))
        assertNull(call(0L))
        assertNull(call(Long.MAX_VALUE))
        assertNull(call(now, 0L))
        assertNull(call(now, Long.MIN_VALUE))
        assertEquals(65_000L, call(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(75_000L, call(Long.MAX_VALUE, Long.MAX_VALUE - 15_000L))
        assertEquals(1L, call(Long.MAX_VALUE - 64_999L, Long.MAX_VALUE))
        assertNull(call(Long.MAX_VALUE - 65_000L, Long.MAX_VALUE))
        assertNull(WakeNotificationLifetimePolicy.remainingMs(now, now,
            Long.MAX_VALUE, 15_000L, 75_000L))
        assertNull(WakeNotificationLifetimePolicy.remainingMs(now, now,
            65_000L, -1L, 75_000L))
        assertNull(WakeNotificationLifetimePolicy.remainingMs(now, now,
            65_000L, 15_000L, 0L))
    }
}
