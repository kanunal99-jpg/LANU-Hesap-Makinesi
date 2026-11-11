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
    fun candidateListener(listener: (String) -> Unit) {}
    suspend fun remoteCandidate(candidate: String) {}
    /** Media is already silenced by Android focus loss before this is called. */
    fun interruptionListener(onHeld: (Boolean) -> Unit, onCameraUnavailable: () -> Unit) {}
    /** Explicit user action after permanent loss; false leaves WebRTC held. */
    suspend fun requestAudioResume(): Boolean = false
    /** A verified remote HOLD/RESUME also invalidates cumulative RTP proof. */
    fun invalidateAfterRemoteHold() {}
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
    // Android calls the focus listener on an arbitrary thread. Keep the native
    // ADM/track changes on the single RTC owner, while disabling the audio
    // track promptly on a focus loss. Neither a notification nor a transient
    // telephone interruption must permanently tear down healthy DTLS/RTP.
    @Volatile private var focusSuppressed = false
    @Volatile private var holdListener: ((Boolean) -> Unit)? = null
    @Volatile private var cameraUnavailableListener: (() -> Unit)? = null
    private var remoteAudio: AudioTrack? = null
    private var cameraPausedForFocus = false
    private var authorizedVideoCall = false

    override fun invalidateAfterRemoteHold() {
        if (!closing.get()) rtpEvidence.invalidateOnHoldTransition()
    }

    override fun interruptionListener(onHeld: (Boolean) -> Unit, onCameraUnavailable: () -> Unit) {
        holdListener = onHeld
        cameraUnavailableListener = onCameraUnavailable
        if (focusSuppressed && !closing.get()) onHeld(true)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        applyAudioFocusChange(change)
    }

    /** Never release a healthy PeerConnection just because SIM/VoIP took focus. */
    private fun applyAudioFocusChange(change: Int) {
        if (closing.get()) return
        when (CallAudioFocusPolicy.classify(change)) {
            CallAudioFocusPolicy.Action.MUTE_AND_HOLD -> {
                if (focusSuppressed) return
                // Disable native output immediately, even before RTC dispatch.
                focusSuppressed = true
                rtpEvidence.invalidateOnHoldTransition()
                runCatching { localAudio?.setEnabled(false) }
                runCatching { remoteAudio?.setEnabled(false) }
                runCatching { localVideo?.setEnabled(false) }
                scope.launch {
                    if (closing.get() || !focusSuppressed) return@launch
                    refreshEffectiveAudioMute()
                    // Release MODE_IN_COMMUNICATION and any selected output
                    // device so SIM/WhatsApp owns its own speaker/Bluetooth.
                    // Native DTLS/ICE and RTP objects stay allocated.
                    runCatching { router.close() }
                    // Send the neutral E2EE busy state immediately after
                    // muting; OEM Camera2.stopCapture may block for seconds.
                    if (!closing.get() && focusSuppressed)
                        runCatching { holdListener?.invoke(true) }
                    // Unblock the camera for a third-party VIDEO call. A
                    // broken OEM stopCapture cannot make us re-enable video.
                    if (videoEnabled && capturer != null && !cameraPausedForFocus) {
                        cameraPausedForFocus = try {
                            capturer!!.stopCapture()
                            true
                        } catch (_: RuntimeException) { false }
                        catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            false
                        } catch (_: LinkageError) { false }
                    }
                }
            }
            CallAudioFocusPolicy.Action.RESTORE_IF_USER_UNMUTED -> {
                if (!focusSuppressed) return
                focusSuppressed = false
                rtpEvidence.invalidateOnHoldTransition()
                scope.launch {
                    if (closing.get() || focusSuppressed) return@launch
                    // Only the authorized still-running call may reacquire
                    // the route, and only after Android grants audio focus.
                    val routeRunning = try {
                        router.start(realCallAuthorized = true,
                            isVideo = authorizedVideoCall).running
                    } catch (_: RuntimeException) { false }
                    catch (_: LinkageError) { false }
                    if (!routeRunning || closing.get() || focusSuppressed ||
                        !refreshEffectiveAudioMute()) {
                        // Another focus-loss callback may race route startup,
                        // or an OEM may reject reopening the audio device.
                        // Keep *all* tracks quiet and the E2EE call held,
                        // without reporting false RESUME or terminating RTP.
                        if (!closing.get()) {
                            focusSuppressed = true
                            refreshEffectiveAudioMute()
                            runCatching { router.close() }
                            holdListener?.invoke(true)
                        }
                        return@launch
                    }
                    if (closing.get() || focusSuppressed) return@launch
                    runCatching { remoteAudio?.setEnabled(true) }
                    if (videoEnabled && cameraPausedForFocus) {
                        // Resume only after Android returns focus and camera
                        // permission is still granted. If an OEM camera stays
                        // unavailable, continue the AUDIO call, video disabled.
                        val restarted = try {
                            check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED)
                            checkNotNull(capturer).startCapture(captureProfile.width,
                                captureProfile.height, captureProfile.fps)
                            true
                        } catch (_: RuntimeException) { false }
                        catch (_: LinkageError) { false }
                        cameraPausedForFocus = false
                        if (!restarted) {
                            videoEnabled = false
                            runCatching { localVideo?.setEnabled(false) }
                            cameraUnavailableListener?.invoke()
                        }
                    }
                    if (videoEnabled && !closing.get() && !focusSuppressed)
                        runCatching { localVideo?.setEnabled(true) }
                    if (!closing.get() && !focusSuppressed) holdListener?.invoke(false)
                }
            }
            CallAudioFocusPolicy.Action.IGNORE -> Unit
        }
    }

    /** User-initiated retry after permanent focus loss; never steal a SIM call. */
    override suspend fun requestAudioResume(): Boolean = withContext(dispatcher) {
        if (closing.get()) return@withContext false
        if (!focusSuppressed) return@withContext true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return@withContext false
        val result = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = focus ?: return@withContext false
                audio.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: RuntimeException) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        catch (_: LinkageError) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            applyAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            true
        } else false // DELAYED may later call GAIN; remain muted until then.
    }

    /**
     * RTC-owner-only transactional mute. A temporarily unavailable OEM
     * AudioRecord during SIM/VoIP handoff is not a broken DTLS session:
     * restore fails CLOSED (remain on hold), never force-end an active call.
     */
    private fun refreshEffectiveAudioMute(): Boolean {
        if (closing.get()) return false
        val muted = CallAudioFocusPolicy.effectiveMute(microphoneMuted, focusSuppressed)
        val track = localAudio ?: return true // ADM not created yet.
        val module = adm ?: return true
        if (muted) {
            runCatching { track.setEnabled(false) }
            runCatching { module.setMicrophoneMute(true) }
            return true
        }
        return try {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "Microphone permission revoked" }
            module.setMicrophoneMute(false)
            check(track.setEnabled(true)) { "Audio track unavailable" }
            true
        } catch (_: RuntimeException) {
            runCatching { track.setEnabled(false) }
            runCatching { module.setMicrophoneMute(true) }
            focusSuppressed = true
            false
        } catch (_: LinkageError) {
            runCatching { track.setEnabled(false) }
            runCatching { module.setMicrophoneMute(true) }
            focusSuppressed = true
            false
        }
    }
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var adm: JavaAudioDeviceModule? = null
    private var egl: EglBase? = null
    private var source: AudioSource? = null
    private var localAudio: AudioTrack? = null
    // Committed only after WebRTC ADM and RTP track accept the same mute state.
    private var microphoneMuted = false
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    // Per-camera token prevents late errors from an abandoned OEM capturer
    // from terminating a newly recovered camera session.
    private val cameraGeneration = AtomicInteger()
    private val cameraSwitchGate = CallCameraSwitchGate()
    private var texture: SurfaceTextureHelper? = null
    private var videoEnabled = false
    private var captureProfile = CallCameraCaptureProfilePolicy.profiles.first()
    // Thread-safe evidence tracker rejects cumulative RTP packets from prior ICE epochs.
    private val rtpEvidence = CallRtpEvidenceTracker()
    private val statsRequestSequence = AtomicLong()
    private val statsSamplingGate = CallStatsSamplingGate()
    // Video-only adaptation never changes the existing Opus/AEC/NS/AGC path.
    private val videoBitratePolicy = AdaptiveVideoBitratePolicy()
    private val remoteStatsGate = RemoteCallStatsGate()
    private var videoStatsEpoch: Long? = null
    @Volatile private var lastCandidateAt = 0L
    @Volatile private var candidateCallback: ((String) -> Unit)? = null
    private var tricklePeer = false
    private var turnConfigured = false
    override fun candidateListener(listener: (String) -> Unit) { candidateCallback = listener }
    override suspend fun remoteCandidate(candidate: String): Unit = withContext(dispatcher) {
        if (closing.get()) return@withContext
        val value = org.json.JSONObject(candidate)
        TrickleIcePolicy.validate(value)
        val pc = connection ?: return@withContext
        // Reject candidates from an old ICE epoch, including late restart callbacks.
        if (!TrickleIcePolicy.matchesEpoch(value, pc.remoteDescription?.description.orEmpty())) return@withContext
        // A valid but unusable optional route must not terminate healthy RTP.
        // SDP candidates and the existing setup deadline remain authoritative.
        pc.addIceCandidate(IceCandidate(value.getString("mid"), value.getInt("index"), value.getString("candidate")))
        Unit
    }
    private var renderers: CallVideoRendererBindings? = null

    override suspend fun start(video: Boolean): Unit = withContext(dispatcher) {
        check(!closing.get())
        authorizedVideoCall = video
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
            nsReported = runCatching { JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported() }.getOrDefault(false),
            preferSoftware = CallAudioRecoveryRegistry.policy.preferSoftware(
                CallAudioPreference(context).preferSoftware()
            )
        )
        fun createAdm(hardwareAec: Boolean, hardwareNs: Boolean): JavaAudioDeviceModule {
            // AudioRecord starts asynchronously after ADM construction. An
            // OEM-specific capture failure can therefore bypass the synchronous
            // factory fallback; remember only a one-bit in-process health flag
            // for the NEXT call. The failing call follows its existing safe
            // error path; no live SDP/track recreation or double AEC/NS.
            fun onCaptureFailure() {
                if (!CallInterruptionFailurePolicy.shouldReportAudioError(
                        closing.get(), focusSuppressed)) return
                val permitted = try {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                } catch (_: RuntimeException) { false }
                if (CallInterruptionFailurePolicy.shouldQuarantineOemCapture(
                        closing.get(), focusSuppressed, hardwareAec || hardwareNs, permitted)) {
                    CallAudioRecoveryRegistry.policy.captureStartupFailed(
                        usingHardware = true,
                        microphonePermissionGranted = permitted,
                        captureStillOwned = !closing.get()
                    )
                }
                audioFailure()
            }
            return JavaAudioDeviceModule.builder(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(hardwareAec)
            .setUseHardwareNoiseSuppressor(hardwareNs)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) = onCaptureFailure()
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = onCaptureFailure()
                override fun onWebRtcAudioRecordError(error: String) = onCaptureFailure()
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) = audioFailure()
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) = audioFailure()
                override fun onWebRtcAudioTrackError(error: String) = audioFailure()
            })
            .createAudioDeviceModule()
        }
        val audioPipeline = CallAudioPipelineFactory.build(audioProcessing,
            createModule = { createAdm(it.hardwareAec, it.hardwareNs) },
            createEngine = { module ->
                PeerConnectionFactory.builder().setAudioDeviceModule(module)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
                    .createPeerConnectionFactory()
            }, releaseModule = { it.release() })
        adm = audioPipeline.first
        factory = audioPipeline.second
        ensureActive(); check(!closing.get())
        // Separate provider protects STUN discovery from a single provider
        // outage. No TURN credentials, hidden billing or guaranteed NAT relay.
        val turn = runCatching { CallTurnConfig(context).read() }.getOrNull()
        turnConfigured = turn != null
        val config = PeerConnection.RTCConfiguration(
            CallIceServerPolicy.stunUrls.map {
                PeerConnection.IceServer.builder(it).createIceServer()
            } + listOfNotNull(turn?.let {
                PeerConnection.IceServer.builder(CallTurnTransportPolicy.urls(it)).setUsername(it.username).setPassword(it.credential).createIceServer()
            })
        ).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        connection = checkNotNull(factory!!.createPeerConnection(config, observer)) { "Bağlantı motoru açılamadı." }
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        source = factory!!.createAudioSource(audioConstraints)
        localAudio = factory!!.createAudioTrack("lanu-audio", source).also {
            // Audio focus may be revoked after requestFocus() but before the
            // microphone track exists. Never open capture in that gap.
            it.setEnabled(!focusSuppressed)
            connection!!.addTrack(it, listOf("lanu-stream"))
        }
        if (focusSuppressed) refreshEffectiveAudioMute()
        ensureActive(); check(!closing.get())
        if (video) startCamera()
        scope.launch {
            while (isActive && !closing.get()) { collectEvidence(); delay(1000) }
        }
    }

    private fun audioFailure() {
        if (CallInterruptionFailurePolicy.shouldReportAudioError(
                closing.get(), focusSuppressed)) {
            failure("Mikrofon veya ses çıkışı kullanılamadı. İzinleri ve bağlı kulaklığı kontrol edin.")
        }
    }

    private fun requestFocus() {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(true).build()
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
        val cameraAttemptsPerHal = IntArray(enumerators.size)
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
                var selected: CameraVideoCapturer? = null
                // Try a smaller capture format on a NEW camera handle before
                // abandoning an otherwise functional OEM Camera1/Camera2 HAL.
                // All attempts remain user-consented and audio-independent.
                for (profile in CallCameraCaptureProfilePolicy.profiles) {
                    if (selected != null ||
                        !CallCameraCaptureProfilePolicy.mayAttempt(cameraAttemptsPerHal[index])) break
                    if (closing.get()) throw CancellationException("Call camera fallback cancelled")
                    cameraAttemptsPerHal[index]++
                    val generation = cameraGeneration.incrementAndGet()
                    val starting = AtomicBoolean(true)
                    val failed = AtomicBoolean(false)
                    val candidate = enumerators[index].createCapturer(device.name,
                        cameraEvents(generation, starting, failed))
                    val started = CallCameraStartupPolicy.tryStart(
                        candidate,
                        start = { camera ->
                            if (closing.get()) throw CancellationException("Call camera startup cancelled")
                            camera.initialize(texture, context, videoSource!!.capturerObserver)
                            camera.startCapture(profile.width, profile.height, profile.fps)
                            if (closing.get()) throw CancellationException("Call camera startup cancelled")
                            check(!failed.get()) { "Kamera açılışında hata." }
                            starting.set(false)
                        },
                        releaseFailed = { camera ->
                            cameraGeneration.incrementAndGet()
                            runCatching { camera.stopCapture() }
                            runCatching { camera.dispose() }
                        }
                    )
                    if (started != null) {
                        captureProfile = profile
                        selected = started
                    }
                }
                selected
            }
        )
        if (closing.get()) throw CancellationException("Call camera startup cancelled")
        checkNotNull(capturer) { "Kamera kullanılamadı; sesli aramayı deneyin." }
        videoEnabled = true
        localVideo = factory!!.createVideoTrack("lanu-video", videoSource).also { track ->
            track.setEnabled(!focusSuppressed)
            connection!!.addTrack(track, listOf("lanu-stream"))
            if (focusSuppressed && capturer != null) {
                cameraPausedForFocus = try {
                    capturer!!.stopCapture()
                    true
                } catch (_: RuntimeException) { false }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                catch (_: LinkageError) { false }
            }
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
    private fun cameraFailure() {
        if (closing.get() || focusSuppressed || cameraPausedForFocus || !videoEnabled) return
        // Losing the camera must not force-close healthy encrypted AUDIO RTP.
        // A delayed camera callback is handled only by the current RTC owner.
        scope.launch {
            if (closing.get() || focusSuppressed || cameraPausedForFocus || !videoEnabled)
                return@launch
            videoEnabled = false
            runCatching { localVideo?.setEnabled(false) }
            runCatching { capturer?.stopCapture() }
            cameraUnavailableListener?.invoke()
        }
    }

    override suspend fun offer(restart: Boolean): String = withContext(dispatcher) {
        val pc = checkNotNull(connection)
        if (restart) pc.restartIce()
        lastCandidateAt = 0L
        val constraints = MediaConstraints().apply { if (restart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        val sdp = createSdp { pc.createOffer(it, constraints) }
        setPreferredLocalSdp(pc, sdp)
        if (!tricklePeer) awaitIce(pc)
        TrickleIcePolicy.advertise(checkNotNull(pc.localDescription).description)
    }

    override suspend fun answer(remote: String): String = withContext(dispatcher) {
        val pc = checkNotNull(connection)
        tricklePeer = TrickleIcePolicy.supported(remote)
        setSdp { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remote)) }
        lastCandidateAt = 0L
        val sdp = createSdp { pc.createAnswer(it, MediaConstraints()) }
        setPreferredLocalSdp(pc, sdp)
        if (!tricklePeer) awaitIce(pc)
        TrickleIcePolicy.advertise(checkNotNull(pc.localDescription).description)
    }

    override suspend fun remoteAnswer(remote: String) = withContext(dispatcher) {
        tricklePeer = TrickleIcePolicy.supported(remote)
        setSdp { connection!!.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, remote)) }
    }
    /**
     * Prefer Opus in-band loss recovery but never strand an existing call:
     * older/OEM WebRTC builds that reject the modified SDP use the native
     * offer/answer unchanged. DTX is intentionally not forced on working audio.
     */
    private suspend fun setPreferredLocalSdp(pc: PeerConnection, original: SessionDescription) {
        val preferredText = OpusSdpPolicy.preferReceiveFec(original.description)
        if (preferredText == original.description) {
            setSdp { pc.setLocalDescription(it, original) }
            return
        }
        val preferred = SessionDescription(original.type, preferredText)
        try {
            setSdp { pc.setLocalDescription(it, preferred) }
        } catch (_: IllegalStateException) {
            setSdp { pc.setLocalDescription(it, original) }
        }
    }

    private suspend fun awaitIce(pc: PeerConnection) {
        // Non-trickle SDP must contain REAL candidates. Fast path requires a
        // stable server-reflexive candidate IN the current local SDP, not a
        // guessed network address or merely an early ICE COMPLETE callback.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        withTimeoutOrNull(CallIceGatherPolicy.GATHER_TIMEOUT_MS) {
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val lastAt = lastCandidateAt
                if (CallIceGatherPolicy.ready(
                    localSdp = pc.localDescription?.description,
                    state = pc.iceGatheringState(),
                    elapsedMs = now - startedAt,
                    lastCandidateAgoMs = lastAt.takeIf { it > 0L }?.let { now - it },
                    preferRelay = turnConfigured
                )) return@withTimeoutOrNull
                delay(50)
            }
        }
        check(CallIceGatherPolicy.hasUsableCandidate(pc.localDescription?.description)) { "Ağ bağlantısı için aday bulunamadı." }
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
        val device = checkNotNull(adm) { "Mikrofon kullanılamıyor." }
        val track = checkNotNull(localAudio) { "Ses kanalı bulunamadı." }
        CallMediaControlTransaction.apply(
            previous = CallAudioFocusPolicy.effectiveMute(microphoneMuted, focusSuppressed),
            requested = CallAudioFocusPolicy.effectiveMute(value, focusSuppressed),
            setDevice = { device.setMicrophoneMute(it) },
            setTrack = { track.setEnabled(!it) }
        )
        check(!closing.get()) { "Görüşme sonlandırıldı." }
        microphoneMuted = value
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
        }
        CallMediaControlTransaction.apply(
            previous = videoEnabled,
            requested = value,
            setDevice = { enabled ->
                if (enabled) camera.startCapture(captureProfile.width,
                    captureProfile.height, captureProfile.fps) else camera.stopCapture()
            },
            setTrack = { track.setEnabled(it) }
        )
        check(!closing.get()) { "Görüşme sonlandırıldı." }
        videoEnabled = value
    }
    override fun switchCamera() {
        if (closing.get()) return
        scope.launch {
            val activeCamera = capturer ?: return@launch
            val ticket = cameraSwitchGate.begin(!closing.get() && videoEnabled)
                ?: return@launch
            try {
                // Camera1/Camera2 may complete this on another thread or
                // throw synchronously on a broken OEM implementation.
                activeCamera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        cameraSwitchGate.finish(ticket)
                    }
                    override fun onCameraSwitchError(errorDescription: String?) {
                        // A camera switch error should not kill the audio call
                        // or surface vendor exception text to the private UI.
                        cameraSwitchGate.finish(ticket)
                    }
                })
                // Some OEMs never send a completion callback. Recover the
                // UI action rather than permanently blocking camera controls.
                scope.launch {
                    delay(5_000)
                    cameraSwitchGate.finish(ticket)
                }
            } catch (cancelled: CancellationException) {
                cameraSwitchGate.finish(ticket)
                throw cancelled
            } catch (_: RuntimeException) {
                cameraSwitchGate.finish(ticket)
            } catch (_: LinkageError) {
                cameraSwitchGate.finish(ticket)
            }
        }
    }
    /** Null clears a manual preference and restores Android call-route priorities. */
    fun chooseRoute(route: CallAudioRoutePolicy.Route?) { scope.launch { router.choose(route) } }
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
        val requestedAtMs = android.os.SystemClock.elapsedRealtime()
        val pc = connection ?: return
        if (!statsSamplingGate.begin(snapshotSequence, requestedAtMs)) return
        try {
        pc.getStats { report ->
            // Callback could arrive late, after a newer sample or call
            // disposal. Never attribute an old report to a fresh ICE epoch.
            if (!statsSamplingGate.finish(snapshotSequence) || closing.get()) return@getStats
            var received = 0L; var sent = 0L
            for (stat in report.statsMap.values) {
                val kind = stat.members["kind"] ?: stat.members["mediaType"]
                if (kind == "audio") {
                    if (stat.type == "inbound-rtp")
                        received = CallRtpCountPolicy.add(received, stat.members["packetsReceived"])
                    if (stat.type == "outbound-rtp")
                        sent = CallRtpCountPolicy.add(sent, stat.members["packetsSent"])
                }
            }
            rtpEvidence.observe(snapshotEpoch, received, sent,
                snapshotSequence = snapshotSequence)?.let(evidence)
            scope.launch {
                if (closing.get() || !videoEnabled || !remoteStatsGate.begin(
                        rtpEvidence.currentEpoch(), snapshotEpoch, snapshotSequence,
                        requestedAtMs, android.os.SystemClock.elapsedRealtime())) return@launch
                if (videoStatsEpoch != snapshotEpoch) {
                    videoStatsEpoch = snapshotEpoch
                    videoBitratePolicy.resetObservations()
                }
                var audioLoss: Double? = null; var audioRtt: Double? = null
                var videoLoss: Double? = null; var videoRtt: Double? = null
                for (stat in report.statsMap.values) {
                    if (stat.type != "remote-inbound-rtp") continue
                    val kind = (stat.members["kind"] ?: stat.members["mediaType"]) as? String ?: continue
                    if (!remoteStatsGate.fresh(kind, stat.timestampUs)) continue
                    val loss = (stat.members["fractionLost"] as? Number)?.toDouble()
                    val rtt = (stat.members["roundTripTime"] as? Number)?.toDouble()?.times(1000.0)
                    if (kind == "audio") { audioLoss = loss; audioRtt = rtt }
                    if (kind == "video") { videoLoss = loss; videoRtt = rtt }
                }
                val voiceFirst = VoiceFirstNetworkPolicy.select(videoLoss, videoRtt, audioLoss, audioRtt)
                if (!voiceFirst.available) return@launch
                val loss = voiceFirst.loss; val rtt = voiceFirst.rttMs
                val now = android.os.SystemClock.elapsedRealtime()
                val target = videoBitratePolicy.recommend(loss, rtt, now) ?: return@launch
                val sender = connection?.senders?.firstOrNull {
                    it.track()?.kind() == "video"
                } ?: return@launch
                val applied = try {
                    val parameters = sender.parameters
                    if (parameters.encodings.isEmpty()) false
                    else {
                        val profile = AdaptiveVideoEncodingPolicy.forBitrate(target)
                        parameters.encodings.forEach {
                            it.maxBitrateBps = target
                            it.maxFramerate = profile.maxFramerate
                            it.scaleResolutionDownBy = profile.scaleResolutionDownBy
                        }
                        sender.setParameters(parameters)
                    }
                } catch (_: RuntimeException) { false }
                  catch (_: LinkageError) { false }
                // A rejected OEM/WebRTC setting is not a successful change.
                // Keep the call and its original encoder running.
                videoBitratePolicy.attemptFinished(target, now, applied)
            }
        }
        } catch (_: RuntimeException) {
            // Optional diagnostics must not terminate real media. The next
            // bounded sample is still permitted on the foreground RTC owner.
            statsSamplingGate.finish(snapshotSequence)
        } catch (_: LinkageError) {
            statsSamplingGate.finish(snapshotSequence)
        }
    }
    private val observer = object : EmptyPeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (closing.get()) return
            lastCandidateAt = android.os.SystemClock.elapsedRealtime()
            // Native reads are serialized with disposal, never performed on a
            // late signaling callback after the peer has been released.
            scope.launch {
                if (closing.get()) return@launch
                val currentUfrag = TrickleIcePolicy.candidateUfrag(connection?.localDescription?.description.orEmpty(),
                    candidate.sdpMid ?: "0", candidate.sdpMLineIndex)
                val advertisedUfrag = Regex("(?:^| )ufrag ([^ ]+)").find(candidate.sdp)?.groupValues?.get(1)
                if (advertisedUfrag != null && advertisedUfrag != currentUfrag) return@launch
                val value = org.json.JSONObject().put("mid", candidate.sdpMid ?: "0")
                    .put("index", candidate.sdpMLineIndex).put("candidate", candidate.sdp)
                    .put("ufrag", currentUfrag)
                if (runCatching { TrickleIcePolicy.validate(value) }.isSuccess) candidateCallback?.invoke(value.toString())
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closing.get()) return
            // Only fresh inbound AND outbound RTP after this transport epoch
            // can restore the "Connected" label following an ICE interruption.
            val action = CallIceConnectionPolicy.classify(state)
            val mediaEvidence = rtpEvidence.updateTransport(
                action == CallIceConnectionPolicy.Action.MEDIA_CONNECTED
            )
            // Only a native FAILED callback expedites recovery. A merely
            // DISCONNECTED handover retains normal grace for self-healing.
            evidence(if (state == PeerConnection.PeerConnectionState.FAILED)
                mediaEvidence.copy(iceFailed = true) else mediaEvidence)
            // FAILED/DISCONNECTED are recoverable for one authenticated,
            // foreground-owned ICE restart. An immediate failure callback here
            // would race its evidence callback and terminate the call first.
            // The ViewModel already imposes its 25-second recovery deadline.
            if (action == CallIceConnectionPolicy.Action.TERMINATE)
                failure("Bağlantı kapandı. Yeniden aramayı deneyin.")
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (closing.get()) return
            if (track is VideoTrack) scope.launch { remoteVideo = track; updateBindings() }
            if (track is AudioTrack) scope.launch {
                remoteAudio = track
                if (focusSuppressed) runCatching { track.setEnabled(false) }
            }
        }
    }

    /** Immediate mute, then release camera, renderers, codecs and audio off the UI thread. */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        focusSuppressed = true
        holdListener = null
        cameraUnavailableListener = null
        statsSamplingGate.invalidate()
        candidateCallback = null
        cameraSwitchGate.invalidate()
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
