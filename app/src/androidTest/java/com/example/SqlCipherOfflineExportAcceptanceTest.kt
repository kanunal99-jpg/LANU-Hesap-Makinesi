package com.example

import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Zetetic documented sqlcipher_export from real plaintext Room v4 to a NEW,
 * isolated encrypted DB file. This test never reads or renames production DB.
 *
 * This is NOT an automatic migration or cutover. A live migration also needs
 * writer isolation, WAL synchronization, atomic rollback, independent backups
 * and physical tests before it may be turned on.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherOfflineExportAcceptanceTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32768)
            while (true) {
                val size = input.read(bytes)
                if (size == -1) break
                hash.update(bytes, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun typedValue(cursor: Cursor, index: Int): DatabaseShadowParity.SqlValue =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> DatabaseShadowParity.SqlValue.Null
            Cursor.FIELD_TYPE_INTEGER -> DatabaseShadowParity.SqlValue.Integer(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> DatabaseShadowParity.SqlValue.Real(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> DatabaseShadowParity.SqlValue.Text(cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> DatabaseShadowParity.SqlValue.Blob(cursor.getBlob(index))
            else -> throw IllegalArgumentException("Unknown SQLite cell")
        }

    private fun evidence(db: androidx.sqlite.db.SupportSQLiteDatabase): List<DatabaseShadowParity.TableProof> =
        DatabaseShadowParity.TABLES.sorted().map { table ->
            db.query("SELECT * FROM `$table`").use { cursor ->
                val rows = mutableListOf<List<DatabaseShadowParity.SqlValue>>()
                while (cursor.moveToNext()) {
                    rows += (0 until cursor.columnCount).map { index -> typedValue(cursor, index) }
                }
                DatabaseShadowParity.fingerprintTyped(table, rows)
            }
        }

    @Test fun exportedExistingRoomV4DataReopensEncryptedAndPreservesPlainSource() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val sourceName = "lanu_ci_export_plain_$suffix.db"
        val shadowName = "lanu_ci_cipher_shadow_export_$suffix.db"
        val plainFile = ctx.getDatabasePath(sourceName)
        val shadowFile = ctx.getDatabasePath(shadowName)
        assertTrue(SqlCipherShadowDatabase.isIsolatedName(shadowName))
        assertFalse("Never use the live DB", plainFile.name == "lanu_calculator_db")
        assertFalse(plainFile.exists())
        assertFalse(shadowFile.exists())

        var source: AppDatabase? = null
        var converted: AppDatabase? = null
        val randomKey = SqlCipherShadowKeyVault.create(ctx, shadowName)
        try {
            val db = Room.databaseBuilder(ctx, AppDatabase::class.java, sourceName)
                .openHelperFactory(PreserveCorruptRoomFactory()).build()
            source = db
            db.settingsDao().insertSetting(SettingsEntity("pin_hash", "PBKDF2-SYNTHETIC-RETAIN"))
            db.settingsDao().insertSetting(SettingsEntity("lanu_peer_proof", "KEEP"))
            db.userProfileDao().insertProfile(UserProfileEntity("LANU-OWNER", "Owner", registeredAt = 1L))
            db.contactDao().insertContact(ContactEntity("LANU-PEER", "Contact", isBlocked = true))
            db.conversationDao().insertConversation(
                ConversationEntity("thread", "LANU-PEER", "Contact", unreadCount = 3))
            db.messageDao().insertMessage(MessageEntity(
                "pending", "thread", "LANU-OWNER", "Owner", "lanu2:ciphertext_fixture",
                timestamp = 5L, status = "PENDING"))
            db.calculationHistoryDao().insertHistory(
                CalculationHistoryEntity(id = 71L, expression = "2+2", result = "4", timestamp = 6L))
            db.callLogDao().insertCallLog(
                CallLogEntity(id = 81L, partnerName = "Contact", partnerPhone = "LANU-PEER",
                    isVideo = false, durationSeconds = 7, isOutgoing = true, timestamp = 8L))

            val before = evidence(db.openHelper.writableDatabase)
            assertEquals(7, before.size)
            assertTrue(before.all { it.count > 0 })
            val version = db.openHelper.writableDatabase.version
            assertEquals("Room v4 only", 4, version)
            // Source is a disposable fixture and no writers remain after close.
            db.close(); source = null
            val beforeHash = digest(plainFile)
            assertTrue(plainFile.inputStream().use {
                val header = ByteArray(16)
                it.read(header) == 16 &&
                    header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            })

            // SQLCipher's Android ATTACH path may fail to create a missing
            // destination inode on some runtimes (SQLITE_CANTOPEN / code 14).
            // Claim only this unique, allowlisted fixture name, exclusively:
            // do not open or truncate an existing DB, even a test shadow.
            assertEquals(plainFile.canonicalFile.parentFile, shadowFile.canonicalFile.parentFile)
            assertTrue("An empty isolated export destination must be created once",
                shadowFile.createNewFile())
            assertEquals("A newly claimed export target must not contain old data",
                0L, shadowFile.length())

            System.loadLibrary("sqlcipher")
            val passphrase = Base64.encodeToString(
                randomKey, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            // The export connection MUST NEVER open a production file. The
            // source is intentionally plaintext, with a non-destructive error
            // handler so corrupt SQLite cannot be silently recreated/deleted.
            val connection = SQLiteDatabase.openDatabase(
                plainFile.absolutePath, null as SQLiteDatabase.CursorFactory?,
                SQLiteDatabase.OPEN_READWRITE)
            try {
                connection.rawQuery("PRAGMA integrity_check", emptyArray()).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ok", it.getString(0))
                }
                connection.execSQL(
                    "ATTACH DATABASE ? AS encrypted KEY ?",
                    arrayOf(shadowFile.absolutePath, passphrase))
                try {
                    connection.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use {
                        assertTrue("SQLCipher export must execute", it.moveToFirst())
                    }
                    // sqlcipher_export intentionally does not carry user_version.
                    connection.execSQL("PRAGMA encrypted.user_version = 4")
                } finally {
                    connection.execSQL("DETACH DATABASE encrypted")
                }
            } finally { connection.close() }

            assertEquals("Plaintext source must be immutable across offline export",
                beforeHash, digest(plainFile))
            assertFalse("Shadow must be a new physical encrypted file",
                shadowFile.inputStream().use {
                    val h = ByteArray(16)
                    it.read(h) == 16 &&
                        h.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
                })
            val encrypted = SqlCipherShadowDatabase.open(ctx, shadowName, randomKey)
            converted = encrypted
            assertEquals("PBKDF2-SYNTHETIC-RETAIN",
                encrypted.settingsDao().getSettingValue("pin_hash"))
            assertEquals("lanu2:ciphertext_fixture",
                encrypted.messageDao().getMessage("pending")?.content)
            assertEquals(71L, encrypted.calculationHistoryDao().getAllHistory().first().single().id)
            assertEquals(81L, encrypted.callLogDao().getAllCallLogs().first().single().id)
            assertTrue("Every SQLite type and row must match across real export",
                DatabaseShadowParity.equivalent(before, evidence(encrypted.openHelper.writableDatabase)))
            encrypted.openHelper.readableDatabase.query("PRAGMA cipher_integrity_check").use {
                assertFalse("SQLCipher page HMAC check must report no errors", it.moveToFirst())
            }
            assertEquals("Plaintext source remained immutable", beforeHash, digest(plainFile))
        } finally {
            converted?.close()
            source?.close()
            randomKey.fill(0)
            // Only disposable randomly named test fixtures.
            ctx.deleteDatabase(shadowName)
            ctx.deleteDatabase(sourceName)
            SqlCipherShadowKeyVault.deleteIsolatedFixture(ctx, shadowName)
        }
    }
}
