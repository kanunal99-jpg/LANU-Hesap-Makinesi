package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.calls.*
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import com.example.features.communication.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class NativeCallStateTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CommunicationRepository
    private lateinit var own: MessagingIdentity
    private lateinit var peer: MessagingIdentity
    private lateinit var vm: NativeCallViewModel
    private val inbox=mutableListOf<JSONObject>()
    private val sent=MutableStateFlow<List<CallSignal>>(emptyList())
    private var starts=0
    private var closes=0
    private var mediaCreated=0
    private var iceRestarts=0
    private var failStart=false
    private var acceptGate:CompletableDeferred<Unit>?=null
    private var report: ((CallMediaEvidence)->Unit)?=null
    private var mediaFailure: ((String)->Unit)?=null
    private val sdp="v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 AA:BB\r\n"

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context=ApplicationProvider.getApplicationContext<Context>()
        own=UserCodes.create(); peer=UserCodes.create()
        db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        db.contactDao().insertContact(ContactEntity(peer.code,"Test kişi"))
        val peerJson=JSONObject().put("id",peer.id).put("code",peer.code).put("nonce",peer.nonce)
            .put("publicKey",peer.publicKey).put("mutual",true)
        val relay=object: MessagingRelay {
            override val configured=true
            override suspend fun request(identity: MessagingIdentity,action:String,data:JSONObject):JSONObject = when(action) {
                "register" -> JSONObject().put("code",own.code).put("nonce",own.nonce)
                "sync" -> JSONObject().put("contacts",JSONArray().put(peerJson)).put("events",JSONArray()).apply {
                    if(data.optInt("callVersion")==1) put("calls",JSONArray(inbox.toList()))
                }
                "callSync" -> JSONObject().put("version",1).put("signals",JSONArray(inbox.take(24)))
                "callAck" -> {
                    val ids=data.getJSONArray("ids")
                    val accepted=(0 until ids.length()).map { ids.getString(it) }.toSet()
                    inbox.removeAll { it.optString("id") in accepted }
                    JSONObject().put("acknowledged",true)
                }
                "callSend" -> {
                    val id=data.getString("id")
                    val plain=PeerMessageCrypto.decrypt(peer,own.id,own.publicKey,CallSignaling.aadId(id),own.id,data.getString("content"))
                    val signal=CallSignaling.decode(plain,id,data.getString("callId"),System.currentTimeMillis())
                    sent.value=sent.value+signal
                    if(signal.kind==CallSignalKind.ACCEPT) acceptGate?.await()
                    JSONObject().put("accepted",true)
                }
                else -> JSONObject()
            }
        }
        repo=CommunicationRepository(db.contactDao(),db.conversationDao(),db.messageDao(),db.settingsDao(),db.userProfileDao(),db.callLogDao(),
            object:MessagingIdentityStore { override fun read()=own;override fun save(identity:MessagingIdentity) { error("No key replacement") } },relay,db)
        repo.ensureIdentity()
        repo.synchronize()
        vm=NativeCallViewModel(context,repo) { evidence,failure ->
            mediaCreated++;report=evidence;mediaFailure=failure
            object:CallMediaSession {
                override suspend fun start(video:Boolean) { starts++;check(!failStart) }
                override suspend fun offer(restart:Boolean):String { if(restart) iceRestarts++;return sdp }
                override suspend fun answer(remote:String)=sdp
                override suspend fun remoteAnswer(remote:String) {}
                override fun mute(value:Boolean) {}
                override fun camera(value:Boolean) {}
                override fun switchCamera() {}
                override fun close() { closes++ }
            }
        }
        vm.activate()
    }
    @After fun cleanup() = runBlocking {
        vm.deactivate()
        delay(40)
        db.close();Dispatchers.resetMain()
    }
    private suspend fun waitPhase(phase:NativeCallPhase)=withTimeout(5000) { vm.state.first { it.phase==phase } }
    private suspend fun awaitSent(kind:CallSignalKind)=withTimeout(5000) { sent.first { it.any { s->s.kind==kind } }.last { it.kind==kind } }
    private suspend fun inject(signal:CallSignal, badAad:Boolean=false) {
        val cipher=PeerMessageCrypto.encrypt(peer,own.id,own.publicKey,if(badAad) signal.id else CallSignaling.aadId(signal.id),CallSignaling.encode(signal))
        inbox+=JSONObject().put("id",signal.id).put("callId",signal.callId).put("senderId",peer.id).put("recipientId",own.id)
            .put("content",cipher).put("createdAt",System.currentTimeMillis()).put("expiresAt",System.currentTimeMillis()+90_000)
        repo.synchronize()
    }
    @Test fun fullCallQueueKeepsUnreadSignalsAtRelayUntilCapacityReturns() = runBlocking {
        // Force a genuinely full foreground queue without a UI collector. An
        // old DROP_OLDEST queue silently ACKed and discarded accepted calls.
        vm.deactivate()
        repo.setCallingForeground(true)
        val ids=(0 until 66).map {
            val signal=CallSignaling.create(UUID.randomUUID().toString(),CallSignalKind.INVITE,false)
            val cipher=PeerMessageCrypto.encrypt(peer,own.id,own.publicKey,
                CallSignaling.aadId(signal.id),CallSignaling.encode(signal))
            inbox += JSONObject().put("id",signal.id).put("callId",signal.callId)
                .put("senderId",peer.id).put("recipientId",own.id).put("content",cipher)
                .put("createdAt",System.currentTimeMillis())
                .put("expiresAt",System.currentTimeMillis()+90_000L)
            signal.id
        }
        repo.pollCallSignals()
        assertEquals(42,inbox.size)
        repo.pollCallSignals()
        assertEquals(18,inbox.size)
        repo.pollCallSignals()
        assertEquals("Only enqueued signals may be acknowledged",2,inbox.size)
        assertEquals(ids[64],inbox[0].getString("id"))
        assertEquals(ids[65],inbox[1].getString("id"))
        assertEquals(ids[0],withTimeout(1000) { repo.callSignals.first() }.signal.id)
        repo.pollCallSignals()
        assertEquals(1,inbox.size)
        assertEquals(ids[65],inbox.single().getString("id"))
        assertEquals(ids[1],withTimeout(1000) { repo.callSignals.first() }.signal.id)
        repo.pollCallSignals()
        assertEquals(0,inbox.size)
        repo.setCallingForeground(false)
    }

    @Test fun outgoingWithoutPermissionCannotSendAnInviteOrStartMedia() = runBlocking {
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,false)
        assertEquals(NativeCallPhase.IDLE,vm.state.value.phase)
        assertEquals(0,mediaCreated);assertTrue(sent.value.isEmpty())
    }
    @Test fun incomingOfferCannotCaptureBeforeExplicitAcceptanceAndPermission() = runBlocking {
        val call=UUID.randomUUID().toString()
        inject(CallSignaling.create(call,CallSignalKind.INVITE,true))
        waitPhase(NativeCallPhase.RINGING)
        inject(CallSignaling.create(call,CallSignalKind.OFFER,true,sdp))
        assertEquals(0,starts)
        vm.accept(UUID.randomUUID().toString(),true)
        assertEquals(0,starts)
        vm.accept(call,false)
        waitPhase(NativeCallPhase.ENDED)
        assertEquals(0,starts);assertEquals(0,mediaCreated)
    }
    @Test fun acceptedOfferStartsOnceAndOnlyRealPacketEvidenceConnects() = runBlocking {
        val call=UUID.randomUUID().toString()
        inject(CallSignaling.create(call,CallSignalKind.INVITE,false));waitPhase(NativeCallPhase.RINGING)
        vm.accept(call,true);awaitSent(CallSignalKind.ACCEPT)
        inject(CallSignaling.create(call,CallSignalKind.OFFER,false,sdp));awaitSent(CallSignalKind.ANSWER)
        assertEquals(1,starts)
        report!!(CallMediaEvidence(true,0,1));assertEquals(NativeCallPhase.CONNECTING,vm.state.value.phase)
        report!!(CallMediaEvidence(true,1,1));waitPhase(NativeCallPhase.CONNECTED)
        vm.end();waitPhase(NativeCallPhase.ENDED);assertEquals(1,closes)
    }
    @Test fun outgoingDoesNotOpenMediaUntilTheMatchingPeerAccepts() = runBlocking {
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,true)
        val invite=awaitSent(CallSignalKind.INVITE)
        assertEquals(0,starts)
        inject(CallSignaling.create(UUID.randomUUID().toString(),CallSignalKind.ACCEPT,false))
        assertEquals(0,starts)
        inject(CallSignaling.create(invite.callId,CallSignalKind.ACCEPT,false));awaitSent(CallSignalKind.OFFER)
        assertEquals(1,starts)
    }
    @Test fun offerIsRetainedWhenAcceptWasDeliveredButItsHttpResponseIsStillPending() = runBlocking {
        acceptGate=CompletableDeferred()
        val call=UUID.randomUUID().toString()
        inject(CallSignaling.create(call,CallSignalKind.INVITE,false));waitPhase(NativeCallPhase.RINGING)
        vm.accept(call,true);awaitSent(CallSignalKind.ACCEPT)
        inject(CallSignaling.create(call,CallSignalKind.OFFER,false,sdp))
        assertEquals(0,starts)
        acceptGate!!.complete(Unit)
        awaitSent(CallSignalKind.ANSWER)
        assertEquals(1,starts)
    }
    @Test fun endingWhileAnOfferWaitsForAcceptCannotStartMediaLater() = runBlocking {
        acceptGate=CompletableDeferred()
        val call=UUID.randomUUID().toString()
        inject(CallSignaling.create(call,CallSignalKind.INVITE,false));waitPhase(NativeCallPhase.RINGING)
        vm.accept(call,true);awaitSent(CallSignalKind.ACCEPT)
        inject(CallSignaling.create(call,CallSignalKind.OFFER,false,sdp))
        vm.end();acceptGate!!.complete(Unit)
        awaitSent(CallSignalKind.END)
        assertEquals(0,starts);assertEquals(NativeCallPhase.ENDED,vm.state.value.phase)
    }
    @Test fun failedMediaInitializationStillReleasesOwnedResources() = runBlocking {
        failStart=true
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,true)
        val invite=awaitSent(CallSignalKind.INVITE)
        inject(CallSignaling.create(invite.callId,CallSignalKind.ACCEPT,false));waitPhase(NativeCallPhase.FAILED)
        assertEquals(1,starts);assertEquals(1,closes)
    }
    @Test fun networkDisconnectTriesOnlyOneRealIceRestartAndRequiresFreshPacketEvidence() = runBlocking {
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,true)
        val invite=awaitSent(CallSignalKind.INVITE)
        inject(CallSignaling.create(invite.callId,CallSignalKind.ACCEPT,false))
        awaitSent(CallSignalKind.OFFER)
        report!!(CallMediaEvidence(true,8,11));waitPhase(NativeCallPhase.CONNECTED)
        report!!(CallMediaEvidence(false,8,11));waitPhase(NativeCallPhase.RECONNECTING)
        withTimeout(2000) { sent.first { list -> list.count { it.kind==CallSignalKind.OFFER } >= 2 } }
        assertEquals("Only one ICE restart may be attempted",1,iceRestarts)
        report!!(CallMediaEvidence(false,0,0))
        report!!(CallMediaEvidence(true,0,12))
        assertEquals("Transport alone is not a restored audio call",NativeCallPhase.RECONNECTING,vm.state.value.phase)
        report!!(CallMediaEvidence(true,12,12));waitPhase(NativeCallPhase.CONNECTED)
        assertEquals(1,iceRestarts)
        vm.end();waitPhase(NativeCallPhase.ENDED)
        assertEquals(1,closes)
    }

    @Test fun ordinaryChatCiphertextCannotCreateAnIncomingCall() = runBlocking {
        inject(CallSignaling.create(UUID.randomUUID().toString(),CallSignalKind.INVITE,false),badAad=true)
        assertEquals(NativeCallPhase.IDLE,vm.state.value.phase);assertEquals(0,starts)
    }
    @Test fun asynchronousCameraOrAudioFailureClosesMediaAndShowsAnHonestError() = runBlocking {
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,true)
        val invite=awaitSent(CallSignalKind.INVITE)
        inject(CallSignaling.create(invite.callId,CallSignalKind.ACCEPT,false));awaitSent(CallSignalKind.OFFER)
        mediaFailure!!("Kamera kullanılamadı; sesli aramayı deneyin.")
        waitPhase(NativeCallPhase.FAILED)
        assertEquals(1,closes);assertTrue(vm.state.value.message.contains("Kamera kullanılamadı"))
    }
    @Test fun leavingPrivateAreaStopsMediaAndInvalidatesLateCallbacks() = runBlocking {
        vm.outgoing(UserCodes.conversationId(own.id,peer.id),false,true)
        val invite=awaitSent(CallSignalKind.INVITE)
        inject(CallSignaling.create(invite.callId,CallSignalKind.ACCEPT,false));awaitSent(CallSignalKind.OFFER)
        vm.deactivate()
        report!!(CallMediaEvidence(true,10,10))
        assertEquals(NativeCallPhase.IDLE,vm.state.value.phase);assertEquals(1,closes)
    }
}
