package com.example.core.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Explicit opt-in. No caller, contact, conversation, E2EE, SDP, token or PIN
 * data may be put into notification preferences, intents or notification UI.
 */
object CallWakePreferences {
    private const val STORE = "lanu_call_wake_v1"
    private const val OPTED_IN = "opted_in"
    fun isEnabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(OPTED_IN, false)
    } catch (_: Exception) { false }
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(OPTED_IN, enabled).commit()
    } catch (_: Exception) { false }
}

/** Reject stale, unsupported or arbitrary FCM payloads without fetching private data. */
object CallWakePolicy {
    const val TYPE = "lanu_call_wake_v1"
    fun eligible(data: Map<String, String>, now: Long): Boolean {
        if (data.size != 2 || data["type"] != TYPE) return false
        val created = data["at"]?.toLongOrNull() ?: return false
        return created > 0 && created in (now - 65_000L)..(now + 15_000L)
    }
}

object CallWakeNotification {
    private const val CHANNEL_ID = "lanu_private_calls_v1"
    private const val NOTIFICATION_ID = 8129

    fun show(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean {
        if (!CallWakePreferences.isEnabled(context) || !CallWakePolicy.eligible(data, now)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val channel = NotificationChannel(CHANNEL_ID, "LANU gelen çağrılar", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Yalnızca isteğe bağlı gelen sesli/görüntülü arama uyarıları"
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(channel)

        // No call ID/peer ID/hidden-navigation extras: opening the notification
        // always follows the ordinary calculator -> 2011. -> optional PIN gate.
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Gelen arama")
            .setContentText("LANU uygulamasını açarak kontrol edin")
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setTimeoutAfter(75_000L)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) { false }
          catch (_: RuntimeException) { false }
    }
}
