package com.example.core.calls

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WebRtcCallState {
    IDLE,
    OUTGOING,
    RINGING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ENDED,
    FAILED
}

class CallManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main)

    val dspProcessor = AudioDspProcessor(context)
    val syncEngine = MediaSyncEngine()

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isCameraOn = MutableStateFlow(true)
    val isCameraOn: StateFlow<Boolean> = _isCameraOn.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(true)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    fun startOutgoingCall(roomId: String, isVideo: Boolean) {
        _callState.value = WebRtcCallState.OUTGOING
        scope.launch {
            kotlinx.coroutines.delay(800)
            _callState.value = WebRtcCallState.RINGING
            kotlinx.coroutines.delay(1000)
            _callState.value = WebRtcCallState.CONNECTING
            kotlinx.coroutines.delay(1000)
            _callState.value = WebRtcCallState.CONNECTED
        }
    }

    fun startIncomingCall(roomId: String, isVideo: Boolean) {
        _callState.value = WebRtcCallState.RINGING
    }

    fun acceptCall() {
        _callState.value = WebRtcCallState.CONNECTING
        scope.launch {
            kotlinx.coroutines.delay(1000)
            _callState.value = WebRtcCallState.CONNECTED
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleCamera() {
        _isCameraOn.value = !_isCameraOn.value
    }

    fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
    }

    fun simulatePoorConnection() {
        _callState.value = WebRtcCallState.RECONNECTING
        syncEngine.onNetworkDegraded()
        scope.launch {
            kotlinx.coroutines.delay(2500)
            syncEngine.onNetworkRecovered()
            _callState.value = WebRtcCallState.CONNECTED
        }
    }

    fun triggerHandoverIceRestart(onComplete: () -> Unit = {}) {
        syncEngine.triggerIceRestart(onComplete)
    }

    fun failCall() {
        _callState.value = WebRtcCallState.FAILED
    }

    fun endCall() {
        _callState.value = WebRtcCallState.ENDED
    }

    fun resetToIdle() {
        _callState.value = WebRtcCallState.IDLE
    }
}
