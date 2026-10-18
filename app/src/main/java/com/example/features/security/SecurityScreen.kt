package com.example.features.security

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.biometric.BiometricPrompt
import com.example.core.security.PinPolicy
import com.example.core.security.BiometricAuthManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executors

@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onUnlockSuccess: () -> Unit,
    onNavigateBack: () -> Unit = {},
    directUnlockPin: String? = null
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val biometricStatus = remember(context) {
        BiometricAuthManager.checkBiometricSupport(context)
    }
    val hasBiometricHardware = remember(biometricStatus) {
        biometricStatus != BiometricAuthManager.BiometricStatus.NO_HARDWARE &&
        biometricStatus != BiometricAuthManager.BiometricStatus.UNSUPPORTED
    }
    val isBiometricAvailableOnDevice = remember(biometricStatus) {
        biometricStatus == BiometricAuthManager.BiometricStatus.AVAILABLE
    }

    val hasPin by viewModel.hasPin.collectAsState()
    val setupStep by viewModel.setupStep.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val showBiometricOptIn by viewModel.showBiometricOptIn.collectAsState()
    val lockoutRemainingSeconds by viewModel.lockoutRemainingSeconds.collectAsState()
    val isAuthenticating by viewModel.isAuthenticating.collectAsState()
    val isReady by viewModel.isReady.collectAsState()
    val inputEnabled = isReady && !isAuthenticating && lockoutRemainingSeconds == 0
    var biometricPrompt by remember { mutableStateOf<BiometricPrompt?>(null) }
    var screenActive by remember { mutableStateOf(true) }

    DisposableEffect(viewModel) {
        screenActive = true
        onDispose {
            screenActive = false
            biometricPrompt?.cancelAuthentication()
            viewModel.resetSetup()
        }
    }

    var enteredDigits by remember { mutableStateOf("") }

    // Haptic vibration helper
    val triggerVibration = {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(30)
        }
    }

    // The calculator entry code only opens authentication; it is never a PIN bypass.
    LaunchedEffect(directUnlockPin, isReady) {
        if (isReady && !directUnlockPin.isNullOrBlank() && directUnlockPin != "2011.") {
            viewModel.handlePinEntered(directUnlockPin, onUnlockSuccess)
        }
    }

    // Centralized biometric prompt launcher helper using BiometricAuthManager
    val showBiometricPrompt = {
        if (hasPin && biometricEnabled && inputEnabled) {
            val activity = context as? FragmentActivity
            if (activity != null) {
                biometricPrompt = BiometricAuthManager.authenticate(
                    activity = activity,
                    onSuccess = {
                        if (screenActive && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            viewModel.onBiometricAuthenticated(onUnlockSuccess)
                        }
                    },
                    onFallbackToPin = {
                        // Keep user on the PIN entry screen and let them enter digits manually
                        viewModel.logEvent("BIOMETRIC_FALLBACK", "Fallback to PIN entry screen")
                    },
                    onError = { errorCode, errorMessage ->
                        viewModel.logEvent("BIOMETRIC_FAILURE", "Biometric authentication failed. Error code: $errorCode")
                        android.widget.Toast.makeText(context, errorMessage, android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // Trigger Biometric verification on launch if enabled and PIN exists
    LaunchedEffect(hasPin, biometricEnabled, isReady) {
        if (hasPin && biometricEnabled && inputEnabled) {
            showBiometricPrompt()
        }
    }

    if (showBiometricOptIn) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBiometricOptIn(false) },
            title = {
                Text(
                    text = "Biyometrik Kimlik Doğrulama",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Gelecekte güvenli iletişim alanına hızlı erişmek için PIN kodu yerine parmak izi veya yüz tanıma kullanmak ister misiniz?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        triggerVibration()
                        viewModel.dismissBiometricOptIn(true)
                    }
                ) {
                    Text("Evet, Etkinleştir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        triggerVibration()
                        viewModel.dismissBiometricOptIn(false)
                    }
                ) {
                    Text("Şimdi Değil")
                }
            }
        )
    }

    val titleText = when {
        !hasPin && setupStep == SecurityViewModel.SetupStep.ENTER_PIN -> "Güvenli PIN'inizi Oluşturun"
        !hasPin && setupStep == SecurityViewModel.SetupStep.CONFIRM_PIN -> "PIN'inizi Onaylayın"
        else -> "Kilidi Açmak İçin PIN Girin"
    }

    val subtitleText = when {
        !hasPin -> "4–12 rakam seçin ve Devam'a dokunun"
        else -> "4–12 haneli PIN'inizi girip Doğrula'ya dokunun"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    triggerVibration()
                    onNavigateBack()
                },
                modifier = Modifier.testTag("security_back_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Hesap Makinesine Dön",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Hesap Makinesine Dön",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.clickable {
                    triggerVibration()
                    onNavigateBack()
                }
            )
        }

        // Top Header without lock icon
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = titleText,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitleText,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        // Animated passcode-dot visualizer
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(maxOf(PinPolicy.MIN_LENGTH, enteredDigits.length)) { index ->
                    val isFilled = index < enteredDigits.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFilled) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
                            )
                            .border(
                                1.dp,
                                if (isFilled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Large Custom Numeric Pad for security feel
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Biometric", "0", "Delete")
            )

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { item ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item == "Biometric" || item == "Delete") Color.Transparent
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable(enabled = inputEnabled && (item != "Biometric" || (hasPin && biometricEnabled))) {
                                    triggerVibration()
                                    if (!inputEnabled) return@clickable
                                    when (item) {
                                        "Delete" -> {
                                            if (enteredDigits.isNotEmpty()) {
                                                enteredDigits = enteredDigits.dropLast(1)
                                            }
                                        }
                                        "Biometric" -> {
                                            if (isBiometricAvailableOnDevice) {
                                                showBiometricPrompt()
                                            } else if (biometricStatus == BiometricAuthManager.BiometricStatus.NONE_ENROLLED) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Kayıtlı parmak izi bulunamadı. Lütfen cihaz ayarlarınızdan bir biyometrik kimlik tanımlayın.",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Biyometrik kimlik doğrulama bu cihazda kullanılamıyor.",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        else -> {
                                            if (enteredDigits.length < PinPolicy.MAX_LENGTH) {
                                                enteredDigits += item
                                            }
                                        }
                                    }
                                }
                                .testTag("pin_btn_$item"),
                            contentAlignment = Alignment.Center
                        ) {
                            when (item) {
                                "Delete" -> {
                                    Icon(
                                        imageVector = Icons.Default.Backspace,
                                        contentDescription = stringResource(R.string.delete_last_digit),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                "Biometric" -> {
                                    if (hasPin && biometricEnabled && hasBiometricHardware) {
                                        Icon(
                                            imageVector = Icons.Default.Fingerprint,
                                            contentDescription = stringResource(R.string.use_biometrics),
                                            tint = if (isBiometricAvailableOnDevice) MaterialTheme.colorScheme.primary 
                                                   else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                else -> {
                                    Text(
                                        text = item,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val pin = enteredDigits
                    enteredDigits = ""
                    viewModel.handlePinEntered(pin, onUnlockSuccess)
                },
                enabled = inputEnabled && PinPolicy.isValid(enteredDigits),
                modifier = Modifier.fillMaxWidth().testTag("pin_submit")
            ) {
                Text(if (isAuthenticating) "Doğrulanıyor…" else if (!hasPin) "Devam" else "Doğrula")
            }
            if (!isReady) {
                TextButton(onClick = { viewModel.checkPinExists() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Güvenlik bilgilerini yeniden yükle")
                }
            }
        }
    }
}
