package com.example.core.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.media.AudioAttributes
import android.media.RingtoneManager
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
    private const val PENDING_REVOKE = "pending_remote_revoke"

    // SharedPreferences contains only non-secret consent flags. Never store
    // the remote device token or E2EE identity to retry revocation.
    fun hasPendingRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(PENDING_REVOKE, false)
    } catch (_: Exception) { true } // Fail closed: do not re-enable until checked.

    fun disableAndQueueRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(OPTED_IN, false).putBoolean(PENDING_REVOKE, true).commit()
    } catch (_: Exception) { false }

    fun acknowledgeRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_REVOKE, false).commit()
    } catch (_: Exception) { false }

    fun isEnabled(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        prefs.getBoolean(OPTED_IN, false) && !prefs.getBoolean(PENDING_REVOKE, false)
    } catch (_: Exception) { false }
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(OPTED_IN, enabled).commit()
    } catch (_: Exception) { false }
}

/** Reject stale, unsupported or arbitrary FCM payloads without fetching private data. */
object CallWakePolicy {
    const val TYPE = "lanu_call_wake_v1"
    // Process-local only: the private foreground host already rings by E2EE.
    @Volatile var privateAreaVisible: Boolean = false
    // Duplicate wake hints should never repeatedly ring. Only timestamp
    // hashes stay in process RAM; no peer/call metadata is persisted.
    private val recent = LinkedHashSet<Long>()
    @Synchronized fun claim(at: Long): Boolean {
        if (!recent.add(at)) return false
        while (recent.size > 64) recent.remove(recent.first())
        return true
    }
    fun eligible(data: Map<String, String>, now: Long): Boolean {
        if (data.size != 2 || data["type"] != TYPE) return false
        val created = data["at"]?.toLongOrNull() ?: return false
        return created > 0 && created in (now - 65_000L)..(now + 15_000L)
    }
}

/** Incoming notifications are wake-up hints, not private deep links or permissions. */
object CallWakeTapPolicy {
    const val ACTION = "com.aistudio.lanucalc.kxwzqm.action.LANU_CALL_WAKE"
    fun requiresFreshEntry(action: String?): Boolean = action == ACTION
}

object CallWakeNotification {
    // Android keeps a channel's first sound choice across APK upgrades.
    // Version the once-silent call channel without touching chat notifications.
    const val CHANNEL_ID = "lanu_private_calls_ringing_v2"
    private const val OLD_CHANNEL_ID = "lanu_private_calls_v1"
    private const val NOTIFICATION_ID = 8129

    /**
     * Use the user's chosen phone ringtone and ring-volume stream; let Android
     * enforce silent mode, DND and notification preferences. Never start our
     * own Ringtone player, background audio loop or raise system volume.
     */
    fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return

        // Respect deliberate old-channel mute/low-priority settings on upgrade.
        val previous = manager.getNotificationChannel(OLD_CHANNEL_ID)
        val userMuted = previous != null && (
            previous.importance < NotificationManager.IMPORTANCE_DEFAULT ||
                (Build.VERSION.SDK_INT >= 30 && previous.hasUserSetSound() && previous.sound == null)
            )
        val incoming = NotificationChannel(
            CHANNEL_ID, "LANU gelen sesli/görüntülü aramalar",
            if (userMuted) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Yalnız isteğe bağlı gerçek gelen sesli ve görüntülü arama uyarıları"
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            if (userMuted) {
                setSound(null, null)
            } else {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
        manager.createNotificationChannel(incoming)
    }

    fun show(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean {
        if (CallWakePolicy.privateAreaVisible || !CallWakePreferences.isEnabled(context) ||
            !CallWakePolicy.eligible(data, now)) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= 26) ensureChannel(manager)

        // No call ID/peer ID/hidden-navigation extras: opening the notification
        // always follows the ordinary calculator -> 2011. -> optional PIN gate.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = CallWakeTapPolicy.ACTION
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
            // On Android 7.x and earlier sound is attached to the notification.
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setTimeoutAfter(75_000L)
            .setAutoCancel(true)
            .build()
        if (!CallWakePolicy.claim(data["at"]!!.toLong())) return false
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) { false }
          catch (_: RuntimeException) { false }
    }
}
