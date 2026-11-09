package com.example

import com.example.core.messaging.RelayRequestBudget
import com.example.core.messaging.WakeReconnectPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayRequestBudgetTest {
    private val owner = "a".repeat(64)
    @Test fun ordinaryTrafficCannotSpendCallReserveOrExceedTotalWindow() {
        var now = 0L
        val budget = RelayRequestBudget { now }
        repeat(84) { assertEquals(0L, budget.admit(owner, "sync")) }
        assertEquals(60000L, budget.admit(owner, "send"))
        repeat(28) { assertEquals(0L, budget.admit(owner, "callSend")) }
        assertEquals(60000L, budget.admit(owner, "callAck"))
        now = 59999; assertEquals(1L, budget.admit(owner, "callSync"))
        now = 60000; assertEquals(0L, budget.admit(owner, "send"))
    }
    @Test fun pollingCannotSpendTheLastEightCallSignalPermits() {
        val budget = RelayRequestBudget { 0L }
        repeat(84) { assertEquals(0L, budget.admit(owner, "sync")) }
        repeat(20) { assertEquals(0L, budget.admit(owner, "callSync")) }
        assertEquals(60000L, budget.admit(owner, "callSync"))
        repeat(8) { assertEquals(0L, budget.admit(owner, "callSend")) }
        assertEquals(60000L, budget.admit(owner, "callAck"))
    }
    @Test fun throttleSurvivesClockRegressionAndCannotBeBypassedByControl() {
        var now = 10000L
        val budget = RelayRequestBudget { now }
        budget.admit(owner, "send"); budget.throttle(owner, 60000)
        now = 5000; assertEquals(60000L, budget.admit(owner, "callSend"))
        now = 70000; assertEquals(0L, budget.admit(owner, "callSend"))
        assertEquals(0L, budget.admit("b".repeat(64), "sync"))
    }
    @Test fun canceledWaitNeverConsumesFuturePermit() = runTest {
        val budget = RelayRequestBudget { testScheduler.currentTime }
        repeat(84) { budget.admit(owner, "sync") }
        val job = launch { budget.awaitPermit(owner, "send") }
        runCurrent(); assertTrue(job.isActive)
        job.cancelAndJoin(); advanceTimeBy(60000)
        repeat(84) { assertEquals(0L, budget.admit(owner, "send")) }
    }
    @Test fun rapidCloseBacksOffButHealthySessionResetsAndJitterIsBounded() {
        assertEquals(2, WakeReconnectPolicy.nextFailures(1, true, 100))
        assertEquals(0, WakeReconnectPolicy.nextFailures(6, true, 20000))
        assertEquals(6, WakeReconnectPolicy.nextFailures(6, false, 50000))
        assertEquals(4000L, WakeReconnectPolicy.delayMillis(1, -100))
        assertEquals(5500L, WakeReconnectPolicy.delayMillis(1, 2000))
        assertEquals(60000L, WakeReconnectPolicy.delayMillis(99, 1000))
    }
}
