package com.example

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Android runtime proof of real SQLCipher, Keystore wrap and typed parity on
 * ONLY synthetic Room v4 fixtures. Never migrates or opens the live database.
 * Executed on API 34 in debug and R8 release instrumentation CI.
 */
@RunWith(AndroidJUnit4::class)
class SqlcipherShadowRoomAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(32768)
            while (true) {
                val n = input.read(bytes)
                if (n == -1) break
                md.update(bytes, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun typeValue(cursor: Cursor, index: Int): DatabaseShadowParity.SqlValue =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> DatabaseShadowParity.SqlValue.Null
            Cursor.FIELD_TYPE_INTEGER -> DatabaseShadowParity.SqlValue.Integer(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> DatabaseShadowParity.SqlValue.Real(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> DatabaseShadowParity.SqlValue.Text(cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> DatabaseShadowParity.SqlValue.Blob(cursor.getBlob(index))
            else -> error("Unsupported SQLite column type")
        }

    private fun values(db: SupportSQLiteDatabase, table: String): Pair<List<String>, List<List<DatabaseShadowParity.SqlValue>>> {
        require(table in DatabaseShadowParity.TABLES) { "Unknown fixture table" }
        return db.query("SELECT * FROM `$table`").use { cursor ->
            val cols = cursor.columnNames.toList()
            val rows = ArrayList<List<DatabaseShadowParity.SqlValue>>()
            while (cursor.moveToNext()) {
                rows += cols.indices.map { typeValue(cursor, it) }
            }
            cols to rows
        }
    }

    private fun raw(value: DatabaseShadowParity.SqlValue): Any? = when (value) {
        DatabaseShadowParity.SqlValue.Null -> null
        is DatabaseShadowParity.SqlValue.Integer -> value.value
        is DatabaseShadowParity.SqlValue.Real -> value.value
        is DatabaseShadowParity.SqlValue.Text -> value.value
        is DatabaseShadowParity.SqlValue.Blob -> value.value
    }

    @Test fun realEncryptedSevenTableShadowRoundtripAndFailurePaths() = runBlocking {
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        val sourceName = "lanu_sqlcipher_plain_fixture_$id.db"
        val shadowName = "lanu_sqlcipher_shadow_test_$id.db"
        val sourceFile = context.getDatabasePath(sourceName)
        val shadowFile = context.getDatabasePath(shadowName)
        assertFalse("Test fixture must start clean", sourceFile.exists())
        assertFalse("Test fixture must start clean", shadowFile.exists())
        var original: AppDatabase? = null
        var encrypted: AppDatabase? = null
        var reopened: AppDatabase? = null
        try {
            val key = IsolatedSqlcipherShadowKeyVault.unlockOrCreate(
                context, id, shadowExists = false
            )
            assertEquals(32, key.size)
            assertArrayEquals(key, IsolatedSqlcipherShadowKeyVault.unlockOrCreate(
                context, id, shadowExists = false
            ))
            val source = Room.databaseBuilder(context, AppDatabase::class.java, sourceName)
                .openHelperFactory(PreserveCorruptRoomFactory()).build()
            original = source
            val identity = UserCodes.create()
            val encryptedMessage = PeerMessageCrypto.encrypt(
                identity, identity.id, identity.publicKey,
                "m-1", "private fixture ciphertext"
            )
            source.settingsDao().insertSetting(SettingsEntity("pin_hash", "synthetic-pin-verifier"))
            source.settingsDao().insertSetting(SettingsEntity("lanu_peer_key", identity.publicKey))
            source.userProfileDao().insertProfile(
                UserProfileEntity(identity.code, "Fixture owner", registeredAt = 1)
            )
            source.contactDao().insertContact(
                ContactEntity("LANU-FRIEND", "Fixture", isBlocked = true)
            )
            source.conversationDao().insertConversation(
                ConversationEntity("conversation-1", "LANU-FRIEND", "Fixture", unreadCount = 3)
            )
            source.messageDao().insertMessage(
                MessageEntity("m-1", "conversation-1", identity.id,
                    "Fixture owner", encryptedMessage, timestamp = 8, status = "PENDING")
            )
            source.calculationHistoryDao().insertHistory(
                CalculationHistoryEntity(id = 99, expression = "7*6", result = "42", timestamp = 9)
            )
            source.callLogDao().insertCallLog(
                CallLogEntity(id = 10, partnerName = "Fixture", partnerPhone = "LANU-FRIEND",
                    isVideo = false, durationSeconds = 10, isOutgoing = false, timestamp = 11)
            )
            val snapshot = DatabaseShadowParity.TABLES.sorted().map { table ->
                val (columns, rows) = values(source.openHelper.writableDatabase, table)
                Triple(table, columns, rows)
            }
            assertEquals(7, snapshot.size)
            assertTrue(snapshot.all { it.third.isNotEmpty() })
            val dest = IsolatedSqlcipherRoomFactory.open(context, shadowFile, key)
            encrypted = dest
            dest.withTransaction {
                val writable = dest.openHelper.writableDatabase
                for ((table, columns, rows) in snapshot) {
                    val targetColumns = values(writable, table).first
                    check(columns == targetColumns) { "Room shadow schema differs" }
                    val insert = "INSERT INTO `$table` (" +
                        columns.joinToString(",") { "`$it`" } + ") VALUES (" +
                        columns.joinToString(",") { "?" } + ")"
                    for (row in rows) {
                        writable.execSQL(insert, row.map(::raw).toTypedArray())
                    }
                }
                val sourceProofs = snapshot.map { (table, _, rows) ->
                    DatabaseShadowParity.fingerprintTyped(table, rows)
                }
                val targetProofs = snapshot.map { (table, _, _) ->
                    val (_, rows) = values(writable, table)
                    DatabaseShadowParity.fingerprintTyped(table, rows)
                }
                check(DatabaseShadowParity.equivalent(sourceProofs, targetProofs)) {
                    "SQLCipher typed parity failed: rollback test shadow transaction"
                }
            }
            assertEquals("synthetic-pin-verifier",
                dest.settingsDao().getSettingValue("pin_hash"))
            assertEquals(encryptedMessage, dest.messageDao().getMessage("m-1")?.content)
            source.close(); original = null
            dest.close(); encrypted = null
            val originalBefore = hash(sourceFile)
            assertTrue(sourceFile.readBytes().take(16).toByteArray()
                .contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            assertFalse("SQLCipher file must not expose SQLite plaintext header",
                shadowFile.inputStream().use {
                    val first = ByteArray(16)
                    it.read(first)
                    first.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
                })
            assertFalse("Encrypted SQLCipher file must not be an empty placeholder",
                shadowFile.length() < 1024L)
            val wrong = IsolatedSqlcipherRoomFactory.open(context, shadowFile,
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
            try {
                try {
                    wrong.settingsDao().getSettingValue("pin_hash")
                    fail("Wrong SQLCipher key must NEVER open a shadow database")
                } catch (_: Exception) {
                    // Wrong key failure expected, original file must survive.
                }
            } finally { wrong.close() }
            val recovered = IsolatedSqlcipherShadowKeyVault.unlockOrCreate(
                context, id, shadowExists = true
            )
            assertArrayEquals(key, recovered)
            val checked = IsolatedSqlcipherRoomFactory.open(context, shadowFile, recovered)
            reopened = checked
            assertEquals("synthetic-pin-verifier",
                checked.settingsDao().getSettingValue("pin_hash"))
            assertEquals(encryptedMessage, checked.messageDao().getMessage("m-1")?.content)
            val afterProofs = snapshot.map { (table, _, _) ->
                DatabaseShadowParity.fingerprintTyped(
                    table, values(checked.openHelper.writableDatabase, table).second)
            }
            val beforeProofs = snapshot.map { (table, _, rows) ->
                DatabaseShadowParity.fingerprintTyped(table, rows)
            }
            assertTrue("All seven typed datasets must survive encrypted close/reopen",
                DatabaseShadowParity.equivalent(beforeProofs, afterProofs))
            assertEquals("Isolated source must never be rewritten", originalBefore, hash(sourceFile))
            // Missing wrapped key cannot regenerate an existing encrypted database.
            val wrap = File(context.noBackupFilesDir, "lanu_shadow_test_$id.wrapped")
            val storedWrap = wrap.readBytes()
            try {
                wrap.writeBytes(storedWrap.copyOf().also { it[it.lastIndex] =
                    (it.last().toInt() xor 1).toByte() })
                try {
                    IsolatedSqlcipherShadowKeyVault.unlockOrCreate(context, id, true)
                    fail("Tampered authentication tag must fail closed")
                } catch (_: IllegalStateException) { }
                wrap.delete()
                try {
                    IsolatedSqlcipherShadowKeyVault.unlockOrCreate(context, id, true)
                    fail("Missing wrapped key cannot create a replacement key")
                } catch (_: IllegalStateException) { }
            } finally {
                wrap.writeBytes(storedWrap)
            }
        } finally {
            reopened?.close()
            encrypted?.close()
            original?.close()
            // Synthetic fixtures only. Never touch 'lanu_calculator_db' or its companions.
            context.deleteDatabase(shadowName)
            context.deleteDatabase(sourceName)
            IsolatedSqlcipherShadowKeyVault.discardIsolatedFixture(context, id)
        }
    }
}
