package com.example.features.communication

import android.Manifest
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.example.core.calls.LanuCallSignalingClient
import com.example.core.calls.LanuWebRtcCallManager
import com.example.core.calls.WebRtcCallState

@Composable
fun RealCallScreen(
    partnerName: String,
    conversationId: String,
    isVideo: Boolean,
    supabaseUrl: String,
    supabaseKey: String,
    onEndCall: () -> Unit
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(WebRtcCallState.CONNECTING) }
    var muted by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(isVideo) }
    var speakerOn by remember { mutableStateOf(true) }
    var manager by remember { mutableStateOf<LanuWebRtcCallManager?>(null) }
    var localRenderer by remember { mutableStateOf<org.webrtc.SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<org.webrtc.SurfaceViewRenderer?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val cameraOk = !isVideo || grants[Manifest.permission.CAMERA] == true
        if (audioOk && cameraOk && manager == null) {
            val signaling = LanuCallSignalingClient(context, supabaseUrl, supabaseKey)
            manager = LanuWebRtcCallManager(context, signaling, conversationId, isVideo) { newState -> state = newState }
        }
    }

    LaunchedEffect(supabaseUrl, supabaseKey, conversationId, isVideo) {
        if (manager == null && supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()) {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (isVideo) add(Manifest.permission.CAMERA)
            }.toTypedArray()
            permissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(manager) {
        manager?.start(outgoing = true)
    }

    DisposableEffect(manager) {
        onDispose { manager?.close() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF020617))) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    org.webrtc.SurfaceViewRenderer(ctx).also {
                        it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        it.setZOrderMediaOverlay(false)
                        it.init(org.webrtc.EglBase.create().eglBaseContext, null)
                        it.setMirror(true)
                        localRenderer = it
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            AndroidView(
                factory = { ctx ->
                    org.webrtc.SurfaceViewRenderer(ctx).also {
                        it.layoutParams = ViewGroup.LayoutParams(420, 620)
                        it.init(org.webrtc.EglBase.create().eglBaseContext, null)
                        remoteRenderer = it
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(width = 120.dp, height = 180.dp)
            )
            LaunchedEffect(localRenderer, remoteRenderer, manager) {
                manager?.attachRenderers(localRenderer, remoteRenderer)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(partnerName, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                when (state) {
                    WebRtcCallState.CONNECTED -> "Bağlandı"
                    WebRtcCallState.RECONNECTING -> "Yeniden bağlanıyor…"
                    WebRtcCallState.FAILED -> "Bağlantı başarısız"
                    WebRtcCallState.ENDED -> "Arama sonlandı"
                    else -> "Bağlanıyor…"
                },
                color = Color.White.copy(alpha = .8f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = {
                muted = !muted
                manager?.setMuted(muted)
            }) { Text(if (muted) "Sesi Aç" else "Sessiz") }
            Spacer(Modifier.width(8.dp))
            if (isVideo) {
                FilledTonalButton(onClick = {
                    cameraOn = !cameraOn
                    manager?.setCameraEnabled(cameraOn)
                }) { Text(if (cameraOn) "Kamera" else "Kamera Kapalı") }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalButton(onClick = {
                speakerOn = !speakerOn
                manager?.setSpeaker(speakerOn)
            }) { Text(if (speakerOn) "Hoparlör" else "Kulaklık") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                manager?.endCall()
                onEndCall()
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))) { Text("Kapat") }
        }
    }
}
