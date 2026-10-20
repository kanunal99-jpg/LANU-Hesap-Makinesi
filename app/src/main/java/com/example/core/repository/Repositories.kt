package com.example.core.repository

import android.os.SystemClock
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.core.security.PinCredentialCodec
import com.example.core.security.PinPolicy
import com.example.core.security.PinAttemptStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.core.crypto.CryptoUtils
import com.example.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.math.BigDecimal

class CalculatorRepository(private val historyDao: CalculationHistoryDao, private val settingsDao: SettingsDao) {
    val history: Flow<List<CalculationHistoryEntity>> = historyDao.getAllHistory()

    suspend fun getDecimalPrecision(): Int {
        val value = settingsDao.getSettingValue("decimal_precision")
        return value?.toIntOrNull() ?: 6
    }

    // Exact decimal is authoritative; Double is a compatibility view only.
    private val _exactMemoryValue = MutableStateFlow(BigDecimal.ZERO)
    val exactMemoryValue: StateFlow<BigDecimal> = _exactMemoryValue.asStateFlow()
    private val _memoryValue = MutableStateFlow(0.0)
    val memoryValue: StateFlow<Double> = _memoryValue.asStateFlow()

    suspend fun saveToHistory(expression: String, result: String) {
        if (expression.isNotBlank() && result != "Error") {
            historyDao.insertHistory(CalculationHistoryEntity(expression = expression, result = result))
            val idsToPrune = historyDao.getHistoryIdsToPrune()
            if (idsToPrune.isNotEmpty()) {
                historyDao.deleteHistoryByIds(idsToPrune)
            }
        }
    }

    suspend fun deleteHistoryItem(id: Long) {
        historyDao.deleteHistory(id)
    }

    suspend fun clearHistory() {
        historyDao.clearAllHistory()
    }
    
    suspend fun deleteHistoryOlderThan(timestamp: Long) {
        historyDao.deleteHistoryOlderThan(timestamp)
    }

    private fun publishMemory(value: BigDecimal) {
        _exactMemoryValue.value = value
        _memoryValue.value = value.toDouble() // Legacy UI projection, never used in arithmetic.
    }

    @Synchronized fun setMemory(value: BigDecimal) = publishMemory(value)
    @Synchronized fun addToMemory(value: BigDecimal) = publishMemory(_exactMemoryValue.value.add(value))
    @Synchronized fun subtractFromMemory(value: BigDecimal) = publishMemory(_exactMemoryValue.value.subtract(value))
    fun setMemory(value: Double) = setMemory(BigDecimal.valueOf(value))
    fun addToMemory(value: Double) = addToMemory(BigDecimal.valueOf(value))
    fun subtractFromMemory(value: Double) = subtractFromMemory(BigDecimal.valueOf(value))
    @Synchronized fun clearMemory() = publishMemory(BigDecimal.ZERO)
}

