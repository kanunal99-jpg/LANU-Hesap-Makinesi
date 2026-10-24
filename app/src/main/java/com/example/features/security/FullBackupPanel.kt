package com.example.features.security

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun FullBackupPanel(
    busy: Boolean,
    pendingRestore: Boolean,
    message: String?,
    onExport: (String) -> Unit,
    onChooseRestore: () -> Unit,
    onRestore: (String) -> Unit,
    onCancelRestore: () -> Unit
) {
    var action by remember { mutableStateOf<String?>(null) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tam şifreli yedek", style = MaterialTheme.typography.titleMedium)
            Text("Kimlik, sohbetler, kişiler, hesap geçmişi ve ayarlar parola ile tek dosyada saklanır. Bu sürümde arşiv sınırı 32 MiB'dir. Yedek parolasını ayrı ve güvenli bir yerde koruyun.")
            Button(onClick = { action = "export" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Tam yedek oluştur") }
            OutlinedButton(onClick = onChooseRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Tam yedek dosyası seç") }
            if (pendingRestore) {
                Text("Bir yedek seçildi. Geri yükleme için parolasını girin.")
                Button(onClick = { action = "restore" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Seçilen yedeği geri yükle") }
                TextButton(onClick = onCancelRestore, enabled = !busy) { Text("Seçimi iptal et") }
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Kayıtlar güvenle işleniyor…")
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    action?.let { selected ->
        var password by remember(selected) { mutableStateOf("") }
        var confirmation by remember(selected) { mutableStateOf("") }
        val exporting = selected == "export"
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(if (exporting) "Tam yedeği parola ile koru" else "Tam yedeği geri yükle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (exporting) "En az 12 karakterli bir parola seçin. Parola olmadan yedek kurtarılamaz. Dosyayı telefon dışında güvenli bir yere kaydedin."
                        else "Mevcut kayıtlar silinmez; çakışan sohbetlerde işlem durur. Bu cihazın mevcut PIN'i ve ayarları korunur; boş kurulumda yedekteki ayarlar gelir. İşlem bitince gizli alan kilitlenir.")
                    OutlinedTextField(password, { password = it.take(256) }, label = { Text("Yedek parolası") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (exporting) OutlinedTextField(confirmation, { confirmation = it.take(256) }, label = { Text("Parolayı tekrar girin") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = password.length >= 12 && (!exporting || password == confirmation), onClick = {
                    val value = password; password = ""; confirmation = ""; action = null
                    if (exporting) onExport(value) else onRestore(value)
                }) { Text(if (exporting) "Şifrele ve kaydet" else "Geri yüklemeyi onayla") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Vazgeç") } }
        )
    }
}
