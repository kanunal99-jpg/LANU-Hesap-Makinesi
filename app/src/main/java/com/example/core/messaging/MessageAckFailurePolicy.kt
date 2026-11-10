package com.example.core.messaging

import com.example.core.network.TransientTransportFailure
import java.io.IOException

/**
 * Durable authenticated incoming E2EE events remain unacknowledged for replay
 * when the ACK transport fails. Only genuine transient transport failures
 * permit the independent ciphertext outbox to drain; no false read/sent
 * receipt, identity bypass, TLS bypass, permission bypass or quota bypass.
 */
internal object MessageAckFailurePolicy {
    fun mayDrainIndependentOutbox(error: IOException): Boolean =
        TransientTransportFailure.isRetryable(error)
}
