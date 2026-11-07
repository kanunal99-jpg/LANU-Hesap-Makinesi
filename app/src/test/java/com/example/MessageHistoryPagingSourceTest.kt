package com.example

import android.content.Context
import androidx.paging.*
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.repository.MessageHistoryPagingSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageHistoryPagingSourceTest {
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private fun row(i: Int, conversation: String = "thread") = MessageEntity("m%05d".format(i), conversation,
        "own", "Owner", "ciphertext-$i", timestamp = (i / 5).toLong(), status = "SENT")
    private suspend fun settle(db: AppDatabase) = withContext(Dispatchers.IO) { db.invalidationTracker.refreshVersionsSync() }
    private suspend fun page(source: MessageHistoryPagingSource, key: MessageHistoryCursor? = null, size: Int = 100): PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity> {
        val result = source.load(PagingSource.LoadParams.Refresh(key, size, false))
        assertTrue(result.toString(), result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
    }
    @Test fun twentyThousandRowsLoadInBoundedDeterministicPagesWithoutLossOrForeignThread() = runBlocking {
        val db = db(); val source = MessageHistoryPagingSource(db, "thread")
        try {
            db.withTransaction {
                repeat(20000) { db.messageDao().insertMessage(row(it)) }
                db.messageDao().insertMessage(row(30000, "other"))
            }
            settle(db)
            var current = page(source)
            assertEquals(100, current.data.size); assertEquals("m19999", current.data.first().id)
            assertNull(current.prevKey)
            val seen = mutableSetOf<String>()
            while (true) {
                assertTrue(current.data.size <= 100)
                current.data.forEach { assertEquals("thread", it.conversationId); assertTrue(seen.add(it.id)) }
                val key = current.nextKey ?: break
                current = source.load(PagingSource.LoadParams.Append(key, 50, false)) as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
            }
            assertEquals(20000, seen.size)
            assertEquals(20001, db.messageDao().countMessages())
        } finally { source.invalidate(); db.close() }
    }
    @Test fun realAnchorSurvivesMassInsertAndDeletedAnchorUsesNeighbor() = runBlocking {
        val db = db()
        try {
            db.withTransaction { repeat(1000) { db.messageDao().insertMessage(row(it)) } }
            settle(db)
            val source = MessageHistoryPagingSource(db, "thread")
            val first = page(source, MessageHistoryCursor(row(500).timestamp, row(500).id))
            val state = PagingState(listOf(first), 50, PagingConfig(50, enablePlaceholders = false), 0)
            val anchor = source.getRefreshKey(state)!!
            val anchorRow = first.data[50]
            assertEquals(anchorRow.id, anchor.id)
            source.invalidate()
            db.withTransaction { repeat(500) { db.messageDao().insertMessage(row(1000 + it)) } }
            settle(db)
            val refreshed = MessageHistoryPagingSource(db, "thread")
            val result = page(refreshed, anchor)
            assertTrue(result.data.any { it.id == anchorRow.id })
            assertEquals(anchorRow.id, result.data[50].id)
            refreshed.invalidate()
            db.messageDao().deleteMessage(anchorRow.id)
            settle(db)
            val afterDelete = MessageHistoryPagingSource(db, "thread")
            val neighbor = page(afterDelete, anchor)
            assertFalse(neighbor.data.any { it.id == anchorRow.id })
            assertEquals(100, neighbor.data.size)
            afterDelete.invalidate()
        } finally { db.close() }
    }
    @Test fun invalidationDetachesObserverAndHugeLoadCannotMaterializeWholeHistory() = runBlocking {
        val db = db(); val source = MessageHistoryPagingSource(db, "thread")
        try {
            db.withTransaction { repeat(300) { db.messageDao().insertMessage(row(it)) } }
            settle(db)
            assertEquals(100, page(source, size = 100000).data.size)
            val field = MessageHistoryPagingSource::class.java.getDeclaredField("observing").apply { isAccessible = true }
            assertTrue(field.getBoolean(source))
            source.invalidate()
            assertFalse(field.getBoolean(source))
            assertTrue(source.load(PagingSource.LoadParams.Refresh(null, 100, false)) is PagingSource.LoadResult.Invalid)
        } finally { source.invalidate(); db.close() }
    }
    @Test fun retryQueueUsesOldestTwentyOwnIdsEvenOutsideNewestPage() = runBlocking {
        val db = db()
        try {
            db.withTransaction {
                repeat(1000) { db.messageDao().insertMessage(row(it).copy(status = if (it < 35) "FAILED" else "SENT")) }
                db.messageDao().insertMessage(row(2000).copy(senderPhone = "peer", status = "FAILED"))
                db.messageDao().insertMessage(row(3000, "other").copy(status = "FAILED"))
            }
            val ids = db.messageDao().failedRetryIds("thread", "own").first()
            assertEquals((0 until 20).map { row(it).id }, ids)
        } finally { db.close() }
    }
}
