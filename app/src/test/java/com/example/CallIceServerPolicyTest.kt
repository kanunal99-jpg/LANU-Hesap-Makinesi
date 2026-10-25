package com.example

import com.example.core.calls.CallIceServerPolicy
import org.junit.Assert.*
import org.junit.Test

class CallIceServerPolicyTest {
    @Test fun independentProvidersAndOnlySafePublicStun() {
        val servers=CallIceServerPolicy.stunUrls
        assertEquals(3,servers.size)
        assertEquals(servers.size,servers.distinct().size)
        assertTrue(servers.all { it.startsWith("stun:") && !it.contains('@') &&
            !it.contains('/') && it.matches(Regex("stun:[a-z0-9.-]+:[0-9]+")) })
        assertTrue("Google discovery must still be available",servers.any { it.contains("google.com") })
        assertTrue("Independent provider must be available",servers.any { it.contains("cloudflare.com") })
        assertFalse("No unauthorised, potentially billable TURN",servers.any { it.startsWith("turn:") || it.startsWith("turns:") })
    }
}
