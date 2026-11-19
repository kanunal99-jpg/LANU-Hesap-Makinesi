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
@Config(sdk = [34])
class DurableMessageAlertAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private val queries = AtomicInteger()
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setQueryCallback({ _, _ -> queries.incrementAndGet() }, java.util.concurrent.Executor { it.run() }).build()
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

    // Regressions reproduced on 1334; the external post/commit boundary remains explicitly accepted.
    @Test fun deletedConversationMustNotRetainPendingAlertWithNotificationsOff() = runBlocking<Unit> {
        val id = receive()
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().putBoolean("opted_in", false).commit()
        assertFalse(MessageWakePreferences.isEnabled(context))
        repo().deleteConversation("conversation")
        assertNull(db.messageDao().getMessage(id))
        assertNull("Deleted conversation left an orphan notification row", db.settingsDao().getSettingValue(DurableMessageAlerts.PREFIX + id))
    }
    @Test fun twoPublicationPassesMustReachUnreadMessageBehind401DeletedRows() = runBlocking<Unit> {
        db.withTransaction {
            repeat(401) { n ->
                // Real state left by deleting messages while delivery preference is off.
                db.settingsDao().insertSetting(SettingsEntity(DurableMessageAlerts.PREFIX + "00000000-0000-0000-0000-" + n.toString().padStart(12, '0'), "deleted-thread"))
            }
        }
        receive("ffffffff-ffff-ffff-ffff-ffffffffffff", "live-thread")
        var posts = 0
        // A Worker with one empty inbox page flushes before sync and after that page.
        assertTrue(journal().publish { posts++; true })
        assertFalse(journal().publish { posts++; true })
        assertEquals("Valid unread alert was deferred behind orphan rows", 1, posts)
    }
    @Test fun knownPostCommitBoundaryCanRepostWhenAndroidStateDisappears() = runBlocking<Unit> {
        receive(); var posts = 0
        try { journal().publish { posts++; throw CancellationException("process dies after Android post") } }
        catch (_: CancellationException) { }
        db.close(); db = open()
        // The earlier Android notification expired/was dismissed before recovery.
        journal().publish { posts++; true }
        // User explicitly leaves this distributed Android/Room ambiguity unchanged.
        assertEquals("Known boundary: Android post survived while Room completion did not", 2, posts)
    }
    @Test fun measureSqlWorkFor200PendingAlertsWhenAndroidRejectsPosting() = runBlocking<Unit> {
        db.withTransaction {
            repeat(200) { n -> receive("10000000-0000-0000-0000-" + n.toString().padStart(12, '0')) }
        }
        queries.set(0)
        val started = System.nanoTime()
        repeat(2) { assertFalse(journal().publish { false }) }
        val count = queries.get(); val elapsed = (System.nanoTime() - started) / 1_000_000
        println("QA_ALERT_SCAN: pending=200 publication_passes=2 sql_statements=$count emulator_elapsed_ms=$elapsed")
        assertEquals(200, db.settingsDao().getPendingMessageAlerts().size)
        assertTrue("Batch publication must avoid per-message SQL: $count", count in 1..60)
    }
    @Test fun clearAndExpiryCleanPendingBatchWhileNotificationsOff() = runBlocking<Unit> {
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().putBoolean("opted_in", false).commit()
        val expired = receive(conversation = "expires")
        receive(conversation = "clears")
        journal().prepare()
        repo().hideMessageLocally(expired)
        repo().clearConversationMessages("clears")
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.BATCH))
    }
    @Test fun bulkPruneDropsReadHiddenAndOrphanRowsButPreservesUnread() = runBlocking<Unit> {
        val read = receive(conversation = "read")
        val hidden = receive(conversation = "hidden")
        val live = receive(conversation = "live")
        db.conversationDao().clearUnreadCount("read")
        db.settingsDao().insertSetting(SettingsEntity("lanu_hidden_$hidden", "true"))
        journal().prune()
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.PREFIX + read))
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.PREFIX + hidden))
        assertNotNull(db.settingsDao().getSettingValue(DurableMessageAlerts.PREFIX + live))
        assertTrue(journal().publish { true })
    }
    @Test fun publicationProgressesAcrossMoreThanOneValidBatch() = runBlocking<Unit> {
        db.withTransaction { repeat(401) { n -> receive("valid-$n") } }
        var posts = 0
        repeat(3) { assertTrue(journal().publish { posts++; true }) }
        assertEquals(3, posts)
        assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
        assertFalse(journal().publish { error("No duplicate") })
    }
    @Test fun successfulPublicationDoesNotAccumulateShownMessageRows() = runBlocking<Unit> {
        repeat(20) {
            receive(); assertTrue(journal().publish { true })
            assertTrue(db.settingsDao().getPendingMessageAlerts().isEmpty())
        }
        assertNotNull(db.settingsDao().getSettingValue(DurableMessageAlerts.SHOWN))
        assertNull(db.settingsDao().getSettingValue(DurableMessageAlerts.BATCH))
    }
}
