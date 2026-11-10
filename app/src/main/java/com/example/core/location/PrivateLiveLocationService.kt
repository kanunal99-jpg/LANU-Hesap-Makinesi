package com.example.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.LanuApplication
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs

/**
 * User-initiated, call-independent foreground-only location sharing.
 *
 * No coordinates, recipient IDs, session IDs or private chat text ever enter
 * notification, Intent extras outside this non-exported service, logs or
 * plaintext preferences. All updates use CommunicationRepository.sendMessage:
 * the established per-peer authenticated E2EE/Room/outbox chain.
 *
 * Only ONE live session per app process. Death/restart FAILS CLOSED: no restart,
 * re-enrollment, boot receiver or background auto-tracking. Receiver checks
 * expiry/freshness; a missing terminal message never promises ongoing tracking.
 */
class PrivateLiveLocationService : Service() {
    data class State(
        val active: Boolean = false,
        val conversationId: String? = null,
        val startedAtMs: Long = 0L,
        val expiresAtMs: Long = 0L,
        val lastFixAtMs: Long = 0L,
        val pointsSent: Int = 0,
        val status: String = ""
    )
    private class Session(
        val id: String,
        val conversationId: String,
        val precise: Boolean,
        val startedAtMs: Long,
        val expiresAtMs: Long,
        val durationMs: Long
    ) {
        var sequence = 0
        var lastQueuedElapsedMs = Long.MIN_VALUE
        var writing = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private var session: Session? = null
    private var locationListener: LocationListener? = null
    private var expiryJob: Job? = null
    private var firstFixJob: Job? = null
    private var healthJob: Job? = null
    private var stopInProgress = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing("Paylaşım durduruldu", sendEnd = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (session != null || stopInProgress) return START_NOT_STICKY
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
                val duration = intent.getLongExtra(EXTRA_DURATION, -1L)
                val precise = intent.getBooleanExtra(EXTRA_PRECISE, false)
                if (conversationId.isNullOrBlank() || conversationId.length > 180 ||
                    duration !in PrivateLiveLocationCodec.durations ||
                    !permissionsGranted(precise)) {
                    reject("Konum veya bildirim izni yok")
                    return START_NOT_STICKY
                }
                if (!hasInternet()) {
                    reject("İnternet bağlantısı yok")
                    return START_NOT_STICKY
                }
                val start = System.currentTimeMillis()
                val end = start + duration
                val fresh = Session(UUID.randomUUID().toString(), conversationId,
                    precise, start, end, duration)
                try {
                    createChannel()
                    val notification = notification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    else startForeground(NOTIFICATION_ID, notification)
                } catch (_: RuntimeException) {
                    reject("Konum paylaşımı başlatılamadı")
                    return START_NOT_STICKY
                }
                session = fresh
                _state.value = State(true, conversationId, start, end,
                    status = "Bağlanıyor")
                expiryJob?.cancel()
                expiryJob = scope.launch {
                    delay(duration)
                    if (session === fresh) stopSharing("Süre doldu", sendEnd = true)
                }
                // Verify the recipient binding through the existing E2EE
                // repository BEFORE location capture; the relay receives only
                // ciphertext and cannot substitute another conversation.
                scope.launch {
                    try {
                        val repo = (application as LanuApplication).container.communicationRepository
                        repo.sendMessage(fresh.conversationId,
                            PrivateLiveLocationCodec.encode(
                                PrivateLiveLocationCodec.Event(
                                    PrivateLiveLocationCodec.Kind.START, fresh.id, 0,
                                    fresh.startedAtMs, fresh.expiresAtMs
                                )
                            ))
                        if (session !== fresh) return@launch
                        // No plaintext, no alternate transport and no fallback
                        // that could bypass the user's selected peer.
                        startLocationUpdates(fresh)
                        runCatching { repo.sendQueuedForeground() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (session === fresh)
                            stopSharing("Güvenli sohbet doğrulanamadı", sendEnd = false)
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                // Android may recreate a service with null/stale intent.
                // Fail closed; never silently resume background tracking.
                reject("Konum paylaşımı durduruldu")
                return START_NOT_STICKY
            }
        }
    }

    private fun permissionsGranted(precise: Boolean): Boolean {
        val coarse = ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notification = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return coarse && (!precise || fine) && notification
    }

    private fun hasInternet(): Boolean {
        return try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: RuntimeException) { false }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(active: Session) {
        if (session !== active || !permissionsGranted(active.precise)) {
            stopSharing("Konum izni değişti", sendEnd = true)
            return
        }
        try {
            // Never mislabel a network fix as user-selected precise GPS.
            // Check provider availability inside the guarded block: OEM
            // LocationManager implementations may throw while toggling GPS.
            val selection = PrivateLiveLocationCodec.chooseProvider(
                precise = active.precise,
                gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER),
                networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
            val provider = when (selection) {
                PrivateLiveLocationCodec.ProviderChoice.GPS -> LocationManager.GPS_PROVIDER
                PrivateLiveLocationCodec.ProviderChoice.NETWORK -> LocationManager.NETWORK_PROVIDER
                null -> throw IllegalStateException("Seçilen konum sağlayıcısı kapalı")
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (session === active) onFix(active, location)
                }
                override fun onProviderDisabled(provider: String) {
                    if (session === active)
                        stopSharing("Konum servisi kapatıldı", sendEnd = true)
                }
            }
            locationListener = listener
            locationManager.requestLocationUpdates(
                provider, PrivateLiveLocationCodec.intervalMs(active.durationMs),
                0f, listener, Looper.getMainLooper()
            )
            _state.value = _state.value.copy(status = "Canlı konum etkin")
            // A silent indoor GPS or missing network fix must never leave a
            // forever-active session that has not delivered a single point.
            firstFixJob?.cancel()
            firstFixJob = scope.launch {
                delay(90_000L)
                if (session === active && active.sequence == 0)
                    stopSharing("İlk konum alınamadı", sendEnd = true)
            }
            healthJob?.cancel()
            healthJob = scope.launch {
                while (isActive && session === active) {
                    delay(30_000L)
                    if (session !== active) return@launch
                    if (!permissionsGranted(active.precise)) {
                        stopSharing("Konum veya bildirim izni kaldırıldı", sendEnd = true)
                        return@launch
                    }
                    if (!hasInternet()) {
                        stopSharing("İnternet bağlantısı kesildi", sendEnd = true)
                        return@launch
                    }
                    val last = active.lastQueuedElapsedMs
                    val elapsed = SystemClock.elapsedRealtime()
                    val allowedGap = PrivateLiveLocationCodec.intervalMs(active.durationMs) * 4
                    if (last != Long.MIN_VALUE && elapsed - last > allowedGap) {
                        stopSharing("Konum güncellemeleri kesildi", sendEnd = true)
                        return@launch
                    }
                }
            }
        } catch (_: SecurityException) {
            stopSharing("Konum izni kaldırıldı", sendEnd = true)
        } catch (_: RuntimeException) {
            stopSharing("Konum sağlayıcısı kullanılamıyor", sendEnd = true)
        }
    }

    private fun onFix(active: Session, location: Location) {
        if (session !== active || stopInProgress) return
        val now = System.currentTimeMillis()
        if (now >= active.expiresAtMs) {
            stopSharing("Süre doldu", sendEnd = true)
            return
        }
        if (!permissionsGranted(active.precise)) {
            stopSharing("Konum izni kaldırıldı", sendEnd = true)
            return
        }
        if (active.sequence >= PrivateLiveLocationCodec.MAX_POINTS_PER_SESSION - 1) {
            stopSharing("Paylaşım sınırına ulaşıldı", sendEnd = true)
            return
        }
        if (active.writing || !location.hasAccuracy() ||
            location.accuracy <= 0f || location.accuracy > 15_000f) return
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() -
            location.elapsedRealtimeNanos) / 1_000_000L
        if (fixAgeMs < 0L || fixAgeMs > 45_000L ||
            abs(now - location.time) > 120_000L) return
        val elapsed = SystemClock.elapsedRealtime()
        val minInterval = PrivateLiveLocationCodec.intervalMs(active.durationMs)
        if (active.lastQueuedElapsedMs != Long.MIN_VALUE &&
            elapsed - active.lastQueuedElapsedMs < minInterval) return
        if (!hasInternet()) {
            stopSharing("İnternet bağlantısı yok", sendEnd = true)
            return
        }
        val point = runCatching {
            PrivateLocationCodec.fromDevice(
                location.latitude, location.longitude, location.accuracy,
                location.time, !active.precise
            )
        }.getOrNull() ?: return
        active.writing = true
        scope.launch {
            try {
                if (session !== active || !permissionsGranted(active.precise) ||
                    System.currentTimeMillis() >= active.expiresAtMs) return@launch
                val next = active.sequence + 1
                val payload = PrivateLiveLocationCodec.encode(
                    PrivateLiveLocationCodec.Event(
                        PrivateLiveLocationCodec.Kind.POINT, active.id, next,
                        active.startedAtMs, active.expiresAtMs, point
                    )
                )
                val repo = (application as LanuApplication).container.communicationRepository
                repo.sendMessage(active.conversationId, payload)
                active.sequence = next
                firstFixJob?.cancel()
                firstFixJob = null
                active.lastQueuedElapsedMs = SystemClock.elapsedRealtime()
                _state.value = _state.value.copy(
                    lastFixAtMs = point.capturedAtMillis,
                    pointsSent = next, status = "Canlı konum etkin"
                )
                // Best-effort transport over the same authenticated encrypted
                // outbox. Network failure stops capture rather than accumulating
                // an unbounded history of undelivered live coordinates.
                repo.sendQueuedForeground()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (session === active)
                    stopSharing("Bağlantı doğrulanamadı", sendEnd = true)
            } finally {
                active.writing = false
            }
        }
    }

