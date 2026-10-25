package com.example.features.communication

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.example.core.delivery.*
import java.text.DateFormat
import java.util.Date

/** Kept inside the existing authenticated, FLAG_SECURE private-area host. */
@Composable
fun DeliveryCenter(onForegroundRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val coordinator = remember(context) { DeliveryCoordinator(context) }
    val model: DeliveryCenterViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = remember(coordinator) { DeliveryCenterViewModel.Factory(coordinator) })
    val snapshot by model.snapshot.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()

    val running = jobs.any { it.state == WorkInfo.State.RUNNING }
    val queued = jobs.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val status = when {
        !snapshot.available -> "Tercihler okunamadı"
        running -> "Sunucu kontrol ediliyor"
        queued -> "Ağ ve Android çalışma sırası bekleniyor"
        snapshot.options.enabled -> "Arka plan görevi doğrulanamadı"
        else -> "Arka plan kontrolü kapalı"
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)),
            RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Teslimat merkezi", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Mesajlarınızın bağlantısını ve arka plan tercihlerini tek yerden yönetin.",
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudSync, contentDescription = null)
                    Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                Text("Son başarılı sunucu kontrolü", style = MaterialTheme.typography.labelLarge)
                Text(if (snapshot.lastCheckedAt == 0L) "Henüz yapılmadı" else
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.lastCheckedAt)),
                    modifier = Modifier.testTag("delivery_last_check"), style = MaterialTheme.typography.bodyLarge)
                Text(outcomeText(snapshot.outcome), style = MaterialTheme.typography.bodyMedium)
                Text("Teslim ve okundu bilgileri her mesajın yanında gösterilir.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DeliverySwitch("Uygulama kapalıyken kontrol", "Google servisleri gerektirmez. İsteğinize bağlıdır.",
                    snapshot.options.enabled, !busy && snapshot.available, "delivery_background") { value ->
                    model.setOptions(snapshot.options.copy(enabled = value))
                }
                HorizontalDivider()
                DeliverySwitch("Wi-Fi / kotasız ağ", "Mobil veri kullanımını sınırlar. Şimdi kontrol et için de geçerlidir.",
                    snapshot.options.unmeteredOnly, !busy && snapshot.available, "delivery_unmetered") { value ->
                    model.setOptions(snapshot.options.copy(unmeteredOnly = value))
                }
            }
        }
        if (error != null || !snapshot.available) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error ?: "Teslimat tercihleri açılamadı. Mesajlarınız korunuyor; uygulama açıkken yenileyebilirsiniz.", Modifier.padding(16.dp))
            }
        }
        Button(onClick = model::checkNow, enabled = !busy && !running && snapshot.available,
            modifier = Modifier.fillMaxWidth().testTag("delivery_check_now")) {
            Text(if (busy) "Hazırlanıyor…" else "Şimdi kontrol et")
        }
        if (snapshot.options.enabled && !running && !queued) {
            OutlinedButton(onClick = model::reconcile, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("Arka plan kontrolünü yeniden hazırla") }
        }
        TextButton(onClick = onForegroundRefresh, modifier = Modifier.fillMaxWidth()) {
            Text("Uygulama açıkken sohbetleri yenile")
        }
        Text("Arka plan kontrolü yaklaşık 15 dakikalık aralıklarla planlanır. Pil tasarrufu, düşük pil ve üretici kısıtları geciktirebilir. " +
            "Android'den zorla durdurduğunuz uygulamayı yeniden açmanız gerekir. Anlık teslim garantisi vermez.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeliverySwitch(title: String, detail: String, checked: Boolean, enabled: Boolean,
                           tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = title })
    }
}

private fun outcomeText(outcome: DeliveryOutcome): String = when (outcome) {
    DeliveryOutcome.NEVER -> "İlk kontrolü başlatabilirsiniz."
    DeliveryOutcome.CHECKED -> "Sunucu yanıtı doğrulandı."
    DeliveryOutcome.NETWORK_WAIT -> "Uygun bağlantı bekleniyor; mesajlar korunuyor."
    DeliveryOutcome.SERVER_BUSY -> "Sunucu bekleme istedi; sınırlı aralıklarla yeniden denenecek."
    DeliveryOutcome.IDENTITY_REQUIRED -> "Önce güvenli alanı açın veya mevcut kimlik yedeğinizi geri yükleyin."
    DeliveryOutcome.SECURE_STORAGE_UNAVAILABLE -> "Güvenli depoya erişilemedi. Telefon kilidini açıp yeniden deneyin."
    DeliveryOutcome.NEEDS_ATTENTION -> "Kontrol tamamlanamadı. Güvenli alandan yeniden deneyin."
}
