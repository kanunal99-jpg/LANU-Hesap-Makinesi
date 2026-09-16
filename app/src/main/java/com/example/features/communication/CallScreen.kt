package com.example.features.communication

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import android.widget.Toast
import kotlinx.coroutines.delay

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.core.calls.*
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.BrightnessAuto

enum class AncLevel { LOW, MEDIUM, HIGH, ADAPTIVE }

@Composable
fun CallScreen(
    partnerName: String,
    isVideo: Boolean,
    themePreference: String = "system",
    onToggleTheme: () -> Unit = {},
    onEndCall: (Int) -> Unit
) {
    val context = LocalContext.current

    val dspProcessor = remember { AudioDspProcessor(context) }
    val dspTelemetry by dspProcessor.telemetry.collectAsState()

    val syncEngine = remember { MediaSyncEngine() }
    val netTelemetry by syncEngine.telemetry.collectAsState()

    var selectedDiagnosticTab by remember { mutableIntStateOf(0) }

    var callState by remember { mutableStateOf(CallState.CONNECTING) }
    var callDurationSeconds by remember { mutableIntStateOf(0) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(isVideo) }
    var isCameraOn by remember { mutableStateOf(isVideo) }
    var isFrontCamera by remember { mutableStateOf(true) }
    var isBluetoothRouted by remember { mutableStateOf(false) }
    var isAncEnabled by remember { mutableStateOf(true) }
    var ancLevel by remember { mutableStateOf(AncLevel.HIGH) }
    var showAncDiagnostics by remember { mutableStateOf(false) }

    LaunchedEffect(isAncEnabled, ancLevel) {
        dspProcessor.updateAncParameters(isAncEnabled, ancLevel.name)
    }

    LaunchedEffect(callState) {
        if (callState == CallState.POOR_CONNECTION || callState == CallState.RECONNECTING) {
            syncEngine.onNetworkDegraded()
        } else if (callState == CallState.CONNECTED) {
            syncEngine.onNetworkRecovered()
        }
    }

    // Pulsing animation for RINGING state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (callState == CallState.RINGING) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = if (callState == CallState.RINGING) 0f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    val waveformPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveform_phase"
    )

    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }

    // Call state machine and clock counter simulation
    LaunchedEffect(Unit) {
        delay(1500)
        callState = CallState.RINGING
        delay(1500)
        callState = CallState.CONNECTED

        while (callState == CallState.CONNECTED || callState == CallState.POOR_CONNECTION) {
            delay(1000)
            callDurationSeconds++
            
            // Simulate random occasional connection drops to verify reconnect states
            if (callDurationSeconds == 15) {
                callState = CallState.POOR_CONNECTION
            } else if (callDurationSeconds == 20) {
                callState = CallState.RECONNECTING
                delay(2000)
                callState = CallState.CONNECTED
            }
        }
    }

    val durationText = String.format(
        Locale.getDefault(),
        "%02d:%02d",
        callDurationSeconds / 60,
        callDurationSeconds % 60
    )

    // Background gradient changes according to connection quality
    val backgroundBrush = when (callState) {
        CallState.POOR_CONNECTION -> {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF3E2723), Color(0xFF1E1E1E))
            )
        }
        CallState.RECONNECTING -> {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF263238), Color(0xFF1E1E1E))
            )
        }
        else -> {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF0F172A), Color(0xFF020617))
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(24.dp)
    ) {
        // Theme Toggle Button
        IconButton(
            onClick = {
                triggerVibration()
                onToggleTheme()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.1f))
        ) {
            val icon = when (themePreference) {
                "dark" -> Icons.Default.DarkMode
                "light" -> Icons.Default.LightMode
                else -> Icons.Default.BrightnessAuto
            }
            Icon(
                imageVector = icon,
                contentDescription = "Tema Değiştir",
                tint = Color.White
            )
        }

        // Top Connection Info HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isVideo) stringResource(R.string.secure_video_call_title) else stringResource(R.string.secure_voice_call_title),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = partnerName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Add E2EE & High Quality Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "E2EE",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF4CAF50).copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HD Opus",
                        color = Color(0xFF4CAF50),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF2196F3).copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SignalCellular4Bar,
                        contentDescription = null,
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "4G Optimized",
                        color = Color(0xFF2196F3),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isAncEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF9C27B0).copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HearingDisabled,
                            contentDescription = null,
                            tint = Color(0xFFE040FB),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val levelText = when(ancLevel) {
                            AncLevel.LOW -> stringResource(R.string.anc_level_low)
                            AncLevel.MEDIUM -> stringResource(R.string.anc_level_medium)
                            AncLevel.HIGH -> stringResource(R.string.anc_level_high)
                            AncLevel.ADAPTIVE -> stringResource(R.string.anc_level_adaptive)
                        }
                        Text(
                            text = stringResource(R.string.anc_active_badge, levelText),
                            color = Color(0xFFE040FB),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Call Status Badge
            Text(
                text = when (callState) {
                    CallState.CONNECTING -> stringResource(R.string.webrtc_connecting)
                    CallState.RINGING -> stringResource(R.string.call_ringing)
                    CallState.CONNECTED -> stringResource(R.string.call_connected, durationText)
                    CallState.POOR_CONNECTION -> stringResource(R.string.call_poor_connection, durationText)
                    CallState.RECONNECTING -> stringResource(R.string.call_reconnecting)
                    CallState.ENDED -> stringResource(R.string.call_ended)
                    CallState.FAILED -> stringResource(R.string.call_failed)
                },
                color = when (callState) {
                    CallState.CONNECTED -> MaterialTheme.colorScheme.primary
                    CallState.POOR_CONNECTION, CallState.RECONNECTING -> Color.Yellow
                    else -> Color.White.copy(alpha = 0.6f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        // Mid Video Feed / Avatar Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            if (isCameraOn && callState != CallState.POOR_CONNECTION && callState != CallState.RECONNECTING) {
                // Interactive Camera Feed Simulation
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.DarkGray)
                ) {
                    Text(
                        text = stringResource(R.string.webrtc_active_stream),
                        color = Color.White,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else {
                // Large Calling Avatar Pulse
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (callState == CallState.RINGING) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                }
                        )
                    }
                    Text(
                        text = partnerName.take(2).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    )
                }
            }
        }

        // Action Tray
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Audio Waveform Visualization
            if (callState == CallState.CONNECTED) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 24.dp)
                ) {
                    val barWidth = 4.dp.toPx()
                    val spacing = 4.dp.toPx()
                    val totalBars = (size.width / (barWidth + spacing)).toInt()
                    val centerY = size.height / 2f
                    
                    for (i in 0 until totalBars) {
                        // base voice wave
                        val baseVoice = if (isMuted) 0f else Math.sin(waveformPhase.toDouble() * 3 + i * 0.4).toFloat() * (size.height / 2f)
                        
                        // noise amplitude depends on ANC
                        val noiseFactor = when {
                            isMuted -> 0f
                            !isAncEnabled -> 1f
                            ancLevel == AncLevel.LOW -> 0.5f
                            ancLevel == AncLevel.MEDIUM -> 0.2f
                            ancLevel == AncLevel.HIGH -> 0.05f
                            else -> 0.1f // ADAPTIVE ANC
                        }
                        
                        val noise = if (isMuted) 0f else (Math.cos((waveformPhase * 8).toDouble() + i * 1.5).toFloat() * (size.height / 2f)) * noiseFactor
                        
                        val totalHeight = Math.abs(baseVoice * 0.5f + noise).coerceAtLeast(4f)
                        val color = if (isMuted) Color.White.copy(alpha = 0.3f) else if (isAncEnabled) Color(0xFF4CAF50) else Color(0xFF2196F3)
                        val alpha = 1f - (Math.abs(i - totalBars / 2f) / (totalBars / 2f)).coerceIn(0f, 1f)
                        
                        drawLine(
                            color = color.copy(alpha = alpha.coerceIn(0.1f, 1f)),
                            start = androidx.compose.ui.geometry.Offset(x = i * (barWidth + spacing) + barWidth / 2, y = centerY - totalHeight / 2),
                            end = androidx.compose.ui.geometry.Offset(x = i * (barWidth + spacing) + barWidth / 2, y = centerY + totalHeight / 2),
                            strokeWidth = barWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }

            // Persistent ANC Control Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val ancEnabledText = stringResource(R.string.anc_enabled)
                val ancDisabledText = stringResource(R.string.anc_disabled)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAncEnabled) Icons.Default.Hearing else Icons.Default.HearingDisabled,
                            contentDescription = null,
                            tint = if (isAncEnabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.anc_toggle),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { 
                                triggerVibration()
                                showAncDiagnostics = !showAncDiagnostics 
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                contentDescription = stringResource(R.string.anc_diagnostics_title),
                                tint = if (showAncDiagnostics) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Switch(
                        checked = isAncEnabled,
                        onCheckedChange = { checked ->
                            triggerVibration()
                            isAncEnabled = checked
                            val msg = if (checked) ancEnabledText else ancDisabledText
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }

                AnimatedVisibility(
                    visible = isAncEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val sliderValue = when (ancLevel) {
                            AncLevel.LOW -> 0f
                            AncLevel.MEDIUM -> 1f
                            AncLevel.HIGH -> 2f
                            AncLevel.ADAPTIVE -> 3f
                        }
                        val levelName = when (ancLevel) {
                            AncLevel.LOW -> stringResource(R.string.anc_level_low)
                            AncLevel.MEDIUM -> stringResource(R.string.anc_level_medium)
                            AncLevel.HIGH -> stringResource(R.string.anc_level_high)
                            AncLevel.ADAPTIVE -> stringResource(R.string.anc_level_adaptive)
                        }
                        Text(
                            text = "Seviye: $levelName",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = sliderValue,
                            onValueChange = { newVal ->
                                val newLevel = when (Math.round(newVal)) {
                                    0 -> AncLevel.LOW
                                    1 -> AncLevel.MEDIUM
                                    2 -> AncLevel.HIGH
                                    else -> AncLevel.ADAPTIVE
                                }
                                if (newLevel != ancLevel) {
                                    triggerVibration()
                                    ancLevel = newLevel
                                }
                            },
                            valueRange = 0f..3f,
                            steps = 2,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            )
                        )
                    }
                }

                // Advanced System, DSP, Latency Sync & Business Architecture Suite
                AnimatedVisibility(
                    visible = showAncDiagnostics,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Diagnostic Tab Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val tabs = listOf("DSP / ANC", "Ağ & Senkron", "İş Modeli")
                            tabs.forEachIndexed { index, title ->
                                val isSelected = selectedDiagnosticTab == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f))
                                        .clickable {
                                            triggerVibration()
                                            selectedDiagnosticTab = index
                                        }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        Divider(color = Color.White.copy(alpha = 0.15f), thickness = 0.5.dp)

                        when (selectedDiagnosticTab) {
                            0 -> {
                                // TAB 0: Hybrid DSP & ANC
                                Text(
                                    text = stringResource(R.string.anc_diagnostics_title),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // Hardware probe result & software fallback
                                if (dspTelemetry.isHardwareSupported && dspTelemetry.isHardwareActive) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                        Text(text = stringResource(R.string.anc_hardware_ok), color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(14.dp))
                                            Text(text = stringResource(R.string.anc_hardware_unsupported), color = Color(0xFFFFB74D), fontSize = 11.sp)
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                            Text(text = stringResource(R.string.anc_software_fallback, 88), color = Color(0xFF4CAF50), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // DSP Mode selector
                                Text(
                                    text = "İşleme Modu Seçimi:",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val modes = listOf(
                                        Triple("Hibrit", DspProcessingMode.AUTO_HYBRID, "Otomatik"),
                                        Triple("Yazılım", DspProcessingMode.SOFTWARE_DSP, "Yazılımsal"),
                                        Triple("Donanım", DspProcessingMode.HARDWARE_PREF, "Donanım")
                                    )
                                    modes.forEach { (label, modeVal, _) ->
                                        val isCurrent = dspTelemetry.mode == modeVal
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isCurrent) Color(0xFF4CAF50).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
                                                .border(1.dp, if (isCurrent) Color(0xFF4CAF50) else Color.Transparent, RoundedCornerShape(4.dp))
                                                .clickable {
                                                    triggerVibration()
                                                    dspProcessor.setDspMode(modeVal)
                                                }
                                                .padding(vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                color = if (isCurrent) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.8f),
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }

                                // DSP Telemetry Metrics
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(text = "Aktif Motor: ${dspTelemetry.activeAlgorithm}", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                                    Text(text = "DSP Gecikmesi: ${String.format(Locale.getDefault(), "%.1f ms (Oboe Ultra-Low Latency)", dspTelemetry.latencyMs)}", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                                    Text(text = "Gürültü Baskılama Oranı: -${dspTelemetry.suppressionRatioDb} dB", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                                    Text(text = "Ortam Gürültü Tabanı: ${String.format(Locale.getDefault(), "%.1f dBFS", dspTelemetry.noiseFloorDbfs)}", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                                    Text(text = "Ses Netlik İndeksi: %${dspTelemetry.voiceClarityScore} (Mükemmel)", color = Color(0xFF81C784), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            1 -> {
                                // TAB 1: Network, Latency & Lip-Sync
                                Text(
                                    text = stringResource(R.string.net_diagnostics_title),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.net_rtt_label, netTelemetry.rttMs),
                                        color = if (netTelemetry.rttMs < 50) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.net_jitter_label, netTelemetry.jitterMs, netTelemetry.playoutBufferDepthMs),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.net_lipsync_label, netTelemetry.lipSyncSkewMs, "Kusursuz Senkron"),
                                        color = Color(0xFF4CAF50),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(R.string.net_packet_loss_label, netTelemetry.packetLossPct),
                                        color = if (netTelemetry.packetLossPct == 0f) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.net_candidate_label, netTelemetry.candidateType),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = stringResource(R.string.net_bitrate_label, netTelemetry.videoResolution, netTelemetry.currentBitrateKbps),
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        text = "Trafik Kontrolü: ${netTelemetry.congestionLevel}",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.sp
                                    )
                                }

                                // Interactive Handover & ICE restart test button
                                Button(
                                    onClick = {
                                        triggerVibration()
                                        syncEngine.triggerIceRestart {
                                            Toast.makeText(context, "Ağ geçişi tamamlandı (Relay TLS :443) - Çağrı kesilmedi!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(6.dp),
                                    enabled = !netTelemetry.isIceRestarting
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (netTelemetry.isIceRestarting) stringResource(R.string.net_ice_restarting) else stringResource(R.string.net_ice_restart),
                                        fontSize = 10.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            2 -> {
                                // TAB 2: Business Model & Architecture
                                Text(
                                    text = stringResource(R.string.business_model_title),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.business_model_desc),
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 9.sp
                                )

                                LanuBusinessArchitecture.tiers.forEach { tier ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (tier.id == "privacy_pro") MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                else Color.White.copy(alpha = 0.05f)
                                            )
                                            .border(
                                                1.dp,
                                                if (tier.id == "privacy_pro") MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                                else Color.White.copy(alpha = 0.1f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = tier.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(text = tier.badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = tier.targetAudience, color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                                            Text(text = tier.price, color = Color(0xFF81C784), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        tier.features.forEach { feat ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(10.dp))
                                                Text(text = feat, color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Aux Control Layer (Speaker, Bluetooth, Camera, Rotate)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Toggle Speaker
                IconButton(
                    onClick = {
                        triggerVibration()
                        isSpeakerOn = !isSpeakerOn
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSpeakerOn) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                ) {
                    Icon(
                        imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                        contentDescription = stringResource(R.string.speaker_toggle),
                        tint = Color.White
                    )
                }

                // Bluetooth routing indicator
                IconButton(
                    onClick = {
                        triggerVibration()
                        isBluetoothRouted = !isBluetoothRouted
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isBluetoothRouted) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = stringResource(R.string.bluetooth_router),
                        tint = if (isBluetoothRouted) MaterialTheme.colorScheme.primary else Color.White
                    )
                }

                // Camera Toggle
                if (isVideo) {
                    IconButton(
                        onClick = {
                            triggerVibration()
                            isCameraOn = !isCameraOn
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isCameraOn) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    ) {
                        Icon(
                            imageVector = if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = stringResource(R.string.camera_toggle),
                            tint = Color.White
                        )
                    }

                    // Rotate Camera Switch
                    IconButton(
                        onClick = {
                            triggerVibration()
                            isFrontCamera = !isFrontCamera
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isFrontCamera) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = stringResource(R.string.cameraswitch_desc),
                            tint = Color.White
                        )
                    }
                }
            }

            // Primary Call actions (Mute, Decline/End)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute Mic
                IconButton(
                    onClick = {
                        triggerVibration()
                        isMuted = !isMuted
                    },
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isMuted) Color.White.copy(alpha = 0.2f) else Color.Transparent)
                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.mute_mic_desc),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Red End Call Button
                IconButton(
                    onClick = {
                        triggerVibration()
                        callState = CallState.ENDED
                        onEndCall(callDurationSeconds)
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .testTag("end_call_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.end_call_desc),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

enum class CallState {
    CONNECTING,
    RINGING,
    CONNECTED,
    POOR_CONNECTION,
    RECONNECTING,
    ENDED,
    FAILED
}
