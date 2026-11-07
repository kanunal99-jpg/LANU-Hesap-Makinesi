package com.example

import androidx.paging.*
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.database.*
import com.example.core.repository.MessageHistoryPagingSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual SQLite and minified paging ABI; no live conversation or identity is used. */
@RunWith(AndroidJUnit4::class)
class MessageHistoryPagingRuntimeTest {
    @Test fun keysetPagesPreserveTwentyThousandRowsAndRealAnchorAcrossNewMessages() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lanu_ci_paging_${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .openHelperFactory(PreserveCorruptRoomFactory()).build()
        var source = MessageHistoryPagingSource(db, "thread")
        fun row(i: Int) = MessageEntity("m%05d".format(i), "thread", "own", "Owner", "lanu2:fixture", timestamp = (i / 5).toLong(), status = "SENT")
        try {
            db.withTransaction { repeat(20000) { db.messageDao().insertMessage(row(it)) } }
            withContext(Dispatchers.IO) { db.invalidationTracker.refreshVersionsSync() }
            val initial = source.load(PagingSource.LoadParams.Refresh(null, 100, false)) as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
            assertEquals(100, initial.data.size); assertEquals("m19999", initial.data.first().id)
            val historyKey = MessageHistoryCursor(row(10000).timestamp, row(10000).id)
            source.invalidate(); source = MessageHistoryPagingSource(db, "thread")
            val older = source.load(PagingSource.LoadParams.Refresh(historyKey, 100, false)) as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
            val anchor = source.getRefreshKey(PagingState(listOf(older), 50, PagingConfig(50, enablePlaceholders = false), 0))!!
            source.invalidate()
            db.withTransaction { repeat(250) { db.messageDao().insertMessage(row(20000 + it)) } }
            withContext(Dispatchers.IO) { db.invalidationTracker.refreshVersionsSync() }
            source = MessageHistoryPagingSource(db, "thread")
            val refreshed = source.load(PagingSource.LoadParams.Refresh(anchor, 100, false)) as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
            assertEquals(anchor.id, refreshed.data[50].id)
            assertEquals(20250, db.messageDao().countMessages())
            val next = source.load(PagingSource.LoadParams.Append(refreshed.nextKey!!, 50, false)) as PagingSource.LoadResult.Page<MessageHistoryCursor, MessageEntity>
            assertTrue(next.data.all { n -> refreshed.data.none { it.id == n.id } })
            source.invalidate()
            assertTrue(source.load(PagingSource.LoadParams.Refresh(null, 100, false)) is PagingSource.LoadResult.Invalid)
        } finally { source.invalidate(); db.close(); ctx.deleteDatabase(name) }
    }
}
