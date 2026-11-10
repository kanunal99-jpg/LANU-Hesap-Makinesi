package com.example.features.communication

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.location.PrivateLocationCodec
import java.text.DateFormat
import java.util.Date

/**
 * No map SDK, map tiles, automatic web requests or reverse-geocoding on chat
 * opening. Google Maps only sees coordinates after an explicit confirmation.
 */
@Composable
internal fun PrivateLocationMessageCard(message: String) {
    val context = LocalContext.current
    val point = remember(message) { PrivateLocationCodec.decodeOrNull(message) }
    var externalAction by remember(message) { mutableStateOf<String?>(null) }

    if (point == null) {
        Text("Konum verisi doğrulanamadı.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("private_location_invalid"))
        return
    }

    Column(modifier = Modifier.testTag("private_location_card")) {
        Text("📍 Paylaşılan konum", style = MaterialTheme.typography.titleSmall)
        Text(if (point.approximate)
            "Yaklaşık konum · koordinatlar yuvarlandı; GPS noktasını göstermez" else
            "Hassas konum · yaklaşık ${point.accuracyMeters} m doğruluk",
            style = MaterialTheme.typography.bodySmall)
        Text("Alınma zamanı: " +
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(point.capturedAtMillis)),
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = { externalAction = "map" },
            modifier = Modifier.testTag("private_location_open_maps")
        ) { Text("Google Haritalar’da aç") }
        OutlinedButton(
            onClick = { externalAction = "directions" },
            modifier = Modifier.testTag("private_location_directions")
        ) { Text("Yol tarifi al") }
    }

    externalAction?.let { action ->
        AlertDialog(
            onDismissRequest = { externalAction = null },
            title = { Text("Google Haritalar’a geçilsin mi?") },
            text = { Text("Bu işlem paylaşılan konumu harici Google Haritalar uygulamasına veya tarayıcıya iletir. Konum otomatik olarak aktarılmaz.") },
            confirmButton = {
                TextButton(onClick = {
                    externalAction = null
                    val url = if (action == "directions")
                        PrivateLocationCodec.mapsDirectionsUrl(point)
                    else PrivateLocationCodec.mapsSearchUrl(point)
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        })
                    } catch (_: Exception) {
                        Toast.makeText(context, "Harita uygulaması açılamadı.",
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
