package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.app.KeyguardManager
import com.example.core.security.MediaPickerReturnPolicy
import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.core.media.InlineMediaPreparer
import com.example.core.media.InlineMediaCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.example.core.security.AppLifecycleObserver
import com.example.features.calculator.CalculatorScreen
import com.example.features.update.UpdateCheckScreen
import com.example.features.calculator.CalculatorViewModel
import com.example.features.calculator.UnitConverterScreen
import com.example.features.calculator.FinanceScreen
import com.example.features.communication.ChatDetailScreen
import com.example.features.communication.CommunicationScreen
import com.example.features.communication.CommunicationViewModel
import com.example.features.security.SecurityScreen
import com.example.features.security.SecurityViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var calcViewModel: CalculatorViewModel
    private lateinit var securityViewModel: SecurityViewModel
    private lateinit var commViewModel: CommunicationViewModel

    // The Activity (not the disposable chat Composable) owns picker result
    // callbacks. Opening a system picker must not bypass PIN/FLAG_SECURE.
    private var pendingPickerConversationId: String? = null

    // Volatile trusted Android system-picker session. Never persists across
    // process restarts; cannot unlock a previously locked private area.
    private var externalPickerStartedAtMs: Long? = null
    private var pickerBackgroundLockExempted = false
    private var screenOffDuringPicker = false

    private fun beginExternalPicker() {
        externalPickerStartedAtMs = SystemClock.elapsedRealtime()
        screenOffDuringPicker = false
    }

    private fun keyguardIsLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked != false

    private fun permittedPickerReturn(): Boolean =
        ::securityViewModel.isInitialized &&
            MediaPickerReturnPolicy.mayResumeWithoutPin(
                requirePinOnReturn = securityViewModel.requirePinOnMediaPickerReturn.value,
                pickerStartedElapsedMs = externalPickerStartedAtMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                isUnlocked = !securityViewModel.isLocked.value,
                screenWasLocked = screenOffDuringPicker || keyguardIsLocked()
            )

    private fun concealPrivateAreaIfLocked() {
        if (::securityViewModel.isInitialized && securityViewModel.isLocked.value &&
            navigationBackstack.lastOrNull()?.let { isSecureScreen(it) } == true) {
            navigationBackstack.clear()
            navigationBackstack.add(Screen.Calculator)
        }
    }

    private fun completeExternalPickerResult() {
        // Some Android system pickers are presented as overlays and do not
        // trigger Activity.onStop. Enforce the same opt-in PIN / expiry /
        // screen-off rules when their result arrives anyway.
        if (::securityViewModel.isInitialized &&
            externalPickerStartedAtMs != null &&
            !permittedPickerReturn()) {
            securityViewModel.lock()
            concealPrivateAreaIfLocked()
        }
        // onStart can be called before or after result delivery. Keep the
        // timestamp until a bypassed onStop/onStart pair is reconciled.
        if (!pickerBackgroundLockExempted) externalPickerStartedAtMs = null
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val chatId = takePendingPickerChat()
        if (chatId != null && uri != null) prepareMedia(chatId) {
            InlineMediaPreparer.imageFromPicker(this, uri)
        }
    }
    private val cameraPreviewPicker = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        val chatId = takePendingPickerChat()
        if (chatId != null && bitmap != null) prepareMedia(chatId) {
            InlineMediaPreparer.cameraPreview(bitmap)
        }
    }
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val chatId = takePendingPickerChat()
        if (chatId != null && uri != null) prepareMedia(chatId) {
            InlineMediaPreparer.documentFromPicker(this, uri)
        }
    }

    private fun takePendingPickerChat(): String? {
        val chatId = pendingPickerConversationId
        pendingPickerConversationId = null
        completeExternalPickerResult()
        return chatId
    }

    private fun prepareMedia(chatId: String, prepare: suspend () -> String) {
        lifecycleScope.launch {
            try {
                val envelope = prepare()
                if (::commViewModel.isInitialized) {
                    // No Toast here: the decoy calculator must not reveal
                    // the existence of hidden chats or a pending media attachment.
                    commViewModel.stashPreparedMedia(chatId, envelope)
                }
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity,
                    "Seçilen öğe işlenemedi.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchMediaPicker(chatId: String, kind: String) {
        if (!::securityViewModel.isInitialized || securityViewModel.isLocked.value) return
        if (pendingPickerConversationId != null) return
        pendingPickerConversationId = chatId
        beginExternalPicker()
        try {
            when (kind) {
                "gallery" -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "camera" -> cameraPreviewPicker.launch(null)
                "document" -> documentPicker.launch(arrayOf("application/pdf", "text/plain"))
                else -> {
                    pendingPickerConversationId = null
                    externalPickerStartedAtMs = null
                    return
                }
            }
        } catch (_: Exception) {
            pendingPickerConversationId = null
            externalPickerStartedAtMs = null
            Toast.makeText(this, "Android dosya seçicisi açılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    // Export is also an external Activity. The chat is re-locked in background,
    // so its callback must never be owned by the disappearing Compose screen.
    private var pendingExportAttachment: InlineMediaCodec.Attachment? = null
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { response ->
        val attachment = pendingExportAttachment
        pendingExportAttachment = null
        completeExternalPickerResult()
        val destination = response.data?.data
        if (response.resultCode == Activity.RESULT_OK &&
            destination != null && attachment != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(destination, "w")?.use { stream ->
                            stream.write(attachment.bytes)
                        } ?: throw IllegalStateException("Dosya açılamadı.")
                    }
                    Toast.makeText(this@MainActivity, "Dosya kaydedildi.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Dosya kaydedilemedi.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveAttachmentWithPicker(attachment: InlineMediaCodec.Attachment) {
        if (!::securityViewModel.isInitialized || securityViewModel.isLocked.value) return
        if (pendingExportAttachment != null) return
        pendingExportAttachment = attachment
        beginExternalPicker()
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(attachment.mime)
                .putExtra(Intent.EXTRA_TITLE, attachment.name)
            exportPicker.launch(intent)
        } catch (_: Exception) {
            pendingExportAttachment = null
            externalPickerStartedAtMs = null
            Toast.makeText(this, "Android dosya kaydetme ekranı açılamadı.", Toast.LENGTH_LONG).show()
        }
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeListener: SensorEventListener? = null

    // Light-weight state-driven routing backstack
    private val navigationBackstack = mutableStateListOf<Screen>(Screen.Calculator)
    private var backgroundTimestamp: Long = 0L

    // Broadcast receiver to detect screen lock (ACTION_SCREEN_OFF)
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                handleLockOnScreenOff()
            }
        }
    }

    // LifecycleObserver to transition to LOCKED state when backgrounded/paused
    private val appLifecycleObserver = AppLifecycleObserver(
        onAppBackgrounded = {
            handleAppBackgrounded()
        },
        onAppForegrounded = {
            handleAppForegrounded()
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        val app = application as LanuApplication
        val container = app.container

        // Register lifecycle observer
        lifecycle.addObserver(appLifecycleObserver)

        // Register screen lock broadcast receiver dynamically
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }

        // Instantiate ViewModels securely with factories
        calcViewModel = ViewModelProvider(this, CalculatorViewModel.Factory(container.calculatorRepository))[CalculatorViewModel::class.java]
        securityViewModel = ViewModelProvider(this, SecurityViewModel.Factory(container.securityRepository, container.securityEventTracker))[SecurityViewModel::class.java]
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
                        val diffTime = (curTime - lastUpdate)
                        lastUpdate = curTime

                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]

                        val speed = Math.abs(x + y + z - last_x - last_y - last_z) / diffTime * 10000

                        if (speed > 800) {
                            if (navigationBackstack.lastOrNull() is Screen.Calculator) {
                                calcViewModel.onKeyPress("AC")
                                // Silent operation: do not vibrate when shake-to-clear triggers.
                            }
                        }
                        last_x = x
                        last_y = y
                        last_z = z
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        accelerometer?.let {
            sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
        }

        setContent {
            // Silent by default across calculator, PIN, and private chat.
            // Compose's host View can play system click sounds independently of
            // explicit Vibrator calls; disable both sources without touching the
            // user's keyboard or global Android accessibility preferences.
            val uiView = LocalView.current
            SideEffect {
                configureSilentUi(uiView)
            }
            val themePreference by securityViewModel.themePreference.collectAsState()
            val buttonColorTheme by securityViewModel.buttonColorTheme.collectAsState()
            val darkTheme = when (themePreference) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                val isLocked by securityViewModel.isLocked.collectAsState()

                val currentScreen = navigationBackstack.lastOrNull() ?: Screen.Calculator
                // The decoy calculator and public utilities allow screenshots.
                // PIN and every private area ALWAYS block screenshots, independently
                // of the user's legacy optional screen-protection preference.
                SideEffect {
                    if (requiresScreenshotProtection(currentScreen)) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }

                // Security Observer: When locked while user is on secure screens (Chat, Call, etc.), redirect back
                LaunchedEffect(isLocked) {
                    if (isLocked) {
                        val currentScreen = navigationBackstack.lastOrNull()
                        if (currentScreen != null && isSecureScreen(currentScreen)) {
                            navigationBackstack.clear()
                            navigationBackstack.add(Screen.Calculator)
                        }
                    }
                }

                BackHandler(enabled = currentScreen is Screen.Communication) {
                    returnToCalculator()
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    // Silent and animation-free transitions during navigation
                    AnimatedContent(
                        targetState = if (isLocked && isSecureScreen(currentScreen)) Screen.Calculator else currentScreen,
                        transitionSpec = {
                            EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "ScreenNavigation"
                    ) { screen ->
                        when (screen) {
                            is Screen.Calculator -> {
                                CalculatorScreen(
                                    viewModel = calcViewModel,
                                    onNavigateToLock = {
                                        // "2011." is the private-area entry gesture, not
                                        // itself the PIN. Ask the durable repository whether
                                        // the owner opted out of a second PIN challenge.
                                        securityViewModel.enterFromCalculator(
                                            onOpen = {
                                                if (!securityViewModel.isLocked.value) {
                                                    navigationBackstack.clear()
                                                    navigationBackstack.add(Screen.Communication)
                                                }
                                            },
                                            onPinRequired = {
                                                securityViewModel.checkPinExists()
                                                navigateTo(Screen.Security())
                                            }
                                        )
                                    },
                                    onNavigateToUnitConverter = { initialValue ->
                                        navigateTo(Screen.UnitConverter(initialValue))
                                    },
                                    onNavigateToFinance = {
                                        navigateTo(Screen.Finance)
                                    },
                                    onNavigateToUpdates = {
                                        navigateTo(Screen.Updates)
                                    },
                                    themePreference = themePreference,
                                    buttonColorTheme = buttonColorTheme,
                                    onToggleTheme = {
                                        val nextTheme = when (themePreference) {
                                            "system" -> "light"
                                            "light" -> "dark"
                                            else -> "system"
                                        }
                                        securityViewModel.setThemePreference(nextTheme)
                                    }
                                )
                            }
                            is Screen.UnitConverter -> {
                                UnitConverterScreen(
                                    initialValue = screen.initialValue,
                                    onNavigateBack = {
                                        navigateBack()
                                    }
                                )
                            }
                            is Screen.Finance -> {
                                FinanceScreen(
                                    onNavigateBack = {
                                        navigateBack()
                                    }
                                )
                            }
                            is Screen.Updates -> {
                                // Edge-to-edge window: keep the navigation action out of the
                                // status bar, and all update controls above the gesture bar.
                                Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                    TextButton(onClick = { navigateBack() }) { Text("← Hesap makinesine dön") }
                                    UpdateCheckScreen(modifier = Modifier.weight(1f))
                                }
                            }
                            is Screen.Security -> {
                                SecurityScreen(
                                    viewModel = securityViewModel,
                                    onUnlockSuccess = {
                                        if (!securityViewModel.isLocked.value) {
                                            navigationBackstack.clear()
                                            navigationBackstack.add(Screen.Communication)
                                            // Only after valid PIN/biometric can an attachment
                                            // draft return to its original private conversation.
                                            val draft = commViewModel.preparedInlineMedia.value
                                            if (draft != null && draft.expiresAtMillis > System.currentTimeMillis()) {
                                                navigationBackstack.add(Screen.ChatDetail(draft.conversationId))
                                            } else if (draft != null) {
                                                commViewModel.clearPreparedMedia()
                                            }
                                        }
                                    },
                                    onNavigateBack = {
                                        returnToCalculator()
                                    },
                                    directUnlockPin = screen.directUnlockPin
                                )
                            }
                            is Screen.Communication -> {
                                CommunicationScreen(
                                    viewModel = commViewModel,
                                    securityViewModel = securityViewModel,
                                    onNavigateToChat = { chatId ->
                                        navigateTo(Screen.ChatDetail(chatId))
                                    },
                                    onNavigateToCalculator = {
                                        returnToCalculator()
                                    }
                                )
                            }
                            is Screen.ChatDetail -> {
                                ChatDetailScreen(
                                    viewModel = commViewModel,
                                    conversationId = screen.conversationId,
                                    onNavigateBack = {
                                        navigateBack()
                                    },
                                    onChoosePhoto = { launchMediaPicker(screen.conversationId, "gallery") },
                                    onTakePhoto = { launchMediaPicker(screen.conversationId, "camera") },
                                    onChooseDocument = { launchMediaPicker(screen.conversationId, "document") },
                                    onSaveAttachment = { attachment -> saveAttachmentWithPicker(attachment) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleLockOnScreenOff() {
        // Screen-off must always override the temporary picker convenience.
        screenOffDuringPicker = true
        pickerBackgroundLockExempted = false
        externalPickerStartedAtMs = null
        if (::securityViewModel.isInitialized) {
            securityViewModel.lock()
        }
        if (::calcViewModel.isInitialized) {
            calcViewModel.onKeyPress("AC")
        }
        val currentScreen = navigationBackstack.lastOrNull()
        if (currentScreen != null && isSecureScreen(currentScreen)) {
            navigationBackstack.clear()
            navigationBackstack.add(Screen.Calculator)
        }
    }

    private fun handleAppBackgrounded() {
        // This is the sole allowed exception: an Activity-launched Android
        // picker, still within the short session, while the phone is unlocked.
        // All ordinary app switches and screen-off events use normal auto-lock.
        if (permittedPickerReturn() &&
            (pendingPickerConversationId != null || pendingExportAttachment != null)) {
            pickerBackgroundLockExempted = true
            return
        }
        pickerBackgroundLockExempted = false
        backgroundTimestamp = SystemClock.elapsedRealtime()
        if (::securityViewModel.isInitialized) {
            securityViewModel.appBackgrounded()
            val timeoutMs = securityViewModel.lockTimeoutMs.value
            // Only lock immediately if timeout is set to 0 (Anında)
            if (timeoutMs == 0L) {
                securityViewModel.lock()
                if (::calcViewModel.isInitialized) {
                    calcViewModel.onKeyPress("AC")
                }
            }
        }
    }

    private fun handleAppForegrounded() {
        if (pickerBackgroundLockExempted) {
            pickerBackgroundLockExempted = false
            val returnIsTrusted = permittedPickerReturn()
            externalPickerStartedAtMs = null
            if (returnIsTrusted) {
                // A short picker round-trip is NOT a new private-area login.
                // Caller already authenticated before opening this picker.
                backgroundTimestamp = 0L
                securityViewModel.checkPinExists()
                return
            }
            // Expired picker, device lock, or explicit opt-in: fail closed.
            securityViewModel.lock()
            concealPrivateAreaIfLocked()
        }
        externalPickerStartedAtMs = null
        if (::securityViewModel.isInitialized) {
            val timeoutMs = securityViewModel.lockTimeoutMs.value
            val elapsed = SystemClock.elapsedRealtime() - backgroundTimestamp
            // If backgrounded and timeout expired (or immediate), lock the app!
            if (backgroundTimestamp > 0L && (timeoutMs >= 0L && elapsed >= timeoutMs)) {
                securityViewModel.lock()
            }
            securityViewModel.appForegrounded()
            securityViewModel.checkPinExists()
        }
        backgroundTimestamp = 0L

        // Conceal the private area on a locked foreground return.
        val hasPin = if (::securityViewModel.isInitialized) securityViewModel.hasPin.value else false
        val isLocked = if (::securityViewModel.isInitialized) securityViewModel.isLocked.value else true

        if (isLocked && hasPin) {
            val currentScreen = navigationBackstack.lastOrNull()
            if (currentScreen != null && isSecureScreen(currentScreen)) {
                navigationBackstack.clear()
                navigationBackstack.add(Screen.Calculator)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // appLifecycleObserver handles foreground events
    }

    override fun onPause() {
        super.onPause()
        // appLifecycleObserver handles background events
    }

    override fun onStop() {
        super.onStop()
        // appLifecycleObserver handles background events
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(appLifecycleObserver)
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {
            // Ignored if receiver wasn't registered
        }
        shakeListener?.let {
            sensorManager?.unregisterListener(it)
        }
    }

    private fun returnToCalculator() {
        securityViewModel.lock()
        calcViewModel.onKeyPress("AC")
        navigationBackstack.clear()
        navigationBackstack.add(Screen.Calculator)
    }

    // Helper functions for our lightweight navigator
    private fun navigateTo(screen: Screen) {
        // Remove existing instances of same class to prevent backstack duplicates
        navigationBackstack.removeAll { it::class == screen::class }
        navigationBackstack.add(screen)
    }

    private fun navigateBack() {
        if (navigationBackstack.size > 1) {
            navigationBackstack.removeAt(navigationBackstack.size - 1)
        }
    }

    private fun isSecureScreen(screen: Screen): Boolean {
        return screen is Screen.Communication || screen is Screen.ChatDetail
    }

    companion object {
        /** Disable automatic host feedback without changing system/keyboard preferences. */
        fun configureSilentUi(view: View) {
            view.isSoundEffectsEnabled = false
            view.isHapticFeedbackEnabled = false
        }

        /** Decoy and public utilities may be captured; PIN + private screens never may. */
        fun requiresScreenshotProtection(screen: Screen): Boolean =
            screen is Screen.Security || screen is Screen.Communication || screen is Screen.ChatDetail
    }

    // Define Screen state models
    sealed class Screen {
        object Calculator : Screen()
        data class UnitConverter(val initialValue: String? = null) : Screen()
        object Finance : Screen()
        object Updates : Screen()
        data class Security(val directUnlockPin: String? = null) : Screen()
        object Communication : Screen()
        data class ChatDetail(val conversationId: String) : Screen()
    }
}

