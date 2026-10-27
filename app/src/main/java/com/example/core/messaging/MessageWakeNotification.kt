package com.example.core.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.calls.CallWakePolicy

/**
 * Separate from call consent. Only boolean flags are stored; NEVER an identity,
 * FCM token, conversation, sender, message content or unread count.
 */
object MessageWakePreferences {
    private const val STORE = "lanu_message_wake_v1"
    private const val ENABLED = "opted_in"
    private const val PENDING = "pending_remote_revoke"
    private const val ENROLLING = "enrollment_in_progress"

    fun isEnabled(context: Context): Boolean = try {
        val p = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        p.getBoolean(ENABLED, false) && !p.getBoolean(PENDING, false)
    } catch (_: Exception) { false }

    fun hasPendingRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(PENDING, false)
    } catch (_: Exception) { true }

    /**
     * Crash-recovery barrier: commit this BEFORE sending a positive signed
     * FCM registration request. A process death after server ACK, but before
     * local consent becomes enabled, must leave a durable revoke marker.
     * No token, identity, message or conversation enters SharedPreferences.
     */
    fun beginEnrollment(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PENDING, false) || prefs.getBoolean(ENABLED, false)) false
        else prefs.edit().putBoolean(ENABLED, false)
            .putBoolean(PENDING, true).putBoolean(ENROLLING, true).commit()
    } catch (_: Exception) { false }

    /**
     * Only a signed server ACK may complete registration. Enrollment runs in
     * an active private UI, while the Worker must not race its rollback.
     */
    fun finishEnrollment(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ENROLLING, false) || !prefs.getBoolean(PENDING, false)) false
        else prefs.edit().putBoolean(ENABLED, true)
            .putBoolean(PENDING, false).putBoolean(ENROLLING, false).commit()
    } catch (_: Exception) { false }

    fun isEnrollmentInProgress(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(ENROLLING, false)
    } catch (_: Exception) { false }

    /**
     * The Application invokes this on every cold start BEFORE queuing retry
     * workers. If the process died mid-registration, revoke the potentially
     * registered remote token. All fields are harmless boolean flags.
     */
    fun recoverInterruptedEnrollment(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ENROLLING, false)) true
        else prefs.edit().putBoolean(ENABLED, false).putBoolean(PENDING, true)
            .putBoolean(ENROLLING, false).commit()
    } catch (_: Exception) { false }

    fun disableAndQueueRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
             .putBoolean(ENABLED, false).putBoolean(PENDING, true)
            .putBoolean(ENROLLING, false).commit()
    } catch (_: Exception) { false }

    fun acknowledgeRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, false).putBoolean(ENROLLING, false).commit()
    } catch (_: Exception) { false }
}

object MessageWakePolicy {
    const val TYPE = "lanu_message_wake_v1"
    private val recent = LinkedHashSet<Long>()

    fun eligible(data: Map<String, String>, now: Long): Boolean {
        if (data.size != 2 || data["type"] != TYPE) return false
        val at = data["at"]?.toLongOrNull() ?: return false
        return at > 0 && at in (now - 120_000L)..(now + 15_000L)
    }

    @Synchronized
    fun claim(at: Long): Boolean {
        if (!recent.add(at)) return false
        while (recent.size > 64) recent.remove(recent.first())
        return true
    }

    @Synchronized
    fun releaseFailed(at: Long) { recent.remove(at) }

    fun safeShow(block: () -> Boolean): Boolean =
        try { block() } catch (_: SecurityException) { false }
        catch (_: RuntimeException) { false }
}

/** No private deep link. The normal calculator gate is mandatory on every tap. */
object MessageWakeTapPolicy {
    const val ACTION = "com.aistudio.lanucalc.kxwzqm.action.LANU_MESSAGE_WAKE"
    fun requiresFreshEntry(action: String?): Boolean = action == ACTION
}

object MessageWakeNotification {
    const val CHANNEL_ID = "lanu_private_message_alerts_v1"
    private const val ID = 8130

    /**
     * A local opt-out must also remove an already displayed neutral alert.
     * Never cancel call notifications: their consent and channel are separate.
     */
    fun cancel(context: Context): Boolean = MessageWakePolicy.safeShow {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return@safeShow false
        manager.cancel(ID)
        true
    }

    fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LANU bildirimleri", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "İsteğe bağlı, içeriği gizli LANU bildirimleri"
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
        )
    }

    fun show(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean =
        MessageWakePolicy.safeShow {
            if (CallWakePolicy.privateAreaVisible || !MessageWakePreferences.isEnabled(context) ||
                !MessageWakePolicy.eligible(data, now)) return@safeShow false
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return@safeShow false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@safeShow false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return@safeShow false
            ensureChannel(manager)
            if (Build.VERSION.SDK_INT >= 26 &&
                (manager.getNotificationChannel(CHANNEL_ID)?.importance ?: 0) <
                    NotificationManager.IMPORTANCE_DEFAULT) return@safeShow false

            val at = data["at"]?.toLongOrNull() ?: return@safeShow false
            if (!MessageWakePolicy.claim(at)) return@safeShow false
            try {
                val launch = Intent(context, MainActivity::class.java).apply {
                    action = MessageWakeTapPolicy.ACTION
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val tap = PendingIntent.getActivity(context, 1, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_lanu_monochrome)
                    .setContentTitle("LANU Hesap Makinesi")
                    // Deliberately no content text, person, style, count or preview.
                    .setContentIntent(tap)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setAutoCancel(true)
                    .build()
                manager.notify(ID, notification)
                true
            } catch (e: SecurityException) {
                MessageWakePolicy.releaseFailed(at)
                false
            } catch (e: RuntimeException) {
                MessageWakePolicy.releaseFailed(at)
                false
            }
        }
}
