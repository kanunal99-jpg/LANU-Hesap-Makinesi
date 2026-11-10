package com.example

import com.example.core.messaging.MessageAckFailurePolicy
import com.example.core.messaging.RelayException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class MessageAckFailurePolicyTest {
    @Test fun temporaryAckNetworkErrorsNeverBlockIndependentEncryptedOutbox() {
        for (error in listOf<IOException>(
            IOException("radio switch"), SocketTimeoutException("timeout"),
            RelayException(408, "gateway timeout"), RelayException(500, "relay 500"),
            RelayException(502, "gateway"), RelayException(503, "service unavailable"),
            RelayException(504, "gateway timeout")
        )) assertTrue(error.javaClass.simpleName,
            MessageAckFailurePolicy.mayDrainIndependentOutbox(error))
    }

    @Test fun authQuotaProtocolTlsAndCancellationRemainFailClosed() {
        val errors = listOf<IOException>(
            RelayException(400, "malformed"), RelayException(401, "auth"),
            RelayException(403, "peer"), RelayException(409, "conflict"),
            RelayException(413, "size"), RelayException(422, "invalid"),
            RelayException(429, "quota"), SSLHandshakeException("bad cert"),
            ProtocolException("invalid HTTP"),
            InterruptedIOException("thread interrupted"),
            IOException("TLS wrapped", SSLHandshakeException("bad cert")),
            IOException("cancel wrapped", java.util.concurrent.CancellationException("gone"))
        )
        errors.forEach { assertFalse(error.javaClass.simpleName,
            MessageAckFailurePolicy.mayDrainIndependentOutbox(error)) }
    }

    @Test fun deeplyNestedUnknownFailureCannotBypassPermanentCause() {
        var wrapped: IOException = RelayException(401, "auth denied")
        repeat(9) { wrapped = IOException("wrapped", wrapped) }
        assertFalse(MessageAckFailurePolicy.mayDrainIndependentOutbox(wrapped))
    }
}
