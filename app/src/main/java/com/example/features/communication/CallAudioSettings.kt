package com.example.features.communication

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.calls.CallAudioPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Available only in the authenticated communication settings. */
@Composable
internal fun CallAudioSettings() {
    val context = LocalContext.current
    val preference = remember(context) { CallAudioPreference(context) }
    var enabled by remember { mutableStateOf(preference.preferSoftware()) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Gürültü ve yankı azaltma", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Yazılım ses işleme", modifier = Modifier.weight(1f))
            Switch(checked = enabled, enabled = !saving,
                modifier = Modifier.testTag("private_software_audio"),
                onCheckedChange = { selected ->
                    saving = true
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) { preference.savePreferSoftware(selected) }
                            failed = !saved
                            if (saved) enabled = selected
                        } finally { saving = false }
                    }
                })
        }
        Text("Ses boğuksa veya yankı varsa deneyin. Bir sonraki aramada WebRTC yazılım gürültü/yankı azaltması kullanılır. Kapatınca otomatik cihaz seçimine dönülür; mevcut görüşme değişmez.",
            style = MaterialTheme.typography.bodySmall)
        if (failed) Text("Tercih kaydedilemedi. Tekrar deneyin.", color = MaterialTheme.colorScheme.error)
    }
}
