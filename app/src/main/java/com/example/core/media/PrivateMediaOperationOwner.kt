package com.example.core.media

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One foreground operation, including confirmation and SAF, belongs to one chat. */
internal class PrivateMediaOperationOwner(
    private val scope: CoroutineScope,
    private val locked: StateFlow<Boolean>,
    private val conversations: Flow<String?>,
    private val currentConversation: () -> String?,
    private val timeoutMillis: Long = 600_000L
) {
    private var active: Job? = null

    fun launch(conversationId: String, task: suspend () -> Unit): Job? {
        require(conversationId.isNotBlank() && timeoutMillis > 0)
        if (active?.isCompleted == false || locked.value || currentConversation() != conversationId) return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            coroutineScope {
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(locked, conversations) { isLocked, chat -> isLocked || chat != conversationId }
                        .first { it }
                    owner.cancel()
                }
                try {
                    withTimeout(timeoutMillis) {
                        currentCoroutineContext().ensureActive()
                        if (!locked.value && currentConversation() == conversationId) task()
                    }
                } finally { watcher.cancel() }
            }
        }
        active = job
        job.start()
        return job
    }

    fun cancel() { active?.cancel() }
}
