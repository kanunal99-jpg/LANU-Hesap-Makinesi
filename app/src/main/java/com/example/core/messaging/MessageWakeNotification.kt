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
    // Consent-safe boolean; never persist token, sender or chat metadata.
    private const val REGISTRATION_IN_FLIGHT = "registration_in_flight"

    fun isEnabled(context: Context): Boolean = try {
        val p = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        p.getBoolean(ENABLED, false) && !p.getBoolean(PENDING, false) &&
            !p.getBoolean(REGISTRATION_IN_FLIGHT, false)
    } catch (_: Exception) { false }

    fun hasPendingRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).let { p ->
            p.getBoolean(PENDING, false) || p.getBoolean(REGISTRATION_IN_FLIGHT, false)
        }
    } catch (_: Exception) { true }

    /**
     * Firebase may call onNewToken while first opt-in is still in flight.
     * Do not schedule a revocation worker at that instant: it could delete
     * the just-registered server token AFTER local consent has committed.
     * On cold process start LanuApplication still schedules recovery via
     * hasPendingRemoteRevoke(), so a killed registration remains recoverable.
     */
    fun shouldQueueOnTokenRotation(context: Context): Boolean = try {
        val p = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        (p.getBoolean(ENABLED, false) &&
            !p.getBoolean(REGISTRATION_IN_FLIGHT, false) &&
            !p.getBoolean(PENDING, false)) || p.getBoolean(PENDING, false)
    } catch (_: Exception) { false }

    /** Legacy/local opt-in helper: never override a pending server cleanup. */
    fun enable(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PENDING, false) ||
            prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)) false
        else prefs.edit().putBoolean(ENABLED, true).commit()
    } catch (_: Exception) { false }

    /**
     * Persist a compensating revoke BEFORE enabling FCM or sending a token.
     * If a signed server write succeeds just before process death, next
     * authenticated settings / WorkManager reconciliation revokes it.
     */
    fun beginRemoteRegistration(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ENABLED, false) ||
            prefs.getBoolean(PENDING, false) ||
            prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)) false
        else prefs.edit().putBoolean(REGISTRATION_IN_FLIGHT, true).commit()
    } catch (_: Exception) { false }

    /** Remote signed registration succeeded; atomically activate local consent. */
    fun completeRemoteRegistration(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(REGISTRATION_IN_FLIGHT, false) ||
            prefs.getBoolean(PENDING, false)) false
        else prefs.edit().putBoolean(ENABLED, true)
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    /** Safe only if no signed server registration request was sent. */
    fun abandonUnsentRegistration(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    fun disableAndQueueRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, false).putBoolean(PENDING, true).commit()
    } catch (_: Exception) { false }

    fun acknowledgeRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, false).putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
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
