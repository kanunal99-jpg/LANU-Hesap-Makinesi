package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import com.example.core.security.PinCredentialCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullDataBackupTest {
    private val password = "Portable-full-backup-2026"
    private class Store(var identity: MessagingIdentity? = null) : MessagingIdentityStore {
        var rejectWrites = false
        override fun read() = identity
        override fun save(identity: MessagingIdentity) { check(!rejectWrites); this.identity = identity }
    }
    private val offline = object : MessagingRelay {
        override val configured = false
        override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = throw RelayException(0, "offline")
    }
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private fun repo(db: AppDatabase, store: Store) = CommunicationRepository(db.contactDao(), db.conversationDao(), db.messageDao(),
        db.settingsDao(), db.userProfileDao(), db.callLogDao(), store, offline, db)

    private suspend fun populate(db: AppDatabase, store: Store): Pair<String, String> {
        val repository = repo(db, store)
        val own = repository.ensureIdentity()
        val peer = UserCodes.create()
        val conversation = UserCodes.conversationId(own.id, peer.id)
        val peerJson = JSONObject().put("id", peer.id).put("code", peer.code).put("nonce", peer.nonce).put("publicKey", peer.publicKey).toString()
        db.settingsDao().insertSetting(SettingsEntity("lanu_peer_conv_$conversation", peerJson))
        db.contactDao().insertContact(ContactEntity(peer.code, "Private friend", isBlocked = true))
        db.conversationDao().insertConversation(ConversationEntity(conversation, peer.code, "Private friend", unreadCount = 3))
        db.settingsDao().insertSetting(SettingsEntity("pin_hash", PinCredentialCodec.create("123456")))
        db.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "false"))
        db.calculationHistoryDao().insertHistory(CalculationHistoryEntity(expression = "12+13", result = "25", timestamp = 100L))
        db.callLogDao().insertCallLog(CallLogEntity(partnerName = "Friend", partnerPhone = peer.code, isVideo = false, durationSeconds = 12, isOutgoing = true, timestamp = 200L))
        repository.saveDraft(conversation, "Unsent private draft")
        val message = repository.sendMessage(conversation, "Portable secret message")
        repository.saveDraft(conversation, "New unsent private draft")
        return conversation to message
    }

    @Test fun `full encrypted archive restores seven tables identity draft and pending ciphertext without overwriting current PIN`() = runBlocking {
        val source = db(); val target = db()
        try {
            val original = Store()
            val (conversation, message) = populate(source, original)
            val sourceRepo = repo(source, original)
            val first = sourceRepo.exportFullBackup(password)
            val second = sourceRepo.exportFullBackup(password)
            assertFalse(first.contentEquals(second))
            assertFalse(String(first, Charsets.ISO_8859_1).contains("Portable secret message"))
            val destination = Store()
            val targetRepo = repo(target, destination)
            targetRepo.ensureIdentity()
            val currentPin = PinCredentialCodec.create("999999")
            target.settingsDao().insertSetting(SettingsEntity("pin_hash", currentPin))
            target.calculationHistoryDao().insertHistory(CalculationHistoryEntity(expression = "1+2", result = "3", timestamp = 300L))
            val result = targetRepo.restoreFullBackup(first, password)
            assertEquals(1, result.messages)
            assertEquals(original.identity, destination.identity)
            assertEquals(source.messageDao().getMessage(message), target.messageDao().getMessage(message))
            assertEquals("Portable secret message", targetRepo.getMessages(conversation).first().single().content)
            assertEquals("New unsent private draft", targetRepo.loadDraft(conversation))
            assertEquals(currentPin, target.settingsDao().getSettingValue("pin_hash"))
            assertEquals("An existing PIN remains enabled even when its old preference was implicit", "true", target.settingsDao().getSettingValue("entry_pin_enabled"))
            assertEquals(2, target.calculationHistoryDao().getAllHistory().first().size)
            assertEquals(1, target.callLogDao().getAllCallLogs().first().size)
            assertTrue(target.contactDao().getAllContacts().first().single().isBlocked)
            targetRepo.restoreFullBackup(first, password)
            assertEquals(1, target.messageDao().countMessages())
            assertEquals(2, target.calculationHistoryDao().getAllHistory().first().size)
            assertEquals(1, target.callLogDao().getAllCallLogs().first().size)
        } finally { source.close(); target.close() }
    }

    @Test fun `wrong password tampering and attacker chosen KDF cost never modify existing data`() = runBlocking {
        val source = db(); val target = db()
        try {
            val original = Store(); populate(source, original)
            val backup = repo(source, original).exportFullBackup(password)
            val owner = Store(); val targetRepo = repo(target, owner); val before = targetRepo.ensureIdentity()
            val tampered = backup.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val expensive = backup.clone().also { it[8] = 0x7f; it[9] = 0xff.toByte(); it[10] = 0xff.toByte(); it[11] = 0xff.toByte() }
            for ((file, pass) in listOf(backup to "wrong-but-long-password", tampered to password, expensive to password)) {
                try { targetRepo.restoreFullBackup(file, pass); fail("Must reject unverified archive") } catch (_: IllegalArgumentException) { }
                assertEquals(before, owner.identity)
                assertEquals(0, target.messageDao().countMessages())
            }
        } finally { source.close(); target.close() }
    }

    @Test fun `database failure during restore rolls back every row and original identity`() = runBlocking {
        val source = db(); val target = db()
        try {
            val original = Store(); populate(source, original)
            val backup = repo(source, original).exportFullBackup(password)
            val owner = Store(); val targetRepo = repo(target, owner); val before = targetRepo.ensureIdentity()
            target.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_restore BEFORE INSERT ON contacts BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            try { targetRepo.restoreFullBackup(backup, password); fail("Must roll back") } catch (_: android.database.SQLException) { }
            assertEquals(before, owner.identity)
            assertEquals(before.code, target.userProfileDao().getUserProfile()!!.phoneNumber)
            assertTrue(target.calculationHistoryDao().getAllHistory().first().isEmpty())
            assertNull(target.settingsDao().getSettingValue("pin_hash"))
            assertEquals(0, target.messageDao().countMessages())
        } finally { source.close(); target.close() }
    }

    @Test fun `different account archive cannot claim current conversations`() = runBlocking {
        val source = db(); val target = db()
        try {
            val original = Store(); populate(source, original)
            val backup = repo(source, original).exportFullBackup(password)
            val owner = Store(); val (_, kept) = populate(target, owner)
            val targetRepo = repo(target, owner); val before = owner.identity
            try { targetRepo.restoreFullBackup(backup, password); fail("Must preserve other account") } catch (_: IllegalArgumentException) { }
            assertEquals(before, owner.identity)
            assertNotNull(target.messageDao().getMessage(kept))
            assertEquals(1, target.messageDao().countMessages())
        } finally { source.close(); target.close() }
    }

    @Test fun `silent SQLite ignore or tampered import rolls back entire backup restore`() = runBlocking {
        val source = db()
        try {
            val original = Store()
            populate(source, original)
            val backup = repo(source, original).exportFullBackup(password)
            // RAISE(IGNORE) does not throw. Old code could report successful
            // restore with a missing encrypted message or call-log record.
            for ((name, sql) in listOf(
                "drop_message" to
                    "CREATE TRIGGER drop_message BEFORE INSERT ON messages BEGIN SELECT RAISE(IGNORE); END",
                "mutate_message" to
                    "CREATE TRIGGER mutate_message AFTER INSERT ON messages BEGIN UPDATE messages SET content = 'wrong-ciphertext' WHERE id = NEW.id; END",
                "drop_call_log" to
                    "CREATE TRIGGER drop_call_log BEFORE INSERT ON call_logs BEGIN SELECT RAISE(IGNORE); END",
                "drop_contact" to
                    "CREATE TRIGGER drop_contact BEFORE INSERT ON contacts BEGIN SELECT RAISE(IGNORE); END"
            )) {
                val target = db()
                try {
                    val store = Store()
                    val destination = repo(target, store)
                    val before = destination.ensureIdentity()
                    target.settingsDao().insertSetting(SettingsEntity("pin_hash", "KEEP_ORIGINAL_PIN"))
                    target.openHelper.writableDatabase.execSQL(sql)
                    try {
                        destination.restoreFullBackup(backup, password)
                        fail("Silent data loss must roll back: $name")
                    } catch (_: IllegalArgumentException) {
                        // A full transaction rejection is expected.
                    }
                    assertEquals(before, store.identity)
                    assertEquals(before.code, target.userProfileDao().getUserProfile()?.phoneNumber)
                    assertEquals("KEEP_ORIGINAL_PIN", target.settingsDao().getSettingValue("pin_hash"))
                    assertNull(target.settingsDao().getSettingValue("lanu_full_restore_identity"))
                    assertEquals(0, target.messageDao().countMessages())
                    assertEquals(0, target.callLogDao().countCallLogs())
                    assertTrue(target.contactDao().getAllContacts().first().isEmpty())
                    assertTrue(target.conversationDao().getAllConversations().first().isEmpty())
                    assertTrue(target.calculationHistoryDao().getAllHistory().first().isEmpty())
                } finally { target.close() }
            }
        } finally { source.close() }
    }

    @Test fun `read failure and broken encrypted identity store cannot create partial restore`() = runBlocking {
        val source = db(); val target = db()
        try {
            val original = Store(); populate(source, original)
            val backup = repo(source, original).exportFullBackup(password)
            val owner = Store(); val targetRepo = repo(target, owner); val before = targetRepo.ensureIdentity()
            owner.rejectWrites = true
            try { targetRepo.restoreFullBackup(backup, password); fail("Must not import without durable identity") } catch (_: IllegalStateException) { }
            assertEquals(before, owner.identity)
            assertEquals(0, target.messageDao().countMessages())
            val stalled = object : java.io.InputStream() {
                override fun read(): Int = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
            }
            try { FullDataBackup.readFile(stalled); fail("Must bound stalled providers") } catch (_: IllegalArgumentException) { }
        } finally { source.close(); target.close() }
    }
}
