package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Safeguards against accidental fallbackToDestructiveMigration in future changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDataPreservationTest {
    @Test fun `PIN settings and history survive a normal database reopening`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lanu_room_reopen_test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            first.settingsDao().insertSetting(SettingsEntity("pin_hash", "legacy-migration-test-record"))
            first.settingsDao().insertSetting(SettingsEntity("lanu_peer_id_saved", "peer"))
            first.close()
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("legacy-migration-test-record", reopened.settingsDao().getSettingValue("pin_hash"))
            assertEquals("peer", reopened.settingsDao().getSettingValue("lanu_peer_id_saved"))
            reopened.close()
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `unknown old schema fails closed without deleting its original data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lanu_unknown_schema_test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            val old = SQLiteDatabase.openOrCreateDatabase(path, null)
            old.execSQL("CREATE TABLE legacy_private_data (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            old.execSQL("INSERT INTO legacy_private_data(key,value) VALUES ('message','KEEP_ME')")
            old.version = 3
            old.close()
            val current = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                try {
                    current.settingsDao().getSettingValue("pin_hash")
                    fail("An unknown schema must never be wiped and recreated silently")
                } catch (_: IllegalStateException) { }
            } finally { current.close() }
            val inspect = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cursor = inspect.rawQuery("SELECT value FROM legacy_private_data WHERE key='message'", null)
                cursor.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("KEEP_ME", it.getString(0))
                }
            } finally { inspect.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun `seven tables including queued messages survive database reopening`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lanu_complete_reopen_test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                first.settingsDao().insertSetting(SettingsEntity("pin_hash", "keep-pin-verifier"))
                first.settingsDao().insertSetting(SettingsEntity("lanu_message_revision_msg-1", "7"))
                first.userProfileDao().insertProfile(
                    com.example.core.database.UserProfileEntity("LANU-OWNER", "Me", registeredAt = 111L))
                first.contactDao().insertContact(
                    com.example.core.database.ContactEntity("LANU-PEER", "Friend", isBlocked = true))
                first.conversationDao().insertConversation(
                    com.example.core.database.ConversationEntity(
                        id = "conversation-1", partnerPhone = "LANU-PEER", partnerName = "Friend",
                        lastMessageText = "Şifreli mesaj", lastMessageTime = 222L, unreadCount = 2))
                first.messageDao().insertMessage(
                    com.example.core.database.MessageEntity(
                        id = "msg-1", conversationId = "conversation-1", senderPhone = "LANU-OWNER",
                        senderName = "Me", content = "lanu2:encrypted-record", timestamp = 333L,
                        status = "PENDING"))
                first.calculationHistoryDao().insertHistory(
                    com.example.core.database.CalculationHistoryEntity(
                        id = 44L, expression = "2+2", result = "4", timestamp = 444L))
                first.callLogDao().insertCallLog(
                    com.example.core.database.CallLogEntity(
                        id = 55L, partnerName = "Friend", partnerPhone = "LANU-PEER",
                        isVideo = false, durationSeconds = 18, isOutgoing = true, timestamp = 555L))
            } finally { first.close() }

            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals("keep-pin-verifier", reopened.settingsDao().getSettingValue("pin_hash"))
                assertEquals("7", reopened.settingsDao().getSettingValue("lanu_message_revision_msg-1"))
                assertEquals("LANU-OWNER", reopened.userProfileDao().getUserProfile()?.phoneNumber)
                assertEquals("Me", reopened.userProfileDao().getUserProfile()?.displayName)
                assertTrue(reopened.contactDao().getContactByPhone("LANU-PEER")!!.isBlocked)
                assertEquals(2, reopened.conversationDao().getConversationById("conversation-1")?.unreadCount)
                val message = reopened.messageDao().getPendingMessages().single()
                assertEquals("msg-1", message.id)
                assertEquals("lanu2:encrypted-record", message.content)
                assertEquals("PENDING", message.status)
                val history = reopened.calculationHistoryDao().getAllHistory().first().single()
                assertEquals(44L, history.id)
                assertEquals("2+2", history.expression)
                assertEquals("4", history.result)
                val call = reopened.callLogDao().getAllCallLogs().first().single()
                assertEquals(55L, call.id)
                assertEquals(18, call.durationSeconds)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
