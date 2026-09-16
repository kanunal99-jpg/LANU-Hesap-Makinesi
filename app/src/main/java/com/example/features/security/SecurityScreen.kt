package com.example.features.security

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.biometric.BiometricPrompt
import com.example.core.security.BiometricAuthManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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

    // Direct unlock trigger if coming from Calculator Easter Egg
    LaunchedEffect(directUnlockPin) {
        if (!directUnlockPin.isNullOrBlank()) {
            enteredDigits = directUnlockPin
            if (directUnlockPin == "2011.") {
                viewModel.handleMasterPinEntered {
                    onUnlockSuccess()
                }
            } else {
                viewModel.handlePinEntered(directUnlockPin) {
                    onUnlockSuccess()
                }
            }
        }
    }

    // Centralized biometric prompt launcher helper using BiometricAuthManager
    val showBiometricPrompt = {
        if (hasPin) {
            val activity = context as? FragmentActivity
            if (activity != null) {
                BiometricAuthManager.authenticate(
                    activity = activity,
                    onSuccess = {
                        viewModel.logEvent("BIOMETRIC_SUCCESS", "Successfully unlocked via biometrics")
                        onUnlockSuccess()
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
    LaunchedEffect(hasPin, biometricEnabled) {
        if (hasPin && biometricEnabled) {
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
        !hasPin -> "Bu PIN, özel iletişim alanınızı kilitleyecektir"
        else -> "Güvenli AES-256 kasa arayüzü"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(4) { index ->
                    val isFilled = index < enteredDigits.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
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
                                .clickable(enabled = lockoutRemainingSeconds <= 0) {
                                    triggerVibration()
                                    if (lockoutRemainingSeconds > 0) return@clickable
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
                                            if (enteredDigits.length < 4) {
                                                enteredDigits += item
                                                if (enteredDigits.length == 4) {
                                                    viewModel.handlePinEntered(enteredDigits) {
                                                        onUnlockSuccess()
                                                    }
                                                    enteredDigits = ""
                                                }
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
                                    if (hasPin && hasBiometricHardware) {
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
        }
    }
}
