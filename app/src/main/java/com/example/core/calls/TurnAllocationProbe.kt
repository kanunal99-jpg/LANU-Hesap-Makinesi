package com.example.core.calls

import android.content.Context
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit owner's relay-only allocation test. No tracks, microphone, camera or peer signaling. */
@androidx.annotation.Keep
object TurnAllocationProbe {
    suspend fun run(context: Context, value: CallTurnConfig.Value, timeoutMs: Long = 12_000L): Boolean {
        CallTurnConfig.validate(value)
        require(timeoutMs in 1_000L..12_000L)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                var factory: PeerConnectionFactory? = null
                var peer: PeerConnection? = null
                var channel: DataChannel? = null
                val closed = AtomicBoolean(false)
                val allocated = CompletableDeferred<Boolean>()
                try {
                    withTimeoutOrNull(timeoutMs) {
                        NativeCallEngine.initialize(context)
                        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                        val config = PeerConnection.RTCConfiguration(listOf(
                            PeerConnection.IceServer.builder(CallTurnTransportPolicy.urls(value)).setUsername(value.username).setPassword(value.credential).createIceServer()
                        )).apply { iceTransportsType = PeerConnection.IceTransportsType.RELAY }
                        peer = checkNotNull(factory!!.createPeerConnection(config, object : EmptyPeerObserver() {
                            override fun onIceCandidate(candidate: IceCandidate) {
                                if (!closed.get() && CallIceGatherPolicy.hasRelayCandidate("a=" + candidate.sdp)) allocated.complete(true)
                            }
                            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                                if (!closed.get() && state == PeerConnection.IceGatheringState.COMPLETE) allocated.complete(false)
                            }
                        }))
                        // A local-only data m-line starts ICE; it has no remote description or data send.
                        channel = checkNotNull(peer!!.createDataChannel("allocation-test", DataChannel.Init()))
                        val offer = CompletableDeferred<SessionDescription>()
                        peer!!.createOffer(object : EmptySdpObserver() {
                            override fun onCreateSuccess(sdp: SessionDescription) { offer.complete(sdp) }
                            override fun onCreateFailure(error: String?) { offer.completeExceptionally(IllegalStateException("Allocation offer unavailable")) }
                        }, MediaConstraints())
                        val sdp = offer.await()
                        val local = CompletableDeferred<Unit>()
                        peer!!.setLocalDescription(object : EmptySdpObserver() {
                            override fun onSetSuccess() { local.complete(Unit) }
                            override fun onSetFailure(error: String?) { local.completeExceptionally(IllegalStateException("Allocation description unavailable")) }
                        }, sdp)
                        local.await()
                        allocated.await()
                    } ?: false
                } finally {
                    closed.set(true)
                    // Every cleanup is attempted, including timeout/cancellation/native failures.
                    try { channel?.dispose() } finally { try { peer?.dispose() } finally { factory?.dispose() } }
                }
            }
        } finally { dispatcher.close() }
    }
}
