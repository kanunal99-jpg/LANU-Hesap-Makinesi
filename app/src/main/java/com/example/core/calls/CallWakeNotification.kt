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
    // Boolean only; never store FCM token, private identity or chat metadata.
    private const val REGISTRATION_IN_FLIGHT = "registration_in_flight"

    // SharedPreferences contains only non-secret consent flags. Never store
    // the remote device token or E2EE identity to retry revocation.
    fun hasPendingRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE).let { prefs ->
            prefs.getBoolean(PENDING_REVOKE, false) ||
                prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)
        }
    } catch (_: Exception) { true } // Fail closed: do not re-enable until checked.

    @Synchronized
    fun disableAndQueueRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(OPTED_IN, false).putBoolean(PENDING_REVOKE, true)
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    @Synchronized
    fun acknowledgeRemoteRevoke(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_REVOKE, false)
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    /**
     * Before FCM token lookup or signed registration, persist that remote
     * cleanup might be needed. Android process death after a server write
     * must never leave a device silently registered without local consent.
     */
    @Synchronized
    fun beginRemoteRegistration(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(OPTED_IN, false) ||
            prefs.getBoolean(PENDING_REVOKE, false) ||
            prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)) false
        else prefs.edit().putBoolean(REGISTRATION_IN_FLIGHT, true).commit()
    } catch (_: Exception) { false }

    /** Commit final consent and clear the pre-network marker atomically. */
    @Synchronized
    fun completeRemoteRegistration(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(REGISTRATION_IN_FLIGHT, false) ||
            prefs.getBoolean(PENDING_REVOKE, false)) false
        else prefs.edit().putBoolean(OPTED_IN, true)
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    /** Only before any signed server registration request was attempted. */
    @Synchronized
    fun abandonUnsentRegistration(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    /** Active UI enrollment owns its registration; workers must leave it alone. */
    fun isRegistrationInProgress(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getBoolean(REGISTRATION_IN_FLIGHT, false)
    } catch (_: Exception) { false }

    /** Called only at process startup, before any UI or worker can register. */
    @Synchronized
    fun recoverInterruptedRegistration(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)) true
        else prefs.edit().putBoolean(OPTED_IN, false).putBoolean(PENDING_REVOKE, true)
            .putBoolean(REGISTRATION_IN_FLIGHT, false).commit()
    } catch (_: Exception) { false }

    fun isEnabled(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        prefs.getBoolean(OPTED_IN, false) && !prefs.getBoolean(PENDING_REVOKE, false) &&
            !prefs.getBoolean(REGISTRATION_IN_FLIGHT, false)
    } catch (_: Exception) { false }
    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putBoolean(OPTED_IN, enabled).commit()
    } catch (_: Exception) { false }
}

/** Reject stale, unsupported or arbitrary FCM payloads without fetching private data. */
object CallWakePolicy {
    const val TYPE = "lanu_call_wake_v1"
    // Android 13+ notifications require runtime permission. The calculator
    // and foreground E2EE messages must remain usable without this permission.
    fun requiresNotificationPermission(api: Int): Boolean = api >= 33
    // A muted (LOW/MIN) channel is still a valid silent notification; a
    // blocked (NONE) or missing channel is not delivery. Do not consume a
    // call's one-shot wake claim when Android cannot display it.
    fun channelCanDisplay(api: Int, importance: Int?): Boolean =
        api < 26 || (importance != null && importance > NotificationManager.IMPORTANCE_NONE)
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

    /**
     * Claims the wake before notifying so concurrent duplicate FCM deliveries
     * cannot start two alerts. If the Android notification service throws,
     * release that failed claim: a later retry may still show the REAL call.
     * A failed notification is not successful delivery.
     */
    fun publishOnce(at: Long, notify: () -> Unit): Boolean {
        if (!claim(at)) return false
        return try {
            notify()
            true
        } catch (_: SecurityException) {
            releaseFailed(at)
            false
        } catch (_: RuntimeException) {
            releaseFailed(at)
            false
        } catch (_: LinkageError) {
            // An OEM framework/linker mismatch must not crash the FCM
            // callback or permanently consume an undelivered call alert.
            // Never swallow VM failures such as OutOfMemoryError.
            releaseFailed(at)
            false
        }
    }

