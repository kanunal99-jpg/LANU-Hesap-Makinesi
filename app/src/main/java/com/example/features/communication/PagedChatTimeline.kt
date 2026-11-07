package com.example.features.communication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.core.database.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Newest at index0; history pages load upward without decrypting the whole DB. */
@Composable
internal fun PagedChatTimeline(
    pages: Flow<PagingData<MessageEntity>>, conversationId: String, activeConversationId: String?,
    latestMessageId: String?, onReloadLatest: () -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (MessageEntity, MessageEntity?) -> Unit
) {
    key(conversationId) {
        val messages = pages.collectAsLazyPagingItems()
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var previousNewest by remember { mutableStateOf<String?>(null) }
        var followPending by remember { mutableStateOf(true) }
        val head = messages.itemSnapshotList.items.firstOrNull()
            ?.takeIf { PagedChatReadingPolicy.visible(it, activeConversationId, conversationId) }?.id
        val authorized = activeConversationId == conversationId
        LaunchedEffect(latestMessageId, authorized) {
            if (!authorized) return@LaunchedEffect
            if (PagedChatReadingPolicy.shouldFollow(previousNewest, latestMessageId,
                    list.layoutInfo.visibleItemsInfo.firstOrNull()?.key)) {
                followPending = true
                if (messages.itemCount > 0 && head != latestMessageId) onReloadLatest()
            }
            previousNewest = latestMessageId
        }
        LaunchedEffect(head, latestMessageId, followPending) {
            if (authorized && followPending && head != null && head == latestMessageId && messages.itemCount > 0) {
                list.scrollToItem(0)
                followPending = false
            }
        }
        val showLatest by remember {
            derivedStateOf {
                messages.itemCount > 0 &&
                    (list.firstVisibleItemIndex > 0 || !messages.loadState.prepend.endOfPaginationReached)
            }
        }
        Box(modifier) {
            LazyColumn(state = list, reverseLayout = true,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("paged_chat_history"),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(count = messages.itemCount, key = messages.itemKey { it.id }) { index ->
                    val message = messages[index]
                    if (PagedChatReadingPolicy.visible(message, activeConversationId, conversationId)) {
                        val older = if (index + 1 < messages.itemCount) messages.peek(index + 1) else null
                        row(message!!, older?.takeIf { PagedChatReadingPolicy.visible(it, activeConversationId, conversationId) })
                    }
                }
                item(key = "history-load-state") {
                    when {
                        messages.loadState.append is LoadState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                        messages.loadState.append is LoadState.Error -> TextButton(onClick = { messages.retry() }) {
                            Text("Önceki mesajları tekrar yükle")
                        }
                    }
                }
            }
            when {
                !authorized || (messages.itemCount == 0 && messages.loadState.refresh is LoadState.Loading) ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                messages.loadState.refresh is LoadState.Error ->
                    TextButton(onClick = { messages.retry() }, modifier = Modifier.align(Alignment.Center)) {
                        Text("Sohbet geçmişini tekrar yükle")
                    }
                messages.itemCount == 0 -> Text("Henüz mesaj yok", Modifier.align(Alignment.Center))
            }
            if (authorized && showLatest) FilledTonalButton(onClick = {
                if (!messages.loadState.prepend.endOfPaginationReached || head != latestMessageId) {
                    followPending = true; onReloadLatest()
                } else scope.launch { if (messages.itemCount > 0) list.animateScrollToItem(0) }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("chat_jump_to_latest")) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("En yeniye git")
            }
        }
    }
}
