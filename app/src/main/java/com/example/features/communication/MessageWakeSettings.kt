package com.example.features.communication

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.core.calls.CallWakePreferences
import com.example.core.messaging.MessageWakeNotification
import com.example.core.messaging.MessageWakePreferences
import com.example.core.messaging.MessageWakeTokenRefreshWorker
import com.example.core.repository.CommunicationRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

/** This component is reachable only after the normal calculator/private-area gate. */
@Composable
fun MessageWakeSettings(repository: CommunicationRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(MessageWakePreferences.isEnabled(context)) }
    var pending by remember { mutableStateOf(MessageWakePreferences.hasPendingRemoteRevoke(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun revokeRemote(): Boolean = try {
        repository.registerMessageWakeToken(null)
        check(MessageWakePreferences.acknowledgeRemoteRevoke(context))
        pending = false
        true
    } catch (_: Exception) {
        pending = true
        runCatching { MessageWakeTokenRefreshWorker.enqueue(context) }
        false
    }

    LaunchedEffect(repository) {
        if (pending) {
            busy = true
            val ok = revokeRemote()
            status = if (ok) "Mesaj bildirimleri sunucuda da kapatıldı."
                else "Bildirimler cihazda kapalı; sunucu iptali bağlantı bekliyor."
            busy = false
        }
    }

    fun openSystemChannel() {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                manager?.let(MessageWakeNotification::ensureChannel)
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, MessageWakeNotification.CHANNEL_ID)
                }
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            status = "Telefon Ayarları > LANU Hesap Makinesi > Bildirimler bölümünü açın."
        }
    }

    fun enable() {
        if (busy || pending) return
        if (!runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)) {
            status = "Telefon bildirimleri kapalı. Önce Android ayarlarından açın."
            openSystemChannel()
            return
        }
        if (!runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)) {
            status = "Mesaj bildirimleri için Firebase yapılandırması eksik."
            return
        }
        busy = true
        status = null
        try {
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result else null
                if (token.isNullOrBlank()) {
                    if (!CallWakePreferences.isEnabled(context)) {
                        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
                    }
                    status = "Bildirim cihaz kaydı alınamadı. Bağlantınızı kontrol edin."
                    busy = false
                } else scope.launch {
                    try {
                        repository.registerMessageWakeToken(token)
                        check(MessageWakePreferences.enable(context))
                        enabled = true
                        status = "Yalnız LANU Hesap Makinesi başlıklı, sesli bildirimler açık."
                    } catch (_: Exception) {
                        // A late server write is possible even if the local commit failed.
                        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
                        enabled = false
                        pending = true
                        val revoked = revokeRemote()
                        status = if (revoked) "Mesaj bildirimleri kapalı."
                            else "Bildirimler kapalı; sunucu iptali yeniden denenecek."
                        if (!CallWakePreferences.isEnabled(context)) {
                            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
                        }
                    } finally { busy = false }
                }
            }
        } catch (_: Exception) {
            if (!CallWakePreferences.isEnabled(context)) {
                runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
            }
            status = "FCM bildirimi kullanılamıyor."
            busy = false
        }
    }

    fun disable() {
        if (busy) return
        if (!MessageWakePreferences.disableAndQueueRemoteRevoke(context)) {
            status = "Tercih kaydedilemedi. Bildirimleri telefon ayarlarından kapatın."
            return
        }
        enabled = false
        pending = true
        busy = true
        // Do not turn off FCM while independent call alerts remain enabled.
        if (!CallWakePreferences.isEnabled(context)) {
            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
        }
        scope.launch {
            val revoked = revokeRemote()
            status = if (revoked) "Mesaj bildirimleri kapalı."
                else "Cihazda kapalı; sunucu iptali bağlantı bekliyor."
            busy = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enable()
        else status = "Bildirim izni verilmedi; mesaj bildirimleri kapalı."
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mesaj bildirimleri", style = MaterialTheme.typography.titleMedium)
                Text("İsteğe bağlı • varsayılan kapalı • sesli bildirim",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = enabled && !pending,
                enabled = !busy && !pending,
                onCheckedChange = { requested ->
                    if (!requested) disable()
                    else {
                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context,
                                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else enable()
                    }
                },
                modifier = Modifier.testTag("private_message_wake_toggle")
            )
        }
        Text("Bildirimde yalnız 'LANU Hesap Makinesi' görünür. Mesaj, gönderen, sohbet, kişi ve önizleme gösterilmez. Ses Android'in sessiz mod ve bildirim kanalı ayarlarına bağlıdır.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        Text("Bildirime dokununca hesap makinesi açılır; gizli alan yalnız mevcut giriş yöntemi ve isteğe bağlı PIN ile açılır. İnternet, Android pil yönetimi ve Google servisleri nedeniyle bildirimin ulaşması garanti değildir.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        if (busy) CircularProgressIndicator()
        if (pending && !busy) OutlinedButton(onClick = {
            busy = true
            scope.launch {
                val revoked = revokeRemote()
                status = if (revoked) "Sunucu iptali tamamlandı."
                    else "Sunucu iptali bekliyor; tekrar deneyin."
                busy = false
            }
        }) { Text("Bekleyen bildirim iptalini yeniden dene") }
        if (enabled) OutlinedButton(onClick = { openSystemChannel() }) {
            Text("Mesaj bildirim sesini ayarla")
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
