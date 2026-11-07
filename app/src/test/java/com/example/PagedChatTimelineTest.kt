package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.*
import com.example.core.database.MessageEntity
import com.example.features.communication.PagedChatTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PagedChatTimelineTest {
    @get:Rule val compose = createComposeRule()
    private fun rows(n: Int) = (n downTo 1).map { MessageEntity("m$it", "thread", "own", "Owner", "text-$it", timestamp = it.toLong(), status = "SENT") }
    private fun data(rows: List<MessageEntity>) = PagingData.from(rows, LoadStates(
        LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true)))

    @Test fun olderReadingSurvivesTwoHundredNewRowsAndLatestButtonReturnsToHead() {
        val pages = MutableStateFlow(data(rows(100))); val latest = mutableStateOf<String?>("m100")
        compose.setContent { MaterialTheme {
            PagedChatTimeline(pages, "thread", "thread", latest.value, {}, Modifier.fillMaxSize()) { message, _ ->
                Text(message.content, Modifier.fillMaxWidth().height(48.dp))
            }
        } }
        compose.onNodeWithTag("paged_chat_history").performScrollToIndex(50)
        compose.onNodeWithText("text-50").assertIsDisplayed()
        compose.runOnIdle { pages.value = data(rows(300)); latest.value = "m300" }
        compose.waitForIdle()
        compose.onNodeWithText("text-50").assertIsDisplayed()
        compose.onNodeWithTag("chat_jump_to_latest").performClick()
        compose.onNodeWithText("text-300").assertIsDisplayed()
    }
    @Test fun boundedWindowRefreshKeepsVisibleMessageEvenWhenItsIndexShrinksByTwoHundred() {
        val pages = MutableStateFlow(data(rows(300)))
        compose.setContent { MaterialTheme {
            PagedChatTimeline(pages, "thread", "thread", "m300", {}, Modifier.fillMaxSize()) { message, _ ->
                Text(message.content, Modifier.fillMaxWidth().height(48.dp))
            }
        } }
        compose.onNodeWithTag("paged_chat_history").performScrollToIndex(250)
        compose.onNodeWithText("text-50").assertIsDisplayed()
        compose.runOnIdle { pages.value = data(rows(100)) }
        compose.waitForIdle()
        compose.onNodeWithText("text-50").assertIsDisplayed()
    }
    @Test fun oldConversationRowsNeverRenderDuringSelectionTransition() {
        val old = MessageEntity("old", "old-thread", "own", "Owner", "DO-NOT-SHOW", status = "SENT")
        val pages = MutableStateFlow(data(listOf(old)))
        compose.setContent { MaterialTheme {
            PagedChatTimeline(pages, "new-thread", "new-thread", "new", {}, Modifier.fillMaxSize()) { message, _ -> Text(message.content) }
        } }
        compose.onNodeWithText("DO-NOT-SHOW").assertDoesNotExist()
    }
    @Test fun incomingBurstFollowsWhenPreviousNewestWasVisible() {
        val pages = MutableStateFlow(data(rows(100))); val latest = mutableStateOf<String?>("m100")
        compose.setContent { MaterialTheme {
            PagedChatTimeline(pages, "thread", "thread", latest.value, {}, Modifier.fillMaxSize()) { message, _ ->
                Text(message.content, Modifier.fillMaxWidth().height(48.dp))
            }
        } }
        compose.onNodeWithText("text-100").assertIsDisplayed()
        compose.runOnIdle { pages.value = data(rows(300)); latest.value = "m300" }
        compose.waitForIdle()
        compose.onNodeWithText("text-300").assertIsDisplayed()
    }
    @Test fun pageFailureOffersRetryWithoutPrintingExceptionDetails() {
        val safeStates = LoadStates(LoadState.Error(IllegalStateException("PRIVATE-PATH")), LoadState.NotLoading(true), LoadState.NotLoading(true))
        val pages = MutableStateFlow(PagingData.empty<MessageEntity>(safeStates))
        compose.setContent { MaterialTheme {
            PagedChatTimeline(pages, "thread", "thread", null, {}, Modifier.fillMaxSize()) { message, _ -> Text(message.content) }
        } }
        compose.onNodeWithText("Sohbet geçmişini tekrar yükle").assertExists()
        compose.onNodeWithText("PRIVATE-PATH").assertDoesNotExist()
    }
}
