package com.example.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executor

/**
 * Manages biometric authentication using AndroidX Biometric.
 *
 * Provides utilities to check for device hardware and enrollment support,
 * present the standardized biometric prompt, and implement robust fallback mechanisms to PIN entry.
 */
object BiometricAuthManager {

    enum class BiometricStatus {
        AVAILABLE,
        NO_HARDWARE,
        HARDWARE_UNAVAILABLE,
        NONE_ENROLLED,
        SECURITY_UPDATE_REQUIRED,
        UNSUPPORTED
    }

    /**
     * Checks detailed biometric hardware and enrollment status on the device.
     */
    fun checkBiometricSupport(context: Context): BiometricStatus {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        return when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SECURITY_UPDATE_REQUIRED
            else -> BiometricStatus.UNSUPPORTED
        }
    }

    /**
     * Quick check whether biometric authentication can currently be used.
     */
    fun isBiometricAvailable(context: Context): Boolean {
        return checkBiometricSupport(context) == BiometricStatus.AVAILABLE
    }

    /**
     * Launches the biometric prompt with strong authenticators.
     * Includes a robust fallback mechanism to the PIN entry screen when the negative button is pressed,
     * when an error occurs, or when hardware fails.
     *
     * @param activity The FragmentActivity hosting the prompt.
     * @param title Prompt title.
     * @param subtitle Prompt subtitle.
     * @param negativeButtonText Label for the fallback button (e.g. "PIN Kodu Kullan").
     * @param onSuccess Callback triggered upon successful biometric verification.
     * @param onFallbackToPin Callback triggered when user chooses PIN fallback or when authentication is canceled.
     * @param onError Callback with error code and localized message.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biyometrik Doğrulama",
        subtitle: String = "LANU Space'e erişmek için kimliğinizi doğrulayın",
        negativeButtonText: String = "PIN Kodu Kullan",
        onSuccess: () -> Unit,
        onFallbackToPin: (() -> Unit)? = null,
        onError: (errorCode: Int, errorMessage: String) -> Unit = { _, _ -> }
    ) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                activity.runOnUiThread {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                            // User specifically opted out or chose PIN fallback
                            onFallbackToPin?.invoke() ?: onError(errorCode, errString.toString())
                        }
                        BiometricPrompt.ERROR_CANCELED -> {
                            // Canceled by app or system
                            onFallbackToPin?.invoke() ?: onError(errorCode, errString.toString())
                        }
                        else -> {
                            onError(errorCode, errString.toString())
                            // Also trigger fallback to ensure user is never locked out
                            onFallbackToPin?.invoke()
                        }
                    }
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                activity.runOnUiThread {
                    onSuccess()
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                activity.runOnUiThread {
                    onError(-1, "Doğrulama başarısız. Lütfen tekrar deneyin.")
                }
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        try {
            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            activity.runOnUiThread {
                onError(-2, e.message ?: "Kimlik doğrulama başlatılamadı")
                onFallbackToPin?.invoke()
            }
        }
    }
}
