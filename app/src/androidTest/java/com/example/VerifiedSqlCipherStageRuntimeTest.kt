package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class VerifiedSqlCipherStageRuntimeTest {
    private val password="synthetic-backup-password"
    private suspend fun populate(db:AppDatabase,own:MessagingIdentity) {
        db.userProfileDao().insertProfile(UserProfileEntity(own.code,"fixture",registeredAt=1))
        db.calculationHistoryDao().insertHistory(CalculationHistoryEntity(42,"1+2","3",1))
        db.settingsDao().insertSetting(SettingsEntity("pin_hash","fixture-only"))
        db.settingsDao().insertSetting(SettingsEntity(RatchetTransactionStore.STATE_KEY,"encrypted-device-state-"+"X".repeat(200000)))
        db.contactDao().insertContact(ContactEntity(own.code,"fixture"))
        db.conversationDao().insertConversation(ConversationEntity("b".repeat(64),own.code,"fixture",lastMessageTime=1))
        db.messageDao().insertMessage(MessageEntity("00000000-0000-4000-8000-000000000001","b".repeat(64),own.id,"fixture","lanu2:"+"A".repeat(40),timestamp=1,status="PENDING"))
        db.callLogDao().insertCallLog(CallLogEntity(partnerName="fixture",partnerPhone=own.code,isVideo=false,durationSeconds=2,isOutgoing=true,timestamp=1))
    }
    private fun cleanup(context:Context,name:String) {
        context.deleteDatabase(name) // This unique test-created stage only.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("lanu_sqlcipher_stage_v1_$name") }
        val id=MessageDigest.getInstance("SHA-256").digest("LANU-SQLCIPHER-STAGE-v1|$name".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        for(suffix in listOf("", ".bak", ".new")) java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal$suffix").delete()
    }
    @Test fun exactSevenTableCopyReopensWithWrappedKeyAndPreservesDeviceStateWithoutExportingIt()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val own=UserCodes.create()
        val source=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        var staged:VerifiedSqlCipherStage.Prepared?=null
        try {
            populate(source,own)
            val archive=source.withTransaction { FullDataBackup.seal(FullDataBackup.capture(source.openHelper.writableDatabase,own),password) }
            val opened=FullDataBackup.open(archive,password)
            assertFalse(opened.payload.toString().contains("encrypted-device-state-"))
            staged=VerifiedSqlCipherStage.prepare(context,source,own,archive,password)
            val key=DatabaseCipherKeyVault.read(context,staged!!.name)
            val copy=try { VerifiedSqlCipherStage.open(context,staged!!.name,key) } finally { key.fill(0) }
            try {
                assertEquals(source.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY),copy.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY))
                assertEquals("fixture-only",copy.settingsDao().getSettingValue("pin_hash"))
                assertEquals(1,copy.messageDao().countMessages())
                val targetSnapshot=copy.withTransaction { FullDataBackup.captureDeviceSnapshot(copy.openHelper.writableDatabase,own) }
                assertEquals(staged!!.tableDigest,VerifiedSqlCipherStage.fingerprint(targetSnapshot.payload))
                assertTrue(runCatching { FullDataBackup.seal(targetSnapshot.payload,password) }.isFailure)
            } finally {copy.close()}
            assertEquals(1,source.messageDao().countMessages())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("lanu_sqlcipher_stage_v1_${staged!!.name}") }
            assertTrue(runCatching { DatabaseCipherKeyVault.read(context,staged!!.name) }.isFailure)
            assertTrue(runCatching { DatabaseCipherKeyVault.create(context,staged!!.name) }.isFailure)
            assertTrue(VerifiedSqlCipherStage.path(context,staged!!.name).isFile)
            archive.fill(0)
        } finally {source.close();staged?.let {cleanup(context,it.name)}}
    }
    @Test fun backupStalenessWrongOwnerAndTamperingNeverChangeCurrentRecords()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val own=UserCodes.create()
        val source=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            populate(source,own)
            val archive=source.withTransaction { FullDataBackup.seal(FullDataBackup.capture(source.openHelper.writableDatabase,own),password) }
            source.settingsDao().insertSetting(SettingsEntity("new-after-backup","preserved"))
            assertTrue(runCatching { VerifiedSqlCipherStage.prepare(context,source,own,archive,password) }.isFailure)
            assertTrue(runCatching { VerifiedSqlCipherStage.prepare(context,source,UserCodes.create(),archive,password) }.isFailure)
            val damaged=archive.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
            assertTrue(runCatching { VerifiedSqlCipherStage.prepare(context,source,own,damaged,password) }.isFailure)
            assertEquals("preserved",source.settingsDao().getSettingValue("new-after-backup"))
            assertEquals(1,source.messageDao().countMessages())
            assertTrue(source.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)!!.length>200000)
            archive.fill(0);damaged.fill(0)
        } finally {source.close()}
    }
}
