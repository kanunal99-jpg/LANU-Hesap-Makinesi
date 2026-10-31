package com.example

import com.example.core.messaging.RelayRequestDeadlinePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** A full 40 MiB authenticated upload SHA check may take 90s on relay. */
class RelayRequestDeadlinePolicyTest {
    @Test fun mediaCompleteWaitsForServerVerificationWithoutUnboundedTimeout() {
        assertEquals(110L, RelayRequestDeadlinePolicy.callTimeoutSeconds("mediaComplete"))
        assertEquals(100L, RelayRequestDeadlinePolicy.readTimeoutSeconds("mediaComplete"))
    }

    @Test fun messagingCallsAreUnchangedAndUntrustedActionsCannotExtendDeadline() {
        for (action in listOf(
            "send", "callSend", "sync", "mediaDownloadGrant", "reservePrivateMedia",
            "enablePrivateMedia", "mediaUploadGrant", "mediaCompleteExtra",
            "", "../mediaComplete", "mediaComplete?no", "MEDIA_COMPLETE"
        )) {
            assertEquals("Default deadline changed for $action", 35L,
                RelayRequestDeadlinePolicy.callTimeoutSeconds(action))
            assertEquals("Default read deadline changed for $action", 20L,
                RelayRequestDeadlinePolicy.readTimeoutSeconds(action))
        }
    }
}
