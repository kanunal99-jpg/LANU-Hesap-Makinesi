package com.example.core.calls

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.net.Uri
import java.util.UUID
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.IncomingCallActivity
import com.example.LanuApplication
import com.example.R
import com.example.core.notifications.PublicNotificationText
import com.example.core.notifications.PublicNotificationIds
import com.example.features.communication.NativeCallPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Only an explicitly accepted/started and runtime-permitted call can start
 * this microphone/camera foreground service. FCM hints never start capture.
 * It retains the application-scoped call while calculator/private UI pauses.
 */
class CallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var ownedCallId: String? = null
    private var activeRequestToken: String? = null
    private var activeStartId: Int = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as LanuApplication).callModel
        if (intent?.action == ACTION_END) {
            val state = model.state.value
            if (CallForegroundOwnershipPolicy.shouldAcceptEndAction(
                    activeRequestToken, intent.getStringExtra(EXTRA_TOKEN),
                    ownedCallId, state.callId, state.phase)) {
                model.end()
                stopSelfResult(startId)
            }
            // An older notification can never end a newer call.
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank()) return START_NOT_STICKY
        activeRequestToken = token
        activeStartId = startId
        val initial = model.state.value
        ownedCallId = initial.callId.takeIf {
            it.isNotBlank() && CallForegroundOwnershipPolicy.active(initial.phase)
        }
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        val foregroundStarted = CallForegroundPlatformSafety.execute {
            createChannel()
            val content = PendingIntent.getActivity(this, 10,
                Intent(this, IncomingCallActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val end = PendingIntent.getService(this, 11,
                Intent(this, CallForegroundService::class.java)
                    .setAction(ACTION_END)
                    // PendingIntent identity includes URI, not extras. A stale
                    // prior-call "Bitir" must never be rebound to a new call.
                    .setData(Uri.parse("lanu-call-end:$token"))
                    .putExtra(EXTRA_TOKEN, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(PublicNotificationText.TITLE)
                .setContentText(PublicNotificationText.CHANNEL_DESCRIPTION)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(R.mipmap.ic_launcher, "Bitir", end)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                useTypedMediaForegroundService(Build.VERSION.SDK_INT)) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                startForeground(ONGOING_ID, notification, type)
            } else startForeground(ONGOING_ID, notification)
            isRunning = true
        }
        if (!foregroundStarted) {
            model.reportServiceUnavailable()
            model.end()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            // Avoid a permanent microphone-typed service if relay preparation
            // failed before any call session could be created.
            scope.launch {
                delay(60_000L)
                if (ownedCallId == null && model.state.value.phase == NativeCallPhase.IDLE)
                    stopSelfResult(activeStartId)
            }
            scope.launch {
                model.state.collect { state ->
                    // An initial stale ENDED state from the preceding call
                    // must not immediately close the brand-new foreground
                    // service before its new authorized session is created.
                    if (ownedCallId == null && state.callId.isNotBlank() &&
                        CallForegroundOwnershipPolicy.active(state.phase)) {
                        ownedCallId = state.callId
                    }
                    if (CallForegroundOwnershipPolicy.shouldStopForTerminal(
                            ownedCallId, state.callId, state.phase)) {
                        stopSelfResult(activeStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL, PublicNotificationText.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
        }
    }
    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        val model = (application as LanuApplication).callModel
        // A destroyed foreground service must never leave live microphone or
        // video media running without Android's persistent call notification.
        val state = model.state.value
        if (CallForegroundOwnershipPolicy.shouldEndOnDestroy(
                ownedCallId, state.callId, state.phase)) model.end()
        model.releaseIdleHost()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "lanu_active_call_v1"
        // MessageWakeNotification keeps legacy ID 8130 so existing message
        // opt-out semantics survive an OTA without canceling this service.
        internal const val ONGOING_ID = PublicNotificationIds.ACTIVE_CALL
        private const val EXTRA_VIDEO = "lanu_call_video"
        private const val EXTRA_TOKEN = "lanu_call_service_request_token"
        private const val ACTION_END = "com.aistudio.lanucalc.kxwzqm.action.LANU_CALL_END"
        @Volatile var isRunning: Boolean = false
            private set
        // camera/microphone FGS types were added in Android 11 (API 30).
        // Android 10 uses the basic FGS overload, not unsupported type bits.
        fun useTypedMediaForegroundService(api: Int): Boolean = api >= 30
        fun start(context: Context, video: Boolean): Boolean =
            CallForegroundPlatformSafety.execute {
                ContextCompat.startForegroundService(context,
                    Intent(context, CallForegroundService::class.java)
                        .putExtra(EXTRA_VIDEO, video)
                        .putExtra(EXTRA_TOKEN, UUID.randomUUID().toString()))
            }
    }
}
