package com.example.core.calls

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean

/** Real peer-to-peer voice/video engine. Supabase is used only for signaling. */
class LanuWebRtcCallManager(
    private val context: Context,
    private val signaling: LanuCallSignalingClient,
    private val conversationId: String,
    private val isVideo: Boolean,
    private val onState: (WebRtcCallState) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var peerConnection: PeerConnection? = null
    private var factory: PeerConnectionFactory? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var lastSignalTimestamp: String? = null
    private var started = false
    private var remoteDescriptionSet = AtomicBoolean(false)
    private var pendingIce = mutableListOf<IceCandidate>()

    val localVideoTrack: VideoTrack? get() = videoTrack
    val remoteVideoTrack: VideoTrack? get() = peerConnection?.let { null }
    val eglContext: EglBase.Context? get() = eglBase?.eglBaseContext

    fun attachRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local
        remoteRenderer = remote
        videoTrack?.addSink(local ?: return)
    }

    fun start(outgoing: Boolean) {
        if (started) return
        started = true
        scope.launch {
            try {
                initializePeerConnection()
                pollSignals()
                if (outgoing) createOffer()
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) { onState(WebRtcCallState.FAILED) }
            }
        }
    }

    private fun initializePeerConnection() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        eglBase = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
        )
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory!!.createPeerConnection(rtcConfig, observer) ?: error("WebRTC PeerConnection oluşturulamadı")

        val audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("LANU_AUDIO", audioSource)
        peerConnection!!.addTrack(audioTrack, listOf("LANU_MEDIA"))
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        if (isVideo) createVideoTrack()
        onState(WebRtcCallState.CONNECTING)
    }

    private fun createVideoTrack() {
        val videoSource = factory!!.createVideoSource(false)
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val cameraName = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        val helper = SurfaceTextureHelper.create("LANU-Capture", eglBase!!.eglBaseContext)
        capturer = enumerator.createCapturer(cameraName, null)
        capturer?.initialize(helper, context, videoSource.capturerObserver)
        capturer?.startCapture(1280, 720, 24)
        videoTrack = factory!!.createVideoTrack("LANU_VIDEO", videoSource)
        localRenderer?.let { videoTrack?.addSink(it) }
        peerConnection?.addTrack(videoTrack, listOf("LANU_MEDIA"))
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onState(WebRtcCallState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.CHECKING -> onState(WebRtcCallState.RECONNECTING)
                PeerConnection.IceConnectionState.FAILED -> onState(WebRtcCallState.FAILED)
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch {
                runCatching {
                    signaling.send(conversationId, currentUserId(), "ice", JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp))
                }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let { track -> remoteRenderer?.let { track.addSink(it) } }
        }
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            receiver.track()?.let { track ->
                if (track is VideoTrack) remoteRenderer?.let { track.addSink(it) }
            }
        }
    }

    private suspend fun createOffer() = suspendCancellableCoroutine<Unit> { cont ->
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        scope.launch {
                            signaling.send(conversationId, currentUserId(), "offer", JSONObject()
                                .put("type", desc.type.canonicalForm())
                                .put("sdp", desc.description))
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                    }
                    override fun onSetFailure(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                }, desc)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error)) }
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private suspend fun pollSignals() {
        while (scope.isActive && started) {
            runCatching {
                val signals = signaling.poll(conversationId, lastSignalTimestamp)
                for (signal in signals) {
                    lastSignalTimestamp = signal.createdAt
                    if (signal.senderId == currentUserId()) continue
                    when (signal.type) {
                        "offer" -> handleOffer(signal.payload)
                        "answer" -> handleAnswer(signal.payload)
                        "ice" -> handleIce(signal.payload)
                        "hangup", "reject", "busy" -> withContext(Dispatchers.Main) { onState(WebRtcCallState.ENDED) }
                    }
                }
            }
            delay(500)
        }
    }

    private suspend fun handleOffer(payload: JSONObject) = withContext(Dispatchers.Main) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, payload.getString("sdp"))
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet.set(true)
                flushPendingIce()
                createAnswer()
            }
            override fun onSetFailure(error: String) { onState(WebRtcCallState.FAILED) }
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(p0: String?) = Unit
        }, desc)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        scope.launch { signaling.send(conversationId, currentUserId(), "answer", JSONObject().put("type", "answer").put("sdp", desc.description)) }
                    }
                    override fun onSetFailure(error: String) { onState(WebRtcCallState.FAILED) }
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                }, desc)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { onState(WebRtcCallState.FAILED) }
            override fun onSetFailure(error: String) = Unit
        }, MediaConstraints())
    }

    private suspend fun handleAnswer(payload: JSONObject) = withContext(Dispatchers.Main) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp"))
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { remoteDescriptionSet.set(true); flushPendingIce(); onState(WebRtcCallState.CONNECTING) }
            override fun onSetFailure(error: String) { onState(WebRtcCallState.FAILED) }
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(p0: String?) = Unit
        }, desc)
    }

    private fun handleIce(payload: JSONObject) {
        val candidate = IceCandidate(payload.optString("sdpMid"), payload.getInt("sdpMLineIndex"), payload.getString("candidate"))
        if (remoteDescriptionSet.get()) peerConnection?.addIceCandidate(candidate) else pendingIce += candidate
    }

    private fun flushPendingIce() {
        pendingIce.forEach { peerConnection?.addIceCandidate(it) }
        pendingIce.clear()
    }

    fun setMuted(muted: Boolean) { audioTrack?.setEnabled(!muted) }
    fun setCameraEnabled(enabled: Boolean) { videoTrack?.setEnabled(enabled) }
    fun setSpeaker(enabled: Boolean) { audioManager.isSpeakerphoneOn = enabled }

    fun endCall() {
        scope.launch { runCatching { signaling.send(conversationId, currentUserId(), "hangup", JSONObject()) } }
        close()
    }

    fun close() {
        started = false
        capturer?.let { runCatching { it.stopCapture() }; it.dispose() }
        videoTrack?.dispose(); audioTrack?.dispose()
        peerConnection?.close(); peerConnection?.dispose()
        factory?.dispose(); eglBase?.release()
        scope.cancel()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun currentUserId(): String = "" // replaced by authenticated user id at integration boundary
}