class SecurityRepository(
    private val context: Context,
    private val settingsDao: SettingsDao,
    // Test-only dependency seam. Production callers must use Android Keystore.
    private val preferencesForTesting: SharedPreferences? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val authenticationMutex = Mutex()
    // Startup legacy migration and user-initiated updates must not race.
    private val credentialsMutex = Mutex()
    private var backgroundedAt: Long? = null

    
    private val sharedPreferences: SharedPreferences? by lazy {
        if (preferencesForTesting != null) preferencesForTesting
        else try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_settings_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Never downgrade biometric authorization data to plain SharedPreferences.
            null
        }
    }

    private val _isLockedState = MutableStateFlow(true)
    val isLockedState: StateFlow<Boolean> = _isLockedState.asStateFlow()

    suspend fun hasPin(): Boolean {
        return settingsDao.getSettingValue("pin_hash") != null
    }

    suspend fun setupPin(pin: String): Boolean = authenticationMutex.withLock {
        // Setup can never replace an existing credential without checking it.
        if (!PinPolicy.isValid(pin) || hasPin()) return@withLock false
        val verifier = withContext(Dispatchers.Default) { PinCredentialCodec.create(pin) }
        settingsDao.insertSetting(SettingsEntity("pin_hash", verifier))
        settingsDao.persistPinGuard(0, 0L, 0)
        _isLockedState.value = false
        true
    }

    suspend fun getPinAttemptStatus(): PinAttemptStatus = authenticationMutex.withLock {
        readAttemptStatus()
    }

    private suspend fun readAttemptStatus(): PinAttemptStatus {
        val value = settingsDao.getSettingValue("pin_attempt_state") ?: return PinAttemptStatus()
        val parts = value.split(':')
        val failures = parts.getOrNull(0)?.toIntOrNull()
        val until = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size != 2 || failures == null || failures !in 0 until PinPolicy.MAX_ATTEMPTS || until == null || until < 0L) {
            // Corrupt counters fail closed for one bounded waiting period.
            saveAttemptState(0, clock() + PinPolicy.LOCKOUT_MS)
            return PinAttemptStatus(remainingSeconds = 30)
        }
        if (until == 0L) return PinAttemptStatus(failedAttempts = failures)
        val remaining = (until - clock()).coerceIn(0L, PinPolicy.MAX_LOCKOUT_MS)
        if (remaining == 0L) {
            saveAttemptState(0, 0L)
            return PinAttemptStatus()
        }
        return PinAttemptStatus(failures, ((remaining + 999L) / 1000L).toInt())
    }

    private suspend fun saveAttemptState(failures: Int, until: Long) {
        // One Room row keeps the counter and deadline atomic across process restarts.
        settingsDao.insertSetting(SettingsEntity("pin_attempt_state", "$failures:$until"))
    }

    private suspend fun nextLockout(): Pair<Int, Long> {
        val stored = settingsDao.getSettingValue("pin_lockout_strikes")
        val previous = when {
            stored == null -> 0
            else -> stored.toIntOrNull()?.takeIf { it in 0..PinPolicy.MAX_LOCKOUT_STRIKES }
                ?: PinPolicy.MAX_LOCKOUT_STRIKES // Corrupt counters cannot reset brute-force protection.
        }
        val strike = (previous + 1).coerceAtMost(PinPolicy.MAX_LOCKOUT_STRIKES)
        return strike to PinPolicy.lockoutForStrike(strike)
    }

    private suspend fun checkPin(pin: String): Boolean {
        val status = readAttemptStatus()
        if (status.remainingSeconds > 0) return false
        val storedHash = settingsDao.getSettingValue("pin_hash") ?: return false
        val matches = withContext(Dispatchers.Default) { PinCredentialCodec.verify(pin, storedHash) }
        if (matches) {
            // Migrate only after proving the old PIN; never erase credentials on failure.
            if (!PinCredentialCodec.isModern(storedHash)) {
                val upgraded = withContext(Dispatchers.Default) { PinCredentialCodec.create(pin) }
                settingsDao.insertSetting(SettingsEntity("pin_hash", upgraded))
            }
            saveAttemptState(0, 0L)
            settingsDao.insertSetting(SettingsEntity("pin_lockout_strikes", "0"))
        } else {
            val failures = status.failedAttempts + 1
            if (failures >= PinPolicy.MAX_ATTEMPTS) {
                val (strike, duration) = nextLockout()
                settingsDao.persistPinGuard(0, clock() + duration, strike)
            } else {
                saveAttemptState(failures, 0L)
            }
        }
        return matches
    }

    suspend fun verifyPin(pin: String): Boolean = authenticationMutex.withLock {
        val matches = checkPin(pin)
        if (matches) {
            updateLastActiveTime()
            _isLockedState.value = false
        }
        matches
    }

    suspend fun changePin(currentPin: String, newPin: String): Boolean = authenticationMutex.withLock {
        if (!PinPolicy.isValid(newPin) || !checkPin(currentPin)) return@withLock false
        val verifier = withContext(Dispatchers.Default) { PinCredentialCodec.create(newPin) }
        settingsDao.insertSetting(SettingsEntity("pin_hash", verifier))
        true
    }

    /** Called only by the successful Android biometric prompt callback. */
    suspend fun completeBiometricUnlock(): Boolean = authenticationMutex.withLock {
        if (!hasPin() || !isBiometricEnabled() || readAttemptStatus().remainingSeconds > 0) {
            return@withLock false
        }
        settingsDao.persistPinGuard(0, 0L, 0)
        updateLastActiveTime()
        _isLockedState.value = false
        true
    }

    suspend fun isScreenProtectionEnabled(): Boolean {
        val value = settingsDao.getSettingValue("screen_protection_enabled")
        return value != "false" // default to true for enterprise privacy
    }

    suspend fun setScreenProtectionEnabled(enabled: Boolean) {
        settingsDao.insertSetting(SettingsEntity("screen_protection_enabled", enabled.toString()))
    }

    suspend fun getLockTimeout(): Long {
        // Returns duration in milliseconds. 0 = immediate, -1 = disabled
        val value = settingsDao.getSettingValue("lock_timeout") ?: "0"
        return value.toLongOrNull() ?: 0L
    }

    suspend fun setLockTimeout(timeoutMs: Long) {
        settingsDao.insertSetting(SettingsEntity("lock_timeout", timeoutMs.toString()))
    }

    suspend fun updateLastActiveTime() {
        settingsDao.insertSetting(SettingsEntity("last_active_time", System.currentTimeMillis().toString()))
    }

    suspend fun handleAppBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (getLockTimeout() == 0L) lock()
    }

    suspend fun handleAppForegrounded() {
        val startedAt = backgroundedAt ?: return
        backgroundedAt = null
        val timeout = getLockTimeout()
        if (timeout >= 0L && SystemClock.elapsedRealtime() - startedAt >= timeout) lock()
    }

    fun lock() {
        _isLockedState.value = true
    }

    suspend fun isBiometricEnabled(): Boolean {
        // If Keystore cannot be used, biometric unlock is ALWAYS off.
        return try { sharedPreferences?.getBoolean("biometric_enabled", false) == true }
        catch (_: Exception) { false }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        val prefs = sharedPreferences
        if (prefs == null) {
            if (enabled) throw SecurityException("Biyometrik güvenli depolama kullanılamıyor; özellik açılmadı.")
            return
        }
        try {
            check(prefs.edit().putBoolean("biometric_enabled", enabled).commit()) {
                "Biyometrik ayar güvenle kaydedilemedi."
            }
        } catch (e: Exception) {
            if (enabled) throw SecurityException("Biyometrik ayar güvenli depoya yazılamadı.", e)
            // Always leave unlock disabled for the current process.
        }
    }

    suspend fun getThemePreference(): String {
        // "system", "light", or "dark"
        return settingsDao.getSettingValue("theme_preference") ?: "system"
    }

    suspend fun setThemePreference(theme: String) {
        settingsDao.insertSetting(SettingsEntity("theme_preference", theme))
    }

    suspend fun getButtonColorTheme(): String {
        return settingsDao.getSettingValue("button_color_theme") ?: "emerald"
    }

    suspend fun setButtonColorTheme(theme: String) {
        settingsDao.insertSetting(SettingsEntity("button_color_theme", theme))
    }

    suspend fun getDecimalPrecision(): Int {
        val value = settingsDao.getSettingValue("decimal_precision")
        return value?.toIntOrNull() ?: 6
    }

    suspend fun setDecimalPrecision(precision: Int) {
        settingsDao.insertSetting(SettingsEntity("decimal_precision", precision.toString()))
    }

    /**
     * Historical builds stored Supabase connection details in the unencrypted Room settings
     * table. Copy them to Keystore-backed preferences *before* removing either old row.
     * An unavailable/corrupted Keystore must never cause a plaintext fallback or data loss.
     */
    suspend fun getSupabaseCredentials(): Pair<String?, String?> = credentialsMutex.withLock {
        val legacyUrl = settingsDao.getSettingValue("supabase_url")
        val legacyKey = settingsDao.getSettingValue("supabase_key")
        val prefs = sharedPreferences
        if (prefs == null) {
            // Unknown secure-store state is not proof that no credentials exist.
            throw SecurityException("Güvenli depolama açılamadı. Bağlantı bilgileri korunuyor.")
        }
        try {
            val hasProtectedUrl = prefs.contains("supabase_url")
            val hasProtectedKey = prefs.contains("supabase_key")
            check(hasProtectedUrl == hasProtectedKey) {
                "Güvenli bağlantı bilgileri eksik; eski kayıtlar korunuyor."
            }
            if (!hasProtectedUrl && (legacyUrl != null || legacyKey != null)) {
                check(legacyUrl != null && legacyKey != null) {
                    "Eski bağlantı bilgileri eksik; mevcut kayıtlar korunuyor."
                }
                check(prefs.edit()
                    .putString("supabase_url", legacyUrl)
                    .putString("supabase_key", legacyKey)
                    .commit()) { "Güvenli bağlantı bilgileri kaydedilemedi." }
            }
            // Verify secure content is actually readable BEFORE erasing the recoverable copy.
            val url = prefs.getString("supabase_url", null)
            val key = prefs.getString("supabase_key", null)
            if ((legacyUrl != null && url == null) || (legacyKey != null && key == null)) {
                throw IllegalStateException("Güvenli bağlantı bilgileri doğrulanamadı.")
            }
            if (legacyUrl != null) settingsDao.deleteSetting("supabase_url")
            if (legacyKey != null) settingsDao.deleteSetting("supabase_key")
            url to key
        } catch (e: Exception) {
            throw SecurityException("Bağlantı bilgileri güvenli depodan okunamadı.", e)
        }
    }

    suspend fun saveSupabaseCredentials(url: String, key: String) = credentialsMutex.withLock {
        val prefs = sharedPreferences
            ?: throw SecurityException("Güvenli depolama açılamadı. Bağlantı bilgileri kaydedilmedi.")
        try {
            check(prefs.edit()
                .putString("supabase_url", url)
                .putString("supabase_key", key)
                .commit()) { "Güvenli bağlantı bilgileri kaydedilemedi." }
        } catch (e: Exception) {
            throw SecurityException("Bağlantı bilgileri güvenli depoya yazılamadı.", e)
        }
        // Do not delete old plaintext copies until the encrypted write commits.
        settingsDao.deleteSetting("supabase_url")
        settingsDao.deleteSetting("supabase_key")
    }
}
