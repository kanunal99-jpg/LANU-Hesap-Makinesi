package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingQueueBatchTest {
    @Test fun `offline backlog remains durable while processing predictable batches`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.messageDao()
            // Simulate real background sync: 215 encrypted queued messages and
            // additional already-sent rows that must never enter the send queue.
            repeat(215) { n ->
                dao.insertMessage(MessageEntity(
                    id = "queued-%03d".format(n),
                    conversationId = "conversation",
                    senderPhone = "own", senderName = "Ben",
                    content = "lanu2:ciphertext-$n",
                    timestamp = 1000L + n / 2L,
                    status = if (n % 2 == 0) "PENDING" else "WAITING"
                ))
            }
            repeat(5) { n ->
                dao.insertMessage(MessageEntity("sent-$n", "conversation", "own",
                    "Ben", "lanu2:already-sent", status = "SENT"))
            }
            assertEquals(220, dao.countMessages())
            val first = dao.getPendingMessages()
            assertEquals(200, first.size)
            assertEquals("queued-000", first.first().id)
            assertEquals("queued-199", first.last().id)
            // A crash leaves all rows present; nothing is pruned when merely read.
            assertEquals(220, dao.countMessages())

            first.forEach { dao.updateMessageStatus(it.id, "SENT") }
            val second = dao.getPendingMessages()
            assertEquals(15, second.size)
            assertEquals("queued-200", second.first().id)
            assertEquals("queued-214", second.last().id)
            second.forEach { dao.updateMessageStatus(it.id, "SENT") }
            assertTrue(dao.getPendingMessages().isEmpty())
            assertEquals("No queued row may be lost after batch processing", 220, dao.countMessages())
        } finally {
            db.close()
        }
    }

    @Test fun `equal timestamps return stable ID order and ignore delivered rows`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.messageDao()
            listOf("zeta", "beta", "alpha").forEach {
                dao.insertMessage(MessageEntity(it, "conv", "own", "Me",
                    "lanu2:encrypted", timestamp = 1234, status = "WAITING"))
            }
            dao.insertMessage(MessageEntity("skip", "conv", "own", "Me",
                "lanu2:encrypted", timestamp = 1000, status = "READ"))
            assertEquals(listOf("alpha", "beta", "zeta"),
                dao.getPendingMessages().map { it.id })
        } finally {
            db.close()
        }
    }
}
