package com.example

import com.example.core.messaging.RatchetStateRollbackGate
import com.example.core.messaging.RatchetStateRollbackGate.Block
import com.example.core.messaging.RatchetStateRollbackGate.Counters
import com.example.core.messaging.RatchetStateRollbackGate.Decision
import org.junit.Assert.*
import org.junit.Test

class RatchetStateRollbackGateTest {
    private val anchor = Counters(
        sentTotal = 120, receivedTotal = 230, dhSteps = 4, backupGeneration = 9
    )
    private fun compare(candidate: Counters, authenticated: Boolean = true,
                        reference: Counters? = anchor): Decision =
        RatchetStateRollbackGate.compare(reference, candidate, authenticated)

    @Test fun missingOrUntrustedAnchorAlwaysBlocksRestore() {
        assertEquals(Decision.Blocked(Block.NO_TRUSTED_ANCHOR),
            compare(anchor, authenticated = false))
        assertEquals(Decision.Blocked(Block.NO_TRUSTED_ANCHOR),
            compare(anchor, reference = null))
    }

    @Test fun oldBackupCannotMoveHighWaterStateBackward() {
        assertEquals(Decision.Blocked(Block.BACKUP_GENERATION_ROLLBACK),
            compare(anchor.copy(backupGeneration = 8)))
        assertEquals(Decision.Blocked(Block.MESSAGE_COUNTER_ROLLBACK),
            compare(anchor.copy(sentTotal = 119)))
        assertEquals(Decision.Blocked(Block.MESSAGE_COUNTER_ROLLBACK),
            compare(anchor.copy(receivedTotal = 229)))
        assertEquals(Decision.Blocked(Block.DH_STEP_ROLLBACK),
            compare(anchor.copy(dhSteps = 3)))
    }

    @Test fun invalidNegativeOrSaturatedCountersFailClosed() {
        for (snapshot in listOf(
            anchor.copy(sentTotal = -1),
            anchor.copy(receivedTotal = -1),
            anchor.copy(dhSteps = -1),
            anchor.copy(backupGeneration = -1),
            anchor.copy(sentTotal = Long.MAX_VALUE)
        )) {
            assertEquals(Decision.Blocked(Block.INVALID_COUNTER), compare(snapshot))
        }
        assertEquals(Decision.Blocked(Block.INVALID_COUNTER),
            compare(anchor, reference = anchor.copy(receivedTotal = Long.MAX_VALUE)))
    }

    @Test fun monotonicOfflineEvidenceNeverAuthorizesRealProtocol() {
        val ahead = anchor.copy(sentTotal = 130, receivedTotal = 245,
            dhSteps = 6, backupGeneration = 10)
        assertEquals(Decision.OfflineComparisonOnly, compare(ahead))
        assertEquals(Decision.OfflineComparisonOnly, compare(anchor))
        assertFalse(compare(ahead).toString().contains("Enabled"))
        assertFalse(compare(ahead).toString().contains("Live"))
    }
}
