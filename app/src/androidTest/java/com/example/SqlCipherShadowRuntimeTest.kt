package com.example

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Real Zetetic SQLCipher+Room tests on an isolated Android runtime.
 * Never opens or writes production lanu_calculator_db and never migrates it.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherShadowRuntimeTest {
    private val context: Context get() =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun isPlaintextDatabase(name: String): Boolean {
        val header = ByteArray(16)
        val path = context.getDatabasePath(name)
        FileInputStream(path).use { assertEquals(16, it.read(header)) }
        return header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun allSevenRoomTablesPersistInEncryptedFileAndWrongKeyCannotEraseThem() = runBlocking {
        val name = "lanu_ci_cipher_shadow_v4_runtime.db"
        assertTrue(SqlCipherShadowDatabase.isIsolatedName(name))
        assertFalse(SqlCipherShadowDatabase.isIsolatedName("lanu_calculator_db"))
        assertFalse(SqlCipherShadowDatabase.isIsolatedName("../lanu_calculator_db"))
        context.deleteDatabase(name) // Disposable, strictly namespaced CI fixture.
        SqlCipherShadowKeyVault.deleteIsolatedFixture(context, name)
        val key = SqlCipherShadowKeyVault.create(context, name)
        val digest = key.copyOf()
        var database: AppDatabase? = null
        try {
            database = SqlCipherShadowDatabase.open(context, name, key)
            val db = database
            db.settingsDao().insertSetting(SettingsEntity("pin_hash", "P0_PRIVATE_VERIFIER_ABC"))
            db.settingsDao().insertSetting(SettingsEntity("lanu_peer_proof", "SECRET_PEER"))
            db.userProfileDao().insertProfile(UserProfileEntity("LANU-OWNER", "Private Owner", registeredAt = 1L))
            db.contactDao().insertContact(ContactEntity("LANU-FRIEND", "Secret Friend", isBlocked = true))
            db.conversationDao().insertConversation(ConversationEntity(
                "thread", "LANU-FRIEND", "Secret Friend", unreadCount = 3, lastMessageTime = 2L))
            db.messageDao().insertMessage(MessageEntity(
                "pending", "thread", "LANU-OWNER", "Private Owner",
                "lanu2:DO_NOT_LEAK_CIPHERTEXT", timestamp = 3L, status = "PENDING"))
            db.calculationHistoryDao().insertHistory(CalculationHistoryEntity(
                id = 9L, expression = "1+2", result = "3", timestamp = 4L))
            db.callLogDao().insertCallLog(CallLogEntity(
                id = 11L, partnerName = "Secret Friend", partnerPhone = "LANU-FRIEND",
                isVideo = true, durationSeconds = 60, isOutgoing = true, timestamp = 5L))
            db.close()
            database = null
            assertFalse("SQLCipher main file must not have the SQLite plaintext header",
                isPlaintextDatabase(name))
            val file = context.getDatabasePath(name)
            assertFalse("No private PIN verifier may be visible in ciphertext",
                file.readBytes().toString(Charsets.ISO_8859_1).contains("P0_PRIVATE_VERIFIER_ABC"))
            assertFalse("No private contact may be visible in ciphertext",
                file.readBytes().toString(Charsets.ISO_8859_1).contains("Secret Friend"))
            val reopenedKey = SqlCipherShadowKeyVault.read(context, name)
            try {
                assertArrayEquals(digest, reopenedKey)
                val reopened = SqlCipherShadowDatabase.open(context, name, reopenedKey)
                try {
                    assertEquals("P0_PRIVATE_VERIFIER_ABC", reopened.settingsDao().getSettingValue("pin_hash"))
                    assertEquals("SECRET_PEER", reopened.settingsDao().getSettingValue("lanu_peer_proof"))
                    assertEquals("Private Owner", reopened.userProfileDao().getUserProfile()?.displayName)
                    assertTrue(reopened.contactDao().getContactByPhone("LANU-FRIEND")!!.isBlocked)
                    assertEquals(3, reopened.conversationDao().getConversationById("thread")?.unreadCount)
                    val queued = reopened.messageDao().getPendingMessages(
                        now = System.currentTimeMillis(), limit = 100).single()
                    assertEquals("lanu2:DO_NOT_LEAK_CIPHERTEXT", queued.content)
                    assertEquals(9L, reopened.calculationHistoryDao().getAllHistory().first().single().id)
                    assertEquals(11L, reopened.callLogDao().getAllCallLogs().first().single().id)
                    reopened.openHelper.readableDatabase.query("PRAGMA cipher_integrity_check").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("ok", it.getString(0))
                    }
                } finally { reopened.close() }
            } finally { reopenedKey.fill(0) }
            val wrongKey = ByteArray(32) { i -> (i + 1).toByte() }
            val wrongDb = SqlCipherShadowDatabase.open(context, name, wrongKey)
            try {
                try {
                    wrongDb.settingsDao().getSettingValue("pin_hash")
                    fail("Wrong database key must not open a Room SQLCipher file")
                } catch (_: Exception) {
                    // The encrypted main file and its -wal/-shm companions are preserved.
                }
            } finally { wrongDb.close(); wrongKey.fill(0) }
            assertFalse(isPlaintextDatabase(name))
            val afterWrongKey = SqlCipherShadowDatabase.open(context, name, digest)
            try {
                assertEquals("P0_PRIVATE_VERIFIER_ABC", afterWrongKey.settingsDao().getSettingValue("pin_hash"))
            } finally { afterWrongKey.close() }
        } finally {
            database?.close()
            key.fill(0)
            digest.fill(0)
            context.deleteDatabase(name)
            SqlCipherShadowKeyVault.deleteIsolatedFixture(context, name)
        }
    }

    @Test
    fun tamperedOrMissingKeystoreEnvelopeNeverGeneratesReplacementKey() {
        val name = "lanu_ci_cipher_shadow_tamper.db"
        context.deleteDatabase(name)
        SqlCipherShadowKeyVault.deleteIsolatedFixture(context, name)
        val key = SqlCipherShadowKeyVault.create(context, name)
        try {
            val prefs = context.getSharedPreferences(
                "lanu_sqlcipher_ci_shadow_envelopes", Context.MODE_PRIVATE)
            val original = prefs.getString(name, null)!!
            assertEquals(32, SqlCipherShadowKeyVault.read(context, name).size)
            assertTrue(prefs.edit().putString(name, "v1:" +
                Base64.encodeToString(ByteArray(70) { 0x55.toByte() },
                    Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)).commit())
            try {
                SqlCipherShadowKeyVault.read(context, name)
                fail("Tampered GCM must not release key")
            } catch (_: SecurityException) { }
            try {
                SqlCipherShadowKeyVault.create(context, name)
                fail("Tampered envelope must not be silently replaced")
            } catch (_: IllegalStateException) { }
            assertTrue(prefs.edit().putString(name, original).commit())
            assertArrayEquals(key, SqlCipherShadowKeyVault.read(context, name))
            assertTrue(prefs.edit().remove(name).commit())
            try {
                SqlCipherShadowKeyVault.read(context, name)
                fail("Missing envelope must not generate new key")
            } catch (_: SecurityException) { }
        } finally {
            key.fill(0)
            context.deleteDatabase(name)
            SqlCipherShadowKeyVault.deleteIsolatedFixture(context, name)
        }
    }
}
