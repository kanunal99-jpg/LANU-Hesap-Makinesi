package com.example.core.calls

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.IncomingCallActivity
import com.example.LanuApplication
import com.example.R
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
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = (application as LanuApplication).callModel
        if (intent?.action == ACTION_END) {
            model.end()
            stopSelf()
            return START_NOT_STICKY
        }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        try {
            createChannel()
            val content = PendingIntent.getActivity(this, 10,
                Intent(this, IncomingCallActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val end = PendingIntent.getService(this, 11,
                Intent(this, CallForegroundService::class.java).setAction(ACTION_END),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("LANU görüşmesi")
                .setContentText("Görüşme sürüyor")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(R.mipmap.ic_launcher, "Bitir", end)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                startForeground(ONGOING_ID, notification, type)
            } else startForeground(ONGOING_ID, notification)
            isRunning = true
        } catch (_: RuntimeException) {
            model.reportServiceUnavailable()
            model.end()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                model.state.collect { state ->
                    if (state.phase == NativeCallPhase.ENDED || state.phase == NativeCallPhase.FAILED)
                        stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL, "LANU etkin görüşme", NotificationManager.IMPORTANCE_LOW))
        }
    }
    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "lanu_active_call_v1"
        private const val ONGOING_ID = 8130
        private const val EXTRA_VIDEO = "lanu_call_video"
        private const val ACTION_END = "com.aistudio.lanucalc.kxwzqm.action.LANU_CALL_END"
        @Volatile var isRunning: Boolean = false
            private set
        fun start(context: Context, video: Boolean): Boolean = try {
            ContextCompat.startForegroundService(context,
                Intent(context, CallForegroundService::class.java).putExtra(EXTRA_VIDEO, video))
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