    private fun stopSharing(reason: String, sendEnd: Boolean) {
        val old = session
        if (stopInProgress) return
        stopInProgress = true
        session = null
        expiryJob?.cancel()
        expiryJob = null
        firstFixJob?.cancel()
        firstFixJob = null
        healthJob?.cancel()
        healthJob = null
        locationListener?.let {
            try { locationManager.removeUpdates(it) }
            catch (_: SecurityException) { }
            catch (_: RuntimeException) { }
        }
        locationListener = null
        _state.value = State(status = reason)
        scope.launch {
            // Stop is a per-recipient E2EE terminal marker, NOT a remote wipe
            // of coordinates that the recipient has already received.
            if (old != null && sendEnd) try {
                val stoppedAt = System.currentTimeMillis()
                    .coerceIn(old.startedAtMs, old.expiresAtMs)
                val payload = PrivateLiveLocationCodec.encode(
                    PrivateLiveLocationCodec.Event(
                        PrivateLiveLocationCodec.Kind.STOP, old.id,
                        (old.sequence + 1).coerceAtMost(
                            PrivateLiveLocationCodec.MAX_POINTS_PER_SESSION
                        ), old.startedAtMs, old.expiresAtMs,
                        stoppedAtMs = stoppedAt
                    )
                )
                val repo = (application as LanuApplication).container.communicationRepository
                repo.sendMessage(old.conversationId, payload)
                runCatching { repo.sendQueuedForeground() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network loss may delay STOP; the receiver must rely on the
                // authenticated expiry and fresh-update test in all cases.
            }
            stopSelf()
        }
    }

    private fun reject(reason: String) {
        _state.value = State(status = reason)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL, "LANU etkin işlemler", NotificationManager.IMPORTANCE_LOW
                ))
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 405,
            Intent(this, PrivateLiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 406,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LANU")
            .setContentText("Etkin paylaşım · durdurmak için bildirimi kullanın")
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(R.mipmap.ic_launcher, "Durdur", stop)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSharing("Uygulama kapatıldı", sendEnd = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        expiryJob?.cancel()
        firstFixJob?.cancel()
        healthJob?.cancel()
        locationListener?.let {
            try { locationManager.removeUpdates(it) }
            catch (_: RuntimeException) { }
        }
        locationListener = null
        session = null
        // Keep the user-facing fail-closed reason until the next explicit
        // session; onDestroy must not overwrite it with a generic status.
        if (_state.value.active) _state.value = State(status = "Paylaşım sona erdi")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "lanu_live_location_v1"
        private const val NOTIFICATION_ID = 4137
        private const val ACTION_START = "com.aistudio.lanucalc.action.PRIVATE_LOCATION_START"
        private const val ACTION_STOP = "com.aistudio.lanucalc.action.PRIVATE_LOCATION_STOP"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PRECISE = "precise"
        private val _state = MutableStateFlow(State())
        val state = _state.asStateFlow()

        /** Invoke only from the visible, PIN-unlocked private chat after
         * user taps Start and grants permissions. Service independently checks. */
        fun start(context: Context, conversationId: String,
            durationMs: Long, precise: Boolean): Boolean {
            if (conversationId.isBlank() ||
                durationMs !in PrivateLiveLocationCodec.durations ||
                _state.value.active) return false
            return try {
                ContextCompat.startForegroundService(context,
                    Intent(context, PrivateLiveLocationService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_CONVERSATION, conversationId)
                        .putExtra(EXTRA_DURATION, durationMs)
                        .putExtra(EXTRA_PRECISE, precise))
                true
            } catch (_: RuntimeException) { false }
        }

        fun stop(context: Context): Boolean = try {
            context.startService(Intent(context, PrivateLiveLocationService::class.java)
                .setAction(ACTION_STOP))
            true
        } catch (_: RuntimeException) { false }
    }
}
