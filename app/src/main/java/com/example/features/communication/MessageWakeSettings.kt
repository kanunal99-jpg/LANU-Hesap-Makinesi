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
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
    } catch (e: CancellationException) {
        throw e
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
        if (busy || pending || MessageWakePreferences.hasPendingRemoteRevoke(context)) return
        if (!runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)) {
            status = "Telefon bildirimleri kapalı. Önce Android ayarlarından açın."
            openSystemChannel()
            return
        }
        if (!runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)) {
            status = "Mesaj bildirimleri için Firebase yapılandırması eksik."
            return
        }
        // Commit a fail-closed recovery marker BEFORE the signed remote call.
        // The worker ignores an active enrollment; a process restart safely
        // converts the orphaned marker into a durable remote revocation.
        if (!MessageWakePreferences.beginEnrollment(context)) {
            status = "Güvenli bildirim tercihi kaydedilemedi. Tekrar deneyin."
            pending = MessageWakePreferences.hasPendingRemoteRevoke(context)
            return
        }
        busy = true
        pending = true
        enabled = false
        status = null
        scope.launch {
            try {
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = true
                // Unlike an unbounded Task callback, this is time-limited and
                // cancellation-aware. No FCM token enters logs or local prefs.
                val token = withContext(Dispatchers.IO) {
                    Tasks.await(messaging.token, 12, TimeUnit.SECONDS)
                }
                check(!token.isNullOrBlank()) { "FCM token unavailable" }
                repository.registerMessageWakeToken(token)
                check(MessageWakePreferences.finishEnrollment(context))
                pending = false
                enabled = true
                status = "Yalnız genel başlıklı bildirim ve arka planda şifreli mesaj eşitleme açık."
            } catch (e: CancellationException) {
                // The persisted opt-out marker survives Compose cancellation.
                MessageWakePreferences.disableAndQueueRemoteRevoke(context)
                runCatching { MessageWakeTokenRefreshWorker.enqueue(context) }
                throw e
            } catch (_: Exception) {
                MessageWakePreferences.disableAndQueueRemoteRevoke(context)
                MessageWakeNotification.cancel(context)
                pending = true
                enabled = false
                val revoked = revokeRemote()
                status = if (revoked) "Mesaj bildirimleri kapalı. Cihaz kaydı tamamlanamadı."
                    else "Bildirim kapalı; sunucu iptali bekliyor. Gizli alan Ayarlarından yeniden deneyin."
            } finally {
                if (!MessageWakePreferences.isEnabled(context) &&
                    !CallWakePreferences.isEnabled(context)) {
                    runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
                }
                busy = false
            }
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
        // Opt-out removes even a previously delivered neutral notification.
        MessageWakeNotification.cancel(context)
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
                Text("Mesaj bildirimi ve anlık eşitleme", style = MaterialTheme.typography.titleMedium)
                Text("İsteğe bağlı • varsayılan kapalı • şifreli arka plan eşitleme",
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
        Text("Bu tercihi açınca yalnız genel bir bildirim gösterilir ve geçerli anonim uyarı geldiğinde mevcut kimliğinizle şifreli mesajlar arka planda eşitlenir. Mesaj, gönderen, sohbet, kişi ve önizleme bildirimde gösterilmez; yeni kimlik oluşturulmaz. Tercihi kapatınca bu anlık eşitleme de iptal edilir.",
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
