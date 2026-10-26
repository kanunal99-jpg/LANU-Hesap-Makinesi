package com.example.features.communication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.calls.CallWakePreferences
import com.example.core.repository.CommunicationRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch

/** Called only inside the already-unlocked private settings screen. */
@Composable
fun CallWakeSettings(repository: CommunicationRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CallWakePreferences.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun startRegistration() {
        if (busy) return
        busy = true
        status = null
        // Without real google-services configuration, never pretend the
        // notification preference registered a reachable device.
        val configured = try { FirebaseApp.getApps(context).isNotEmpty() } catch (_: Exception) { false }
        if (!configured) {
            status = "Çağrı bildirimi için Firebase henüz yapılandırılmamış."
            busy = false
            return
        }
        try {
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result else null
                if (token.isNullOrBlank()) {
                    FirebaseMessaging.getInstance().isAutoInitEnabled = false
                    status = "Bildirim cihaz kaydı alınamadı. Bağlantınızı kontrol edin."
                    busy = false
                } else {
                    scope.launch {
                        try {
                            repository.registerCallWakeToken(token)
                            check(CallWakePreferences.setEnabled(context, true)) { "Yerel izin kaydedilemedi" }
                            enabled = true
                            status = "Yalnız gelen sesli/görüntülü arama uyarıları etkin."
                        } catch (_: Exception) {
                            // Server or credential failure is not success.
                            CallWakePreferences.setEnabled(context, false)
                            FirebaseMessaging.getInstance().isAutoInitEnabled = false
                            enabled = false
                            status = "Sunucu çağrı bildirim kaydını doğrulayamadı. Etkinleştirilmedi."
                        } finally { busy = false }
                    }
                }
            }
        } catch (_: Exception) {
            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
            status = "Firebase çağrı bildirim servisi kullanılamıyor."
            busy = false
        }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRegistration()
        else status = "Bildirim izni verilmedi. Çağrı bildirimleri kapalı."
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        HorizontalDivider()
        Text("Gelen arama bildirimleri", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp))
        Text("Yalnız sesli ve görüntülü aramalar. Sohbet ve mesaj bildirimi gönderilmez. Kilit ekranında kişi adı veya gizli içerik görünmez.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        Text("Bildirime dokununca hesap makinesi açılır; gizli alana 2011. ve isteğe bağlı PIN ile girilir. İnternet, Android pil yönetimi ve Google servisleri nedeniyle her çağrının bildirimi garanti değildir.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
        if (busy) CircularProgressIndicator()
        else if (enabled) OutlinedButton(onClick = {
            // Consent is revoked locally before any network work; a failed
            // remote revoke cannot cause a push to show a notification.
            CallWakePreferences.setEnabled(context, false)
            enabled = false
            runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
            status = "Bu cihazda arama bildirimi kapatıldı."
            scope.launch {
                try { repository.registerCallWakeToken(null) }
                catch (_: Exception) { status = "Bu cihazda kapalı. Sunucu kaydı ağ bağlantısında yeniden kontrol edilmeli." }
            }
        }) { Text("Arama bildirimlerini kapat") }
        else Button(onClick = {
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startRegistration()
        }) { Text("Yalnız arama bildirimlerini etkinleştir") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
    }
}
