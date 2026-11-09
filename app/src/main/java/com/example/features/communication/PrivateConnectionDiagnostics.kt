package com.example.features.communication

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.core.calls.CallConnectionReadiness
import com.example.core.diagnostics.PrivateConnectionMetrics

/** Only mounted in the already-authorized private settings screen. */
@Composable
fun PrivateConnectionDiagnostics() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(PrivateConnectionMetrics.isEnabled()) }
    var summary by remember { mutableStateOf("") }
    var readiness by remember { mutableStateOf("") }
    fun refresh() {
        summary = PrivateConnectionMetrics.safeSummary()
        readiness = runCatching { CallConnectionReadiness.read(context).summary() }.getOrDefault("Sistem durumu okunamadı; telefon ayarlarını kontrol edin.")
    }
    OutlinedButton(onClick = { refresh(); open = true }, modifier = Modifier.fillMaxWidth()) { Text("Bağlantı ölçümü ve arama hazırlığı") }
    if (open) AlertDialog(onDismissRequest = { open = false },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Yerel bağlantı denetimi") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ölçüm yalnız bu uygulama oturumunda bellekte tutulur. İçerik, kişi, adres ve kimlik kaydedilmez; veri dışarı gönderilmez. Her aşamanın son 128 başarılı örneği gösterilir.")
                Row { Switch(checked = measuring, onCheckedChange = { measuring = it; PrivateConnectionMetrics.setEnabled(it); refresh() }); Text("Bu oturumda ölç", Modifier.padding(12.dp)) }
                Text(summary)
                Text("HTTP süreleri yerel istek ve yanıtı ölçer; iki telefon arasındaki teslim süresi değildir. Arama süresi yalnız kabulden gerçek çift yönlü ses kanıtına kadar ölçülür. Örnek yoksa süre gösterilmez.")
                TextButton(onClick = { PrivateConnectionMetrics.clear(); refresh() }) { Text("Ölçümleri temizle") }
                HorizontalDivider()
                Text(readiness)
                Text("Bu kontrol bildirim teslimi, zil veya arama başarısı kanıtı değildir. Pil ve sessiz/DND tercihleri değiştirilmez. Ayarlardan dönünce Yenile'ye dokunun.")
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
                }) { Text("Telefon uygulama ayarlarını aç") }
            }
        }, confirmButton = { TextButton(onClick = { refresh() }) { Text("Yenile") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Kapat") } })
}
