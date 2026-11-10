package com.example.features.communication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.location.PrivateLiveLocationCodec
import com.example.core.location.PrivateLiveLocationService
import java.text.DateFormat
import java.util.Date

/** PIN-open chat only. Return from permission UI is discarded after navigation. */
@Composable
internal fun PrivateLiveLocationControls(
    conversationId: String, enabled: Boolean, showPicker: Boolean, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val live by PrivateLiveLocationService.state.collectAsState()
    var duration by remember(conversationId) {
        mutableLongStateOf(PrivateLiveLocationCodec.FIFTEEN_MINUTES_MS)
    }
    var precise by remember(conversationId) { mutableStateOf(false) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    var awaitingPermission by remember(conversationId) { mutableStateOf(false) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestPicker by rememberUpdatedState(showPicker)

    fun granted(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notice = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return coarse && (!precise || fine) && notice
    }

    fun begin() {
        if (!latestPicker || !latestEnabled || live.active || !granted()) {
            error = "Konum veya görünür bildirim izni eksik."
            return
        }
        if (PrivateLiveLocationService.start(context, conversationId, duration, precise)) {
            error = null
            awaitingPermission = false
            onDismiss()
        } else error = "Canlı konum başlatılamadı. Tekrar deneyin."
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (awaitingPermission && latestPicker && latestEnabled) {
            awaitingPermission = false
            if (granted()) begin()
            else error = "Canlı paylaşım için konum ve görünür bildirim izni gereklidir."
        } else awaitingPermission = false
    }

    LaunchedEffect(showPicker, enabled, conversationId) {
        if (!showPicker || !enabled) {
            error = null
            awaitingPermission = false
        }
    }

    if (live.active && live.conversationId == conversationId) {
        Surface(tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().testTag("live_location_active")) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Canlı konum paylaşımı etkin", style = MaterialTheme.typography.titleSmall)
                Text("Bitiş: " + DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(live.expiresAtMs)), style = MaterialTheme.typography.bodySmall)
                Text(if (live.lastFixAtMs > 0L)
                    "Son güncelleme: " + DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(live.lastFixAtMs)) + " · " + live.pointsSent + " nokta"
                    else "İlk konum alınıyor…", style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = {
                        if (!PrivateLiveLocationService.stop(context))
                            Toast.makeText(context,
                                "Paylaşım durdurulamadı. Bildirimdeki Durdur seçeneğini kullanın.",
                                Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.testTag("live_location_stop")
                ) { Text("Paylaşımı şimdi durdur") }
            }
        }
    } else if (live.active) {
        Text("Başka bir güvenli sohbette canlı paylaşım etkin. Önce onu durdurun.",
            modifier = Modifier.padding(12.dp).testTag("live_location_other_chat"),
            style = MaterialTheme.typography.bodySmall)
    } else if (live.status.isNotBlank() && enabled) {
        Text("Konum paylaşımı: " + live.status,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("live_location_last_status"),
            style = MaterialTheme.typography.bodySmall)
    }

    if (!showPicker || !enabled) return
    AlertDialog(
        onDismissRequest = { awaitingPermission = false; onDismiss() },
        title = { Text("Şifreli canlı konum") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yalnızca bu sohbetin doğrulanmış kişisine süreli konum gönderilir.")
                Text("Uygulamayı son uygulamalardan kaldırsanız veya ekranı kilitleseniz de paylaşım seçilen süre boyunca devam eder. Süre bitmeden durdurmak için Android bildirimindeki Durdur butonuna basın (görünmüyorsa bildirimi genişletin) ya da sohbetten Paylaşımı şimdi durdur seçin.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Android Ayarlar'dan Zorla durdur, telefonun işlemi kapatması veya konum izninin kaldırılması paylaşımı kesebilir.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Paylaşım süresi", style = MaterialTheme.typography.titleSmall)
                listOf(
                    PrivateLiveLocationCodec.FIFTEEN_MINUTES_MS to "15 dakika",
                    PrivateLiveLocationCodec.ONE_HOUR_MS to "1 saat",
                    PrivateLiveLocationCodec.EIGHT_HOURS_MS to "8 saat"
                ).forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = duration == value, onClick = { duration = value })
                        Text(label)
                    }
                }
                Text("Konum hassasiyeti", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !precise, onClick = { precise = false })
                    Text("Yaklaşık (daha gizli)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = precise, onClick = { precise = true })
                    Text("Hassas GPS")
                }
                Text("Bağlantı/izin kesilirse güvenlik için paylaşım durur. Önceden alıcıya ulaşmış noktalar uzaktan silinemez.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("live_location_error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !awaitingPermission && !live.active,
                modifier = Modifier.testTag("live_location_confirm"),
                onClick = {
                    error = null
                    if (granted()) begin()
                    else {
                        awaitingPermission = true
                        val permissions = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (precise) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33)
                            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                        launcher.launch(permissions.toTypedArray())
                    }
                }
            ) { Text("Canlı paylaşımı başlat") }
        },
        dismissButton = {
            TextButton(onClick = { awaitingPermission = false; onDismiss() }) { Text("Vazgeç") }
        }
    )
}
