package com.example.features.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker
import com.example.core.security.PinPolicy
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

    // Until durable settings load, require authentication (fail closed).
    private val _entryPinEnabled = MutableStateFlow(true)
    val entryPinEnabled: StateFlow<Boolean> = _entryPinEnabled.asStateFlow()

    val isLocked = repository.isLockedState

    private val _setupStep = MutableStateFlow(SetupStep.ENTER_PIN)
    val setupStep: StateFlow<SetupStep> = _setupStep.asStateFlow()

    private val _tempPin = MutableStateFlow("")
    val tempPin: StateFlow<String> = _tempPin.asStateFlow()

    private val _lockTimeoutMs = MutableStateFlow(0L)
    val lockTimeoutMs: StateFlow<Long> = _lockTimeoutMs.asStateFlow()

    // Assume the stricter choice until the persisted setting is loaded.
    private val _requirePinOnMediaPickerReturn = MutableStateFlow(true)
    val requirePinOnMediaPickerReturn: StateFlow<Boolean> = _requirePinOnMediaPickerReturn.asStateFlow()

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
    private var authenticationGeneration = 0

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating = _isAuthenticating.asStateFlow()

    private var pinLoaded = false
    private var settingsLoaded = false
    private val _isReady = MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

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
            try {
                _hasPin.value = repository.hasPin()
                _entryPinEnabled.value = repository.isEntryPinEnabled()
                refreshAttemptStatus()
                pinLoaded = true
                _isReady.value = pinLoaded && settingsLoaded
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                pinLoaded = false
                _isReady.value = false
                _errorMessage.value = "Güvenlik bilgileri okunamadı. Lütfen tekrar deneyin."
                repository.lock()
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                _lockTimeoutMs.value = repository.getLockTimeout()
                // Existing users default to OFF specifically for a short trusted
                // Android gallery/camera/document-picker return, not all backgrounding.
                _requirePinOnMediaPickerReturn.value =
                    try { repository.requirePinOnMediaPickerReturn() }
                    catch (_: Exception) { true }
                _biometricEnabled.value = repository.isBiometricEnabled()
                _screenProtectionEnabled.value = repository.isScreenProtectionEnabled()
                _themePreference.value = repository.getThemePreference()
                _buttonColorTheme.value = repository.getButtonColorTheme()
                _decimalPrecision.value = repository.getDecimalPrecision()
                settingsLoaded = true
                _isReady.value = pinLoaded && settingsLoaded
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                settingsLoaded = false
                _isReady.value = false
                repository.lock()
                _errorMessage.value = "Ayarlar güvenle yüklenemedi. Kayıtlar korunuyor; yeniden deneyin."
            }
        }
    }

    fun reloadAfterRestore() {
        pinLoaded = false
        settingsLoaded = false
        _isReady.value = false
        checkPinExists()
        loadSettings()
    }

    /**
     * "2011." opens the hidden area. PIN verification is performed only when
     * the durable, user-managed entry protection is enabled. No caller may
     * blindly unlock based on a cached UI switch or the entry code itself.
     */
    fun enterFromCalculator(onOpen: () -> Unit, onPinRequired: () -> Unit) {
        // A lock/screen-off can happen while the Room preference is suspended.
        // Never let an earlier asynchronous "2011." request reopen a newly
        // locked private session, even when optional entry PIN is disabled.
        val entryGeneration = authenticationGeneration
        viewModelScope.launch {
            if (entryGeneration != authenticationGeneration) return@launch
            if (!_isReady.value) {
                _errorMessage.value = "Güvenlik ayarları hazırlanıyor. Lütfen yeniden deneyin."
                return@launch
            }
            try {
                val entryAllowed = repository.tryEnterWithoutPin()
                if (entryGeneration != authenticationGeneration) {
                    repository.lock()
                    return@launch
                }
                if (entryAllowed) {
                    val hasSavedPin = repository.hasPin()
                    if (entryGeneration != authenticationGeneration) {
                        repository.lock()
                        return@launch
                    }
                    _hasPin.value = hasSavedPin
                    _entryPinEnabled.value = false
                    eventTracker.logEvent("PRIVATE_ENTRY_OPTIONAL", "PIN kapalı, özel alana giriş")
                    onOpen()
                } else {
                    _entryPinEnabled.value = true
                    onPinRequired()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.lock()
                if (entryGeneration != authenticationGeneration) return@launch
                _entryPinEnabled.value = true
                _errorMessage.value = "Güvenlik tercihi doğrulanamadı. PIN ile devam edin."
                onPinRequired()
            }
        }
    }

    /** Disabling with an existing PIN requires password re-verification. */
    suspend fun changeEntryPinRequirement(enabled: Boolean, currentPin: String? = null): Boolean {
        if (_isAuthenticating.value) return false
        _isAuthenticating.value = true
        return try {
            val success = repository.setEntryPinEnabled(enabled, currentPin)
            refreshAttemptStatus()
            if (success) {
                _entryPinEnabled.value = enabled
                eventTracker.logEvent("PIN_ENTRY_PREFERENCE", if (enabled) "PIN koruması açıldı" else "PIN koruması kapatıldı")
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = "PIN giriş tercihi kaydedilemedi."
            false
        } finally {
            _isAuthenticating.value = false
        }
    }

    /** For users without a PIN: create one only upon their explicit choice. */
    suspend fun createEntryPin(pin: String): Boolean {
        if (_isAuthenticating.value || !PinPolicy.isValid(pin)) return false
        _isAuthenticating.value = true
        return try {
            val success = repository.setupPin(pin)
            if (success) {
                _hasPin.value = true
                _entryPinEnabled.value = true
                _setupStep.value = SetupStep.COMPLETED
                eventTracker.logEvent("PIN_SETUP", "PIN kullanıcı tercihiyle oluşturuldu")
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = "PIN oluşturulamadı."
            false
        } finally {
            _isAuthenticating.value = false
        }
    }

    fun handlePinEntered(pin: String, onSuccess: () -> Unit) {
        if (!_isReady.value || _isAuthenticating.value) return
        if (!PinPolicy.isValid(pin)) {
            _errorMessage.value = "PIN 4–12 rakamdan oluşmalıdır."
            return
        }
        val generation = authenticationGeneration
        _isAuthenticating.value = true
        viewModelScope.launch {
            try {
                _errorMessage.value = null
                // Read the authority, rather than a possibly stale startup UI value.
                _hasPin.value = repository.hasPin()
                if (_hasPin.value) {
                    val correct = repository.verifyPin(pin)
                    refreshAttemptStatus()
                    if (generation != authenticationGeneration) {
                        repository.lock()
                        return@launch
                    }
                    if (correct) {
                        eventTracker.logEvent("SUCCESSFUL_UNLOCK", "PIN doğrulaması başarılı")
                        onSuccess()
                    } else if (_lockoutRemainingSeconds.value == 0) {
                        _errorMessage.value = "Hatalı PIN kodu! (${_failedAttempts.value}/5 deneme)"
                        eventTracker.logEvent("FAILED_PIN_ATTEMPT", "PIN doğrulaması başarısız")
                    } else {
                        eventTracker.logEvent("BRUTE_FORCE_LOCKOUT", "PIN deneme sınırı: 30 saniye bekleme")
                    }
                } else {
                    when (_setupStep.value) {
                        SetupStep.ENTER_PIN -> {
                            _tempPin.value = pin
                            _setupStep.value = SetupStep.CONFIRM_PIN
                        }
                        SetupStep.CONFIRM_PIN -> {
                            if (pin != _tempPin.value) {
                                resetSetup()
                                _errorMessage.value = "PIN kodları eşleşmiyor. Lütfen baştan girin."
                            } else if (repository.setupPin(pin)) {
                                _tempPin.value = ""
                                _hasPin.value = true
                                _entryPinEnabled.value = true
                                if (generation != authenticationGeneration) {
                                    repository.lock()
                                    return@launch
                                }
                                _setupStep.value = SetupStep.COMPLETED
                                eventTracker.logEvent("PIN_SETUP", "Yeni güvenli PIN oluşturuldu")
                                onSuccess()
                            } else {
                                _errorMessage.value = "PIN kaydedilemedi. Lütfen tekrar deneyin."
                            }
                        }
                        SetupStep.COMPLETED -> Unit
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.lock()
                _errorMessage.value = "Doğrulama tamamlanamadı. Lütfen tekrar deneyin."
            } finally {
                _isAuthenticating.value = false
            }
        }
    }

    private suspend fun updateAttemptStatus() {
        val status = repository.getPinAttemptStatus()
        _failedAttempts.value = status.failedAttempts
        _lockoutRemainingSeconds.value = status.remainingSeconds
        if (status.remainingSeconds > 0) {
            _errorMessage.value = "Çok fazla hatalı deneme. Lütfen ${status.remainingSeconds} saniye bekleyin."
        }
    }

    private suspend fun refreshAttemptStatus() {
        updateAttemptStatus()
        lockoutJob?.cancel()
        if (_lockoutRemainingSeconds.value > 0) {
            lockoutJob = viewModelScope.launch {
                while (_lockoutRemainingSeconds.value > 0) {
                    delay(1000L)
                    updateAttemptStatus()
                }
                _errorMessage.value = null
            }
        }
    }

    fun onBiometricAuthenticated(onSuccess: () -> Unit) {
        if (!_isReady.value || _isAuthenticating.value) return
        val generation = authenticationGeneration
        _isAuthenticating.value = true
        viewModelScope.launch {
            try {
                val success = repository.completeBiometricUnlock()
                refreshAttemptStatus()
                if (generation != authenticationGeneration) {
                    repository.lock()
                } else if (success) {
                    _errorMessage.value = null
                    eventTracker.logEvent("BIOMETRIC_SUCCESS", "Biyometrik doğrulama başarılı")
                    onSuccess()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.lock()
                _errorMessage.value = "Biyometrik giriş tamamlanamadı. PIN kullanın."
            } finally {
                _isAuthenticating.value = false
            }
        }
    }

    suspend fun changePin(currentPin: String, newPin: String): Boolean {
        if (_isAuthenticating.value || !PinPolicy.isValid(newPin)) return false
        _isAuthenticating.value = true
        return try {
            val success = repository.changePin(currentPin, newPin)
            refreshAttemptStatus()
            if (success) {
                _hasPin.value = true
                eventTracker.logEvent("PIN_CHANGED", "PIN kodu başarıyla güncellendi")
            }
            success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _errorMessage.value = "PIN güncellenemedi. Lütfen tekrar deneyin."
            false
        } finally {
            _isAuthenticating.value = false
        }
    }

    private fun protectedOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.lock()
                _errorMessage.value = "İşlem güvenle tamamlanamadı. Kayıtlarınız korunuyor; yeniden deneyin."
            }
        }
    }

    fun setScreenProtectionEnabled(enabled: Boolean) {
        protectedOperation {
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
        protectedOperation {
            if (enable) {
                repository.setBiometricEnabled(true)
                _biometricEnabled.value = true
            }
            _showBiometricOptIn.value = false
            _onPendingSuccessAction.value?.invoke()
            _onPendingSuccessAction.value = null
        }
    }

    fun updateLockTimeout(timeoutMs: Long) {
        protectedOperation {
            repository.setLockTimeout(timeoutMs)
            _lockTimeoutMs.value = timeoutMs
        }
    }

    fun setRequirePinOnMediaPickerReturn(enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.setRequirePinOnMediaPickerReturn(enabled)
                _requirePinOnMediaPickerReturn.value = enabled
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _errorMessage.value = "Medya PIN tercihi kaydedilemedi."
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        protectedOperation {
            repository.setBiometricEnabled(enabled)
            _biometricEnabled.value = enabled
        }
    }

    fun setThemePreference(theme: String) {
        protectedOperation {
            repository.setThemePreference(theme)
            _themePreference.value = theme
        }
    }

    fun setButtonColorTheme(theme: String) {
        protectedOperation {
            repository.setButtonColorTheme(theme)
            _buttonColorTheme.value = theme
        }
    }

    fun setDecimalPrecision(precision: Int) {
        protectedOperation {
            repository.setDecimalPrecision(precision)
            _decimalPrecision.value = precision
        }
    }

    fun resetSetup() {
        authenticationGeneration++
        _setupStep.value = SetupStep.ENTER_PIN
        _tempPin.value = ""
        _errorMessage.value = null
    }

    fun lock() {
        resetSetup()
        repository.lock()
    }

    fun unlock() {
        // Unlocking must only happen through successful PIN/biometric authentication.
        // This method remains for ViewModel compatibility but deliberately performs no bypass.
        eventTracker.logEvent("UNLOCK_IGNORED", "Direct unlock request rejected")
    }

    fun appForegrounded() {
        protectedOperation {
            repository.handleAppForegrounded()
        }
    }

    fun appBackgrounded() {
        protectedOperation {
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
