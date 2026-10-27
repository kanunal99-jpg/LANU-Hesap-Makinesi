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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real native WebRTC. No fabricated connection, level, noise dB or latency. */
interface CallMediaSession {
    suspend fun start(video: Boolean)
    suspend fun offer(restart: Boolean = false): String
    suspend fun answer(remote: String): String
    suspend fun remoteAnswer(remote: String)
    suspend fun mute(value: Boolean)
    suspend fun camera(value: Boolean)
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
    // Per-camera token prevents late errors from an abandoned OEM capturer
    // from terminating a newly recovered camera session.
    private val cameraGeneration = AtomicInteger()
    private var texture: SurfaceTextureHelper? = null
    private var videoEnabled = false
    // Thread-safe evidence tracker rejects cumulative RTP packets from prior ICE epochs.
    private val rtpEvidence = CallRtpEvidenceTracker()
    private val statsRequestSequence = AtomicLong()
    @Volatile private var lastCandidateAt = 0L
    private var renderers: CallVideoRendererBindings? = null

    override suspend fun start(video: Boolean): Unit = withContext(dispatcher) {
        check(!closing.get())
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Mikrofon izni gerekiyor." }
        if (video) check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "Kamera izni gerekiyor." }
        initialize(context)
        requestFocus()
        router.start(realCallAuthorized = true, isVideo = video)
        egl = WebRtcEglFactory.create()
        renderers = CallVideoRendererBindings(egl!!.eglBaseContext)
        // Query AEC and NS independently. WebRTC ADM selects its software APM
        // path if an OEM lacks a built-in effect; do not stack a second Android
        // AudioEffect or report an unmeasured dB/ANC improvement.
        val audioProcessing = CallAudioProcessingPolicy.choose(
            aecReported = runCatching { JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported() }.getOrDefault(false),
            nsReported = runCatching { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() }.getOrDefault(false)
        )
        adm = JavaAudioDeviceModule.builder(context).setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(audioProcessing.hardwareAec)
            .setUseHardwareNoiseSuppressor(audioProcessing.hardwareNs)
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
        // Separate provider protects STUN discovery from a single provider
        // outage. No TURN credentials, hidden billing or guaranteed NAT relay.
        val config = PeerConnection.RTCConfiguration(
            CallIceServerPolicy.stunUrls.map {
                PeerConnection.IceServer.builder(it).createIceServer()
            }
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
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
        // Closing is an aborted user session, never a recoverable camera HAL
        // failure. The fallback policy preserves CancellationException.
        if (closing.get()) throw CancellationException("Call camera startup cancelled")
        // On some OEM devices the Camera2 capability probe, enumeration, or
        // individual createCapturer call can throw even when Camera2 reports
        // supported. Prefer Camera2, but actually enumerate/open Camera1 as
        // a fallback rather than treating a capability flag as success.
        val enumerators = buildList<CameraEnumerator> {
            if (runCatching { Camera2Enumerator.isSupported(context) }.getOrDefault(false)) {
                runCatching { Camera2Enumerator(context) }.getOrNull()?.let { add(it) }
            }
            runCatching { Camera1Enumerator(false) }.getOrNull()?.let { add(it) }
        }
        // The camera may exist yet fail synchronously during initialize/open.
        // Create one source/texture, then retry *real* Camera2 or Camera1
        // capturers and dispose each failed candidate before moving on.
        videoSource = factory!!.createVideoSource(false)
        texture = checkNotNull(SurfaceTextureHelper.create("LANU-Camera", egl!!.eglBaseContext)) {
            "Kamera yüzeyi açılamadı."
        }
        capturer = CallCameraFallback.select(
            enumerate = enumerators.map { cameraEnumerator ->
                {
                    if (closing.get()) throw CancellationException("Call camera enumeration cancelled")
                    cameraEnumerator.deviceNames.map { device ->
                        CallCameraFallback.Device(
                            device,
                            runCatching { cameraEnumerator.isFrontFacing(device) }.getOrDefault(false)
                        )
                    }
                }
            },
            open = { index, device ->
                if (closing.get()) throw CancellationException("Call camera fallback cancelled")
                val generation = cameraGeneration.incrementAndGet()
                // Some OEM callbacks report startup failure before startCapture
                // returns. Retry that camera rather than closing the entire call.
                val starting = AtomicBoolean(true)
                val failed = AtomicBoolean(false)
                val candidate = enumerators[index].createCapturer(device.name,
                    cameraEvents(generation, starting, failed))
                CallCameraStartupPolicy.tryStart(
                    candidate,
                    start = { camera ->
                        if (closing.get()) throw CancellationException("Call camera startup cancelled")
                        camera.initialize(texture, context, videoSource!!.capturerObserver)
                        camera.startCapture(1280, 720, 24)
                        if (closing.get()) throw CancellationException("Call camera startup cancelled")
                        check(!failed.get()) { "Kamera açılışında hata." }
                        starting.set(false)
                    },
                    releaseFailed = { camera ->
                        // Invalidate late callbacks before disposing the failed
                        // native camera, then retry an actual alternate HAL.
                        cameraGeneration.incrementAndGet()
                        runCatching { camera.stopCapture() }
                        runCatching { camera.dispose() }
                    }
                )
            }
        )
        if (closing.get()) throw CancellationException("Call camera startup cancelled")
        checkNotNull(capturer) { "Kamera kullanılamadı; sesli aramayı deneyin." }
        videoEnabled = true
        localVideo = factory!!.createVideoTrack("lanu-video", videoSource).also { track ->
            track.setEnabled(true)
            connection!!.addTrack(track, listOf("lanu-stream"))
        }
        updateBindings()
    }

