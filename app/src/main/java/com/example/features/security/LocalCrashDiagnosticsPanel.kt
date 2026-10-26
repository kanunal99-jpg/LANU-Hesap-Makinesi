package com.example.features.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.core.diagnostics.CrashDiagnostics
import com.example.core.diagnostics.LocalCrashReport
import com.example.core.diagnostics.PrivateRelayHealth
import java.text.DateFormat
import java.util.Date

/**
 * Private-area-only, user-initiated display/copy of minimized crash metadata.
 * The public calculator/update screen must never expose private code paths.
 */
@Composable
fun LocalCrashDiagnosticsPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var record by remember(context) { mutableStateOf(LocalCrashReport.read(context)) }
    var systemExit by remember(context) { mutableStateOf(CrashDiagnostics.mostRecentAbnormalExit(context)) }
    var detailsVisible by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var relaySummary by remember { mutableStateOf(PrivateRelayHealth.safeSummary()) }

    Card(Modifier.fillMaxWidth().testTag("private_crash_diagnostics")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yerel çökme tanılaması", style = MaterialTheme.typography.titleMedium)
            Text(
                "Yalnız uygulama hata türü ve LANU kaynak kodu satırları tutulur. " +
                    "PIN, mesaj içeriği, kullanıcı kodu ve hata açıklaması kaydedilmez; otomatik gönderim yapılmaz.",
                style = MaterialTheme.typography.bodySmall
            )
            val current = record
            if (current == null) {
                Text("Son 7 gün içinde yakalanmış ayrıntılı hata kaydı yok.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(current.type)
                Text(
                    DateFormat.getDateTimeInstance().format(Date(current.timestamp)),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { detailsVisible = !detailsVisible }) {
                    Text(if (detailsVisible) "Kod satırlarını gizle" else "Kod satırlarını göster")
                }
                if (detailsVisible) {
                    Text(
                        current.appFrames.joinToString("\n").ifBlank { "LANU kod satırı bulunamadı." },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("private_crash_frames")
                    )
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(current.sanitizedSummary()))
                }, modifier = Modifier.testTag("copy_private_crash")) {
                    Text("Tanılamayı kopyala")
                }
                TextButton(onClick = { confirmClear = true }) { Text("Tanılama kaydını sil") }
            }
            Text("Android cihaz kapanış tanılaması", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android 11 ve üzerinde son 7 günün yalnızca güvenli kapanış nedeni ve zamanı gösterilir. " +
                    "ANR izi, hata metni, mesaj veya kullanıcı kimliği okunmaz ve gönderilmez.",
                style = MaterialTheme.typography.bodySmall
            )
            val abnormal = systemExit
            if (abnormal == null) {
                Text("Android tarafından bildirilen yakın tarihli anormal kapanış yok veya bu cihaz desteklemiyor.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text(abnormal.label, modifier = Modifier.testTag("private_os_exit_reason"))
                Text(DateFormat.getDateTimeInstance().format(Date(abnormal.timestamp)),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Bu oturumun sunucu sağlığı", style = MaterialTheme.typography.titleSmall)
            Text(
                "Yalnız başarılı bağlantı ve genel hata sınıflarının sayısı gösterilir. " +
                    "Kimlik, URL, mesaj, PIN, cihaz belirteci veya hata metni saklanmaz; " +
                    "veriler cihazdan gönderilmez ve uygulama kapanınca sıfırlanır.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(relaySummary, modifier = Modifier.testTag("private_relay_health_summary"))
            TextButton(onClick = {
                record = LocalCrashReport.read(context)
                systemExit = CrashDiagnostics.mostRecentAbnormalExit(context)
                relaySummary = PrivateRelayHealth.safeSummary()
            }) {
                Text("Kayıtları yenile")
            }
            TextButton(onClick = {
                PrivateRelayHealth.clear()
                relaySummary = PrivateRelayHealth.safeSummary()
            }) {
                Text("Oturum sayaçlarını sıfırla")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Yalnız tanılama kaydı silinsin mi?") },
            text = { Text("Bu işlem sohbetlere, PIN'e ve uygulama verilerine dokunmaz.") },
            confirmButton = {
                Button(onClick = {
                    if (LocalCrashReport.clear(context)) record = null
                    confirmClear = false
                }) { Text("Kaydı sil") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") }
            }
        )
    }
}
