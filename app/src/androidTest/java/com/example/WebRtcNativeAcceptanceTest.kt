package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real ICE/DTLS/SCTP peers, no external STUN, mic, camera, account or fake CONNECTED. */
@RunWith(AndroidJUnit4::class)
class WebRtcNativeAcceptanceTest {
    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(value:SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(value:String?) {}
        override fun onSetFailure(value:String?) {}
    }
    private open class Observer : PeerConnection.Observer {
        override fun onSignalingChange(s:PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s:PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b:Boolean) {}
        override fun onIceGatheringChange(s:PeerConnection.IceGatheringState) {}
        override fun onIceCandidate(c:IceCandidate) {}
        override fun onIceCandidatesRemoved(c:Array<out IceCandidate>) {}
        override fun onAddStream(s:MediaStream) {}
        override fun onRemoveStream(s:MediaStream) {}
        override fun onDataChannel(d:DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }
    private suspend fun create(block:(SdpObserver)->Unit):SessionDescription=suspendCancellableCoroutine { c ->
        block(object:Sdp() {
            override fun onCreateSuccess(value:SessionDescription) { if(c.isActive)c.resume(value) }
            override fun onCreateFailure(value:String?) { if(c.isActive)c.resumeWithException(IllegalStateException("SDP creation failed")) }
        })
    }
    private suspend fun set(block:(SdpObserver)->Unit):Unit=suspendCancellableCoroutine { c ->
        block(object:Sdp() {
            override fun onSetSuccess() { if(c.isActive)c.resume(Unit) }
            override fun onSetFailure(value:String?) { if(c.isActive)c.resumeWithException(IllegalStateException("SDP application failed")) }
        })
    }
    private suspend fun gathered(pc:PeerConnection):String=withTimeout(10_000L) {
        while (true) {
            val sdp=pc.localDescription?.description.orEmpty()
            if(sdp.contains("a=candidate:") && pc.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE) return@withTimeout sdp
            delay(50)
        }
        error("Unreachable")
    }
    @Test fun nativePeersExchangeRealEncryptedPacketsAndRestartIceOnDebugAndRelease()=runBlocking {
        withTimeout(45_000L) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                .createInitializationOptions())
            val factory=PeerConnectionFactory.builder().createPeerConnectionFactory()
            val received=CompletableDeferred<String>(); val opened=CompletableDeferred<Unit>()
            val remoteReady=CompletableDeferred<Unit>()
            var remoteChannel:DataChannel?=null
            val cfg=PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
            val a=checkNotNull(factory.createPeerConnection(cfg,object:Observer() {}))
            val b=checkNotNull(factory.createPeerConnection(cfg,object:Observer() {
                override fun onDataChannel(d:DataChannel) {
                    remoteChannel=d
                    d.registerObserver(object:DataChannel.Observer {
                        override fun onBufferedAmountChange(previous:Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer:DataChannel.Buffer) {
                            val bytes=ByteArray(buffer.data.remaining());buffer.data.get(bytes)
                            received.complete(String(bytes,Charsets.UTF_8))
                        }
                    })
                    remoteReady.complete(Unit)
                }
            }))
            val channel=a.createDataChannel("lanu-native-smoke",DataChannel.Init())
            try {
                channel.registerObserver(object:DataChannel.Observer {
                    override fun onBufferedAmountChange(previous:Long) {}
                    override fun onStateChange() { if(channel.state()==DataChannel.State.OPEN)opened.complete(Unit) }
                    override fun onMessage(buffer:DataChannel.Buffer) {}
                })
                val offer=create { a.createOffer(it,MediaConstraints()) }
                set { a.setLocalDescription(it,offer) }
                val first=gathered(a)
                assertTrue(first.contains("a=fingerprint:sha-256 "))
                set { b.setRemoteDescription(it,SessionDescription(SessionDescription.Type.OFFER,first)) }
                val answer=create { b.createAnswer(it,MediaConstraints()) }
                set { b.setLocalDescription(it,answer) }
                val fullAnswer=gathered(b)
                set { a.setRemoteDescription(it,SessionDescription(SessionDescription.Type.ANSWER,fullAnswer)) }
                if(channel.state()!=DataChannel.State.OPEN)opened.await()
                remoteReady.await()
                assertTrue(channel.send(DataChannel.Buffer(ByteBuffer.wrap("native-verified".toByteArray()),false)))
                assertEquals("native-verified",received.await())
                a.restartIce()
                val restart=create { a.createOffer(it,MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("IceRestart","true")) }) }
                val oldUfrag=Regex("a=ice-ufrag:([^\\r\\n]+)").find(first)!!.groupValues[1]
                val newUfrag=Regex("a=ice-ufrag:([^\\r\\n]+)").find(restart.description)!!.groupValues[1]
                assertNotEquals("ICE restart must actually change credentials",oldUfrag,newUfrag)
            } finally {
                channel.unregisterObserver();channel.close();channel.dispose()
                remoteChannel?.unregisterObserver();remoteChannel?.close();remoteChannel?.dispose()
                a.close();b.close();a.dispose();b.dispose();factory.dispose()
            }
        }
    }
}
