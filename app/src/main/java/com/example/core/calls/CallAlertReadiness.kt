package com.example.core.calls

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Local, consented settings inspection. No enrollment, identity or network work. */
internal object CallAlertReadiness {
    fun describe(context: Context): String = try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        CallWakeNotification.ensureChannel(manager)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val channel = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel(CallWakeNotification.CHANNEL_ID) else null
        buildList {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)) add("Uygulama bildirim izni kapalı.")
            if (Build.VERSION.SDK_INT >= 26 && (channel?.importance ?: 0) < NotificationManager.IMPORTANCE_HIGH)
                add("Gelen aramalar kategorisinde Uyarı ve Açılır bildirim seçin.")
            if (Build.VERSION.SDK_INT >= 26 && channel?.lockscreenVisibility == android.app.Notification.VISIBILITY_SECRET)
                add("Gelen aramalar kategorisinde kilit ekranında göstermeyi açın.")
            if (Build.VERSION.SDK_INT >= 26 && channel?.sound == null) add("Gelen aramalar kategorisinde bir zil sesi seçin.")
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_RING) == 0)
                add("Telefon sessiz/titreşim modunda veya zil ses seviyesi sıfır.")
            if (RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) == null)
                add("Telefonun varsayılan zil sesini seçin.")
            if (Build.VERSION.SDK_INT >= 23 && manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
                add("Rahatsız Etmeyin ayarı arama sesini sınırlayabilir.")
            if (!CallWakeNotification.canShowFullScreen(manager)) add("Kilit ekranı için tam ekran arama iznini açın.")
            if (isEmpty()) add("Arama sesi ve ekrana geliş için gerekli telefon ayarları açık.")
        }.joinToString("\n")
    } catch (_: RuntimeException) {
        "Telefon ayarlarını okuyamadık. Aşağıdaki zil sesi ve tam ekran izin düğmelerini kontrol edin."
    } catch (_: LinkageError) {
        "Telefon ayarlarını okuyamadık. Aşağıdaki zil sesi ve tam ekran izin düğmelerini kontrol edin."
    }
}
