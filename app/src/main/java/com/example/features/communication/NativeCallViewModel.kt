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
import java.util.UUID

enum class NativeCallPhase { IDLE, DIALING, RINGING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED }
data class NativeCallUiState(
    val callId: String = "", val name: String = "", val video: Boolean = false,
    val phase: NativeCallPhase = NativeCallPhase.IDLE, val muted: Boolean = false,
    val cameraOn: Boolean = true, val message: String = "", val connectedAt: Long = 0L
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
    }
    private var session: Session? = null
    private var foreground = false
    private var receiving: Job? = null
    private var activePoll: Job? = null
    private var pipeline: Job? = null
    private var starting: Job? = null
    private var timeout: Job? = null
    private val workJobs = mutableSetOf<Job>()
    private val seen = LinkedHashSet<String>()

    fun activate() {
        if (foreground) return
        foreground = true
        repository.setCallingForeground(true)
        receiving = viewModelScope.launch {
            repository.callSignals.collect { handle(it) }
        }
        viewModelScope.launch {
            try { repository.prepareCalling() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Existing foreground chat sync will retry; no idle extra loop. */ }
        }
    }

    fun deactivate() {
        foreground = false
        repository.setCallingForeground(false)
        repository.setCallInProgress(false)
        receiving?.cancel(); receiving = null
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
        val engine = _media.value ?: return
        val value = !_state.value.muted
        engine.mute(value); _state.value = _state.value.copy(muted = value)
    }
    fun camera() {
        val engine = _media.value ?: return
        val value = !_state.value.cameraOn
        engine.camera(value); _state.value = _state.value.copy(cameraOn = value)
    }
    fun switchCamera() { _media.value?.switchCamera() }

    private fun handle(incoming: ReceivedCallSignal) {
        val s = incoming.signal
        if (!foreground || !seen.add(s.id)) return
        while (seen.size > 512) seen.remove(seen.first())
        if (s.kind == CallSignalKind.INVITE) {
            if (System.currentTimeMillis() - s.createdAt > 45_000) return
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
                    engine.start(current.video)
                    send(current, CallSignalKind.OFFER, engine.offer())
                }
            }
            CallSignalKind.OFFER -> if (!current.outgoing && _state.value.phase in setOf(
                NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING)) {
                // The caller can receive ACCEPT before our HTTP response comes back.
                // Retain that OFFER instead of ACKing and losing it while ACCEPT is pending.
                if (current.offers >= 2) return // Initial negotiation plus one ICE restart.
                current.offers++
                val previous = pipeline
                pipeline = guarded(current) {
                    previous?.join()
                    if (session !== current || !foreground) return@guarded
                    val engine = _media.value ?: createEngine(current).also {
                        _media.value = it; it.start(current.video)
                    }
                    send(current, CallSignalKind.ANSWER, engine.answer(s.sdp))
                }
            }
            CallSignalKind.ANSWER -> if (current.outgoing && _media.value != null) {
                pipeline = guarded(current) { _media.value?.remoteAnswer(s.sdp) }
            }
            CallSignalKind.REJECT -> finish("Karşı taraf aramayı reddetti.", notify = false)
            CallSignalKind.BUSY -> finish("Karşı taraf başka bir aramada.", notify = false)
            CallSignalKind.END -> finish("Karşı taraf görüşmeyi sonlandırdı.", notify = false)
            else -> Unit
        }
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
                    pipeline = guarded(current) { send(current, CallSignalKind.OFFER, _media.value!!.offer(restart = true)) }
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
                try { repository.pollCallSignals().forEach(::handle) }
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
        session = null
        repository.setCallInProgress(false)
        pipeline?.cancel(); starting?.cancel(); activePoll?.cancel(); timeout?.cancel()
        workJobs.toList().forEach { it.cancel() }; workJobs.clear()
        _media.value?.close(); _media.value = null
        val previous = _state.value
        _state.value = previous.copy(phase = NativeCallPhase.ENDED, message = message)
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
