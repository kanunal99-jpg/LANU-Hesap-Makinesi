package com.example.core.repository

import androidx.paging.*
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.example.core.database.*
import kotlinx.coroutines.*

/** Read-only keyset pages retain a real message anchor across concurrent inserts. */
internal class MessageHistoryPagingSource(
    private val database: AppDatabase,
    private val conversationId: String
) : PagingSource<MessageHistoryCursor, MessageEntity>() {
    private val lock = Any()
    private var observing = false
    private val observer = object : InvalidationTracker.Observer("messages") {
        override fun onInvalidated(tables: Set<String>) { invalidate() }
    }
    init {
        require(conversationId.isNotBlank())
        registerInvalidatedCallback {
            synchronized(lock) {
                if (observing) { database.invalidationTracker.removeObserver(observer); observing = false }
            }
        }
    }
    private fun observe(): Boolean = synchronized(lock) {
        if (invalid) return false
        if (!observing) {
            database.invalidationTracker.addObserver(observer)
            observing = true
        }
        if (invalid) {
            database.invalidationTracker.removeObserver(observer); observing = false
            false
        } else true
    }
    override suspend fun load(params: LoadParams<MessageHistoryCursor>): LoadResult<MessageHistoryCursor, MessageEntity> = withContext(Dispatchers.IO) {
        try {
            if (!observe()) return@withContext LoadResult.Invalid()
            require(params.loadSize > 0)
            val size = params.loadSize.coerceAtMost(100)
            val dao = database.messageDao()
            val page = database.withTransaction {
                val key = params.key
                val rows = when (params) {
                    is LoadParams.Refresh -> if (key == null) dao.latestMessagePage(conversationId, size)
                        else {
                            val pivot = dao.messageHistoryCursor(conversationId, key.id) ?: key
                            var newer = dao.newerMessagePage(conversationId, pivot.timestamp, pivot.id, size / 2).asReversed()
                            val older = dao.messagePageAtOrBefore(conversationId, pivot.timestamp, pivot.id, size - newer.size)
                            if (newer.size + older.size < size)
                                newer = dao.newerMessagePage(conversationId, pivot.timestamp, pivot.id, size - older.size).asReversed()
                            newer + older
                        }
                    is LoadParams.Append -> dao.olderMessagePage(conversationId, key!!.timestamp, key.id, size)
                    is LoadParams.Prepend -> dao.newerMessagePage(conversationId, key!!.timestamp, key.id, size).asReversed()
                }
                val first = rows.firstOrNull()?.let { MessageHistoryCursor(it.timestamp, it.id) }
                val last = rows.lastOrNull()?.let { MessageHistoryCursor(it.timestamp, it.id) }
                LoadResult.Page(rows,
                    prevKey = first?.takeIf { dao.hasNewerMessages(conversationId, it.timestamp, it.id) },
                    nextKey = last?.takeIf { dao.hasOlderMessages(conversationId, it.timestamp, it.id) })
            }
            currentCoroutineContext().ensureActive()
            if (invalid) LoadResult.Invalid() else page
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { LoadResult.Error(IllegalStateException("Sohbet geçmişi yüklenemedi.")) }
        catch (_: LinkageError) { LoadResult.Error(IllegalStateException("Sohbet geçmişi yüklenemedi.")) }
    }
    override fun getRefreshKey(state: PagingState<MessageHistoryCursor, MessageEntity>): MessageHistoryCursor? =
        state.anchorPosition?.let { state.closestItemToPosition(it) }?.let { MessageHistoryCursor(it.timestamp, it.id) }
}
