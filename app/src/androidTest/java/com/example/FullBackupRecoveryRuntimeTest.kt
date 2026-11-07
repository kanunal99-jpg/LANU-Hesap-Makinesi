package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullBackupRecoveryRuntimeTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Recovery-runtime-password-2026"
    private val directory: File get() = File(ctx.cacheDir, "backup-verification")
    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest()
    }
    private suspend fun archive(): Pair<ByteArray, File> {
        val name = "lanu_ci_recovery_source_${UUID.randomUUID().toString().replace("-", "")}.db"
        val source = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .openHelperFactory(PreserveCorruptRoomFactory()).build()
        try {
            val own = UserCodes.create()
            source.settingsDao().insertSetting(SettingsEntity("pin_hash", "KEEP-PIN"))
            source.settingsDao().insertSetting(SettingsEntity("entry_pin_enabled", "false"))
            source.userProfileDao().insertProfile(UserProfileEntity(own.code, "Owner", registeredAt = 7))
            source.contactDao().insertContact(ContactEntity("peer", "Contact", isBlocked = true))
            source.conversationDao().insertConversation(ConversationEntity("thread", "peer", "Contact", unreadCount = 2))
            source.messageDao().insertMessage(MessageEntity("pending", "thread", own.id, "Owner", "lanu2:fixture", status = "PENDING"))
            source.calculationHistoryDao().insertHistory(CalculationHistoryEntity(id = 71, expression = "2+2", result = "4"))
            source.callLogDao().insertCallLog(CallLogEntity(id = 81, partnerName = "Contact", partnerPhone = "peer", isVideo = true, durationSeconds = 7, isOutgoing = true))
            val payload = source.withTransaction { FullDataBackup.capture(source.openHelper.writableDatabase, own) }
            return FullDataBackup.seal(payload, password) to ctx.getDatabasePath(name)
        } finally { source.close() }
    }
    private fun probes() = directory.listFiles().orEmpty().filter { BackupRecoveryFilePolicy.isProbeOrSidecar(it.name) }
    private fun deleteSource(file: File) {
        check(file.name.startsWith("lanu_ci_recovery_source_"))
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.absolutePath + it).delete() }
    }

    @Test fun realEncryptedReopenPreservesSevenTablesSourceArchiveAndUnrelatedFiles() = runBlocking {
        val (archive, source) = archive()
        val before = hash(source); val archiveCopy = archive.copyOf()
        directory.mkdirs()
        val untouched = File(directory, "unrelated.lanubak").apply { writeText("KEEP") }
        val stale = File(directory, "lanu-backup-probe-${"f".repeat(32)}.db").apply { writeBytes(ByteArray(64)) }
        try {
            val externalArchive = File(ctx.cacheDir, "lanu-ci-read-archive.enc").apply { writeBytes(archive) }
            try {
                val read = BackupArchiveReader.read(ctx, android.net.Uri.fromFile(externalArchive))
                try { assertArrayEquals(archiveCopy, read) } finally { read.fill(0) }
            } finally { externalArchive.delete() }
            repeat(2) {
                val result = FullBackupRecoveryVerifier.verify(ctx, archive, password)
                assertEquals(7, result.tablesVerified)
                assertEquals(1, result.summary.messages)
                assertEquals(1, result.summary.contacts)
                assertEquals(1, result.summary.calculations)
                assertTrue(probes().isEmpty())
                assertArrayEquals(before, hash(source))
                assertArrayEquals(archiveCopy, archive)
                assertEquals("KEEP", untouched.readText())
            }
            assertFalse(stale.exists())
            assertTrue(runCatching { FullBackupRecoveryVerifier.requireProbePath(ctx, source) }.isFailure)
            assertTrue(runCatching { FullBackupRecoveryVerifier.requireProbePath(ctx, ctx.getDatabasePath("lanu_calculator_db")) }.isFailure)
        } finally { archive.fill(0); archiveCopy.fill(0); untouched.delete(); stale.delete(); deleteSource(source) }
    }

    @Test fun wrongPasswordTamperingAndPartialConstraintFailureNeverSucceedOrChangeSource() = runBlocking {
        val (archive, source) = archive(); val before = hash(source)
        try {
            assertTrue(runCatching { FullBackupRecoveryVerifier.verify(ctx, archive, "wrong-password-2026") }.isFailure)
            val altered = archive.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            try { assertTrue(runCatching { FullBackupRecoveryVerifier.verify(ctx, altered, password) }.isFailure) }
            finally { altered.fill(0) }
            val opened = FullDataBackup.open(archive, password)
            opened.payload.getJSONObject("tables").getJSONArray("messages").getJSONArray(0).put(4, org.json.JSONObject.NULL)
            val invalid = FullDataBackup.seal(opened.payload, password)
            try { assertTrue(runCatching { FullBackupRecoveryVerifier.verify(ctx, invalid, password) }.isFailure) }
            finally { invalid.fill(0) }
            assertTrue(probes().isEmpty())
            assertArrayEquals(before, hash(source))
        } finally { archive.fill(0); deleteSource(source) }
    }
}
