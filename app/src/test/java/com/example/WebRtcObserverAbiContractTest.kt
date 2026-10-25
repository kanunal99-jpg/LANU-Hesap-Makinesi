package com.example

import com.example.core.calls.EmptyPeerObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Guard native Observer callback ABI: method dispatch must never fall back to
 * an Android desugared PeerConnection.Observer$-CC companion missing from the
 * minified target APK. The actual native ICE/DTLS loopback stays mandatory.
 */
class WebRtcObserverAbiContractTest {
    @Test fun allNativeDefaultCallbacksHaveConcreteImplementations() {
        val adapter = EmptyPeerObserver::class.java
        val signatures = listOf(
            "onStandardizedIceConnectionChange",
            "onConnectionChange",
            "onIceCandidateError",
            "onSelectedCandidatePairChanged",
            "onAddTrack",
            "onRemoveTrack",
            "onTrack"
        )
        for (methodName in signatures) {
            val methods = adapter.declaredMethods.filter { it.name == methodName }
            assertEquals("RTC-JNI-OBSERVER-001: concrete method required for $methodName", 1, methods.size)
            assertTrue("RTC-JNI-OBSERVER-001: method must be public", Modifier.isPublic(methods.single().modifiers))
            assertEquals(Void.TYPE, methods.single().returnType)
        }
    }
}
