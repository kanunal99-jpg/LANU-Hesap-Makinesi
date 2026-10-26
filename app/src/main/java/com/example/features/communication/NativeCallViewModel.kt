package com.example.features.communication

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.calls.*
import com.example.core.messaging.RelayException
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.util.UUID

enum class NativeCallPhase { IDLE, DIALING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }
data class NativeCallUiState(
    val callId: String = "", val name: String = "", val video: Boolean = false,
    val phase: NativeCallPhase = NativeCallPhase.IDLE, val muted: Boolean = false,
    val cameraOn: Boolean = false, val message: String = "", val connectedAt: Long = 0L
)

/** One foreground-only real call. Permission dialog callbacks are bound to call IDs. */
class NativeCallViewModel(
    context: Context,
    private val repository: CommunicationRepository,
    private val mediaFactory: ((CallMediaEvidence) -> Unit, (String) -> Unit) -> CallMediaSession =
        { evidence, failure -> NativeCallEngine(context.applicationContext, evidence, failure) }
) : ViewModel() {
    private val _state = MutableStateFlow(NativeCallUiState())
    val state = _state.asStateFlow()
    private val _media = MutableStateFlow<CallMediaSession?>(null)
    val media = _media.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private class Session(val id: String, val peer: CallPeer, val video: Boolean, val outgoing: Boolean) {
        var restarting = false
        var offers = 0
        // Keep only a hash, never SDP/IP metadata or raw offer contents.
        // A retransmission with a fresh envelope UUID is still the same OFFER.
        var lastOfferDigest: ByteArray? = null
        // Only accept one authenticated ANSWER for each locally generated OFFER.
        // A unique replayed ANSWER after CONNECTED must not re-enter stable SDP.
        var awaitingAnswer = false
    }
    private var session: Session? = null
    private var foreground = false
    private var receiving: Job? = null
    // Foreground preparation can suspend on the relay; cancel it on exit so
    // a late response cannot race later call-queue owners or private-area UI.
    private var preparation: Job? = null
    private var activePoll: Job? = null
    private var pipeline: Job? = null
    private var starting: Job? = null
    private var timeout: Job? = null
    private var muteChange: Job? = null
    private var cameraChange: Job? = null
    private val workJobs = mutableSetOf<Job>()
    private val seen = LinkedHashSet<String>()
    // Call-level tombstones complement signal UUID dedupe: a replayed INVITE
    // with a NEW envelope ID cannot reopen an already ended call.
    // Bound both age and size; never persist peer/call metadata to disk.
    private val closedCalls = LinkedHashMap<String, Long>()

    private fun callKey(peerId: String, callId: String) = "$peerId:$callId"

    private fun pruneClosedCalls(now: Long = android.os.SystemClock.elapsedRealtime()) {
        val staleBefore = now - 2 * CallSignaling.TTL_MILLIS
        closedCalls.entries.removeAll { it.value < staleBefore }
        while (closedCalls.size > 512) closedCalls.remove(closedCalls.keys.first())
    }

    private fun recentlyClosed(peerId: String, callId: String): Boolean {
        pruneClosedCalls()
        return callKey(peerId, callId) in closedCalls
    }

    fun activate() {
        if (foreground) return
        foreground = true
        com.example.core.calls.CallWakePolicy.privateAreaVisible = true
        repository.setCallingForeground(true)
        receiving = viewModelScope.launch {
            repository.callSignals.collect { handle(it) }
        }
        preparation = viewModelScope.launch {
            try { repository.prepareCalling() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Existing foreground chat sync will retry; no idle extra loop. */ }
        }
    }

    fun deactivate() {
        foreground = false
        com.example.core.calls.CallWakePolicy.privateAreaVisible = false
        repository.setCallingForeground(false)
        repository.setCallInProgress(false)
        receiving?.cancel(); receiving = null
        preparation?.cancel(); preparation = null
        starting?.cancel()
        finish("Güvenli alandan çıkıldığı için görüşme kapatıldı.")
        _state.value = NativeCallUiState()
        _notice.value = null
    }

    fun outgoing(conversationId: String, video: Boolean, permissionGranted: Boolean) {
        if (!foreground || session != null || starting?.isActive == true) return
        if (!permissionGranted) { _notice.value = "Görüşme için mikrofon, görüntülü arama için kamera izni gerekir."; return }
        starting = viewModelScope.launch {
            var started: Session? = null
            try {
                repository.prepareCalling()
                if (!foreground || session != null) return@launch
                val peer = repository.callPeerForConversation(conversationId)
                val current = Session(UUID.randomUUID().toString(), peer, video, true)
                started = current
                session = current
                repository.setCallInProgress(true)
                _state.value = NativeCallUiState(current.id, peer.name, video, NativeCallPhase.DIALING)
                startActivePoll(current)
                deadline(current, 45_000L, "Arama yanıtlanmadı.")
                send(current, CallSignalKind.INVITE)
            } catch (e: CancellationException) { throw e }
            catch (_: LinkageError) { if (foreground && (started == null || session === started)) fail("Bu cihazda görüşme motoru açılamadı.") }
            catch (e: Exception) { if (foreground && (started == null || session === started)) fail(safeError(e)) }
        }
    }

    fun accept(callId: String, permissionGranted: Boolean) {
        val current = session ?: return
        if (!foreground || current.id != callId || _state.value.phase != NativeCallPhase.RINGING) return
        if (!permissionGranted) { _notice.value = "Mikrofon/kamera izni verilmedi."; finish("İzin verilmedi.", CallSignalKind.REJECT); return }
        _state.value = _state.value.copy(phase = NativeCallPhase.CONNECTING)
        deadline(current, 35_000, "Bağlantı kurulamadı; farklı bir ağ deneyin.")
        pipeline = guarded(current) { send(current, CallSignalKind.ACCEPT) }
    }
    fun reject() = finish("Arama reddedildi.", CallSignalKind.REJECT)
    fun end() = finish("Görüşme sonlandırıldı.")
    fun dismiss() { if (session == null) _state.value = NativeCallUiState(); _notice.value = null }
    fun clearNotice() { _notice.value = null }
    fun muted() {
        val current = session ?: return
        val engine = _media.value ?: return
        if (!foreground || _state.value.phase !in setOf(NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING) || muteChange?.isActive == true) return
        val target = !_state.value.muted
        muteChange = guarded(current) {
            engine.mute(target)
            // Only actual microphone success for the same live call changes UI.
            if (session === current && foreground)
                _state.value = _state.value.copy(muted = target)
        }
    }
    fun camera() {
        val current = session ?: return
        val engine = _media.value ?: return
        if (!foreground || !current.video || _state.value.phase !in setOf(NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING) || cameraChange?.isActive == true) return
        val target = !_state.value.cameraOn
        cameraChange = guarded(current) {
            engine.camera(target)
            // No false camera indication on permission/HAL failure or a late
            // callback from a call already closed in the private area.
            if (session === current && foreground)
                _state.value = _state.value.copy(cameraOn = target)
        }
    }
    fun switchCamera() {
        if (session?.video != true || !foreground || !_state.value.cameraOn ||
            _state.value.phase !in setOf(NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING) ||
            cameraChange?.isActive == true) return
        _media.value?.switchCamera()
    }

    private fun handle(incoming: ReceivedCallSignal) {
        val s = incoming.signal
        if (!foreground || !seen.add(s.id)) return
        while (seen.size > 512) seen.remove(seen.first())
        if (s.kind == CallSignalKind.INVITE) {
            if (System.currentTimeMillis() - s.createdAt > 45_000) return
            // Retry with another signal UUID is still the SAME call. Sending
            // BUSY here would terminate the caller's valid ringing session.
            if (session?.let { it.id == s.callId && it.peer.id == incoming.peer.id } == true) return
            if (recentlyClosed(incoming.peer.id, s.callId)) return
            if (session != null) {
                val busy = Session(s.callId, incoming.peer, s.video, false)
                viewModelScope.launch { runCatching { send(busy, CallSignalKind.BUSY) } }
                return
            }
            val current = Session(s.callId, incoming.peer, s.video, false)
            session = current
            repository.setCallInProgress(true)
            _state.value = NativeCallUiState(s.callId, incoming.peer.name, s.video, NativeCallPhase.RINGING)
            startActivePoll(current)
            deadline(current, 45_000, "Arama süresi doldu.")
            return
        }
        val current = session ?: return
        if (s.callId != current.id || incoming.peer.id != current.peer.id || s.video != current.video) return
        when (s.kind) {
            CallSignalKind.ACCEPT -> if (current.outgoing && _state.value.phase == NativeCallPhase.DIALING) {
                _state.value = _state.value.copy(phase = NativeCallPhase.CONNECTING)
                deadline(current, 35_000, "Bağlantı kurulamadı; farklı bir ağ deneyin.")
                pipeline = guarded(current) {
                    val engine = createEngine(current)
                    _media.value = engine
                    startConfirmedMedia(current, engine)
                    val offerSdp = engine.offer()
                    if (session !== current || !foreground) return@guarded
                    current.awaitingAnswer = true
                    send(current, CallSignalKind.OFFER, offerSdp)
                }
            }
            CallSignalKind.OFFER -> if (!current.outgoing && _state.value.phase in setOf(
                NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING)) {
                // The caller can receive ACCEPT before our HTTP response comes back.
                // Retain that OFFER instead of ACKing and losing it while ACCEPT is pending.
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.sdp.toByteArray(Charsets.UTF_8))
                if (current.lastOfferDigest?.contentEquals(digest) == true) return
                if (current.offers >= 2) return // Initial negotiation plus one genuine ICE restart.
                current.lastOfferDigest = digest
                current.offers++
                val previous = pipeline
                pipeline = guarded(current) {
                    previous?.join()
                    if (session !== current || !foreground) return@guarded
                    val engine = _media.value ?: createEngine(current).also {
                        _media.value = it; startConfirmedMedia(current, it)
                    }
                    val answerSdp = engine.answer(s.sdp)
                    // Native SDP creation can finish after the private area
                    // closed. A stale callback must not signal a dead call.
                    if (session !== current || !foreground) return@guarded
                    send(current, CallSignalKind.ANSWER, answerSdp)
                }
            }
            CallSignalKind.ANSWER -> if (current.outgoing && current.awaitingAnswer &&
                _media.value != null && _state.value.phase in setOf(
                    NativeCallPhase.CONNECTING, NativeCallPhase.RECONNECTING)) {
                // Claim the one outstanding answer slot synchronously on the UI
                // dispatcher. An extra ANSWER with a fresh signal UUID is ignored.
                current.awaitingAnswer = false
                pipeline = guarded(current) { _media.value?.remoteAnswer(s.sdp) }
            }
            CallSignalKind.REJECT -> finish("Karşı taraf aramayı reddetti.", notify = false)
            CallSignalKind.BUSY -> finish("Karşı taraf başka bir aramada.", notify = false)
            CallSignalKind.END -> finish("Karşı taraf görüşmeyi sonlandırdı.", notify = false)
            else -> Unit
        }
    }

    // A requested video call is not an active camera: hardware initialization
    // must finish before the UI can truthfully report a live local camera.
    // A late completion from an ended/hidden call must not revive its state.
    private suspend fun startConfirmedMedia(current: Session, engine: CallMediaSession) {
        engine.start(current.video)
        // Native startup may complete after the user ended the call. Do not
        // continue SDP negotiation with a disposed session or revive its UI.
        if (session !== current || !foreground) {
            throw CancellationException("Call ended while media was starting")
        }
        if (current.video) _state.value = _state.value.copy(cameraOn = true)
    }

    private fun createEngine(current: Session): CallMediaSession = mediaFactory(
        { value -> viewModelScope.launch {
            if (session !== current || !foreground) return@launch
            if (value.connected) {
                timeout?.cancel()
                _state.value = _state.value.copy(phase = NativeCallPhase.CONNECTED,
                    connectedAt = _state.value.connectedAt.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime())
            } else if (_state.value.phase == NativeCallPhase.CONNECTED && !value.transportConnected) {
                _state.value = _state.value.copy(phase = NativeCallPhase.RECONNECTING)
                deadline(current, 25_000, "Ağ bağlantısı kesildi.")
                if (current.outgoing && !current.restarting) {
                    current.restarting = true
                    pipeline = guarded(current) {
                        val engine = _media.value ?: return@guarded
                        val offerSdp = engine.offer(restart = true)
                        if (session !== current || !foreground) return@guarded
                        current.awaitingAnswer = true
                        send(current, CallSignalKind.OFFER, offerSdp)
                    }
                }
            }
        } }, { message -> viewModelScope.launch { if (session === current) fail(message) } })

    private fun guarded(current: Session, block: suspend () -> Unit): Job {
        val job = viewModelScope.launch {
            try { if (session === current && foreground) block() }
            catch (e: CancellationException) { throw e }
            catch (_: LinkageError) { if (session === current) fail("Bu cihazda görüşme motoru açılamadı.") }
            catch (e: Exception) { if (session === current) fail(safeError(e)) }
        }
        workJobs += job
        job.invokeOnCompletion { workJobs -= job }
        return job
    }
    private fun startActivePoll(current: Session) {
        activePoll?.cancel()
        activePoll = viewModelScope.launch {
            while (isActive && session === current && foreground) {
                var delayMs = 2000L
                try { repository.pollCallSignals() /* Repository enqueues before relay ACK; callSignals flow delivers. */ }
                catch (e: CancellationException) { throw e }
                catch (e: RelayException) { if (e.status == 429) delayMs = e.retryAfterMillis.coerceIn(5_000, 120_000) }
                catch (_: Exception) { /* P2P media may continue; native evidence/deadline remains authoritative. */ }
                delay(delayMs)
            }
        }
    }
    private suspend fun send(current: Session, kind: CallSignalKind, sdp: String = "") =
        repository.sendCallSignal(current.peer, CallSignaling.create(current.id, kind, current.video, sdp))
    private fun deadline(current: Session, millis: Long, message: String) {
        timeout?.cancel()
        timeout = viewModelScope.launch { delay(millis); if (session === current) finish(message) }
    }
    private fun fail(message: String) {
        _notice.value = message
        if (session != null) { finish(message); _state.value = _state.value.copy(phase = NativeCallPhase.FAILED) }
    }
    private fun finish(message: String, kind: CallSignalKind = CallSignalKind.END, notify: Boolean = true) {
        val current = session ?: return
        pruneClosedCalls()
        closedCalls[callKey(current.peer.id, current.id)] = android.os.SystemClock.elapsedRealtime()
        pruneClosedCalls()
        session = null
        repository.setCallInProgress(false)
        pipeline?.cancel(); starting?.cancel(); activePoll?.cancel(); timeout?.cancel()
        workJobs.toList().forEach { it.cancel() }; workJobs.clear()
        _media.value?.close(); _media.value = null
        val previous = _state.value
        _state.value = previous.copy(phase = NativeCallPhase.ENDED, message = message, muted = false, cameraOn = false)
        viewModelScope.launch {
            if (notify) runCatching { send(current, kind) }
            if (previous.connectedAt > 0) runCatching {
                repository.addCallLog(current.peer.name, current.peer.code, current.video,
                    ((android.os.SystemClock.elapsedRealtime() - previous.connectedAt) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), current.outgoing)
            }
        }
    }
    private fun safeError(error: Exception) = when {
        error is RelayException && error.status == 409 -> "İki telefonda da güncel LANU güvenli alanı açık olmalı ve kişiler karşılıklı kayıtlı olmalıdır."
        error is RelayException && error.status == 429 -> "Arama isteği sınırına ulaşıldı. Biraz bekleyip yeniden deneyin."
        else -> "Arama kurulamadı. İzinleri ve bağlantıyı kontrol edip yeniden deneyin."
    }
    override fun onCleared() { deactivate(); super.onCleared() }
    class Factory(private val context: Context, private val repository: CommunicationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeCallViewModel(context.applicationContext, repository) as T
    }
}
