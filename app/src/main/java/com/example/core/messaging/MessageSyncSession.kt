package com.example.core.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** In-memory authority scoped to one background sync; never affects foreground calls. */
internal class MessageSyncSession(private val allowed: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MessageSyncSession> {
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[Key]?.ensureAllowed()
        }
    }
    private var incoming = 0
    @Synchronized fun newIncomingCount(): Int = incoming
    @Synchronized fun recordNewIncoming() { incoming++ }
    fun ensureAllowed() { if (!allowed()) throw MessageSyncRevoked() }
    /** Same boundary as persisted opt-out: no socket is enqueued after its commit. */
    fun <T> startRequest(block: () -> T): T = synchronized(MessageWakePreferences.requestGate) {
        ensureAllowed()
        block()
    }
}

internal class MessageSyncRevoked : CancellationException("Background message consent revoked")
