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

private class PresentedHistory {
    var ids: List<String> = emptyList()
    var newestId: String? = null
    var follow = false
}

/** Newest at index0; history pages load upward without decrypting the whole DB. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        val head = messages.itemSnapshotList.items.firstOrNull()
            ?.takeIf { PagedChatReadingPolicy.visible(it, activeConversationId, conversationId) }?.id
        val authorized = activeConversationId == conversationId
        val snapshot = messages.itemSnapshotList
        val ids = remember(snapshot) { snapshot.items.map { it.id } }
        val presented = remember { PresentedHistory() }
        // One pre-remeasure decision owns BOTH history anchoring and newest
        // following. Competing scroll effects can otherwise undo each other.
        SideEffect {
            if (authorized) {
                // layoutInfo may include an extra row in content padding before
                // firstVisibleItemIndex. Use the actual scroll anchor, otherwise
                // a page arriving before metadata makes the old head look hidden.
                val visible = list.layoutInfo.visibleItemsInfo
                    .filter { it.index >= list.firstVisibleItemIndex }.sortedBy { it.index }
                val newestChanged = presented.newestId != latestMessageId
                if (newestChanged) {
                    presented.follow = PagedChatReadingPolicy.shouldFollow(
                        presented.newestId, latestMessageId, visible.firstOrNull()?.key) &&
                        (presented.newestId != null || list.firstVisibleItemIndex == 0)
                    presented.newestId = latestMessageId
                }
                if (presented.follow && head != null && head == latestMessageId && ids.isNotEmpty()) {
                    list.requestScrollToItem(0)
                    presented.follow = false
                } else if (newestChanged && presented.follow && messages.itemCount > 0) {
                    onReloadLatest()
                } else if (presented.ids != ids && !presented.follow) {
                    // Stable lazy keys only search a nearby range. Retain the
                    // actual visible key even across a200-index shift/drop.
                    val anchor = visible.firstNotNullOfOrNull { item ->
                        (item.key as? String)?.let { key -> ids.indexOf(key).takeIf { it >= 0 } }
                    }
                    if (anchor != null && anchor != list.firstVisibleItemIndex)
                        list.requestScrollToItem(anchor, list.firstVisibleItemScrollOffset)
                }
            }
            presented.ids = ids
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
                    presented.follow = true; onReloadLatest()
                } else {
                    presented.follow = false
                    scope.launch { if (messages.itemCount > 0) list.animateScrollToItem(0) }
                }
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("chat_jump_to_latest")) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("En yeniye git")
            }
        }
    }
}
