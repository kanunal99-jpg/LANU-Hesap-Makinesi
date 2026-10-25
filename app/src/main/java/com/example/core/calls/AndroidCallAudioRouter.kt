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
            return _state.value.copy(status = "PERMISSION_DENIED")
        } catch (_: RuntimeException) {
            resetBeforeStart()
            return _state.value.copy(status = "AUDIO_MODE_UNAVAILABLE")
        }
        running = true
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
        return selectRoute()
    }

    @Synchronized
    fun refresh(): State = if (running) selectRoute() else _state.value

    @Synchronized
    override fun close() {
        if (!running) return
        running = false
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

    private fun selectRoute(): State {
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
            try { audioManager.clearCommunicationDevice() }
            catch (_: RuntimeException) { /* try other available routes */ }
            for (route in candidates) {
                val matching = devices.filter { classify(it.type) == route }
                for (device in matching) {
                    val applied = try {
                        audioManager.setCommunicationDevice(device) &&
                            audioManager.communicationDevice?.id == device.id
                    } catch (_: RuntimeException) { false }
                    if (applied) {
                        return State(true, route, null, available, "ROUTED")
                            .also { _state.value = it }
                    }
                }
            }
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
