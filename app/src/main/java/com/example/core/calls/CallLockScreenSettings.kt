package com.example.core.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Read-only inspection and user-driven OS settings navigation; never a fake permission toggle. */
internal object CallLockScreenSettings {
    enum class State(val description: String) {
        CALLS_OFF("Arama bildirimleri kapalı. Bu düğme yalnız telefon ayarını açar; aramaları etkinleştirmez."),
        BLOCKED("Android arama bildirimlerini engelliyor. Bildirim iznini ve arama kategorisini kontrol edin."),
        HIDDEN("Arama kategorisi kilit ekranında gizli. Telefon ayarından görünürlüğünü kontrol edin."),
        REDACTED("Arama kategorisinde içerik gizlenebilir. Kilit ekranı ayarını kontrol edin."),
        GENERIC_ALLOWED("Kategori genel arama bilgisini gösterebilir. Samsung’un genel kilit ekranı ayarı ayrıca geçerlidir."),
        SYSTEM_CONTROLLED("Kilit ekranı görünürlüğünü telefon ayarından kontrol edin."),
        UNAVAILABLE("Kilit ekranı durumu okunamadı. Telefon ayarından kontrol edin.")
    }
    enum class Destination { CHANNEL, APP, DETAILS, UNAVAILABLE }

    fun inspect(context: Context): State = try {
        if (!CallWakePreferences.isEnabled(context)) State.CALLS_OFF
        else if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) State.BLOCKED
        else if (Build.VERSION.SDK_INT < 26) State.SYSTEM_CONTROLLED
        else {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = manager?.getNotificationChannel(CallWakeNotification.CHANNEL_ID)
            when {
                channel == null -> State.SYSTEM_CONTROLLED
                channel.importance == NotificationManager.IMPORTANCE_NONE -> State.BLOCKED
                channel.lockscreenVisibility == Notification.VISIBILITY_SECRET -> State.HIDDEN
                channel.lockscreenVisibility == Notification.VISIBILITY_PRIVATE -> State.REDACTED
                channel.lockscreenVisibility == Notification.VISIBILITY_PUBLIC -> State.GENERIC_ALLOWED
                else -> State.SYSTEM_CONTROLLED
            }
        }
    } catch (_: RuntimeException) { State.UNAVAILABLE }
      catch (_: LinkageError) { State.UNAVAILABLE }

    /** No channel creation, opt-in, sound changes, global lock-screen write or network work. */
    fun open(context: Context, start: (Intent) -> Unit = { context.startActivity(it) }): Destination {
        if (Build.VERSION.SDK_INT >= 26) {
            val exists = try {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.getNotificationChannel(CallWakeNotification.CHANNEL_ID) != null
            } catch (_: RuntimeException) { false } catch (_: LinkageError) { false }
            if (exists) {
                val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CallWakeNotification.CHANNEL_ID)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Use only the public category settings API. Lock-screen-only
                // filter constants are not part of the public Android SDK.
                if (launch(channelIntent, start)) return Destination.CHANNEL
            }
        }
        return openApp(context, start)
    }

    /** App-only fallback for Samsung's per-app hide-content setting, never all apps. */
    fun openApp(context: Context, start: (Intent) -> Unit = { context.startActivity(it) }): Destination {
        if (Build.VERSION.SDK_INT >= 26) {
            if (launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), start)) return Destination.APP
        }
        if (launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), start)) return Destination.DETAILS
        return Destination.UNAVAILABLE
    }
    private fun launch(intent: Intent, start: (Intent) -> Unit): Boolean = try {
        start(intent); true
    } catch (_: RuntimeException) { false } catch (_: LinkageError) { false }
}
