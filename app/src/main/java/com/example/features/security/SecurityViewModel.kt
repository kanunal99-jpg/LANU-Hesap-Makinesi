package com.example.features.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecurityAuditLog(
    val type: String,
    val timestamp: Long,
    val details: String
)

class SecurityViewModel(
    private val repository: SecurityRepository,
    private val eventTracker: SecurityEventTracker
) : ViewModel() {

    private val _hasPin = MutableStateFlow(false)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    val isLocked = repository.isLockedState

    private val _setupStep = MutableStateFlow(SetupStep.ENTER_PIN)
    val setupStep: StateFlow<SetupStep> = _setupStep.asStateFlow()

    private val _tempPin = MutableStateFlow("")
    val tempPin: StateFlow<String> = _tempPin.asStateFlow()

    private val _lockTimeoutMs = MutableStateFlow(0L)
    val lockTimeoutMs: StateFlow<Long> = _lockTimeoutMs.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(false)
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _screenProtectionEnabled = MutableStateFlow(true)
    val screenProtectionEnabled: StateFlow<Boolean> = _screenProtectionEnabled.asStateFlow()

    private val _themePreference = MutableStateFlow("system")
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    private val _buttonColorTheme = MutableStateFlow("emerald")
    val buttonColorTheme: StateFlow<String> = _buttonColorTheme.asStateFlow()

    private val _decimalPrecision = MutableStateFlow(6)
    val decimalPrecision: StateFlow<Int> = _decimalPrecision.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _failedAttempts = MutableStateFlow(0)
    val failedAttempts: StateFlow<Int> = _failedAttempts.asStateFlow()

    private val _lockoutRemainingSeconds = MutableStateFlow(0)
    val lockoutRemainingSeconds: StateFlow<Int> = _lockoutRemainingSeconds.asStateFlow()

    private var lockoutJob: Job? = null

    private val _showBiometricOptIn = MutableStateFlow(false)
    val showBiometricOptIn: StateFlow<Boolean> = _showBiometricOptIn.asStateFlow()

    private val _onPendingSuccessAction = MutableStateFlow<(() -> Unit)?>(null)

    enum class SetupStep {
        ENTER_PIN,
        CONFIRM_PIN,
        COMPLETED
    }

    init {
        checkPinExists()
        loadSettings()
    }

    fun checkPinExists() {
        viewModelScope.launch {
            _hasPin.value = repository.hasPin()
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _lockTimeoutMs.value = repository.getLockTimeout()
            _biometricEnabled.value = repository.isBiometricEnabled()
            _screenProtectionEnabled.value = repository.isScreenProtectionEnabled()
            _themePreference.value = repository.getThemePreference()
            _buttonColorTheme.value = repository.getButtonColorTheme()
            _decimalPrecision.value = repository.getDecimalPrecision()
        }
    }

    fun handlePinEntered(pin: String, onSuccess: () -> Unit) {
        if (_lockoutRemainingSeconds.value > 0) {
            _errorMessage.value = "Çok fazla hatalı deneme! Lütfen ${_lockoutRemainingSeconds.value} saniye bekleyin."
            return
        }

        viewModelScope.launch {
            _errorMessage.value = null
            if (_hasPin.value) {
                val correct = repository.verifyPin(pin)
                if (correct) {
                    _failedAttempts.value = 0
                    eventTracker.logEvent("SUCCESSFUL_UNLOCK", "PIN code verification succeeded")
                    onSuccess()
                } else {
                    _failedAttempts.value += 1
                    val attempts = _failedAttempts.value
                    if (attempts >= 5) {
                        triggerLockout()
                    } else {
                        eventTracker.logEvent("FAILED_PIN_ATTEMPT", "PIN verification failed (Attempt $attempts/5)")
                        _errorMessage.value = "Hatalı PIN kodu! ($attempts/5 deneme)"
                    }
                }
            } else {
                when (_setupStep.value) {
                    SetupStep.ENTER_PIN -> {
                        if (pin.length < 4) {
                            _errorMessage.value = "PIN en az 4 haneli olmalıdır"
                            return@launch
                        }
                        _tempPin.value = pin
                        _setupStep.value = SetupStep.CONFIRM_PIN
                    }
                    SetupStep.CONFIRM_PIN -> {
                        if (pin == _tempPin.value) {
                            val success = repository.setupPin(pin)
                            if (success) {
                                eventTracker.logEvent("PIN_SETUP", "Yeni güvenli PIN oluşturuldu")
                                _setupStep.value = SetupStep.COMPLETED
                                _hasPin.value = true
                                _failedAttempts.value = 0
                                onSuccess()
                            } else {
                                _errorMessage.value = "PIN kaydedilemedi"
                            }
                        } else {
                            _errorMessage.value = "PIN kodları eşleşmiyor! Lütfen baştan girin."
                            _setupStep.value = SetupStep.ENTER_PIN
                            _tempPin.value = ""
                        }
                    }
                    SetupStep.COMPLETED -> Unit
                }
            }
        }
    }

    private fun triggerLockout() {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            _failedAttempts.value = 0
            eventTracker.logEvent("BRUTE_FORCE_LOCKOUT", "5 hatalı PIN denemesi - 30 saniye kilitlendi")
            for (sec in 30 downTo 1) {
                _lockoutRemainingSeconds.value = sec
                _errorMessage.value = "Çok fazla hatalı deneme! Lütfen $sec saniye bekleyin."
                delay(1000L)
            }
            _lockoutRemainingSeconds.value = 0
            _errorMessage.value = null
        }
    }

    suspend fun changePin(currentPin: String, newPin: String): Boolean {
        if (newPin.length < 4) return false
        val success = repository.changePin(currentPin, newPin)
        if (success) {
            _hasPin.value = true
            _failedAttempts.value = 0
            eventTracker.logEvent("PIN_CHANGED", "PIN kodu başarıyla güncellendi")
        }
        return success
    }

    fun setScreenProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setScreenProtectionEnabled(enabled)
            _screenProtectionEnabled.value = enabled
            eventTracker.logEvent("SECURITY_CONFIG", "Ekran/Önizleme koruması: $enabled")
        }
    }

    fun getSecurityLogs(): List<SecurityAuditLog> {
        return eventTracker.getLoggedEvents().map {
            SecurityAuditLog(
                type = it["type"] as? String ?: "EVENT",
                timestamp = (it["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                details = it["details"] as? String ?: ""
            )
        }
    }

    fun clearSecurityLogs() {
        eventTracker.clearEvents()
        eventTracker.logEvent("LOGS_CLEARED", "Güvenlik günlüğü temizlendi")
    }

    /**
     * Legacy entry point retained for compatibility. It no longer contains a
     * hardcoded PIN and always follows the normal authentication/setup flow.
     */
    fun handleMasterPinEntered(onSuccess: () -> Unit) {
        _errorMessage.value = "Özel PIN girişi kaldırıldı. Normal PIN doğrulamasını kullanın."
        eventTracker.logEvent("SECURITY_CONFIG", "Hardcoded master PIN yolu devre dışı bırakıldı")
    }

    fun dismissBiometricOptIn(enable: Boolean) {
        viewModelScope.launch {
            if (enable) {
                setBiometricEnabled(true)
            }
            _showBiometricOptIn.value = false
            _onPendingSuccessAction.value?.invoke()
            _onPendingSuccessAction.value = null
        }
    }

    fun updateLockTimeout(timeoutMs: Long) {
        viewModelScope.launch {
            repository.setLockTimeout(timeoutMs)
            _lockTimeoutMs.value = timeoutMs
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBiometricEnabled(enabled)
            _biometricEnabled.value = enabled
        }
    }

    fun setThemePreference(theme: String) {
        viewModelScope.launch {
            repository.setThemePreference(theme)
            _themePreference.value = theme
        }
    }

    fun setButtonColorTheme(theme: String) {
        viewModelScope.launch {
            repository.setButtonColorTheme(theme)
            _buttonColorTheme.value = theme
        }
    }

    fun setDecimalPrecision(precision: Int) {
        viewModelScope.launch {
            repository.setDecimalPrecision(precision)
            _decimalPrecision.value = precision
        }
    }

    fun resetSetup() {
        _setupStep.value = SetupStep.ENTER_PIN
        _tempPin.value = ""
        _errorMessage.value = null
    }

    fun lock() {
        repository.lock()
    }

    fun unlock() {
        // Unlocking must only happen through successful PIN/biometric authentication.
        // This method remains for ViewModel compatibility but deliberately performs no bypass.
        eventTracker.logEvent("UNLOCK_IGNORED", "Direct unlock request rejected")
    }

    fun appForegrounded() {
        viewModelScope.launch {
            repository.handleAppForegrounded()
        }
    }

    fun appBackgrounded() {
        viewModelScope.launch {
            repository.handleAppBackgrounded()
        }
    }

    fun logEvent(eventType: String, details: String = "") {
        eventTracker.logEvent(eventType, details)
    }

    class Factory(
        private val repository: SecurityRepository,
        private val eventTracker: SecurityEventTracker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SecurityViewModel(repository, eventTracker) as T
        }
    }
}
