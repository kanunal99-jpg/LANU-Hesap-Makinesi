package com.example

import com.example.core.media.PrivateMediaOperationOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMediaOperationOwnerTest {
    @Test fun lockCancelsConfirmationOrTransferAndWipesOwnedKeyBeforeAnotherOperation() = runTest {
        val locked = MutableStateFlow(false); val chat = MutableStateFlow<String?>("a")
        val owner = PrivateMediaOperationOwner(this, locked, chat, { chat.value })
        val key = ByteArray(32) { 7 }; var sent = false
        val wait = CompletableDeferred<Unit>()
        val first = owner.launch("a") {
            try { wait.await(); sent = true } finally { key.fill(0) }
        }!!
        runCurrent()
        assertNull(owner.launch("a") { fail("No concurrent upload") })
        locked.value = true; runCurrent()
        assertTrue(first.isCancelled); assertTrue(key.all { it == 0.toByte() })
        wait.complete(Unit); runCurrent(); assertFalse(sent)
        assertNull(owner.launch("a") { fail("Locked") })
        locked.value = false
        var next = false
        owner.launch("a") { next = true }!!.join()
        assertTrue(next)
    }

    @Test fun switchingChatsCancelsOldSafWaitAndNeverResurrectsItOnReturning() = runTest {
        val locked = MutableStateFlow(false); val chat = MutableStateFlow<String?>("a")
        val owner = PrivateMediaOperationOwner(this, locked, chat, { chat.value })
        val picker = CompletableDeferred<Unit>(); var exported = false; var destroyed = false
        val first = owner.launch("a") {
            try { picker.await(); exported = true } finally { destroyed = true }
        }!!
        runCurrent(); chat.value = "b"; runCurrent()
        assertTrue(first.isCancelled); assertTrue(destroyed)
        chat.value = "a"; picker.complete(Unit); runCurrent()
        assertFalse(exported)
        assertNull(owner.launch("b") { fail("Stale recipient") })
    }

    @Test fun deadlineReleasesOperationAndRunsCleanupWithoutCancellingParentScope() = runTest {
        val locked = MutableStateFlow(false); val chat = MutableStateFlow<String?>("a")
        val owner = PrivateMediaOperationOwner(this, locked, chat, { chat.value }, 1000)
        var cleaned = false
        val job = owner.launch("a") { try { awaitCancellation() } finally { cleaned = true } }!!
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertTrue(job.isCancelled); assertTrue(cleaned); assertTrue(isActive)
        var completed = false
        owner.launch("a") { completed = true }!!.join()
        assertTrue(completed)
    }

    @Test fun navigationBeforeLazyJobStartsCannotBeginProviderOrNetworkWork() = runTest {
        val locked = MutableStateFlow(false); val chat = MutableStateFlow<String?>("a")
        val owner = PrivateMediaOperationOwner(this, locked, chat, { chat.value })
        var calls = 0
        val job = owner.launch("a") { calls++ }!!
        chat.value = "b"; runCurrent(); job.join()
        assertEquals(0, calls)
    }

    @Test fun explicitActivityCloseCancelsOnlyItsOwnedOperation() = runTest {
        val locked = MutableStateFlow(false); val chat = MutableStateFlow<String?>("a")
        val owner = PrivateMediaOperationOwner(this, locked, chat, { chat.value })
        val job = owner.launch("a") { awaitCancellation() }!!
        runCurrent(); owner.cancel(); job.join()
        assertTrue(job.isCancelled); assertTrue(isActive)
    }
}
