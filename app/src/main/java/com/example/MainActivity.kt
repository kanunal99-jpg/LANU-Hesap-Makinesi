package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.core.security.AppLifecycleObserver
import com.example.core.security.AppLockState
import com.example.core.security.AppLockViewModel
import com.example.features.calculator.AuditPanelScreen
import com.example.features.calculator.CalculatorScreen
import com.example.features.calculator.CalculatorViewModel
import com.example.features.calculator.CollaborationScreen
import com.example.features.calculator.FinanceScreen
import com.example.features.calculator.UnitConverterScreen
import com.example.features.communication.ChatDetailScreen
import com.example.features.communication.CommunicationScreen
import com.example.features.communication.CommunicationViewModel
import com.example.features.communication.RealCallScreen
import com.example.features.security.SecurityScreen
import com.example.features.security.SecurityViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var calcViewModel: CalculatorViewModel
    private lateinit var securityViewModel: SecurityViewModel
    private lateinit var appLockViewModel: AppLockViewModel
    private lateinit var commViewModel: CommunicationViewModel

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeListener: SensorEventListener? = null

    private val navigationBackstack = mutableStateListOf<Screen>(Screen.Calculator)
    private var backgroundTimestamp: Long = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) handleLockOnScreenOff()
        }
    }

    private val appLifecycleObserver = AppLifecycleObserver(
        onAppBackgrounded = { handleAppBackgrounded() },
        onAppForegrounded = { handleAppForegrounded() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LanuApplication
        val container = app.container
        lifecycle.addObserver(appLifecycleObserver)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

        calcViewModel = ViewModelProvider(this, CalculatorViewModel.Factory(container.calculatorRepository))[CalculatorViewModel::class.java]
        securityViewModel = ViewModelProvider(this, SecurityViewModel.Factory(container.securityRepository, container.securityEventTracker))[SecurityViewModel::class.java]
        appLockViewModel = ViewModelProvider(this, AppLockViewModel.Factory(securityViewModel))[AppLockViewModel::class.java]
        commViewModel = ViewModelProvider(this, CommunicationViewModel.Factory(container.communicationRepository))[CommunicationViewModel::class.java]

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        shakeListener = object : SensorEventListener {
            private var lastUpdate: Long = 0
            private var last_x = 0f
            private var last_y = 0f
            private var last_z = 0f

            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                    val curTime = System.currentTimeMillis()
                    if ((curTime - lastUpdate) > 100) {
                        val diffTime = curTime - lastUpdate
                        lastUpdate = curTime
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        val speed = Math.abs(x + y + z - last_x - last_y - last_z) / diffTime * 10000
                        if (speed > 800 && navigationBackstack.lastOrNull() is Screen.Calculator) {
                            calcViewModel.onKeyPress("AC")
                            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                @Suppress("DEPRECATION") vibrator?.vibrate(100)
                            }
                        }
                        last_x = x; last_y = y; last_z = z
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        accelerometer?.let { sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI) }

        setContent {
            val themePreference by securityViewModel.themePreference.collectAsState()
            val buttonColorTheme by securityViewModel.buttonColorTheme.collectAsState()
            val darkTheme = when (themePreference) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                val isLocked by securityViewModel.isLocked.collectAsState()
                val hasPin by securityViewModel.hasPin.collectAsState()
                val screenProtection by securityViewModel.screenProtectionEnabled.collectAsState()
                val incomingCall by commViewModel.incomingCall.collectAsState()

                LaunchedEffect(screenProtection) {
                    if (screenProtection) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }

                LaunchedEffect(isLocked) {
                    if (isLocked) {
                        val currentScreen = navigationBackstack.lastOrNull()
                        if (currentScreen != null && isSecureScreen(currentScreen)) {
                            navigationBackstack.clear()
                            if (hasPin) navigationBackstack.add(Screen.Security()) else navigationBackstack.add(Screen.Calculator)
                        }
                    }
                }

                if (incomingCall != null && !isLocked) {
                    val call = incomingCall!!
                    AlertDialog(
                        onDismissRequest = { commViewModel.rejectIncomingCall() },
                        title = { Text(if (call.isVideo) "Görüntülü arama" else "Sesli arama") },
                        text = { Text("${call.partnerName} sizi arıyor.") },
                        confirmButton = {
                            TextButton(onClick = {
                                commViewModel.acceptIncomingCall()
                                navigateTo(Screen.Call(call.conversationId, call.partnerName, call.isVideo, outgoing = false))
                            }) { Text("Cevapla") }
                        },
                        dismissButton = {
                            TextButton(onClick = { commViewModel.rejectIncomingCall() }) { Text("Reddet") }
                        }
                    )
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = navigationBackstack.lastOrNull() ?: Screen.Calculator,
                        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                        label = "ScreenNavigation"
                    ) { screen ->
                        when (screen) {
                            is Screen.Calculator -> CalculatorScreen(
                                viewModel = calcViewModel,
                                onNavigateToLock = { securityViewModel.checkPinExists(); navigateTo(Screen.Security()) },
                                onNavigateWithDirectUnlock = { directPin -> securityViewModel.checkPinExists(); navigateTo(Screen.Security(directPin)) },
                                onNavigateToUnitConverter = { initialValue -> navigateTo(Screen.UnitConverter(initialValue)) },
                                onNavigateToFinance = { navigateTo(Screen.Finance) },
                                onNavigateToCollaboration = { navigateTo(Screen.Collaboration) },
                                onNavigateToAuditPanel = { navigateTo(Screen.AuditPanel) },
                                themePreference = themePreference,
                                buttonColorTheme = buttonColorTheme,
                                onToggleTheme = {
                                    val nextTheme = when (themePreference) { "system" -> "light"; "light" -> "dark"; else -> "system" }
                                    securityViewModel.setThemePreference(nextTheme)
                                }
                            )
                            is Screen.UnitConverter -> UnitConverterScreen(initialValue = screen.initialValue, onNavigateBack = { navigateBack() })
                            is Screen.Finance -> FinanceScreen(onNavigateBack = { navigateBack() })
                            is Screen.Collaboration -> CollaborationScreen(onNavigateBack = { navigateBack() })
                            is Screen.AuditPanel -> AuditPanelScreen(onNavigateBack = { navigateBack() })
                            is Screen.Security -> SecurityScreen(
                                viewModel = securityViewModel,
                                onUnlockSuccess = { appLockViewModel.unlockApp(); securityViewModel.checkPinExists(); navigateTo(Screen.Communication) },
                                onNavigateBack = { navigateTo(Screen.Calculator) },
                                directUnlockPin = screen.directUnlockPin
                            )
                            is Screen.Communication -> CommunicationScreen(
                                viewModel = commViewModel,
                                securityViewModel = securityViewModel,
                                onNavigateToChat = { chatId -> navigateTo(Screen.ChatDetail(chatId)) },
                                onNavigateToCalculator = { securityViewModel.lock(); navigateTo(Screen.Calculator) }
                            )
                            is Screen.ChatDetail -> ChatDetailScreen(
                                viewModel = commViewModel,
                                conversationId = screen.conversationId,
                                onNavigateBack = { navigateBack() },
                                onNavigateToCall = { partner, isVideo -> navigateTo(Screen.Call(screen.conversationId, partner, isVideo, outgoing = true)) }
                            )
                            is Screen.Call -> RealCallScreen(
                                partnerName = screen.partnerName,
                                conversationId = screen.conversationId,
                                isVideo = screen.isVideo,
                                outgoing = screen.outgoing,
                                supabaseUrl = container.supabaseBaseUrl,
                                supabaseKey = container.supabasePublishableKey,
                                onEndCall = {
                                    commViewModel.insertSystemMessage(screen.partnerName, "📞 Arama Bitti")
                                    navigateBack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleLockOnScreenOff() {
        if (::appLockViewModel.isInitialized) appLockViewModel.lockApp() else if (::securityViewModel.isInitialized) securityViewModel.lock()
        if (::calcViewModel.isInitialized) calcViewModel.onKeyPress("AC")
        val currentScreen = navigationBackstack.lastOrNull()
        if (currentScreen != null && isSecureScreen(currentScreen)) {
            navigationBackstack.clear(); navigationBackstack.add(Screen.Calculator)
        }
    }

    private fun handleAppBackgrounded() {
        backgroundTimestamp = System.currentTimeMillis()
        if (::securityViewModel.isInitialized) {
            securityViewModel.appBackgrounded()
            val timeoutMs = securityViewModel.lockTimeoutMs.value
            if (timeoutMs == 0L) {
                if (::appLockViewModel.isInitialized) appLockViewModel.lockApp() else securityViewModel.lock()
                if (::calcViewModel.isInitialized) calcViewModel.onKeyPress("AC")
            }
        }
    }

    private fun handleAppForegrounded() {
        if (::securityViewModel.isInitialized) {
            val timeoutMs = securityViewModel.lockTimeoutMs.value
            val elapsed = System.currentTimeMillis() - backgroundTimestamp
            if (backgroundTimestamp > 0L && (timeoutMs == 0L || elapsed >= timeoutMs)) {
                if (::appLockViewModel.isInitialized) appLockViewModel.lockApp() else securityViewModel.lock()
            }
            securityViewModel.appForegrounded(); securityViewModel.checkPinExists()
        }
        backgroundTimestamp = 0L
        val hasPin = if (::securityViewModel.isInitialized) securityViewModel.hasPin.value else false
        val isLocked = if (::appLockViewModel.isInitialized) appLockViewModel.lockState.value == AppLockState.LOCKED else if (::securityViewModel.isInitialized) securityViewModel.isLocked.value else true
        if (isLocked && hasPin) {
            val currentScreen = navigationBackstack.lastOrNull()
            if (currentScreen != null && isSecureScreen(currentScreen)) {
                navigationBackstack.clear(); navigationBackstack.add(Screen.Security())
            }
        }
    }

    override fun onStart() { super.onStart() }
    override fun onPause() { super.onPause() }
    override fun onStop() { super.onStop() }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(appLifecycleObserver)
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        shakeListener?.let { sensorManager?.unregisterListener(it) }
    }

    private fun navigateTo(screen: Screen) {
        navigationBackstack.removeAll { it::class == screen::class }
        navigationBackstack.add(screen)
    }

    private fun navigateBack() {
        if (navigationBackstack.size > 1) navigationBackstack.removeAt(navigationBackstack.size - 1)
    }

    private fun isSecureScreen(screen: Screen): Boolean = screen is Screen.Communication || screen is Screen.ChatDetail || screen is Screen.Call

    sealed class Screen {
        object Calculator : Screen()
        data class UnitConverter(val initialValue: String? = null) : Screen()
        object Finance : Screen()
        object Collaboration : Screen()
        object AuditPanel : Screen()
        data class Security(val directUnlockPin: String? = null) : Screen()
        object Communication : Screen()
        data class ChatDetail(val conversationId: String) : Screen()
        data class Call(val conversationId: String, val partnerName: String, val isVideo: Boolean, val outgoing: Boolean = true) : Screen()
    }
}
