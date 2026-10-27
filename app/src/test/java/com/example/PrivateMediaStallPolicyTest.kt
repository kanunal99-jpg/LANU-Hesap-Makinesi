package com.example

import com.example.core.media.PrivateMediaTransferBudget
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaStallPolicyTest {
    @Test fun repeatedZeroBytesCannotCreateInfinitePrivateMediaHashLoop() {
        val policy = PrivateMediaTransferBudget
        assertEquals(1, policy.boundedZeroReads(0, 0))
        assertEquals(2, policy.boundedZeroReads(1, 0))
        try { policy.boundedZeroReads(2, 0); fail("Stalled stream accepted") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun validProgressResetsStallBudget() {
        val policy = PrivateMediaTransferBudget
        assertEquals(0, policy.boundedZeroReads(2, 65536))
        assertEquals(1, policy.boundedZeroReads(0, 0))
        assertEquals(0, policy.boundedZeroReads(1, 1))
    }
}
