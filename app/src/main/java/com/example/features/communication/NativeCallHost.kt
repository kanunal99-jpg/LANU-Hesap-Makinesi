package com.example.features.communication

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.example.core.calls.NativeCallEngine
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer

/** Secure dialog shared by the home and chat screens; media is never auto-accepted. */
@Composable
fun NativeCallHost(model: NativeCallViewModel, request: Pair<String, Boolean>?, onRequestConsumed: () -> Unit) {
    val state by model.state.collectAsState()
    val media by model.media.collectAsState()
    val engine = media as? NativeCallEngine
    val notice by model.notice.collectAsState()
    val context = LocalContext.current
    LifecycleStartEffect(state.callId, state.phase, context) {
        val ringtone = if (state.phase == NativeCallPhase.RINGING) runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }.getOrNull() else null
        onStopOrDispose { runCatching { ringtone?.stop() } }
    }
    var confirm by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var permissionTarget by remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    LifecycleStartEffect(model) {
        model.activate()
        onStopOrDispose { permissionTarget = null; confirm = null; model.deactivate() }
    }
    LaunchedEffect(request) { if (request != null) { confirm = request; onRequestConsumed() } }
    LaunchedEffect(state.callId) {
        if (state.phase == NativeCallPhase.RINGING) { confirm = null; permissionTarget = null }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val target = permissionTarget
        permissionTarget = null
        if (target != null) {
            val microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val camera = !target.second || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (target.third) model.accept(target.first, microphone && camera)
            else model.outgoing(target.first, target.second, microphone && camera)
        }
    }
    fun authorize(id: String, video: Boolean, incoming: Boolean) {
        val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            if (incoming) model.accept(id, true) else model.outgoing(id, video, true)
        } else {
            permissionTarget = Triple(id, video, incoming)
            permissions.launch(needed.toTypedArray())
        }
    }
    if (confirm != null) {
        val target = confirm!!
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (target.second) "Görüntülü arama" else "Sesli arama") },
            text = { Text("İki telefonda da güncel LANU ve güvenli alan açık olmalıdır. Doğrudan görüşmede IP adresinizi karşı taraf ve Google/Cloudflare STUN hizmetleri görebilir. Mikrofon ve kamera yalnız görüşme kabul edilince açılır. Güvenli alandan çıkarsanız görüşme kapanır.") },
            confirmButton = { TextButton(onClick = { confirm = null; authorize(target.first, target.second, false) }) { Text("Aramayı başlat") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } })
    }
    if (state.phase != NativeCallPhase.IDLE) {
        val ended = state.phase == NativeCallPhase.ENDED || state.phase == NativeCallPhase.FAILED
        Dialog(onDismissRequest = { if (ended) model.dismiss() }, properties = DialogProperties(
            dismissOnBackPress = ended, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
            securePolicy = SecureFlagPolicy.SecureOn)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(when (state.phase) {
                        NativeCallPhase.RINGING -> if (state.video) "Gelen görüntülü arama" else "Gelen sesli arama"
                        NativeCallPhase.DIALING -> "Aranıyor…"
                        NativeCallPhase.CONNECTING -> "Güvenli bağlantı kuruluyor…"
                        NativeCallPhase.CONNECTED -> "Bağlandı"
                        NativeCallPhase.RECONNECTING -> "Ağ bağlantısı yenileniyor…"
                        else -> state.message.ifBlank { "Arama kapandı." }
                    }, style = MaterialTheme.typography.bodyLarge)
                    if (state.connectedAt > 0 && !ended) {
                        var elapsed by remember { mutableLongStateOf(0) }
                        LaunchedEffect(state.connectedAt) { while (true) { elapsed = (SystemClock.elapsedRealtime()-state.connectedAt)/1000; delay(1000) } }
                        Text("${elapsed/60}:${(elapsed%60).toString().padStart(2,'0')}")
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                        if (state.video && engine != null && !ended) {
                            CallVideo(engine!!, true, Modifier.fillMaxSize())
                            if (state.cameraOn) CallVideo(engine!!, false, Modifier.align(Alignment.TopEnd).padding(12.dp).width(112.dp).height(152.dp))
                        } else Icon(if (state.video) Icons.Default.Videocam else Icons.Default.Call, null, Modifier.size(72.dp))
                    }
                    if (engine != null && !ended) {
                        val controlsReady = state.phase in setOf(NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING)
                        CallAudioRouteControls(engine!!)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalIconButton(onClick = model::muted, enabled = controlsReady) { Icon(if (state.muted) Icons.Default.MicOff else Icons.Default.Mic, if (state.muted) "Mikrofonu aç" else "Mikrofonu sustur") }
                            if (state.video) {
                                FilledTonalIconButton(onClick = model::camera, enabled = controlsReady) { Icon(if (state.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff, "Kamerayı aç/kapat") }
                                FilledTonalIconButton(onClick = model::switchCamera, enabled = controlsReady && state.cameraOn) { Icon(Icons.Default.Cameraswitch, "Ön/arka kamera") }
                            }
                        }
                        Text("WebRTC gürültü ve yankı azaltma · kalite cihaza ve ortama bağlıdır", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.phase == NativeCallPhase.RINGING) {
                        Text("Doğrudan görüşmede IP adresinizi karşı taraf ve Google/Cloudflare STUN hizmetleri görebilir. Güvenli alandan çıkarsanız görüşme biter.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { authorize(state.callId, state.video, true) }) { Text("Kabul et") }
                            OutlinedButton(onClick = model::reject) { Text("Reddet") }
                        }
                    } else if (ended) Button(onClick = model::dismiss) { Text("Kapat") }
                    else Button(onClick = model::end, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.CallEnd, null); Spacer(Modifier.width(8.dp)); Text("Aramayı bitir")
                    }
                }
            }
        }
    }
    if (notice != null && state.phase == NativeCallPhase.IDLE) {
        AlertDialog(onDismissRequest = model::clearNotice, title = { Text("Arama bilgisi") },
            text = { Text(notice!!) }, confirmButton = { TextButton(onClick = model::clearNotice) { Text("Tamam") } })
    }
}

@Composable
private fun CallVideo(engine: NativeCallEngine, remote: Boolean, modifier: Modifier) {
    key(engine, remote) {
        AndroidView(factory = { context -> SurfaceViewRenderer(context).also {
            it.setZOrderMediaOverlay(!remote)
            engine.bind(it, remote)
        } },
            modifier = modifier, onRelease = { engine.unbind(it) })
    }
}

@Composable
private fun CallAudioRouteControls(engine: NativeCallEngine) {
    val route by engine.router.state.collectAsState()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { engine.refreshRoutes() }
    Box {
        TextButton(onClick = { menu = true }) { Text("Ses çıkışı: ${route.selected?.name?.let(::routeName) ?: "Sistem"}") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (item in route.available) DropdownMenuItem(text = { Text(routeName(item.name)) }, onClick = { menu = false; engine.chooseRoute(item) })
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                DropdownMenuItem(text = { Text("Bluetooth kulaklığa izin ver") }, onClick = { menu = false; bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        }
    }
}
private fun routeName(name: String) = when (name) {
    "WIRED_HEADSET" -> "Kablolu / USB kulaklık"
    "BLUETOOTH_HEADSET" -> "Bluetooth kulaklık"
    "EARPIECE" -> "Ahize"
    "SPEAKER" -> "Hoparlör"
    else -> "Sistem"
}
