package com.example.features.communication

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.core.calls.CallTurnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TurnServerSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("İsteğe bağlı bağlantı aktarma sunucusu") }
    if (expanded) AlertDialog(onDismissRequest = { if (!busy) { password = ""; expanded = false } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("TURN bağlantı yedeği") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yalnız sahip olduğunuz veya kullanmaya yetkili olduğunuz sunucuyu girin. Uygulama sunucu satın almaz. Aktarma trafiği sağlayıcınızın kotasına tabi olabilir. Ayarı kaydetmek bağlantı testi değildir.")
                OutlinedTextField(url, { url = it.take(300) }, label = { Text("turns:sunucu:443?transport=tcp") }, singleLine = true)
                OutlinedTextField(username, { username = it.take(256) }, label = { Text("Kullanıcı adı") }, singleLine = true)
                OutlinedTextField(password, { password = it.take(512) }, label = { Text("Parola") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                message?.let { Text(it) }
                TextButton(enabled = !busy, onClick = { scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { CallTurnConfig(context).clear() }; password = ""; message = "Aktarma ayarı kaldırıldı." }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { message = "Ayar kaldırılamadı." }
                    finally { busy = false }
                } }) { Text("Kayıtlı sunucuyu kaldır") }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { CallTurnConfig(context).save(CallTurnConfig.Value(url.trim(), username, password)) }; password = ""; message = "Şifreli kaydedildi. Sonraki aramada denenecek; erişim henüz doğrulanmadı." }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Adres ve kimlik bilgilerini kontrol edin. Ayar doğrulanamadı." }
            finally { busy = false }
        } }) { Text("Kaydet") } }, dismissButton = { TextButton(enabled = !busy, onClick = { password = ""; expanded = false }) { Text("Kapat") } })
}
