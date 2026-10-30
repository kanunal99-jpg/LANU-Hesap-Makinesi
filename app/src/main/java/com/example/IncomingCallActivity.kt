package com.example

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.calls.CallWakePolicy
import com.example.core.calls.CallWakePreferences
import com.example.core.calls.CallWakeTapPolicy
import com.example.features.communication.NativeCallPhase
import com.example.features.communication.NativeCallHost
import com.example.features.communication.CallReturnPrivacyPolicy
import com.example.ui.theme.MyApplicationTheme

/**
 * Public, call-only entry. No hidden navigation, PIN bypass or private chat
 * screen. Actual caller/signaling are verified independently from FCM via
 * the same encrypted relay as foreground calls.
 */
class IncomingCallActivity : ComponentActivity() {
    private var answerAt by mutableLongStateOf(0L)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (!mayOpenCallUi()) { finish(); return }
        readWakeIntent(intent)
        setContent {
            val state by (application as LanuApplication).callModel.state.collectAsState()
            var observedLiveCall by remember { mutableStateOf(false) }
            // A recent FCM hint is not proof that a call exists. Leave the
            // neutral verification screen if no signed invitation arrives.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(30_000L)
                if (!observedLiveCall &&
                    (application as LanuApplication).callModel.state.value.phase == NativeCallPhase.IDLE) finish()
            }
            LaunchedEffect(state.phase, state.callId) {
                // MainActivity can dismiss a completed call concurrently,
                // resetting the shared state to IDLE. Close this call-only
                // Activity in BOTH orders; never reveal the private back stack.
                if (state.callId.isNotBlank() && state.phase in setOf(
                        NativeCallPhase.RINGING, NativeCallPhase.DIALING,
                        NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED,
                        NativeCallPhase.RECONNECTING)) observedLiveCall = true
                if (CallReturnPrivacyPolicy.requiresLockedCalculator(state.phase, state.callId) ||
                    (observedLiveCall && state.phase == NativeCallPhase.IDLE)) finish()
            }
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text(
                            if (observedLiveCall) "Görüşme" else "Bildirim doğrulanıyor",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (observedLiveCall) "Bağlantı güvenle yönetiliyor."
                            else "Lütfen kontrol tamamlanana kadar bekleyin."
                        )
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = { finish() }) { Text("Kapat") }
                    }
                    NativeCallHost(
                        model = (application as LanuApplication).callModel,
                        request = null,
                        onRequestConsumed = {},
                        revealPeerName = false,
                        answerHintAtMs = answerAt,
                        onAnswerHintConsumed = { answerAt = 0L }
                    )
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!mayOpenCallUi()) { finish(); return }
        readWakeIntent(intent)
    }
    private fun incomingWakeAt(incoming: Intent?): Long =
        incoming?.getLongExtra(CallWakeTapPolicy.EXTRA_WAKE_AT, 0L) ?: 0L

    private fun mayOpenCallUi(): Boolean {
        val phase = (application as LanuApplication).callModel.state.value.phase
        val live = phase in setOf(NativeCallPhase.RINGING, NativeCallPhase.DIALING,
            NativeCallPhase.CONNECTING, NativeCallPhase.CONNECTED, NativeCallPhase.RECONNECTING)
        val at = incomingWakeAt(intent)
        return CallWakeTapPolicy.mayOpenCallUi(
            CallWakePreferences.isEnabled(this), live,
            CallWakeTapPolicy.freshWakeIntent(intent?.action, at, System.currentTimeMillis())
        )
    }
    private fun readWakeIntent(incoming: Intent?) {
        answerAt = 0L
        if (incoming?.action != CallWakeTapPolicy.ANSWER ||
            !CallWakePreferences.isEnabled(this)) return
        val at = incoming.getLongExtra(CallWakeTapPolicy.EXTRA_WAKE_AT, 0L)
        // This merely records a recent user gesture; it does NOT authorize
        // any peer or microphone permission until a signed invite matches.
        if (CallWakeTapPolicy.freshWakeIntent(incoming.action, at, System.currentTimeMillis())) answerAt = at
    }
}
