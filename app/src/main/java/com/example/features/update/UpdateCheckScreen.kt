package com.example.features.update

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SecurityUpdate
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.update.LanuUpdate
import com.example.core.update.SecureOtaUpdater
import com.example.core.update.UpdateStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Explicit user-initiated update flow. Never silently downloads private data,
 * requests uninstall, or claims an incompatible signer can update in place.
 */
@Composable
fun UpdateCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var readyRelease by remember { mutableStateOf<LanuUpdate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (busy) return
        busy = true
        notice = null
        status = null
        ready = null
        readyRelease = null
        operation = "GitHub sürümleri kontrol ediliyor…"
        scope.launch {
            try {
                status = SecureOtaUpdater.check(context)
            } catch (e: Exception) {
                notice = e.message ?: "Güncelleme denetimi yapılamadı."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("ota_update_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.SecurityUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("Güncelleme Kontrol", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Yalnız resmi LANU GitHub sürümleri",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kurulu sürüm", fontWeight = FontWeight.SemiBold)
                Text("Android sürüm kodu: ${SecureOtaUpdater.installedVersion(context)}")
                Text("Yükleme öncesi APK imzası, SHA-256 özeti, paket adı ve sürüm numarası doğrulanır.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(operation, style = MaterialTheme.typography.bodyMedium)
        }
        when (val current = status) {
            is UpdateStatus.Current -> {
                Text("Yeni uygun bir sürüm bulunamadı. Yüklü sürüm kodu: ${current.versionCode}.",
                    modifier = Modifier.testTag("ota_up_to_date"))
            }
            is UpdateStatus.Incompatible -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Güncelleme güvenlik nedeniyle engellendi", fontWeight = FontWeight.Bold)
                        Text("Bulunan sürüm: ${current.tag}")
                        Text(current.reason, modifier = Modifier.testTag("ota_signature_blocked"))
                    }
                }
            }
            is UpdateStatus.Available -> {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Yeni sürüm hazır: ${current.release.tag}", fontWeight = FontWeight.Bold)
                        Text("Sürüm kodu: ${current.release.versionCode}")
                        if (readyRelease == current.release && ready?.isFile == true) {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("ota_install"),
                                onClick = {
                                    try {
                                        if (!SecureOtaUpdater.launchInstaller(context, ready!!)) {
                                            notice = "Android ayarlarında LANU için bilinmeyen uygulama yükleme iznini onaylayın. Ardından buraya dönerek Kur düğmesine yeniden dokunun."
                                        } else {
                                            notice = "Android sistem yükleyicisi açıldı. Güncelleme için ekrandaki onayı verin."
                                        }
                                    } catch (e: Exception) {
                                        notice = e.message ?: "Android yükleyici açılamadı."
                                    }
                                }
                            ) {
                                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Android üzerinden güncelle")
                            }
                        } else {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("ota_download"),
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    operation = "APK indiriliyor ve güvenliği doğrulanıyor…"
                                    notice = null
                                    scope.launch {
                                        try {
                                            ready = SecureOtaUpdater.download(context, current.release)
                                            readyRelease = current.release
                                        } catch (e: Exception) {
                                            ready = null
                                            readyRelease = null
                                            notice = e.message ?: "Güncelleme indirilemedi."
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            ) { Text("Güvenli APK'yi indir ve doğrula") }
                        }
                    }
                }
            }
            null -> Unit
        }

        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("ota_notice"))
        }

        OutlinedButton(
            onClick = ::check,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("ota_check_button")
        ) { Text("Güncelleme kontrol et") }

        HorizontalDivider()
        Text(
            "LANU, mevcut uygulamayı silmez ve sessiz kurulum yapmaz. " +
                "İmza farklıysa veri kaybı riski nedeniyle kurulum engellenir. " +
                "Android 8+ sürümlerinde ilk kurulumda sistem izin onayı gerekebilir. " +
                "Gizli alandaki PIN, kimlik ve sohbetler için otomatik veri silme yapılmaz.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
