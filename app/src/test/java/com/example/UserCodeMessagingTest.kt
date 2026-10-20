package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserCodeMessagingTest {
    private lateinit var db: AppDatabase
    private val vault = object : MessagingIdentityStore {
        var value: MessagingIdentity? = null
        override fun read() = value
        override fun save(identity: MessagingIdentity) { value = identity }
    }
    private val offline = object : MessagingRelay {
        override val configured = false
        override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = throw RelayException(0, "offline")
    }
    private fun repository(
        store: MessagingIdentityStore = vault,
        relay: MessagingRelay = offline,
        conversationDao: ConversationDao = db.conversationDao(),
        messageDao: MessageDao = db.messageDao()
    ) = CommunicationRepository(
        db.contactDao(), conversationDao, messageDao, db.settingsDao(),
        db.userProfileDao(), db.callLogDao(), store, relay, db)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        vault.value = null
    }
    @After fun close() { db.close() }

    @Test fun `code is generated once and survives repository recreation`() = runBlocking {
        val first = repository().ensureIdentity()
        repeat(5) { assertEquals(first, repository().ensureIdentity()) }
        assertEquals(first.code, repository().getUserProfile().first)
        assertFalse(first.code.startsWith("+"))
        assertEquals(first.code, UserCodes.normalize(first.code.lowercase().replace("-", " ")))
    }
    @Test fun `explicit code change keeps account keys contacts and history`() = runBlocking {
        val repo = repository()
        val first = repo.ensureIdentity()
        val peer = UserCodes.create()
        repo.addContact("Arkadaş", peer.code)
        db.conversationDao().insertConversation(ConversationEntity("history", peer.code, "Arkadaş"))
        val changed = repo.rotateCode()
        assertNotEquals(first.code, changed.code)
        assertEquals(first.id, changed.id)
        assertEquals(first.publicKey, changed.publicKey)
        assertEquals(first.privateKey, changed.privateKey)
        assertNotNull(db.contactDao().getContactByPhone(peer.code))
        assertNotNull(db.conversationDao().getConversationById("history"))
        assertEquals(changed.code, repository().ensureIdentity().code)
    }
    @Test fun `code validates and binds to public key`() {
        val first = UserCodes.create()
        val other = UserCodes.create()
        assertTrue(UserCodes.matches(first.code, first.publicKey, first.nonce))
        assertFalse(UserCodes.matches(first.code, other.publicKey, first.nonce))
        listOf("+905001234567", "1989", "2026", "LANU-0000-0000-0000-0000-0000", "").forEach { assertEquals("", UserCodes.normalize(it)) }
    }
    @Test fun `independent identities produce different contact codes`() {
        val identities = (1..50).map { UserCodes.create() }
        assertEquals(50, identities.map { it.code }.distinct().size)
        assertEquals(50, identities.map { it.id }.distinct().size)
    }
    @Test fun `unreadable identity never silently creates replacement`() = runBlocking {
        val broken = object : MessagingIdentityStore {
            override fun read(): MessagingIdentity? = error("Keystore unavailable")
            override fun save(identity: MessagingIdentity) { fail("Must not save a replacement") }
        }
        try { repository(broken).ensureIdentity(); fail("Must fail closed") } catch (_: IllegalStateException) { }
    }
    @Test fun `own code and phone numbers cannot be saved as new contacts`() = runBlocking {
        val repo = repository()
        val own = repo.ensureIdentity()
        for (code in listOf(own.code, "+905001234567", "1989")) {
            try { repo.addContact("Kişi", code); fail("Must reject $code") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `legacy profile migration preserves name and own message ownership`() = runBlocking {
        db.userProfileDao().insertProfile(UserProfileEntity("+905001234567", "Ünal"))
        db.conversationDao().insertConversation(ConversationEntity("legacy", "+905009999999", "Eski sohbet"))
        db.messageDao().insertMessage(MessageEntity("old", "legacy", "+905001234567", "Ünal", "legacy content", status = "SENT"))
        val repo = repository()
        val own = repo.ensureIdentity()
        assertEquals("Ünal", repo.getUserProfile().second)
        assertEquals(own.code, db.userProfileDao().getUserProfile()!!.phoneNumber)
        assertEquals(own.id, db.messageDao().getMessage("old")!!.senderPhone)
        assertNotNull(db.conversationDao().getConversationById("legacy"))
    }
    @Test fun `leaving private area never resets identity`() = runBlocking {
        val repo = repository()
        val first = repo.ensureIdentity()
        repo.logout()
        assertEquals(first, repository().ensureIdentity())
    }
    @Test fun `two independent clients decrypt each others messages only`() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val mallory = UserCodes.create()
        val id = java.util.UUID.randomUUID().toString()
        val text = "Telefon numarası olmadan merhaba 🌍"
        val encrypted = PeerMessageCrypto.encrypt(alice, bob.id, bob.publicKey, id, text)
        assertNotEquals(text, encrypted)
        assertEquals(text, PeerMessageCrypto.decrypt(bob, alice.id, alice.publicKey, id, alice.id, encrypted))
        assertEquals(text, PeerMessageCrypto.decrypt(alice, bob.id, bob.publicKey, id, alice.id, encrypted))
        try { PeerMessageCrypto.decrypt(mallory, alice.id, alice.publicKey, id, alice.id, encrypted); fail("Wrong recipient must fail") } catch (_: Exception) { }
    }
    @Test fun `message metadata and ciphertext cannot be substituted`() {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        val id = java.util.UUID.randomUUID().toString()
        val cipher = PeerMessageCrypto.encrypt(alice, bob.id, bob.publicKey, id, "merhaba")
        try { PeerMessageCrypto.decrypt(bob, alice.id, alice.publicKey, "another-message", alice.id, cipher); fail("Must reject changed id") } catch (_: Exception) { }
        assertNotEquals(cipher, PeerMessageCrypto.encrypt(alice, bob.id, bob.publicKey, id, "merhaba"))
    }
    @Test fun `request signatures prove the private key never needs transmission`() {
        val own = UserCodes.create()
        val payload = "{\"action\":\"register\",\"identityId\":\"${own.id}\"}"
        val signature = UserCodes.decode(UserCodes.sign(own, payload))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(UserCodes.decode(own.publicKey)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key); verifier.update(payload.toByteArray())
        assertTrue(verifier.verify(signature))
        verifier.initVerify(key); verifier.update((payload + "changed").toByteArray())
        assertFalse(verifier.verify(signature))
    }
    @Test fun `conversation identity is independent of public contact code`() {
        val a = UserCodes.create()
        val b = UserCodes.create()
        assertEquals(UserCodes.conversationId(a.id, b.id), UserCodes.conversationId(b.id, a.id))
        val rotated = a.copy(nonce = UserCodes.newNonce())
        assertEquals(UserCodes.conversationId(a.id, b.id), UserCodes.conversationId(rotated.id, b.id))
    }

    @Test fun `password encrypted backup restores exact identity with random salt and iv`() {
        val own = UserCodes.create()
        val password = "Yedek-parolam-çok-güçlü"
        val first = IdentityBackup.export(own, password)
        val second = IdentityBackup.export(own, password)
        assertNotEquals(first, second)
        assertFalse(first.contains(own.privateKey))
        assertFalse(first.contains(own.code))
        assertEquals(own, IdentityBackup.restore(first, password))
        assertEquals(own, IdentityBackup.restore(second, password))
    }
    @Test fun `wrong backup password tampering and untrusted kdf are rejected`() {
        val own = UserCodes.create()
        val backup = IdentityBackup.export(own, "a-long-password-123")
        val tampered = JSONObject(backup).put("data", UserCodes.encode(ByteArray(128))).toString()
        val expensive = JSONObject(backup).put("iterations", Int.MAX_VALUE).toString()
        for ((value, password) in listOf(backup to "wrong-password-123", tampered to "a-long-password-123", expensive to "a-long-password-123")) {
            try { IdentityBackup.restore(value, password); fail("Invalid backup must fail") } catch (_: IllegalArgumentException) { }
        }
        try { IdentityBackup.export(own, "1234"); fail("Short password must fail") } catch (_: IllegalArgumentException) { }
    }
    @Test fun `android 23 fallback derives the same key as native pbkdf2`() {
        val salt = ByteArray(16) { it.toByte() }
        for (password in listOf("password12345", "Türkçe-parola-ışık")) {
            val expected = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
                javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 12, 256)).encoded
            assertArrayEquals(expected, IdentityBackup.softwarePbkdf2(password, salt, 12))
        }
    }
    @Test fun `fresh device can recover code but existing other history is protected`() = runBlocking {
        val recovered = UserCodes.create()
        val backup = IdentityBackup.export(recovered, "a-long-password-123")
        val repo = repository()
        val initial = repo.ensureIdentity()
        db.conversationDao().insertConversation(ConversationEntity("history", "legacy", "Eski sohbet"))
        try { repo.restoreIdentity(backup, "a-long-password-123"); fail("Must preserve other history") } catch (_: IllegalArgumentException) { }
        assertEquals(initial, repo.ensureIdentity())
        db.conversationDao().deleteConversation("history")
        assertEquals(recovered, repo.restoreIdentity(backup, "a-long-password-123"))
        assertEquals(recovered, repository().ensureIdentity())
    }

    @Test fun `incoming ciphertext must authenticate before storing and sending delivery or read receipts`() = runBlocking {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        vault.save(alice)
        val conversationId = UserCodes.conversationId(alice.id, bob.id)
        val messageId = java.util.UUID.randomUUID().toString()
        val eventId = java.util.UUID.randomUUID().toString()
        var ciphertext = "lanu2:" + "A".repeat(40)
        var acknowledgements = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = when (action) {
                "register" -> JSONObject().put("code", alice.code).put("nonce", alice.nonce)
                "sync" -> JSONObject()
                    .put("contacts", org.json.JSONArray().put(
                        JSONObject().put("id", bob.id).put("code", bob.code)
                            .put("nonce", bob.nonce).put("publicKey", bob.publicKey)
                            .put("codeAtAdd", bob.code).put("mutual", true)))
                    .put("events", org.json.JSONArray().put(JSONObject().put("id", eventId)
                        .put("message", JSONObject().put("id", messageId)
                            .put("senderId", bob.id).put("recipientId", alice.id)
                            .put("revision", 1).put("status", "SENT")
                            .put("createdAt", System.currentTimeMillis())
                            .put("content", ciphertext))))
                "ack" -> JSONObject().put("acknowledged", true).also { acknowledgements++ }
                else -> error("Unexpected relay call: $action")
            }
        }
        val repo = repository(relay = relay)
        assertTrue(runCatching { repo.synchronize(conversationId) }.exceptionOrNull() is SecurityException)
        assertNull("Tampered message must never be saved", db.messageDao().getMessage(messageId))
        assertEquals("An invalid message must never be marked delivered or read", 0, acknowledgements)

        ciphertext = PeerMessageCrypto.encrypt(bob, alice.id, alice.publicKey, messageId, "doğrulanmış mesaj")
        repo.synchronize(conversationId)
        assertNotNull("Authentic message must be saved for offline access", db.messageDao().getMessage(messageId))
        assertTrue("Valid message must be acknowledged", acknowledgements > 0)
        assertEquals("doğrulanmış mesaj", repo.getMessages(conversationId).first()[0].content)
    }

    @Test fun `recipient gets offline message on entering private area and unread badge counts once`() = runBlocking {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        vault.save(bob)
        val conversationId = UserCodes.conversationId(alice.id, bob.id)
        val messageId = java.util.UUID.randomUUID().toString()
        val eventId = java.util.UUID.randomUUID().toString()
        val ciphertext = PeerMessageCrypto.encrypt(alice, bob.id, bob.publicKey, messageId, "Sonradan gelen mesaj")
        var serverOnline = false
        val readAcks = mutableListOf<List<String>>()
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                if (!serverOnline) throw RelayException(503, "Temporary offline")
                return when (action) {
                    "register" -> JSONObject().put("code", bob.code).put("nonce", bob.nonce)
                    "sync" -> JSONObject()
                        .put("contacts", org.json.JSONArray().put(JSONObject()
                            .put("id", alice.id).put("code", alice.code)
                            .put("nonce", alice.nonce).put("publicKey", alice.publicKey)
                            .put("codeAtAdd", alice.code).put("mutual", true)))
                        .put("events", org.json.JSONArray().put(JSONObject()
                            .put("id", eventId).put("message", JSONObject()
                                .put("id", messageId).put("senderId", alice.id)
                                .put("recipientId", bob.id).put("revision", 1)
                                .put("status", "SENT").put("deleted", false)
                                .put("createdAt", System.currentTimeMillis())
                                .put("content", ciphertext))))
                    "ack" -> JSONObject().put("acknowledged", true).also {
                        val readIds = data.optJSONArray("readIds") ?: org.json.JSONArray()
                        readAcks.add((0 until readIds.length()).map { readIds.getString(it) })
                    }
                    else -> error("Unexpected operation: $action")
                }
            }
        }
        val repo = repository(relay = relay)
        assertTrue(runCatching { repo.synchronize() }.exceptionOrNull() is RelayException)
        assertNull(db.messageDao().getMessage(messageId))

        // User B unlocks the private area once online, after A sent the message.
        serverOnline = true
        repo.synchronize()
        assertEquals(1, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        assertEquals("Sonradan gelen mesaj", repo.getMessages(conversationId).first().single().content)
        assertTrue(readAcks.all { it.isEmpty() })

        // A duplicate event after reconnect must not inflate the unread count.
        repo.synchronize()
        assertEquals(1, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        repo.markConversationOpened(conversationId)
        assertEquals(0, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        repo.synchronize(conversationId)
        assertEquals(0, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        assertTrue("Read is sent only when B opens the conversation", readAcks.any { messageId in it })
    }

    @Test fun `local expiration never destroys unsent messages and offline delete cancels queue`() = runBlocking {
        val repo = repository()
        val own = repo.ensureIdentity()
        val pendingId = java.util.UUID.randomUUID().toString()
        db.messageDao().insertMessage(MessageEntity(
            pendingId, "conversation", own.id, "Ben", "lanu2:fakeciphertext", status = "PENDING"
        ))
        repo.hideMessageLocally(pendingId)
        assertNotNull("An unsent message must stay queued even if the local timer expires", db.messageDao().getMessage(pendingId))
        db.messageDao().updateMessageStatus(pendingId, "WAITING")
        repo.hideMessageLocally(pendingId)
        assertNotNull("Mutual-contact waiting messages must remain queued", db.messageDao().getMessage(pendingId))
        db.messageDao().updateMessageStatus(pendingId, "SENT")
        repo.hideMessageLocally(pendingId)
        assertNull("Only a relay-accepted message may expire locally", db.messageDao().getMessage(pendingId))

        val cancelledId = java.util.UUID.randomUUID().toString()
        db.messageDao().insertMessage(MessageEntity(
            cancelledId, "conversation", own.id, "Ben", "lanu2:fakeciphertext", status = "PENDING"
        ))
        repo.deleteMessage(cancelledId)
        assertNull("An offline queued message can be cancelled without calling the relay", db.messageDao().getMessage(cancelledId))
    }

    @Test fun `failed unread increment rolls back received message and replay revision`() = runBlocking {
        val alice = UserCodes.create()
        val bob = UserCodes.create()
        vault.save(bob)
        val conversationId = UserCodes.conversationId(alice.id, bob.id)
        val messageId = java.util.UUID.randomUUID().toString()
        val eventId = java.util.UUID.randomUUID().toString()
        val ciphertext = PeerMessageCrypto.encrypt(alice, bob.id, bob.publicKey, messageId, "Kesintiden sonra da görünmeli")
        var ackCount = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = when (action) {
                "register" -> JSONObject().put("code", bob.code).put("nonce", bob.nonce)
                "sync" -> JSONObject()
                    .put("contacts", org.json.JSONArray().put(JSONObject()
                        .put("id", alice.id).put("code", alice.code)
                        .put("nonce", alice.nonce).put("publicKey", alice.publicKey)
                        .put("codeAtAdd", alice.code).put("mutual", true)))
                    .put("events", org.json.JSONArray().put(JSONObject()
                        .put("id", eventId)
                        .put("message", JSONObject()
                            .put("id", messageId).put("senderId", alice.id)
                            .put("recipientId", bob.id).put("revision", 1)
                            .put("status", "SENT").put("createdAt", System.currentTimeMillis())
                            .put("content", ciphertext))))
                "ack" -> JSONObject().put("acknowledged", true).also { ackCount++ }
                else -> error("Unexpected request: $action")
            }
        }
        val crashingDao = object : ConversationDao by db.conversationDao() {
            override suspend fun incrementUnreadCount(id: String) {
                throw IllegalStateException("Simulated crash between message storage and unread increment")
            }
        }
        try {
            repository(relay = relay, conversationDao = crashingDao).synchronize()
            fail("Unread-count failure must stop the transaction")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Simulated crash"))
        }
        assertNull("Message insert must roll back", db.messageDao().getMessage(messageId))
        assertNull("Replay revision must roll back", db.settingsDao().getSettingValue("lanu_message_revision_$messageId"))
        assertNull("Conversation creation must roll back", db.conversationDao().getConversationById(conversationId))
        assertEquals("No receipt may be acknowledged before committing", 0, ackCount)

        repository(relay = relay).synchronize()
        assertNotNull(db.messageDao().getMessage(messageId))
        assertEquals(1, db.conversationDao().getConversationById(conversationId)?.unreadCount)
        assertEquals("1", db.settingsDao().getSettingValue("lanu_message_revision_$messageId"))
        assertEquals(1, ackCount)
        repository(relay = relay).synchronize()
        assertEquals("Repeated event must not increase unread", 1,
            db.conversationDao().getConversationById(conversationId)?.unreadCount)
    }

    @Test fun `deleting conversation removes local ciphertext queued messages and hides replay`() = runBlocking {
        val repo = repository()
        val conv = "conversation-to-erase"
        db.conversationDao().insertConversation(ConversationEntity(
            conv, "LANU-PEER", "Friend", lastMessageText = "secret", unreadCount = 4))
        val messages = listOf(
            MessageEntity("queued-to-erase", conv, "my-old-id", "Me", "lanu2:pending", status = "PENDING"),
            MessageEntity("delivered-to-erase", conv, "peer-id", "Friend", "lanu2:received", status = "READ")
        )
        messages.forEach { db.messageDao().insertMessage(it) }
        repo.deleteConversation(conv)
        assertNull(db.conversationDao().getConversationById(conv))
        messages.forEach { msg ->
            assertNull("Deleting a conversation must also remove its ciphertext", db.messageDao().getMessage(msg.id))
            assertEquals("true", db.settingsDao().getSettingValue("lanu_hidden_${msg.id}"))
        }
        assertTrue(db.messageDao().getPendingMessages().isEmpty())
    }

    @Test fun `failed deletion rolls back tombstones and preserves messages and conversation`() = runBlocking {
        val conv = "conversation-failed-delete"
        db.conversationDao().insertConversation(ConversationEntity(conv, "LANU-PEER", "Friend"))
        db.messageDao().insertMessage(MessageEntity(
            "still-on-disk", conv, "other-id", "Friend", "lanu2:stored", status = "READ"))
        val broken = object : MessageDao by db.messageDao() {
            override suspend fun deleteAllMessagesForConversation(convId: String) {
                throw IllegalStateException("Simulated deletion failure")
            }
        }
        try {
            repository(messageDao = broken).deleteConversation(conv)
            fail("Conversation deletion must fail atomically")
        } catch (_: IllegalStateException) { }
        assertNotNull(db.messageDao().getMessage("still-on-disk"))
        assertNotNull(db.conversationDao().getConversationById(conv))
        assertNull("Rollback must not hide undeleted messages",
            db.settingsDao().getSettingValue("lanu_hidden_still-on-disk"))
    }

    @Test fun `clear conversation erases messages but keeps an empty unread-free thread`() = runBlocking {
        val conv = "conversation-clear"
        db.conversationDao().insertConversation(ConversationEntity(
            conv, "LANU-PEER", "Friend", lastMessageText = "private", unreadCount = 3))
        db.messageDao().insertMessage(MessageEntity(
            "clear-me", conv, "other-id", "Friend", "lanu2:confidential", status = "READ"))
        repository().clearConversationMessages(conv)
        assertNull(db.messageDao().getMessage("clear-me"))
        assertEquals("true", db.settingsDao().getSettingValue("lanu_hidden_clear-me"))
        val thread = db.conversationDao().getConversationById(conv)
        assertNotNull(thread)
        assertEquals("", thread?.lastMessageText)
        assertEquals(0, thread?.unreadCount)
    }
}
