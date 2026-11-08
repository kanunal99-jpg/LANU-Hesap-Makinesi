package com.example

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RatchetTransactionStoreRuntimeTest {
    private fun owner()=MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun cleanup(context:Context,id:String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("lanu_ratchet_wrap_v1_$id") }
        val keyOwner=MessageDigest.getInstance("SHA-256").digest("LANU-KEY-WRAP|$id".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        for (name in listOf(id,keyOwner)) for(suffix in listOf("", ".bak", ".new"))
            java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$name.journal$suffix").delete()
    }
    @Test fun danglingSymlinkCannotRedirectDurableCiphertext() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id=owner();val journal=RatchetFileJournal(context.noBackupFilesDir,id)
        val outside=java.io.File(context.noBackupFilesDir,"lanu-test-uncreated-"+UUID.randomUUID())
        val link=java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal")
        try {
            android.system.Os.symlink(outside.absolutePath,link.absolutePath)
            assertFalse(link.exists()) // A dangling link fooled an exists-only guard.
            assertTrue(runCatching {journal.write("synthetic-ciphertext")}.isFailure)
            assertFalse(outside.exists())
        } finally {link.delete();outside.delete()}
    }
    @Test fun keystoreReopenRoleBindingAndMissingKeyDoNotRegenerate() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val id=owner()
        try {
            val secrets=RatchetSecrets.open(context,id,create=true)
            val sealed=secrets.seal("state","synthetic-state")
            secrets.close()
            val reopened=RatchetSecrets.open(context,id)
            assertEquals("synthetic-state",reopened.open("state",sealed))
            assertTrue(runCatching { reopened.open("journal",sealed) }.isFailure)
            val tampered=sealed.substring(0,20)+(if(sealed[20]=='A') "B" else "A")+sealed.substring(21)
            assertTrue(runCatching { reopened.open("state",tampered) }.isFailure)
            reopened.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("lanu_ratchet_wrap_v1_$id") }
            assertTrue(runCatching { RatchetSecrets.open(context,id,create=true) }.isFailure)
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("lanu_ratchet_wrap_v1_$id"))
        } finally { cleanup(context,id) }
    }
    @Test fun eachCrashBoundaryReplaysExactMessageOnceAndRejectsDatabaseRollback() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        for (failure in RatchetTransactionStore.Checkpoint.values()) {
            val id=owner(); val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
            val secrets=RatchetSecrets.open(context,id,create=true)
            try {
                val conversation="b".repeat(64); val messageId=UUID.randomUUID().toString()
                db.conversationDao().insertConversation(ConversationEntity(conversation,"fixture","fixture",lastMessageTime=0))
                val journal=RatchetFileJournal(context.noBackupFilesDir,id)
                val store=RatchetTransactionStore(db,journal,secrets) { if(it==failure) error("synthetic crash") }
                val row=MessageEntity(messageId,conversation,"a".repeat(64),"fixture","lanu3-local:fixture",timestamp=1,status="PENDING")
                assertTrue(runCatching { store.transact { RatchetTransactionStore.Change("state-1",row,
                    settings=mapOf("lanu_dr_wire_$messageId" to "exact-ciphertext"),
                    preview=RatchetTransactionStore.Preview("Şifreli mesaj",1,true)) to Unit } }.isFailure)
                val reopened=RatchetTransactionStore(db,journal,secrets)
                reopened.recover(); reopened.recover()
                assertEquals(row,db.messageDao().getMessage(messageId))
                assertEquals(1,db.conversationDao().getConversationById(conversation)!!.unreadCount)
                assertEquals("exact-ciphertext",db.settingsDao().getSettingValue("lanu_dr_wire_$messageId"))
                val before=db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)!!
                reopened.transact { old -> assertEquals("state-1",old); RatchetTransactionStore.Change("state-2") to Unit }
                db.settingsDao().insertSetting(SettingsEntity(RatchetTransactionStore.STATE_KEY,before))
                assertTrue(runCatching { reopened.recover() }.isFailure)
                assertEquals(row,db.messageDao().getMessage(messageId))
            } finally { secrets.close(); db.close(); cleanup(context,id) }
        }
    }
    @Test fun independentStoresSerializeAndNestedRoomTransactionIsRejected() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val id=owner()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val secrets=RatchetSecrets.open(context,id,create=true)
        try {
            val journal=RatchetFileJournal(context.noBackupFilesDir,id)
            val first=RatchetTransactionStore(db,journal,secrets); val second=RatchetTransactionStore(db,journal,secrets)
            coroutineScope { (1..20).map { n -> async { (if(n%2==0)first else second).transact { old ->
                RatchetTransactionStore.Change(((old?.toInt() ?: 0)+1).toString()) to Unit
            } } }.forEach { it.await() } }
            first.transact { old -> assertEquals("20",old); RatchetTransactionStore.Change("21") to Unit }
            db.withTransaction { assertTrue(runCatching { first.transact<Unit> { error("must never execute") } }.isFailure) }
            first.recover()
        } finally { secrets.close(); db.close(); cleanup(context,id) }
    }
}
