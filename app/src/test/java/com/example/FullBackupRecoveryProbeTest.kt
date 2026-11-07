package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullBackupRecoveryProbeTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val password = "Recovery-probe-password-2026"
    private fun target(): AppDatabase {
        val dir = File(ctx.cacheDir, "backup-verification").apply { mkdirs() }
        val file = File(dir, "lanu-backup-probe-${UUID.randomUUID().toString().replace("-", "")}.db")
        return Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .openHelperFactory(PreserveCorruptRoomFactory()).build()
    }
    private suspend fun snapshot(source: AppDatabase): org.json.JSONObject {
        val own = UserCodes.create()
        source.settingsDao().insertSetting(SettingsEntity("pin_hash", "keep-pin"))
        source.userProfileDao().insertProfile(UserProfileEntity(own.code, "Owner", registeredAt = 7))
        source.contactDao().insertContact(ContactEntity("peer", "Contact", isBlocked = true))
        source.conversationDao().insertConversation(ConversationEntity("thread", "peer", "Contact", unreadCount = 2))
        source.messageDao().insertMessage(MessageEntity("pending", "thread", own.id, "Owner", "cipher", status = "PENDING"))
        source.calculationHistoryDao().insertHistory(CalculationHistoryEntity(id = 71, expression = "2+2", result = "4"))
        source.callLogDao().insertCallLog(CallLogEntity(id = 81, partnerName = "Contact", partnerPhone = "peer", isVideo = false, durationSeconds = 7, isOutgoing = true))
        return source.withTransaction { FullDataBackup.capture(source.openHelper.writableDatabase, own) }
    }
    private fun open(payload: org.json.JSONObject) = FullDataBackup.open(FullDataBackup.seal(payload, password), password)

    @Test fun exactSevenTableRehearsalRetainsSparseIdsWithoutTouchingSource() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build(); val target = target()
        try {
            val opened = open(snapshot(source))
            target.withTransaction {
                val sql = target.openHelper.writableDatabase
                FullDataBackup.populateRecoveryProbe(sql, opened)
                FullDataBackup.verifyRecoveryProbe(sql, opened)
            }
            assertEquals(71L, target.calculationHistoryDao().getAllHistory().first().single().id)
            assertEquals("keep-pin", source.settingsDao().getSettingValue("pin_hash"))
            assertEquals("PENDING", source.messageDao().getMessage("pending")!!.status)
            assertTrue(runCatching { source.withTransaction {
                FullDataBackup.populateRecoveryProbe(source.openHelper.writableDatabase, opened)
            } }.exceptionOrNull() is IllegalArgumentException)
        } finally { source.close(); target.close() }
    }

    @Test fun silentIgnoreOrTypeCoercionCannotPassAndRollsBackEveryTable() = runBlocking {
        for (coercion in listOf(false, true)) {
            val source = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build(); val target = target()
            try {
                val payload = snapshot(source)
                if (coercion) payload.getJSONObject("tables").getJSONArray("user_profiles").getJSONArray(0).put(3, "7")
                val opened = open(payload)
                if (!coercion) target.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER ignore_probe_message BEFORE INSERT ON messages BEGIN SELECT RAISE(IGNORE); END")
                assertTrue(runCatching { target.withTransaction {
                    val sql = target.openHelper.writableDatabase
                    FullDataBackup.populateRecoveryProbe(sql, opened)
                    FullDataBackup.verifyRecoveryProbe(sql, opened)
                } }.isFailure)
                for (table in DatabaseShadowParity.TABLES) target.openHelper.writableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
                    assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
                }
                assertEquals("keep-pin", source.settingsDao().getSettingValue("pin_hash"))
            } finally { source.close(); target.close() }
        }
    }

    @Test fun cancellationRollsBackAndExistingProbeIsNeverOverwritten() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build(); val target = target()
        try {
            val opened = open(snapshot(source)); var checks = 0
            assertTrue(runCatching { target.withTransaction {
                FullDataBackup.populateRecoveryProbe(target.openHelper.writableDatabase, opened) {
                    if (++checks == 3) throw CancellationException("cancel")
                }
            } }.exceptionOrNull() is CancellationException)
            assertNull(target.settingsDao().getSettingValue("pin_hash"))
            target.settingsDao().insertSetting(SettingsEntity("keep", "original"))
            assertTrue(runCatching { target.withTransaction {
                FullDataBackup.populateRecoveryProbe(target.openHelper.writableDatabase, opened)
            } }.isFailure)
            assertEquals("original", target.settingsDao().getSettingValue("keep"))
        } finally { source.close(); target.close() }
    }
}
