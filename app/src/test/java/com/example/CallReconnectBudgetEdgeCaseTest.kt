package com.example

import com.example.core.calls.CallReconnectBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression boundaries for encrypted WebRTC ICE restart attempts. */
class CallReconnectBudgetEdgeCaseTest {
    @Test fun maximumPermittedBudgetAllowsExactlyTwoClaims() {
        val budget = CallReconnectBudget(2)
        assertEquals(2, budget.remaining())
        assertTrue(budget.claim())
        assertEquals(1, budget.remaining())
        assertTrue(budget.claim())
        assertEquals(0, budget.remaining())
        repeat(10) { assertFalse(budget.claim()) }
        assertEquals(0, budget.remaining())
    }

    @Test fun independentCallBudgetsCannotSpendOneAnothersAttempts() {
        val first = CallReconnectBudget(2)
        val second = CallReconnectBudget(2)
        assertTrue(first.claim())
        assertTrue(first.claim())
        assertFalse(first.claim())
        assertEquals(2, second.remaining())
        assertTrue(second.claim())
        assertEquals(1, second.remaining())
    }

    @Test fun parallelCallbacksNeverExceedConfiguredTwoRestarts() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val gate = CountDownLatch(1)
            val budget = CallReconnectBudget(2)
            val jobs = (1..32).map {
                pool.submit<Boolean> { gate.await(); budget.claim() }
            }
            gate.countDown()
            assertEquals(2, jobs.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(0, budget.remaining())
        } finally {
            pool.shutdownNow()
        }
    }
}