    /**
     * A user can revoke call-alert consent while the native Android service
     * is creating the notification. Re-check on both sides of notify() and
     * retract that specific call ID if consent was withdrawn in flight.
     * This does not touch independent message alerts or start media.
     */
    // All call hints share one Android notification ID. Serialize post/retract
    // transactions so an older revoked wake cannot cancel a newer valid alert.
    // The lock covers the post-consent check and cancellation, not UI consent writes.
    @Synchronized
    fun publishWhileConsented(
        at: Long,
        isConsented: () -> Boolean,
        notify: () -> Unit,
        cancel: () -> Unit
    ): Boolean {
        if (!isConsented()) return false
        var delivered = false
        val succeeded = publishOnce(at) {
            if (isConsented()) {
                notify()
                delivered = true
                if (!isConsented()) {
                    cancel()
                    delivered = false
                }
            }
        }
        // If consent was withdrawn after the first check, no alert was
        // delivered (or the just-posted alert was retracted). Do not burn the
        // deduplication claim: a later valid retry after fresh opt-in must work.
        // Successful delivery still keeps its claim to suppress duplicate rings.
        if (succeeded && !delivered) releaseFailed(at)
        return succeeded && delivered
    }

    /**
     * Local opt-out must cancel after any in-flight post/retract transaction.
     * Otherwise the UI may cancel first and a delayed FCM notify can leave a
     * visible call alert after consent was withdrawn. Never touch message IDs.
     */
    @Synchronized
    fun cancelPostedCallAlert(cancel: () -> Boolean): Boolean = cancel()

    @Synchronized private fun releaseFailed(at: Long) {
        recent.remove(at)
    }
    /** OEM notification services can reject channel creation, permission probes,
     * PendingIntent creation, or payload construction BEFORE notify() is called.
     * Never let these platform failures crash the FCM callback or consume a
     * dedupe claim for an alert that was never posted.
     */
    fun safePlatformDelivery(action: () -> Boolean): Boolean = try {
        action()
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    } catch (_: LinkageError) {
        // Some vendor notification APIs fail at runtime with an ABI/linker
        // error even when the platform reports a compatible Android level.
        // Treat this as a failed notification; foreground calls still work.
        false
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

    /** Clears only the already displayed call alert after local opt-out. */
    fun cancel(context: Context): Boolean = CallWakePolicy.safePlatformDelivery {
        CallWakePolicy.cancelPostedCallAlert {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return@cancelPostedCallAlert false
            manager.cancel(NOTIFICATION_ID)
            true
        }
    }

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

    fun show(context: Context, data: Map<String, String>, now: Long = System.currentTimeMillis()): Boolean =
        CallWakePolicy.safePlatformDelivery {
        if (CallWakePolicy.privateAreaVisible || !CallWakePreferences.isEnabled(context) ||
            !CallWakePolicy.eligible(data, now)) return@safePlatformDelivery false
        if (CallWakePolicy.requiresNotificationPermission(Build.VERSION.SDK_INT) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@safePlatformDelivery false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return@safePlatformDelivery false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@safePlatformDelivery false

        if (Build.VERSION.SDK_INT >= 26) ensureChannel(manager)
        if (!CallWakePolicy.channelCanDisplay(
                Build.VERSION.SDK_INT,
                if (Build.VERSION.SDK_INT >= 26)
                    manager.getNotificationChannel(CHANNEL_ID)?.importance else null
            )) return@safePlatformDelivery false

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
        CallWakePolicy.publishWhileConsented(
            at = data["at"]!!.toLong(),
            isConsented = { !CallWakePolicy.privateAreaVisible && CallWakePreferences.isEnabled(context) },
            notify = { manager.notify(NOTIFICATION_ID, notification) },
            cancel = { manager.cancel(NOTIFICATION_ID) }
        )
    }
}
