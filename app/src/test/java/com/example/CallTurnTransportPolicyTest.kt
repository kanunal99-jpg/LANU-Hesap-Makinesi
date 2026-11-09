package com.example
import com.example.core.calls.CallTurnConfig
import com.example.core.calls.CallTurnTransportPolicy
import org.junit.Assert.*
import org.junit.Test
class CallTurnTransportPolicyTest {
    @Test fun oldProfileHasNoImplicitNetworkExpansion() {
        val v=CallTurnConfig.Value("turn:relay.example:3478?transport=udp","owner","secret")
        assertEquals(listOf(v.url),CallTurnTransportPolicy.urls(v))
    }
    @Test fun explicitAlternativeRetainsExactHostPortAndPrimaryPreference() {
        val udp="turn:relay.example:443?transport=udp"
        val tcp="turn:relay.example:443?transport=tcp"
        assertEquals(listOf(udp,tcp),CallTurnTransportPolicy.urls(CallTurnConfig.Value(udp,"owner","secret",true)))
        assertEquals(listOf(tcp,udp),CallTurnTransportPolicy.urls(CallTurnConfig.Value(tcp,"owner","secret",true)))
    }
    @Test fun tlsCannotBeDowngradedEvenWithAlternativePreference() {
        val uri="turns:relay.example:443?transport=tcp"
        assertEquals(listOf(uri),CallTurnTransportPolicy.urls(CallTurnConfig.Value(uri,"owner","secret",true)))
    }
}
