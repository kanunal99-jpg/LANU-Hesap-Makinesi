package com.example

import com.example.core.calls.CallRtpCountPolicy
import org.junit.Assert.*
import org.junit.Test

class CallRtpCountPolicyTest {
    @Test fun normalIntegerAndMissingOptionalRtpCountersRemainCompatible() {
        assertEquals(0L, CallRtpCountPolicy.add(0L, null))
        assertEquals(10L, CallRtpCountPolicy.add(0L, 10))
        assertEquals(15L, CallRtpCountPolicy.add(10L, 5L))
        assertEquals(15L, CallRtpCountPolicy.add(10L, 5.0))
        assertEquals(15L, CallRtpCountPolicy.add(15L, null))
    }

    @Test fun invalidRtcStatsCannotFakeBidirectionalMedia() {
        for (invalid in listOf<Any>(
            -1, -0.5, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 1.5, "500", Long.MAX_VALUE
        )) {
            assertEquals("Invalid counter accepted: $invalid", -1L,
                CallRtpCountPolicy.add(0L, invalid))
        }
    }

    @Test fun sumOverflowOrPriorInvalidCounterNeverWrapsPositive() {
        assertEquals(-1L, CallRtpCountPolicy.add(Long.MAX_VALUE - 2, 5L))
        assertEquals(-1L, CallRtpCountPolicy.add(-1L, 100L))
        assertEquals(-1L, CallRtpCountPolicy.add(-1L, null))
    }
}
