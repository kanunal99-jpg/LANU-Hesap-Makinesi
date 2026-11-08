package com.example

import com.example.core.calls.CallTurnConfig
import org.junit.Assert.*
import org.junit.Test

class CallTurnConfigTest {
    @Test fun acceptsExplicitAuthenticatedTurnAndTurnsAddressesOnly() {
        for (url in listOf("turns:relay.example.com:443?transport=tcp", "turn:relay.example.com:3478?transport=udp"))
            CallTurnConfig.validate(CallTurnConfig.Value(url, "user", "secret"))
    }
    @Test fun rejectsUnboundedPortsUrlCredentialsCleartextAndMalformedInputs() {
        for (url in listOf("http://relay.example.com", "turn:user:password@host:3478?transport=udp", "turn:host:0?transport=udp", "turns:host:443?transport=udp"))
            assertThrows(IllegalArgumentException::class.java) { CallTurnConfig.validate(CallTurnConfig.Value(url, "u", "p")) }
        assertThrows(IllegalArgumentException::class.java) { CallTurnConfig.validate(CallTurnConfig.Value("turn:host:3478?transport=udp", "", "p")) }
        assertFalse(CallTurnConfig.Value("private", "private", "private").toString().contains("private"))
    }
}
