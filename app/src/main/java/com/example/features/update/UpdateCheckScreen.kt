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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.update.VerifiedUpdate
import com.example.core.update.SecureOtaUpdater
import com.example.core.update.UpdateStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/**
 * Explicit user-initiated update flow. Never silently downloads private data,
 * requests uninstall, or claims an incompatible signer can update in place.
 */
@Composable
fun UpdateCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var ready by remember { mutableStateOf<VerifiedUpdate?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (busy) return
        busy = true
        notice = null
        status = null
        ready = null
        downloadProgress = null
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
            val progress = downloadProgress
            if (progress == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Text(operation, style = MaterialTheme.typography.bodyMedium)
            if (downloadJob?.isActive == true) {
                Text(
                    "İndirilen: ${downloadedBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = { downloadJob?.cancel() },
                    modifier = Modifier.testTag("ota_cancel_download")
                ) { Text("İndirmeyi iptal et") }
            }
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
                        if (current.reason.contains("APK imzası")) {
                            Text(
                                "Bu hata Android'in imza denetimidir; aynı sertifikayla imzalanmamış " +
                                    "iki APK birbirinin üzerine güncelleme olarak kurulamaz.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Henüz hiç PIN, sohbet veya hesap geçmişi oluşturmadıysanız yalnız bir defa " +
                                    "temiz kurulum yapabilirsiniz: Resmî APK'yi indirin, " +
                                    "Android Ayarlar'dan eski LANU'yu kaldırın, İndirilenler'den yeni APK'yi kurun. " +
                                    "Kaldırma işlemi LANU içindeki bütün verileri siler. " +
                                    "Veriniz varsa kaldırmayın; güvenli taşıma gereklidir.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("ota_clean_install_guidance")
                            )
                            SecureOtaUpdater.officialReleasePage(current.tag)?.let { releasePage ->
                                OutlinedButton(
                                    onClick = { uriHandler.openUri(releasePage) },
                                    modifier = Modifier.fillMaxWidth().testTag("ota_official_release_link")
                                ) { Text("Resmî APK sayfasını aç") }
                            }
                        }
                    }
                }
            }
            is UpdateStatus.Available -> {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Yeni sürüm hazır: ${current.release.tag}", fontWeight = FontWeight.Bold)
                        Text("Sürüm kodu: ${current.release.versionCode}")
                        val checked = ready
                        if (checked != null && checked.release == current.release && checked.file.isFile) {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("ota_install"),
                                onClick = {
                                    busy = true
                                    operation = "APK doğrulanıyor ve güvenli kurulum kopyası hazırlanıyor…"
                                    notice = null
                                    scope.launch {
                                        try {
                                            // Keep the verified installer snapshot independent of the
                                            // resumable OTA download cache and its eviction policy.
                                            val installSnapshot = SecureOtaUpdater.stageForInstaller(context, checked)
                                            if (!SecureOtaUpdater.launchInstaller(context, installSnapshot)) {
                                                notice = "Android ayarlarında LANU için bilinmeyen uygulama yükleme iznini onaylayın. Ardından buraya dönerek Kur düğmesine yeniden dokunun."
                                            } else {
                                                notice = "Android sistem yükleyicisi açıldı. Kurulum için ekrandaki onayı verin."
                                            }
                                        } catch (e: Exception) {
                                            ready = null
                                            notice = e.message ?: "Kurulum öncesi doğrulama başarısız."
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Yeniden doğrula ve Android üzerinden güncelle")
                            }
                        } else {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("ota_download"),
                                onClick = {
                                    if (busy) return@Button
                                    busy = true
                                    downloadProgress = null
                                    downloadedBytes = 0L
                                    operation = "APK güvenli şekilde indiriliyor…"
                                    notice = null
                                    downloadJob = scope.launch {
                                        try {
                                            ready = SecureOtaUpdater.download(context, current.release) { received, total ->
                                                downloadedBytes = received
                                                downloadProgress = total?.takeIf { it > 0L }
                                                    ?.let { (received.toDouble() / it).toFloat().coerceIn(0f, 1f) }
                                                operation = if (total != null && total > 0L) {
                                                    "APK indiriliyor: ${(received * 100L / total).coerceIn(0L, 100L)}%"
                                                } else "APK indiriliyor…"
                                            }
                                        } catch (_: CancellationException) {
                                            ready = null
                                            notice = "İndirme iptal edildi; eksik APK temizlendi."
                                        } catch (e: Exception) {
                                            ready = null
                                            notice = e.message ?: "Güncelleme indirilemedi."
                                        } finally {
                                            busy = false
                                            downloadJob = null
                                            downloadProgress = null
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
