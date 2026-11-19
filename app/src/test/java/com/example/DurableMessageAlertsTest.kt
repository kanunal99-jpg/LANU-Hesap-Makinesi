package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class DurableMessageAlertsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    @Before fun setup() { name = "alert-test-${UUID.randomUUID()}"; db = open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }
    private fun journal() = DurableMessageAlerts(db)
    private suspend fun receive(id: String = UUID.randomUUID().toString(), conversation: String = "conversation"): String {
        db.withTransaction {
            db.conversationDao().insertConversation(ConversationEntity(conversation, "peer", "peer", unreadCount = 1))
            db.messageDao().insertMessage(MessageEntity(id, conversation, "peer", "peer", "encrypted fixture", status = "SENT"))
            journal().enqueueInTransaction(id, conversation)
        }
        return id
    }
    private fun repo() = CommunicationRepository(db.contactDao(), db.conversationDao(), db.messageDao(),
        db.settingsDao(), db.userProfileDao(), db.callLogDao(), object : MessagingIdentityStore {
            override fun read(): MessagingIdentity? = null
            override fun save(identity: MessagingIdentity) = error("No identity may be created")
        }, object : MessagingRelay {
            override val configured = false
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = error("Local alerts need no HTTP")
        }, db)

    @Test fun processDiesBeforePostAndReopenedRoomRetriesExactlyOnce() = runBlocking<Unit> {
        val id = receive(); val prepared = journal().prepare()!!
        db.close(); db = open() // New DB handle and journal, no in-memory counter.
        var posts = 0
        assertTrue(journal().publish { token -> assertEquals(prepared.token, token); posts++; true })
        assertEquals(prepared.token, db.settingsDao().getSettingValue(DurableMessageAlerts.SHOWN))
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.PREFIX + id))
        assertFalse(journal().publish { posts++; true })
        assertEquals(1, posts)
        assertNotNull(db.messageDao().getMessage(id))
    }
    @Test fun processDiesAfterPostBeforeCommitKeepsSameTokenForAndroidDeduplication() = runBlocking<Unit> {
        receive(); val acceptedByAndroid = mutableSetOf<String>(); var actualPosts = 0
        try {
            journal().publish { token -> acceptedByAndroid += token; actualPosts++; throw CancellationException("simulated process boundary") }
            fail("Transaction must be interrupted")
        } catch (_: CancellationException) { }
        assertTrue(db.settingsDao().getPendingMessageAlerts().isNotEmpty())
        db.close(); db = open()
        assertTrue(journal().publish { token -> if (acceptedByAndroid.add(token)) actualPosts++; true })
        assertEquals(1, actualPosts)
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
    }
    @Test fun messageReadAfterPreparationBeforePostCannotAlert() = runBlocking<Unit> {
        receive(); journal().prepare()
        repo().markConversationOpened("conversation") // Actual production read transaction.
        assertFalse(journal().publish { fail("Already read message must not alert"); true })
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.BATCH))
    }
    @Test fun failedNotificationRemainsPendingUntilConfirmedAndThenCannotRepeat() = runBlocking<Unit> {
        receive()
        assertFalse(journal().publish { false })
        val batch = db.settingsDao().getSettingValue(DurableMessageAlerts.BATCH)
        assertNotNull(batch); assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.SHOWN))
        assertTrue(journal().publish { true })
        assertFalse(journal().publish { fail("Completed alert must not repeat"); true })
    }
    @Test fun concurrentWorkersCannotPostSamePendingMessageTwice() = runBlocking<Unit> {
        receive(); val posts = AtomicInteger()
        val first = async(Dispatchers.Default) { journal().publish { posts.incrementAndGet(); true } }
        val second = async(Dispatchers.Default) { journal().publish { posts.incrementAndGet(); true } }
        assertEquals(1, listOf(first.await(), second.await()).count { it })
        assertEquals(1, posts.get())
    }
    @Test fun readPreparedBatchCannotHideAnotherUnreadPendingConversation() = runBlocking<Unit> {
        receive(conversation = "read-thread"); val old = journal().prepare()!!
        repo().markConversationOpened("read-thread")
        receive(conversation = "unread-thread")
        assertTrue(journal().publish { token -> assertNotEquals(old.token, token); true })
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
    }
    @Test fun rollbackNeverLeavesAnOrphanPendingAlert() = runBlocking<Unit> {
        val id = UUID.randomUUID().toString()
        try {
            db.withTransaction { receive(id); throw IllegalStateException("synthetic rollback") }
        } catch (_: IllegalStateException) { }
        assertNull(db.messageDao().getMessage(id))
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
    }
}
