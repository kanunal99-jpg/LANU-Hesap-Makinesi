package com.example.features.communication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.calls.CallLockScreenSettings

@Composable
internal fun CallLockScreenControl(state: CallLockScreenSettings.State, onOpenApp: () -> Unit, enabled: Boolean = true, onOpen: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Kilit ekranı görünürlüğü", style = MaterialTheme.typography.titleSmall)
        Text(state.description, style = MaterialTheme.typography.bodySmall)
        Text("Yalnız genel arama bilgisi ve Yanıtla/Reddet için kullanılır; kişi adı ve mesaj içerikleri açılmaz. " +
            "Telefon ayarında ‘Bildirimleri gösterme’ yerine görünürlük seçeneğini kontrol edin. " +
            "Samsung’un ‘İçeriği gizle’ tercihi ayrıca etkili olabilir.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp))
        OutlinedButton(onClick = onOpen, enabled = enabled, modifier = Modifier.testTag("call_lock_screen_settings")) {
            Text("Kilit ekranında göster")
        }
        OutlinedButton(onClick = onOpenApp, enabled = enabled, modifier = Modifier.testTag("call_lock_content_settings")) {
            Text("İçeriği gizle ayarını kontrol et")
        }
    }
}
