package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.runBlocking
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
    private fun repository(store: MessagingIdentityStore = vault, relay: MessagingRelay = offline) = CommunicationRepository(
        db.contactDao(), db.conversationDao(), db.messageDao(), db.settingsDao(), db.userProfileDao(), db.callLogDao(), store, relay)
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
}
