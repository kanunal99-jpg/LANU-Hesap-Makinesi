package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DurableDraftAndQueueIsolationTest {
    private lateinit var db: AppDatabase
    private lateinit var own: MessagingIdentity
    private lateinit var peer: MessagingIdentity
    private lateinit var repo: CommunicationRepository
    private lateinit var conversation: String
    private var now = 1000000L
    private var inbound = JSONArray()
    private val actions = mutableListOf<String>()
    private var sendFailure: Int? = null
    private var badMessage: String? = null
    private val store = object : MessagingIdentityStore {
        override fun read() = own
        override fun save(identity: MessagingIdentity) { own = identity }
    }
    private val relay = object : MessagingRelay {
        override val configured = true
        override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
            actions += action
            return when (action) {
                "register" -> JSONObject().put("code", own.code).put("nonce", own.nonce)
                "sync" -> JSONObject().put("contacts", JSONArray().put(JSONObject()
                    .put("id", peer.id).put("code", peer.code).put("nonce", peer.nonce)
                    .put("publicKey", peer.publicKey).put("mutual", true)))
                    .put("events", inbound)
                "send" -> {
                    if (sendFailure != null && (badMessage == null || badMessage == data.getString("messageId")))
                        throw RelayException(sendFailure!!, "test error", 60000L)
                    JSONObject().put("status", "SENT")
                }
                "ack" -> JSONObject()
                else -> error("Unexpected action $action")
            }
        }
    }
    private fun repository() = CommunicationRepository(db.contactDao(), db.conversationDao(), db.messageDao(),
        db.settingsDao(), db.userProfileDao(), db.callLogDao(), store, relay, db, clock = { now })

    @Before fun setup() = runBlocking {
        own = UserCodes.create(); peer = UserCodes.create()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        conversation = UserCodes.conversationId(own.id, peer.id)
        repo = repository()
        repo.ensureIdentity()
        db.conversationDao().insertConversation(ConversationEntity(conversation, peer.code, "Friend"))
        repo.synchronize()
        now += 6000L
        actions.clear()
    }
    @After fun close() { db.close() }

    @Test fun `draft survives repository recreation and plaintext is never stored`() = runBlocking {
        val text = "Kaybolmaması gereken Türkçe taslak 🔐"
        repo.saveDraft(conversation, text)
        val stored = db.settingsDao().getSettingValue("lanu_draft_$conversation")!!
        assertTrue(stored.startsWith("lanu2:"))
        assertFalse(stored.contains(text))
        assertEquals(text, repository().loadDraft(conversation))
        repo.deleteConversation(conversation)
        repo.saveDraft(conversation, "delayed old UI write")
        assertNull(db.settingsDao().getSettingValue("lanu_draft_$conversation"))
    }

    @Test fun `successful queue clears only matching draft and stores ciphertext atomically`() = runBlocking {
        repo.saveDraft(conversation, "queued text")
        val id = repo.sendMessage(conversation, "queued text")
        assertEquals("", repository().loadDraft(conversation))
        assertEquals("PENDING", db.messageDao().getMessage(id)!!.status)
        assertTrue(db.messageDao().getMessage(id)!!.content.startsWith("lanu2:"))
        repo.saveDraft(conversation, "new text being composed")
        repo.sendMessage(conversation, "older text")
        assertEquals("new text being composed", repo.loadDraft(conversation))
    }

    @Test fun `failed queue metadata update rolls back message and preserves draft`() = runBlocking {
        repo.saveDraft(conversation, "keep me")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_conversation BEFORE INSERT ON conversations BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repo.sendMessage(conversation, "keep me"); fail("Queue must fail atomically") } catch (_: android.database.SQLException) { }
        assertEquals(0, db.messageDao().countMessages())
        assertEquals("keep me", repo.loadDraft(conversation))
    }

    @Test fun `invalid outgoing message cannot block incoming receipt or later sends`() = runBlocking {
        val first = repo.sendMessage(conversation, "bad outgoing")
        val second = repo.sendMessage(conversation, "valid outgoing")
        badMessage = first; sendFailure = 413
        val id = UUID.randomUUID().toString()
        val cipher = PeerMessageCrypto.encrypt(peer, own.id, own.publicKey, id, "incoming is delivered")
        inbound = JSONArray().put(JSONObject().put("id", UUID.randomUUID().toString()).put("message", JSONObject()
            .put("id", id).put("senderId", peer.id).put("recipientId", own.id).put("revision", 1)
            .put("createdAt", now).put("status", "SENT").put("content", cipher)))
        repo.synchronize(conversation)
        assertNotNull(db.messageDao().getMessage(id))
        assertEquals("FAILED", db.messageDao().getMessage(first)!!.status)
        assertEquals("SENT", db.messageDao().getMessage(second)!!.status)
        assertTrue(actions.indexOf("ack") < actions.indexOf("send"))
        assertEquals(3, db.messageDao().countMessages())
        repo.retryMessage(first)
        assertEquals("PENDING", db.messageDao().getMessage(first)!!.status)
    }

    @Test fun `more than 200 deferred rows cannot starve a new ready message`() = runBlocking {
        repeat(205) { n ->
            val id = "waiting-$n"
            db.messageDao().insertMessage(MessageEntity(id, conversation, own.id, "Me", "lanu2:retained", timestamp = n.toLong(), status = "WAITING"))
            db.settingsDao().insertSetting(SettingsEntity("lanu_retry_after_$id", (now + 60000L).toString()))
        }
        val ready = repo.sendMessage(conversation, "ready now")
        assertEquals(listOf(ready), db.messageDao().getPendingMessages(now, 5).map { it.id })
        repo.synchronize()
        assertEquals("SENT", db.messageDao().getMessage(ready)!!.status)
        assertEquals(206, db.messageDao().countMessages())
    }

    @Test fun `rate limit preserves queue across recreation and delays retries`() = runBlocking {
        val id = repo.sendMessage(conversation, "retry safely")
        sendFailure = 429
        repo.synchronize()
        assertEquals("PENDING", db.messageDao().getMessage(id)!!.status)
        assertTrue(db.messageDao().getPendingMessages(now, 5).isEmpty())
        sendFailure = null
        now += 60001L
        repository().synchronize()
        assertEquals("SENT", db.messageDao().getMessage(id)!!.status)
    }
}
