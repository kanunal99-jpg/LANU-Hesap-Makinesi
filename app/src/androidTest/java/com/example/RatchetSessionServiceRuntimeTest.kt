package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RatchetSessionServiceRuntimeTest {
    private class Endpoint(val context:Context,val own:MessagingIdentity):AutoCloseable {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val keys=RatchetSecrets.open(context,own.id,create=true)
        val journal=RatchetFileJournal(context.noBackupFilesDir,own.id)
        val service=RatchetSessionService(db,RatchetTransactionStore(db,journal,keys),keys)
        override fun close() {
            keys.close();db.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("lanu_ratchet_wrap_v1_${own.id}") }
            val keyOwner=MessageDigest.getInstance("SHA-256").digest("LANU-KEY-WRAP|${own.id}".toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it) }
            for(id in listOf(own.id,keyOwner)) for(suffix in listOf("", ".bak", ".new"))
                java.io.File(context.noBackupFilesDir,"lanu-ratchet-v1/$id.journal$suffix").delete()
        }
    }
    @Test fun actualRatchetMessagesPersistHistoryAndConvergeAfterSimultaneousFirstSend()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        Endpoint(context,UserCodes.create()).use { a -> Endpoint(context,UserCodes.create()).use { b ->
            val conversation=UserCodes.conversationId(a.own.id,b.own.id)
            a.db.conversationDao().insertConversation(ConversationEntity(conversation,b.own.code,"fixture",lastMessageTime=0))
            b.db.conversationDao().insertConversation(ConversationEntity(conversation,a.own.code,"fixture",lastMessageTime=0))
            val ab=a.service.prepare(a.own,b.own.id);val bb=b.service.prepare(b.own,a.own.id)
            a.service.pin(a.own,b.own.id,b.own.publicKey,bb);b.service.pin(b.own,a.own.id,a.own.publicKey,ab)
            val aid=UUID.randomUUID().toString();val bid=UUID.randomUUID().toString()
            val aw=a.service.queue(a.own,b.own.id,b.own.publicKey,aid,conversation,"birinci uç",1)
            val bw=b.service.queue(b.own,a.own.id,a.own.publicKey,bid,conversation,"ikinci uç",1)
            assertEquals("birinci uç",b.service.receive(b.own,a.own.id,a.own.publicKey,aid,aw,1,1,"SENT",true))
            assertEquals("ikinci uç",a.service.receive(a.own,b.own.id,b.own.publicKey,bid,bw,1,1,"SENT",true))
            repeat(8) { n ->
                val id=UUID.randomUUID().toString();val text="yanıt $n"
                val wire=a.service.queue(a.own,b.own.id,b.own.publicKey,id,conversation,text,n+2L)
                assertEquals(text,b.service.receive(b.own,a.own.id,a.own.publicKey,id,wire,n+2L,1,"SENT",true))
                val stored=b.db.messageDao().getMessage(id)!!
                assertTrue(stored.content.startsWith("lanu3-local:lanu2:"))
                assertFalse(stored.content.contains(text))
                assertEquals(text,RatchetSessionService.history(b.own,id,stored.content))
                assertTrue(runCatching { b.service.receive(b.own,a.own.id,a.own.publicKey,id,wire,n+2L,2,"SENT",true) }.isFailure)
                val rid=UUID.randomUUID().toString()
                val reply=b.service.queue(b.own,a.own.id,a.own.publicKey,rid,conversation,"geri $n",n+2L)
                assertEquals("geri $n",a.service.receive(a.own,b.own.id,b.own.publicKey,rid,reply,n+2L,1,"SENT",true))
            }
            // Preserve the existing18KiB inline attachment envelope budget
            // through nested Olm + outer LANU2, rather than shrinking it silently.
            val attachmentText="x".repeat(25000)
            val attachmentId=UUID.randomUUID().toString()
            val attachmentWire=a.service.queue(a.own,b.own.id,b.own.publicKey,attachmentId,conversation,attachmentText,20)
            assertTrue(attachmentWire.length<=50000)
            assertEquals(attachmentText,b.service.receive(b.own,a.own.id,a.own.publicKey,attachmentId,attachmentWire,20,1,"SENT",true))
            val oldState=a.db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY)
            val bad=JSONObject(bb.toString()).put("peer",b.own.id)
            assertTrue(runCatching { a.service.pin(a.own,b.own.id,b.own.publicKey,bad) }.isFailure)
            val changed=b.service.prepare(b.own,a.own.id)
            changed.put("generation",UUID.randomUUID().toString())
            changed.put("signature",UserCodes.sign(b.own,RatchetProtocol.transcript(changed)))
            assertTrue(runCatching { a.service.pin(a.own,b.own.id,b.own.publicKey,changed) }.isFailure)
            assertEquals(oldState,a.db.settingsDao().getSettingValue(RatchetTransactionStore.STATE_KEY))
            assertEquals(10,b.db.conversationDao().getConversationById(conversation)!!.unreadCount)
        } }
    }
    @Test fun hostileJsonDepthIsRejectedBeforeRecursiveParsing() {
        assertTrue(runCatching { RatchetProtocol.parse("{\"x\":".repeat(2000)+"0"+"}".repeat(2000)) }.isFailure)
        assertTrue(runCatching { RatchetProtocol.parse("{\"text\":\"unterminated}") }.isFailure)
    }
}
