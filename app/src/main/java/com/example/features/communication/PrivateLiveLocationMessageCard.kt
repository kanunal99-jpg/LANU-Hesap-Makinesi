package com.example.features.communication

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.location.PrivateLiveLocationCodec
import com.example.core.location.PrivateLocationCodec
import java.text.DateFormat
import java.util.Date

/**
 * Untrusted peer ciphertext is already authenticated before this point.
 * Strict message grammar still gates every Google Maps URL; no map request
 * or coordinate export on a passive chat render.
 */
@Composable
internal fun PrivateLiveLocationMessageCard(message: String) {
    val context = LocalContext.current
    val event = remember(message) { PrivateLiveLocationCodec.decodeOrNull(message) }
    var externalAction by remember(message) { mutableStateOf<String?>(null) }
    if (event == null) {
        Text("Canlı konum verisi doğrulanamadı.",
            modifier = Modifier.testTag("live_location_invalid"),
            color = MaterialTheme.colorScheme.error)
        return
    }
    val expires = DateFormat.getDateTimeInstance(
        DateFormat.SHORT, DateFormat.SHORT
    ).format(Date(event.expiresAtMs))
    val now = System.currentTimeMillis()
    Column(modifier = Modifier.testTag("live_location_message_card")) {
        when (event.kind) {
            PrivateLiveLocationCodec.Kind.START -> {
                Text("Canlı konum paylaşımı başlatıldı",
                    style = MaterialTheme.typography.titleSmall)
                Text("Süre sonu: $expires",
                    style = MaterialTheme.typography.bodySmall)
            }
            PrivateLiveLocationCodec.Kind.STOP -> {
                Text("Canlı konum paylaşımı durduruldu",
                    style = MaterialTheme.typography.titleSmall)
                Text("Önceden gönderilmiş noktalar geri alınamaz.",
                    style = MaterialTheme.typography.bodySmall)
            }
            PrivateLiveLocationCodec.Kind.POINT -> {
                val point = event.point ?: return
                Text("Canlı konum · güncelleme " + event.sequence,
                    style = MaterialTheme.typography.titleSmall)
                val fresh = !PrivateLiveLocationCodec.expired(event.expiresAtMs, now) &&
                    PrivateLiveLocationCodec.fresh(point.capturedAtMillis, now)
                Text(if (fresh) "Yeni konum bildirimi" else "Eski konum bildirimi",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (point.approximate) "Yaklaşık konum" else
                    "Hassas konum · yaklaşık " + point.accuracyMeters + " m doğruluk",
                    style = MaterialTheme.typography.bodySmall)
                Text("Ölçüm: " + DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(point.capturedAtMillis)) + " · Bitiş: $expires",
                    style = MaterialTheme.typography.bodySmall)
                if (PrivateLiveLocationCodec.expired(event.expiresAtMs, now)) {
                    Text("Paylaşım süresi doldu. Bu noktaya harita erişimi kapatıldı.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { externalAction = "map" },
                        modifier = Modifier.testTag("live_location_open_maps")) {
                        Text("Google Haritalar'da aç")
                    }
                    OutlinedButton(onClick = { externalAction = "directions" },
                        modifier = Modifier.testTag("live_location_directions")) {
                        Text("Yol tarifi al")
                    }
                }
            }
        }
    }

    val selectedPoint = event.point
    if (selectedPoint != null && externalAction != null) {
        AlertDialog(
            onDismissRequest = { externalAction = null },
            title = { Text("Konum Google Haritalar'a aktarılsın mı?") },
            text = { Text(
                "Yalnızca onayınızla harici harita uygulamasına bu noktanın koordinatları aktarılacaktır."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    val selected = externalAction
                    externalAction = null
                    // Expiry is checked again at the actual tap, not only
                    // when Compose initially drew the location card.
                    if (PrivateLiveLocationCodec.expired(
                            event.expiresAtMs, System.currentTimeMillis())) {
                        Toast.makeText(context, "Bu konumun süresi doldu.",
                            Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val url = try {
                        if (selected == "directions")
                            PrivateLocationCodec.mapsDirectionsUrl(selectedPoint)
                        else PrivateLocationCodec.mapsSearchUrl(selectedPoint)
                    } catch (_: IllegalArgumentException) { return@TextButton }
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        })
                    } catch (_: RuntimeException) {
                        Toast.makeText(context, "Harita açılamadı.",
                            Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Haritalar'ı aç") }
            },
            dismissButton = {
                TextButton(onClick = { externalAction = null }) { Text("Vazgeç") }
            }
        )
    }
}
