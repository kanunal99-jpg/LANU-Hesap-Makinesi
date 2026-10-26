package com.example

import com.example.core.calls.CallReconnectBudget
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CallReconnectBudgetTest {
    @Test fun oneAuthenticatedCallMayRestartOnlyOnce() {
        val budget = CallReconnectBudget()
        assertEquals(1, budget.remaining())
        assertTrue(budget.claim())
        assertFalse(budget.claim())
        assertFalse(budget.claim())
        assertEquals(0, budget.remaining())
    }

    @Test fun everyNewCallGetsItsOwnBudget() {
        val priorCall = CallReconnectBudget()
        assertTrue(priorCall.claim())
        assertFalse(priorCall.claim())
        val nextCall = CallReconnectBudget()
        assertTrue(nextCall.claim())
        assertEquals(0, nextCall.remaining())
    }

    @Test fun simultaneousTransportCallbacksCannotDoubleRestart() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val budget = CallReconnectBudget()
            val futures = (1..24).map {
                pool.submit<Boolean> { start.await(); budget.claim() }
            }
            start.countDown()
            assertEquals(1, futures.count { it.get(3, TimeUnit.SECONDS) })
            assertEquals(0, budget.remaining())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun invalidBudgetCannotSilentlyAllowInfiniteRenegotiation() {
        for (limit in listOf(0, 3, Int.MAX_VALUE)) {
            try { CallReconnectBudget(limit); fail("Unsafe ICE restart limit accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
