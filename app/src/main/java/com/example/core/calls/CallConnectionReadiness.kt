package com.example.core.calls

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Local OS facts only. This never requests permissions, sends push, or opens media. */
object CallConnectionReadiness {
    data class Facts(val consent: Boolean, val notifications: Boolean, val channel: Boolean,
        val sound: Boolean, val unrestrictedBattery: Boolean?, val microphone: Boolean,
        val bluetooth: Boolean, val pendingRevoke: Boolean) {
        fun summary() = listOf(
            "Arama bildirimi tercihi: " + if (consent) "açık" else "kapalı",
            "Sistem bildirim izni: " + if (notifications) "açık" else "kapalı",
            "Arama kanalı: " + if (channel) "açık" else "kapalı veya henüz oluşturulmadı",
            "Kanal sesi: " + if (sound) "tanımlı; sessiz/DND ayarı ayrıca geçerli" else "sessiz veya kapalı",
            "Pil kısıtlaması: " + when (unrestrictedBattery) { true -> "standart optimizasyon dışında"; false -> "Android optimizasyonu uygulanıyor"; null -> "okunamadı" },
            "Mikrofon izni: " + if (microphone) "verilmiş" else "görüşme sırasında istenecek",
            "Bluetooth izni: " + if (bluetooth) "uygun" else "yakındaki cihaz izni gerekiyor",
            "Bekleyen bildirim iptali: " + if (pendingRevoke) "var" else "yok"
        ).joinToString("\n")
    }
    fun read(context: Context): Facts {
        fun permitted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channel = if (Build.VERSION.SDK_INT >= 26) manager?.getNotificationChannel(CallWakeNotification.CHANNEL_ID) else null
        val channelEnabled = if (Build.VERSION.SDK_INT >= 26) channel != null && channel.importance > NotificationManager.IMPORTANCE_NONE else true
        return Facts(CallWakePreferences.isEnabled(context),
            NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || permitted(Manifest.permission.POST_NOTIFICATIONS)),
            channelEnabled, channelEnabled && (Build.VERSION.SDK_INT < 26 || channel?.sound != null),
            runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
            permitted(Manifest.permission.RECORD_AUDIO), Build.VERSION.SDK_INT < 31 || permitted(Manifest.permission.BLUETOOTH_CONNECT),
            CallWakePreferences.hasPendingRemoteRevoke(context))
    }
}