    private fun cameraEvents(
        generation: Int, starting: AtomicBoolean, failed: AtomicBoolean
    ) = object : CameraVideoCapturer.CameraEventsHandler {
        private fun activeFailure() {
            if (generation != cameraGeneration.get()) return
            failed.set(true)
            if (!starting.get()) cameraFailure()
        }
        override fun onCameraError(error: String) = activeFailure()
        override fun onCameraDisconnected() = activeFailure()
        override fun onCameraFreezed(error: String) = activeFailure()
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

    // A suspended control completes only after the native media operation
    // actually succeeded. The UI must never optimistically claim mute/camera.
    override suspend fun mute(value: Boolean): Unit = withContext(dispatcher) {
        check(!closing.get()) { "Görüşme sonlandırıldı." }
        checkNotNull(adm) { "Mikrofon kullanılamıyor." }.setMicrophoneMute(value)
        check(checkNotNull(localAudio) { "Ses kanalı bulunamadı." }.setEnabled(!value)) {
            "Mikrofon durumu değiştirilemedi."
        }
        check(!closing.get()) { "Görüşme sonlandırıldı." }
    }
    override suspend fun camera(value: Boolean): Unit = withContext(dispatcher) {
        check(!closing.get()) { "Görüşme sonlandırıldı." }
        if (value == videoEnabled) return@withContext
        val camera = checkNotNull(capturer) { "Kamera akışı açılamadı." }
        val track = checkNotNull(localVideo) { "Görüntü kanalı bulunamadı." }
        if (value) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Kamera izni gerekiyor."
            }
            camera.startCapture(1280, 720, 24)
        } else camera.stopCapture()
        check(!closing.get()) { "Görüşme sonlandırıldı." }
        check(track.setEnabled(value)) { "Kamera durumu değiştirilemedi." }
        videoEnabled = value
    }
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
        // Capture transport epoch BEFORE async getStats, not inside its callback.
        // Otherwise a late old result could be attributed to a newer connection.
        val snapshotEpoch = rtpEvidence.currentEpoch()
        val snapshotSequence = statsRequestSequence.incrementAndGet()
        connection?.getStats { report ->
            if (closing.get()) return@getStats
            var received = 0L; var sent = 0L
            for (stat in report.statsMap.values) {
                val kind = stat.members["kind"] ?: stat.members["mediaType"]
                if (kind != "audio") continue
                if (stat.type == "inbound-rtp")
                    received = CallRtpCountPolicy.add(received, stat.members["packetsReceived"])
                if (stat.type == "outbound-rtp")
                    sent = CallRtpCountPolicy.add(sent, stat.members["packetsSent"])
            }
            rtpEvidence.observe(snapshotEpoch, received, sent,
                snapshotSequence = snapshotSequence)?.let(evidence)
        }
    }
    private val observer = object : EmptyPeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) { lastCandidateAt = android.os.SystemClock.elapsedRealtime() }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closing.get()) return
            // Only fresh inbound AND outbound RTP after this transport epoch
            // can restore the "Connected" label following an ICE interruption.
            val action = CallIceConnectionPolicy.classify(state)
            evidence(rtpEvidence.updateTransport(
                action == CallIceConnectionPolicy.Action.MEDIA_CONNECTED
            ))
            // FAILED/DISCONNECTED are recoverable for one authenticated,
            // foreground-owned ICE restart. An immediate failure callback here
            // would race its evidence callback and terminate the call first.
            // The ViewModel already imposes its 25-second recovery deadline.
            if (action == CallIceConnectionPolicy.Action.TERMINATE)
                failure("Bağlantı kapandı. Yeniden aramayı deneyin.")
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
/**
 * Explicitly implement every WebRTC Observer default callback instead of relying on
 * the Android 23 default-interface desugaring companion (Observer$-CC).
 *
 * CI 40203471771 proved the missing companion throws NoClassDefFoundError
 * on native's signaling thread after setLocalDescription(answer), then JNI
 * terminates the entire process with SIGABRT. R8 stays ENABLED.
 * This adapter is shared by production peers and release instrumentation peers.
 */
@androidx.annotation.Keep
open class EmptyPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidate(candidate: IceCandidate) {}
    override fun onIceCandidateError(event: IceCandidateErrorEvent) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(channel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
    override fun onRemoveTrack(receiver: RtpReceiver) {}
    override fun onTrack(transceiver: RtpTransceiver) {}
}
