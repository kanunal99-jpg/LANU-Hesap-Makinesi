package com.example.core.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real native WebRTC. No fabricated connection, level, noise dB or latency. */
interface CallMediaSession {
    suspend fun start(video: Boolean)
    suspend fun offer(restart: Boolean = false): String
    suspend fun answer(remote: String): String
    suspend fun remoteAnswer(remote: String)
    fun mute(value: Boolean)
    fun camera(value: Boolean)
    fun switchCamera()
    fun close()
}

class NativeCallEngine(
    context: Context,
    private val evidence: (CallMediaEvidence) -> Unit,
    private val failure: (String) -> Unit
) : CallMediaSession {
    private val context = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "LANU-RTC") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ ->
        if (!closing.get()) failure("Görüşme kaynağı kullanılamadı.")
    })
    private val closing = AtomicBoolean(false)
    val router = AndroidCallAudioRouter(this.context)
    private val audio = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private var ownsFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { value ->
        if (value == AudioManager.AUDIOFOCUS_LOSS || value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            if (!closing.get()) failure("Ses başka bir görüşmeye ayrıldı; arama sonlandırıldı.")
    }
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var adm: JavaAudioDeviceModule? = null
    private var egl: EglBase? = null
    private var source: AudioSource? = null
    private var localAudio: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var videoEnabled = false
    @Volatile private var transportConnected = false
    @Volatile private var lastCandidateAt = 0L
    private var renderers: CallVideoRendererBindings? = null

    override suspend fun start(video: Boolean): Unit = withContext(dispatcher) {
        check(!closing.get())
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Mikrofon izni gerekiyor." }
        if (video) check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Kamera izni gerekiyor." }
        initialize(context)
        requestFocus()
        router.start(realCallAuthorized = true, isVideo = video)
        egl = EglBase.create()
        renderers = CallVideoRendererBindings(egl!!.eglBaseContext)
        adm = JavaAudioDeviceModule.builder(context).setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) = audioFailure()
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = audioFailure()
                override fun onWebRtcAudioRecordError(error: String) = audioFailure()
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) = audioFailure()
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) = audioFailure()
                override fun onWebRtcAudioTrackError(error: String) = audioFailure()
            })
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext)).createPeerConnectionFactory()
        ensureActive(); check(!closing.get())
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        connection = checkNotNull(factory!!.createPeerConnection(config, observer)) { "Bağlantı motoru açılamadı." }
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        source = factory!!.createAudioSource(audioConstraints)
        localAudio = factory!!.createAudioTrack("lanu-audio", source).also {
            it.setEnabled(true); connection!!.addTrack(it, listOf("lanu-stream"))
        }
        ensureActive(); check(!closing.get())
        if (video) startCamera()
        scope.launch {
            while (isActive && !closing.get()) { collectEvidence(); delay(1000) }
        }
    }

    private fun audioFailure() { if (!closing.get()) failure("Mikrofon veya ses çıkışı kullanılamadı. İzinleri ve bağlı kulaklığı kontrol edin.") }

    private fun requestFocus() {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            audio.requestAudioFocus(focus!!)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Ses odağı alınamadı." }
        ownsFocus = true
    }

    private fun startCamera() {
        val enum: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val ordered = enum.deviceNames.sortedBy { if (enum.isFrontFacing(it)) 0 else 1 }
        capturer = ordered.firstNotNullOfOrNull { enum.createCapturer(it, cameraEvents) }
            ?: Camera1Enumerator(false).let { fallback -> fallback.deviceNames.firstNotNullOfOrNull { fallback.createCapturer(it, cameraEvents) } }
        checkNotNull(capturer) { "Kamera kullanılamadı; sesli aramayı deneyin." }
        videoSource = factory!!.createVideoSource(false)
        texture = SurfaceTextureHelper.create("LANU-Camera", egl!!.eglBaseContext)
        capturer!!.initialize(texture, context, videoSource!!.capturerObserver)
        check(!closing.get())
        capturer!!.startCapture(1280, 720, 24)
        videoEnabled = true
        localVideo = factory!!.createVideoTrack("lanu-video", videoSource).also { track ->
            track.setEnabled(true)
            connection!!.addTrack(track, listOf("lanu-stream"))
        }
        updateBindings()
    }

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String) = cameraFailure()
        override fun onCameraDisconnected() = cameraFailure()
        override fun onCameraFreezed(error: String) = cameraFailure()
        override fun onCameraOpening(name: String) {}
        override fun onFirstFrameAvailable() {}
        override fun onCameraClosed() {}
    }
    private fun cameraFailure() { if (!closing.get()) failure("Kamera kullanılamadı; sesli aramayı deneyin.") }

    override suspend fun offer(restart: Boolean): String = withContext(dispatcher) {
        val pc = checkNotNull(connection)
        if (restart) pc.restartIce()
        lastCandidateAt = 0L
        val constraints = MediaConstraints().apply { if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        val sdp = createSdp { pc.createOffer(it, constraints) }
        setSdp { pc.setLocalDescription(it, sdp) }
        awaitIce(pc)
        checkNotNull(pc.localDescription).description
    }

    override suspend fun answer(remote: String): String = withContext(dispatcher) {
        val pc = checkNotNull(connection)
        setSdp { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remote)) }
        lastCandidateAt = 0L
        val sdp = createSdp { pc.createAnswer(it, MediaConstraints()) }
        setSdp { pc.setLocalDescription(it, sdp) }
        awaitIce(pc)
        checkNotNull(pc.localDescription).description
    }

    override suspend fun remoteAnswer(remote: String) = withContext(dispatcher) {
        setSdp { connection!!.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, remote)) }
    }
    private suspend fun awaitIce(pc: PeerConnection) {
        // Android's network callback can arrive AFTER an initial empty COMPLETE.
        // Non-trickle SDP must include real candidates, never that empty snapshot.
        withTimeoutOrNull(8_000) {
            while (isActive) {
                val hasCandidate = pc.localDescription?.description?.contains("a=candidate:") == true
                if (hasCandidate && pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE &&
                    android.os.SystemClock.elapsedRealtime() - lastCandidateAt >= 250) return@withTimeoutOrNull
                delay(50)
            }
        }
        check(pc.localDescription?.description?.contains("a=candidate:") == true) { "Ağ bağlantısı için aday bulunamadı." }
    }
    private suspend fun createSdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { c ->
        start(object : EmptySdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) { if (c.isActive) c.resume(sdp) }
            override fun onCreateFailure(error: String?) { if (c.isActive) c.resumeWithException(IllegalStateException("Görüşme teklifi hazırlanamadı.")) }
        })
    }
    private suspend fun setSdp(start: (SdpObserver) -> Unit) = suspendCancellableCoroutine<Unit> { c ->
        start(object : EmptySdpObserver() {
            override fun onSetSuccess() { if (c.isActive) c.resume(Unit) }
            override fun onSetFailure(error: String?) { if (c.isActive) c.resumeWithException(IllegalStateException("Görüşme bağlantısı doğrulanamadı.")) }
        })
    }

    override fun mute(value: Boolean) { scope.launch { adm?.setMicrophoneMute(value); localAudio?.setEnabled(!value) } }
    override fun camera(value: Boolean) { scope.launch {
        if (closing.get()) return@launch
        if (value == videoEnabled) return@launch
        if (value) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            capturer?.startCapture(1280,720,24)
        } else capturer?.stopCapture()
        videoEnabled = value
        localVideo?.setEnabled(value)
    } }
    override fun switchCamera() { scope.launch { capturer?.switchCamera(null) } }
    fun chooseRoute(route: CallAudioRoutePolicy.Route) { scope.launch { router.choose(route) } }
    fun refreshRoutes() { scope.launch { router.refresh() } }

    fun bind(renderer: SurfaceViewRenderer, remote: Boolean) {
        if (closing.get()) return
        scope.launch { if (!closing.get()) renderers?.bind(renderer, remote) }
    }
    fun unbind(renderer: SurfaceViewRenderer) {
        if (closing.get()) return
        scope.launch { renderers?.unbind(renderer) }
    }
    private fun updateBindings() {
        scope.launch { renderers?.tracks(localVideo, remoteVideo) }
    }

    private fun collectEvidence() {
        connection?.getStats { report ->
            if (closing.get()) return@getStats
            var received = 0L; var sent = 0L
            for (stat in report.statsMap.values) {
                val kind = stat.members["kind"] ?: stat.members["mediaType"]
                if (kind != "audio") continue
                if (stat.type == "inbound-rtp") received += (stat.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                if (stat.type == "outbound-rtp") sent += (stat.members["packetsSent"] as? Number)?.toLong() ?: 0L
            }
            evidence(CallMediaEvidence(transportConnected, received, sent))
        }
    }
    private val observer = object : EmptyPeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) { lastCandidateAt = android.os.SystemClock.elapsedRealtime() }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            transportConnected = state == PeerConnection.PeerConnectionState.CONNECTED
            if (state == PeerConnection.PeerConnectionState.FAILED && !closing.get()) failure("Ağlar arasında doğrudan bağlantı kurulamadı. Farklı bir ağ deneyin.")
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED) evidence(CallMediaEvidence())
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack && !closing.get()) scope.launch { remoteVideo = track; updateBindings() }
        }
    }

    /** Immediate mute, then release camera, renderers, codecs and audio off the UI thread. */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching { localAudio?.setEnabled(false) }; runCatching { localVideo?.setEnabled(false) }
        scope.launch {
            try {
                runCatching { renderers?.close() }
                runCatching { capturer?.stopCapture() }; runCatching { capturer?.dispose() }
                runCatching { connection?.close() }; runCatching { connection?.dispose() }
                runCatching { localAudio?.dispose() }; runCatching { source?.dispose() }
                runCatching { localVideo?.dispose() }; runCatching { videoSource?.dispose() }
                runCatching { texture?.dispose() }; runCatching { factory?.dispose() }; runCatching { adm?.release() }
                runCatching { router.close() }; runCatching { egl?.release() }
                if (ownsFocus) runCatching {
                    if (Build.VERSION.SDK_INT >= 26) focus?.let { audio.abandonAudioFocusRequest(it) }
                    else { @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener) }
                }
            } finally { scope.cancel(); dispatcher.close() }
        }
    }
    companion object {
        @Volatile private var initialized = false
        @Synchronized fun initialize(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                .createInitializationOptions())
            initialized = true
        }
    }
}

open class EmptySdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
open class EmptyPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
}
