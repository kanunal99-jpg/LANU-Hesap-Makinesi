package com.example.core.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.pm.PackageManager
import com.example.core.calls.CallAudioRoutePolicy.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * Android voice/video-call audio route controller (API 23+).
 *
 * Not started from the current demo: a future REAL WebRTC AudioDeviceModule
 * must own its lifecycle and call start() only after call setup and user
 * microphone authorization. This component does not capture/transmit sound,
 * assert headset ANC support, or request Bluetooth pairing.
 *
 * API 31+: AudioManager communication-device API with selected-device check.
 * API 23-30: platform wired routing/speaker and asynchronous Bluetooth SCO.
 * Never claim a pending Bluetooth SCO request is a connected audio route.
 */
class AndroidCallAudioRouter(context: Context) : Closeable {
    data class State(
        val running: Boolean = false,
        val selected: Route? = null,
        val pending: Route? = null,
        val available: Set<Route> = emptySet(),
        val status: String = "NO_ACTIVE_CALL"
    )

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var running = false
    private var videoCall = false
    private var preferred: Route? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scoTimeout: Runnable? = null
    private var scoConfirmed = false
    private var scoRequested = false
    private var scoFailed = false
    private var callbackRegistered = false
    private var receiverRegistered = false
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var communicationGeneration = 0L
    private var communicationRequestId = 0L
    private var pendingCommunicationDeviceId: Int? = null
    private var pendingCommunicationRoute: Route? = null
    private var communicationTimeout: Runnable? = null
    // Session-local only. OEM rejects are retried after device changes or
    // a deliberate user route selection, never in a tight loop.
    private val rejectedCommunicationIds = mutableSetOf<Int>()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            onDeviceListChanged()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            onDeviceListChanged()
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            if (!running || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    synchronized(this@AndroidCallAudioRouter) {
                        if (scoRequested) {
                            scoConfirmed = true
                            cancelScoTimeout()
                            _state.value = _state.value.copy(
                                selected = Route.BLUETOOTH_HEADSET,
                                pending = null,
                                status = "ROUTED"
                            )
                        }
                    }
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    synchronized(this@AndroidCallAudioRouter) {
                        // A sticky DISCONNECTED broadcast often precedes an SCO
                        // connection attempt. It does not mean that negotiation
                        // has already failed. An explicit timeout handles that.
                        if (scoRequested && scoConfirmed) {
                            scoFailed = true
                            stopSco()
                            selectRoute()
                        }
                    }
                }
            }
        }
    }

    /**
     * Fail closed before the actual media owner confirms a permitted call.
     * This does not create a microphone stream, audio focus or an ICE session.
     */
    @Synchronized
    fun start(realCallAuthorized: Boolean, isVideo: Boolean): State {
        if (!realCallAuthorized) return _state.value
        if (running) return _state.value
        videoCall = isVideo
        preferred = null
        scoFailed = false
        previousMode = try { audioManager.mode } catch (_: RuntimeException) { null }
        @Suppress("DEPRECATION")
        try { previousSpeaker = audioManager.isSpeakerphoneOn } catch (_: RuntimeException) {
            previousSpeaker = null
        }
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: SecurityException) {
            resetBeforeStart()
            return State(status = "PERMISSION_DENIED").also { _state.value = it }
        } catch (_: RuntimeException) {
            resetBeforeStart()
            return State(status = "AUDIO_MODE_UNAVAILABLE").also { _state.value = it }
        }
        running = true
        communicationGeneration++
        rejectedCommunicationIds.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val generation = communicationGeneration
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                synchronized(this@AndroidCallAudioRouter) {
                    if (!CallCommunicationDeviceConfirmationPolicy.validCallCallback(
                            running, communicationGeneration, generation
                        )) return@synchronized
                    if (CallCommunicationDeviceConfirmationPolicy.confirmed(
                            pendingCommunicationDeviceId, device?.id
                        ) && device != null &&
                        availableOutputDevices().any { it.id == device.id } &&
                        (pendingCommunicationRoute != Route.BLUETOOTH_HEADSET ||
                            hasBluetoothAccess())) {
                        val route = pendingCommunicationRoute ?: return@synchronized
                        clearPendingCommunication()
                        _state.value = State(true, route, null, _state.value.available, "ROUTED")
                    }
                    // Never claim an old/unrelated callback confirmed this route.
                }
            }
            try {
                audioManager.addOnCommunicationDeviceChangedListener(
                    appContext.mainExecutor, listener
                )
                communicationListener = listener
            } catch (_: RuntimeException) {
                // Refresh can confirm the route; timeout remains bounded.
            }
        }
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            callbackRegistered = true
        } catch (_: RuntimeException) { /* manual refresh still works */ }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(
                        scoReceiver, filter, Context.RECEIVER_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(scoReceiver, filter)
                }
                receiverRegistered = true
            } catch (_: RuntimeException) { /* no false SCO connected report */ }
        }
        return selectRoute()
    }

    @Synchronized
    fun choose(route: Route?): State {
        if (!running) return _state.value
        preferred = route
        // On old platforms an explicit retry may be requested by the user.
        if (route == Route.BLUETOOTH_HEADSET) scoFailed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            rejectedCommunicationIds.clear() // intentional OEM recovery retry
        // An explicit route tap must still reapply the device, allowing a user
        // to recover a stuck OEM path even if the route label is unchanged.
        return selectRoute(forceReapply = true)
    }

    @Synchronized
    fun refresh(): State = if (running) selectRoute() else _state.value

    @Synchronized
    override fun close() {
        if (!running) return
        running = false
        communicationGeneration++ // reject callbacks from the ended call
        clearPendingCommunication()
        rejectedCommunicationIds.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationListener?.let { listener ->
                try { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
                catch (_: RuntimeException) { }
            }
            communicationListener = null
        }
        if (callbackRegistered) {
            try { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            catch (_: RuntimeException) { }
            callbackRegistered = false
        }
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(scoReceiver) }
            catch (_: RuntimeException) { }
            receiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: RuntimeException) { }
        } else {
            stopSco()
            @Suppress("DEPRECATION")
            try { previousSpeaker?.let { audioManager.isSpeakerphoneOn = it } }
            catch (_: RuntimeException) { }
        }
        try { previousMode?.let { audioManager.mode = it } }
        catch (_: RuntimeException) { }
        previousMode = null
        previousSpeaker = null
        preferred = null
        scoFailed = false
        _state.value = State()
    }

    private fun resetBeforeStart() {
        previousMode = null
        previousSpeaker = null
        preferred = null
        running = false
    }

    private fun onDeviceListChanged() {
        synchronized(this) {
            if (!running) return
            scoFailed = false // new hardware may be a working route
            rejectedCommunicationIds.clear()
            selectRoute()
        }
    }

    private fun classify(type: Int): Route? = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> Route.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> Route.BLUETOOTH_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Route.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Route.SPEAKER
        else -> null // A2DP-only output is not a bidirectional call microphone
    }

    private fun availableOutputDevices(): List<AudioDeviceInfo> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.filter { it.isSink }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        }
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun hasBluetoothAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    private fun cancelScoTimeout() {
        scoTimeout?.let { mainHandler.removeCallbacks(it) }
        scoTimeout = null
    }

    private fun scheduleScoTimeout() {
        cancelScoTimeout()
        val timeout = Runnable {
            synchronized(this) {
                if (!running || !scoRequested || scoConfirmed) return@synchronized
                scoFailed = true
                stopSco()
                selectRoute() // earpiece/speaker fallback rather than eternal "pending"
            }
        }
        scoTimeout = timeout
        mainHandler.postDelayed(timeout, 8_000L)
    }

    private fun clearPendingCommunication() {
        communicationTimeout?.let { mainHandler.removeCallbacks(it) }
        communicationTimeout = null
        pendingCommunicationDeviceId = null
        pendingCommunicationRoute = null
    }

    private fun scheduleCommunicationTimeout(deviceId: Int, requestId: Long) {
        val generation = communicationGeneration
        val startedAt = SystemClock.elapsedRealtime()
        val timeout = object : Runnable {
            override fun run() {
                synchronized(this@AndroidCallAudioRouter) {
                    if (!CallCommunicationDeviceConfirmationPolicy.validCallCallback(
                            running, communicationGeneration, generation
                        ) || pendingCommunicationDeviceId != deviceId ||
                        communicationRequestId != requestId) return

                    val now = SystemClock.elapsedRealtime()
                    if (!CallCommunicationDeviceConfirmationPolicy.expired(startedAt, now)) {
                        // Handler uses uptime rather than elapsed realtime:
                        // protect against a callback running before the bound.
                        mainHandler.postDelayed(this,
                            (CallCommunicationDeviceConfirmationPolicy.CONFIRMATION_TIMEOUT_MS -
                                (now - startedAt).coerceAtLeast(0L)).coerceAtLeast(1L))
                        return
                    }
                    // A device may have become active without a listener
                    // callback (OEM error); check before discarding real audio.
                    val currentId = try { audioManager.communicationDevice?.id }
                        catch (_: RuntimeException) { null }
                    if (CallCommunicationDeviceConfirmationPolicy.confirmed(deviceId, currentId) &&
                        availableOutputDevices().any { it.id == deviceId }) {
                        val route = pendingCommunicationRoute
                        clearPendingCommunication()
                        if (route != null) {
                            _state.value = State(true, route, null, _state.value.available, "ROUTED")
                            return
                        }
                    }

                    // A timed-out request must not start an unbounded retry
                    // loop. Try a distinct real output or system default.
                    clearPendingCommunication()
                    rejectedCommunicationIds.add(deviceId)
                    try { audioManager.clearCommunicationDevice() }
                    catch (_: RuntimeException) { /* bounded fallback still runs */ }
                    selectRoute()
                }
            }
        }
        communicationTimeout = timeout
        mainHandler.postDelayed(timeout,
            CallCommunicationDeviceConfirmationPolicy.CONFIRMATION_TIMEOUT_MS)
    }

    private fun selectRoute(forceReapply: Boolean = false): State {
        if (!running) return _state.value
        val devices = availableOutputDevices()
        // Android 12+ Nearby Devices permission is requested only when a real
        // call needs Bluetooth. Built-in/wired calls must remain usable when
        // the user declines Bluetooth access.
        val bluetoothAllowed = hasBluetoothAccess()
        val available = devices.mapNotNull { classify(it.type) }
            .filter { it != Route.BLUETOOTH_HEADSET || bluetoothAllowed }.toSet()
        val candidates = CallAudioRoutePolicy.priorities(available, videoCall, preferred)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ may call this again while wired/Bluetooth devices are
            // unchanged. Clearing a correctly selected device causes an
            // unnecessary audio-path interruption during an active call.
            val active = try { audioManager.communicationDevice }
            catch (_: RuntimeException) { null }
            val activeRoute = active?.let { classify(it.type) }
            val pendingId = pendingCommunicationDeviceId
            if (!forceReapply && pendingId != null) {
                if (CallCommunicationDeviceConfirmationPolicy.confirmed(
                        pendingId, active?.id
                    ) && devices.any { it.id == pendingId } &&
                    (pendingCommunicationRoute != Route.BLUETOOTH_HEADSET || bluetoothAllowed)) {
                    val confirmedRoute = pendingCommunicationRoute
                    clearPendingCommunication()
                    if (confirmedRoute != null)
                        return State(true, confirmedRoute, null, available, "ROUTED")
                            .also { _state.value = it }
                } else if (CallCommunicationDeviceConfirmationPolicy.stillPreferred(
                        pendingId, pendingCommunicationRoute,
                        devices.map { it.id }.toSet(), candidates
                    )) {
                    return State(true, null, pendingCommunicationRoute, available, "ROUTE_PENDING")
                        .also { _state.value = it }
                }
            }
            clearPendingCommunication()
            if (!forceReapply && CallAudioRoutePolicy.keepActiveCommunicationDevice(
                    activeRoute,
                    active != null && devices.any { it.id == active.id } &&
                        active.id !in rejectedCommunicationIds,
                    candidates
                )) {
                return State(true, activeRoute, null, available, "ROUTED")
                    .also { _state.value = it }
            }
            // Android can switch to the next device directly. Only an
            // explicit retry of the same route requires resetting the
            // possibly stuck OEM selection first.
            if (CallAudioRoutePolicy.needsExplicitRouteReset(
                    forceReapply, activeRoute, candidates.firstOrNull()
                )) {
                try { audioManager.clearCommunicationDevice() }
                catch (_: RuntimeException) { /* still attempt direct routing */ }
            }
            for (route in candidates) {
                val matching = devices.filter { classify(it.type) == route }
                for (device in matching) {
                    if (device.id in rejectedCommunicationIds ||
                        (route == Route.BLUETOOTH_HEADSET && !bluetoothAllowed)) continue
                    // Reserve the pending request first: some OEM callbacks
                    // can be delivered while a new route is being selected.
                    pendingCommunicationDeviceId = device.id
                    pendingCommunicationRoute = route
                    val requestId = ++communicationRequestId
                    val accepted = try { audioManager.setCommunicationDevice(device) }
                        catch (_: RuntimeException) { false }
                    if (!accepted) {
                        clearPendingCommunication()
                        rejectedCommunicationIds.add(device.id)
                        continue
                    }
                    val selectedId = try { audioManager.communicationDevice?.id }
                        catch (_: RuntimeException) { null }
                    if (CallCommunicationDeviceConfirmationPolicy.confirmed(
                            device.id, selectedId
                        ) || pendingCommunicationDeviceId == null) {
                        clearPendingCommunication()
                        return State(true, route, null, available, "ROUTED")
                            .also { _state.value = it }
                    }
                    scheduleCommunicationTimeout(device.id, requestId)
                    return State(true, null, route, available, "ROUTE_PENDING")
                        .also { _state.value = it }
                }
            }
            // All requests failed: release a stale selection to let Android
            // restore its system-default route instead of keeping dead audio.
            try { audioManager.clearCommunicationDevice() }
            catch (_: RuntimeException) { /* SYSTEM_DEFAULT is still truthful */ }
        } else {
            if (scoRequested && Route.BLUETOOTH_HEADSET !in available) stopSco()
            for (route in candidates) {
                try {
                    when (route) {
                        Route.BLUETOOTH_HEADSET -> {
                            if (scoFailed || !receiverRegistered ||
                                !audioManager.isBluetoothScoAvailableOffCall) continue
                            @Suppress("DEPRECATION")
                            if (!scoRequested) {
                                audioManager.startBluetoothSco()
                                scoRequested = true
                                scoConfirmed = false
                                scheduleScoTimeout()
                            }
                            if (scoConfirmed) {
                                return State(true, route, null, available, "ROUTED")
                                    .also { _state.value = it }
                            }
                            // Must wait for SCO_AUDIO_STATE_CONNECTED broadcast.
                            return State(true, null, route, available, "BLUETOOTH_PENDING")
                                .also { _state.value = it }
                        }
                        Route.WIRED_HEADSET, Route.EARPIECE, Route.SPEAKER -> {
                            stopSco()
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = route == Route.SPEAKER
                            @Suppress("DEPRECATION")
                            val matches = audioManager.isSpeakerphoneOn ==
                                (route == Route.SPEAKER)
                            if (matches) return State(
                                true, route, null, available,
                                "PLATFORM_ROUTE_REQUESTED"
                            ).also { _state.value = it }
                        }
                    }
                } catch (_: SecurityException) {
                    // Missing permission must not crash the active call.
                } catch (_: RuntimeException) {
                    // Broken vendor audio HAL: fall back to next output.
                }
            }
        }
        return State(true, null, null, available, "SYSTEM_DEFAULT")
            .also { _state.value = it }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoRequested) return
        scoRequested = false
        scoConfirmed = false
        cancelScoTimeout()
        try { audioManager.stopBluetoothSco() } catch (_: RuntimeException) { }
    }
}
