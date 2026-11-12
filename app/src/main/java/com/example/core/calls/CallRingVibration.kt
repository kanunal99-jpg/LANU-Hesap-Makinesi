package com.example.core.calls

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Only an independently decrypted E2EE INVITE may start repeated haptics.
 * An unauthenticated FCM hint is notification-only, never a vibrator loop.
 */
object CallRingVibrationPolicy {
    internal val PATTERN = longArrayOf(0L, 650L, 650L, 650L, 1_800L)

    fun allowed(
        ringerMode: Int,
        interruptionFilter: Int,
        channelImportance: Int?,
        channelVibrates: Boolean?
    ): Boolean =
        (ringerMode == AudioManager.RINGER_MODE_NORMAL ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE) &&
            interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            channelImportance != NotificationManager.IMPORTANCE_NONE &&
            channelVibrates != false
}

/**
 * Scoped to a foreground call UI and stopped on accept/reject, timeout or
 * lifecycle stop. Never bypass Silent / DND / disabled notification channels.
 */
object CallRingVibration {
    @Suppress("DEPRECATION")
    fun start(context: Context): (() -> Unit)? {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return null
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return null
            val channel = if (Build.VERSION.SDK_INT >= 26)
                manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID) else null
            if (!CallRingVibrationPolicy.allowed(
                    audio.ringerMode, manager.currentInterruptionFilter,
                    channel?.importance, channel?.shouldVibrate()
                )) return null
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                ?: return null
            if (!vibrator.hasVibrator()) return null
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(CallRingVibrationPolicy.PATTERN, 0),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            } else vibrator.vibrate(CallRingVibrationPolicy.PATTERN, 0)
            val stop: () -> Unit = {
                try { vibrator.cancel() }
                catch (_: RuntimeException) { }
                catch (_: LinkageError) { }
            }
            stop
        } catch (_: SecurityException) { null }
          catch (_: RuntimeException) { null }
          catch (_: LinkageError) { null }
    }
}
