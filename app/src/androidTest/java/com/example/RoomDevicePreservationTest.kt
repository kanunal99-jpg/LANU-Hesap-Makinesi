package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.AppDatabase
import com.example.core.database.CalculationHistoryEntity
import com.example.core.database.CallLogEntity
import com.example.core.database.ContactEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.MessageEntity
import com.example.core.database.SettingsEntity
import com.example.core.database.UserProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Device/emulator acceptance gate for CURRENT Room v4 storage, not a substitute for
 * historical v1-v3 migration fixtures, full-disk SQLCipher, or OTA on a physical phone.
 * Tests only isolated databases and never touches the user's lanu_calculator_db.
 */
@RunWith(AndroidJUnit4::class)
class RoomDevicePreservationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allSevenTablesSurviveRealSQLiteCloseAndReopen() = runBlocking {
        val name = "lanu_instrumented_v4_reopen.db"
        context.deleteDatabase(name)
        try {
            val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                original.settingsDao().insertSetting(SettingsEntity("pin_hash", "keep-pin"))
                original.settingsDao().insertSetting(SettingsEntity("lanu_peer_key", "keep-identity"))
                original.userProfileDao().insertProfile(UserProfileEntity("LANU-OWNER", "Owner", registeredAt = 1L))
                original.contactDao().insertContact(ContactEntity("LANU-FRIEND", "Friend", isBlocked = true))
                original.conversationDao().insertConversation(
                    ConversationEntity(
                        id = "thread", partnerPhone = "LANU-FRIEND", partnerName = "Friend",
                        unreadCount = 2, lastMessageTime = 2L
                    )
                )
                original.messageDao().insertMessage(
                    MessageEntity(
                        id = "queued", conversationId = "thread", senderPhone = "LANU-OWNER",
                        senderName = "Owner", content = "lanu2:ciphertext", timestamp = 3L,
                        status = "PENDING"
                    )
                )
                original.calculationHistoryDao().insertHistory(
                    CalculationHistoryEntity(id = 41L, expression = "1+2", result = "3", timestamp = 4L)
                )
                original.callLogDao().insertCallLog(
                    CallLogEntity(
                        id = 43L, partnerName = "Friend", partnerPhone = "LANU-FRIEND",
                        isVideo = false, durationSeconds = 5, isOutgoing = true, timestamp = 6L
                    )
                )
            } finally {
                original.close()
            }

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals("keep-pin", reopened.settingsDao().getSettingValue("pin_hash"))
                assertEquals("keep-identity", reopened.settingsDao().getSettingValue("lanu_peer_key"))
                assertEquals("LANU-OWNER", reopened.userProfileDao().getUserProfile()?.phoneNumber)
                assertTrue(reopened.contactDao().getContactByPhone("LANU-FRIEND")!!.isBlocked)
                assertEquals(2, reopened.conversationDao().getConversationById("thread")?.unreadCount)
                val queued = reopened.messageDao().getPendingMessages().single()
                assertEquals("queued", queued.id)
                assertEquals("lanu2:ciphertext", queued.content)
                assertEquals("PENDING", queued.status)
                assertEquals(41L, reopened.calculationHistoryDao().getAllHistory().first().single().id)
                assertEquals(43L, reopened.callLogDao().getAllCallLogs().first().single().id)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun unknownHistoricalSchemaFailsClosedButKeepsOldRows() = runBlocking {
        val name = "lanu_instrumented_unknown_v3.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            val unknown = SQLiteDatabase.openOrCreateDatabase(path, null)
            try {
                unknown.execSQL("CREATE TABLE legacy_private_data (id TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                unknown.execSQL("INSERT INTO legacy_private_data VALUES ('message', 'DO_NOT_DELETE')")
                unknown.version = 3
            } finally {
                unknown.close()
            }
            val current = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                try {
                    current.settingsDao().getSettingValue("pin_hash")
                    fail("Unknown legacy v3 database must NOT be silently recreated")
                } catch (_: IllegalStateException) {
                    // Safe rejection is expected until authentic v3 schemas are available.
                }
            } finally {
                current.close()
            }

            val after = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                after.rawQuery("SELECT value FROM legacy_private_data WHERE id='message'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("DO_NOT_DELETE", it.getString(0))
                }
            } finally {
                after.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun crashedTransactionDoesNotLeaveOrphanedMessages() = runBlocking {
        val name = "lanu_instrumented_atomicity.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            try {
                database.withTransaction {
                    database.conversationDao().insertConversation(
                        ConversationEntity("thread", "LANU-FRIEND", "Friend")
                    )
                    database.messageDao().insertMessage(
                        MessageEntity(
                            "orphan", "thread", "LANU-OWNER", "Owner",
                            "lanu2:ciphertext", status = "WAITING"
                        )
                    )
                    throw IOException("Simulated interruption before commit")
                }
                fail("Expected the injected transaction failure")
            } catch (_: IOException) {
                // Entire transaction must be rolled back.
            }
            assertNull(database.conversationDao().getConversationById("thread"))
            assertNull(database.messageDao().getMessage("orphan"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun androidBackupIsDisabledForInstalledApp() {
        assertEquals(
            "LANU must not allow Android's normal automatic app backup",
            0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }
}
