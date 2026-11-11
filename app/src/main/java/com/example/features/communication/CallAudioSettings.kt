package com.example.features.communication

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.core.calls.CallAudioPreference
import com.example.core.calls.CallAudioRecoveryRegistry
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
                modifier = Modifier.testTag("private_software_audio").semantics {
                    contentDescription = "Yazılım gürültü ve yankı azaltma"
                },
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
        Text("Ses boğuksa veya yankı varsa deneyin. Bir sonraki aramada WebRTC yazılım gürültü/yankı azaltması kullanılır. Bu tercih mevcut görüşmeyi değiştirmez.",
            style = MaterialTheme.typography.bodySmall)
        if (CallAudioRecoveryRegistry.policy.hardwareQuarantined()) {
            Text("Mikrofon başlatma hatası algılandı. Güvenlik için uygulama yeniden açılana kadar sonraki görüşmelerde WebRTC yazılım ses işleme kullanılacak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (failed) Text("Tercih kaydedilemedi. Tekrar deneyin.", color = MaterialTheme.colorScheme.error)
    }
}
