package com.example

import com.example.core.diagnostics.PrivateConnectionMetrics as M
import org.junit.Assert.*
import org.junit.Test

class WakeDiagnosticsTest {
    @Test fun measurementsRequireOptInAndNeverSurviveDisable() {
        M.setEnabled(false)
        M.recordWake(M.WakeKind.MESSAGE, true, true, 12000)
        assertEquals(0, M.wakeSummary(M.WakeKind.MESSAGE).received)
        M.setEnabled(true)
        M.recordWake(M.WakeKind.MESSAGE, true, true, 12000)
        M.recordWake(M.WakeKind.MESSAGE, true, false, -100)
        assertEquals(M.WakeSummary(2, 1, 1), M.wakeSummary(M.WakeKind.MESSAGE))
        M.setEnabled(false)
        assertEquals(M.WakeSummary(0, 0, 0), M.wakeSummary(M.WakeKind.MESSAGE))
    }
}
