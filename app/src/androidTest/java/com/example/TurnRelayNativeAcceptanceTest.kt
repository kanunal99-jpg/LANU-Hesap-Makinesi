package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.calls.EmptyPeerObserver
import com.example.core.calls.CallTurnConfig
import com.example.core.calls.TurnAllocationProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.*
import java.nio.ByteBuffer

/** Real authenticated TURN/ICE/DTLS/SCTP, no microphone, camera or user account. */
@RunWith(AndroidJUnit4::class)
class TurnRelayNativeAcceptanceTest {
    private fun credentials(): Pair<String,String> {
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("lanu_turn_ci")=="true") { "CI TURN fixture missing; refusing synthetic success" }
        return checkNotNull(args.getString("lanu_turn_user")) to checkNotNull(args.getString("lanu_turn_password"))
    }
    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp:SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error:String?) {}
        override fun onSetFailure(error:String?) {}
    }
    private suspend fun create(block:(SdpObserver)->Unit):SessionDescription {
        val result=CompletableDeferred<SessionDescription>()
        block(object:Sdp() {
            override fun onCreateSuccess(sdp:SessionDescription) { result.complete(sdp) }
            override fun onCreateFailure(error:String?) { result.completeExceptionally(IllegalStateException("TURN offer failed")) }
        })
        return result.await()
    }
    private suspend fun set(block:(SdpObserver)->Unit) {
        val result=CompletableDeferred<Unit>()
        block(object:Sdp() {
            override fun onSetSuccess() { result.complete(Unit) }
            override fun onSetFailure(error:String?) { result.completeExceptionally(IllegalStateException("TURN SDP failed")) }
        })
        result.await()
    }
    /**
     * Android libwebrtc exposes gathered/trickled candidates through onIceCandidate.
     * localDescription.description is not required to be rewritten with those
     * candidates; checking SDP text alone produced false relay failures in CI.
     * Do not replace a real relay-only ICE allocation with a synthetic candidate.
     */
    private suspend fun gathered(peer:PeerConnection, observed:MutableList<IceCandidate>):List<IceCandidate> =
        withTimeout(12_000L) {
            while(true) {
                if(peer.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE) {
                    val candidates=synchronized(observed) { observed.toList() }
                    val relayCount = candidates.count {
                        Regex("(?:^|\\s)typ relay(?:\\s|$)").containsMatchIn(it.sdp)
                    }
                    // Only candidate counts/types: never SDP, addresses, credentials, or ports.
                    check(candidates.isNotEmpty() && relayCount == candidates.size) {
                        "Authenticated relay route missing (total=${candidates.size}, relay=$relayCount)"
                    }
                    return@withTimeout candidates
                }
                delay(50)
            }
            error("Unreachable")
        }
    private suspend fun roundTrip(transport:String, port:Int=3478, alternate:Boolean=false) = withTimeout(45_000L) {
        val (user,password)=credentials()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).setInjectableLogger({ _,_,_-> },Logging.Severity.LS_NONE).createInitializationOptions())
        val factory=PeerConnectionFactory.builder().createPeerConnectionFactory()
        var a:PeerConnection?=null; var b:PeerConnection?=null
        var outgoing:DataChannel?=null; var incoming:DataChannel?=null
        val outgoingOpened=CompletableDeferred<Unit>(); val incomingOpened=CompletableDeferred<Unit>()
        val remoteReady=CompletableDeferred<Unit>()
        val firstCandidates=java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
        val secondCandidates=java.util.Collections.synchronizedList(mutableListOf<IceCandidate>())
        val receivedByA=CompletableDeferred<String>(); val receivedByB=CompletableDeferred<String>()
        fun observer(channel:DataChannel, received:CompletableDeferred<String>, opened:CompletableDeferred<Unit>)=object:DataChannel.Observer {
            override fun onBufferedAmountChange(previous:Long) {}
            override fun onStateChange() { if(channel.state()==DataChannel.State.OPEN) opened.complete(Unit) }
            override fun onMessage(buffer:DataChannel.Buffer) {
                val bytes=ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                received.complete(String(bytes,Charsets.UTF_8))
            }
        }
        fun config()=PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder(com.example.core.calls.CallTurnTransportPolicy.urls(
                CallTurnConfig.Value("turn:10.0.2.2:$port?transport=$transport",user,password,alternate)))
                .setUsername(user).setPassword(password).createIceServer()
        )).apply { iceTransportsType=PeerConnection.IceTransportsType.RELAY; sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN }
        try {
            a=checkNotNull(factory.createPeerConnection(config(),object:EmptyPeerObserver() {
                override fun onIceCandidate(candidate:IceCandidate) { firstCandidates.add(candidate) }
            }))
            b=checkNotNull(factory.createPeerConnection(config(),object:EmptyPeerObserver() {
                override fun onIceCandidate(candidate:IceCandidate) { secondCandidates.add(candidate) }
                override fun onDataChannel(channel:DataChannel) {
                    incoming=channel; channel.registerObserver(observer(channel,receivedByB,incomingOpened)); remoteReady.complete(Unit)
                }
            }))
            val first=checkNotNull(a); val second=checkNotNull(b)
            val channel=checkNotNull(first.createDataChannel("authenticated-relay-test",DataChannel.Init()))
            outgoing=channel
            channel.registerObserver(observer(channel,receivedByA,outgoingOpened))
            val offer=create { first.createOffer(it,MediaConstraints()) }
            set { first.setLocalDescription(it,offer) }
            val offerCandidates=gathered(first,firstCandidates)
            set { second.setRemoteDescription(it,checkNotNull(first.localDescription)) }
            offerCandidates.forEach { check(second.addIceCandidate(it)) { "Remote offer ICE candidate rejected" } }
            val answer=create { second.createAnswer(it,MediaConstraints()) }
            set { second.setLocalDescription(it,answer) }
            val answerCandidates=gathered(second,secondCandidates)
            set { first.setRemoteDescription(it,checkNotNull(second.localDescription)) }
            answerCandidates.forEach { check(first.addIceCandidate(it)) { "Remote answer ICE candidate rejected" } }
            outgoingOpened.await(); remoteReady.await(); incomingOpened.await()
            check(channel.send(DataChannel.Buffer(ByteBuffer.wrap("relay-a-to-b".toByteArray()),false)))
            assertEquals("relay-a-to-b",receivedByB.await())
            check(checkNotNull(incoming).send(DataChannel.Buffer(ByteBuffer.wrap("relay-b-to-a".toByteArray()),false)))
            assertEquals("relay-b-to-a",receivedByA.await())
        } finally {
            try { outgoing?.dispose() }
            finally { try { incoming?.dispose() }
                finally { try { a?.dispose() }
                    finally { try { b?.dispose() } finally { factory.dispose() } } } }
        }
    }
    @Test fun authenticatedUdpRelayCarriesRealEncryptedPacketsBothWays()=runBlocking { roundTrip("udp"); Unit }
    @Test fun authenticatedTcpRelayCarriesRealEncryptedPacketsBothWays()=runBlocking { roundTrip("tcp"); Unit }
    @Test fun udpUnavailableUsesExplicitSameServerTcpAlternative()=runBlocking {
        val (user,password)=credentials()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(TurnAllocationProbe.run(context,CallTurnConfig.Value("turn:10.0.2.2:3479?transport=udp",user,password),3_000L))
        roundTrip("udp",3479,true)
    }
    @Test fun productionAllocationProbeAcceptsValidCredentialsAndRejectsInvalidOnes()=runBlocking {
        val (user,password)=credentials()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(TurnAllocationProbe.run(context,CallTurnConfig.Value("turn:10.0.2.2:3478?transport=udp",user,password)))
        assertFalse(TurnAllocationProbe.run(context,CallTurnConfig.Value("turn:10.0.2.2:3478?transport=udp",user,password+"invalid"),3_000L))
    }
}
