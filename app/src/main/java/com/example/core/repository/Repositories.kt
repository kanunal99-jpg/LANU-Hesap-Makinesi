package com.example.core.repository

import android.os.SystemClock
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.core.security.PinPolicy
import com.example.core.security.PinAttemptStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.core.crypto.CryptoUtils
import com.example.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class CalculatorRepository(private val historyDao: CalculationHistoryDao, private val settingsDao: SettingsDao) {
    val history: Flow<List<CalculationHistoryEntity>> = historyDao.getAllHistory()

    suspend fun getDecimalPrecision(): Int {
        val value = settingsDao.getSettingValue("decimal_precision")
        return value?.toIntOrNull() ?: 6
    }

    // Scientific Calculator memory registers
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

    fun setMemory(value: Double) {
        _memoryValue.value = value
    }

    fun addToMemory(value: Double) {
        _memoryValue.value += value
    }

    fun subtractFromMemory(value: Double) {
        _memoryValue.value -= value
    }

    fun clearMemory() {
        _memoryValue.value = 0.0
    }
}

class SecurityRepository(
    private val context: Context,
    private val settingsDao: SettingsDao,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val authenticationMutex = Mutex()
    private var backgroundedAt: Long? = null

    
    private val sharedPreferences: SharedPreferences by lazy {
        try {
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
        } catch (e: Exception) {
            // Safe fallback if KeyStore is not initialized or available (common on sandbox emulators)
            context.getSharedPreferences("secure_settings_prefs_fallback", Context.MODE_PRIVATE)
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
        settingsDao.insertSetting(SettingsEntity("pin_hash", CryptoUtils.hashPin(pin)))
        saveAttemptState(0, 0L)
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
        val remaining = (until - clock()).coerceIn(0L, PinPolicy.LOCKOUT_MS)
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

    private suspend fun checkPin(pin: String): Boolean {
        val status = readAttemptStatus()
        if (status.remainingSeconds > 0) return false
        val storedHash = settingsDao.getSettingValue("pin_hash") ?: return false
        val matches = PinPolicy.isValid(pin) && java.security.MessageDigest.isEqual(
            storedHash.toByteArray(Charsets.UTF_8),
            CryptoUtils.hashPin(pin).toByteArray(Charsets.UTF_8)
        )
        if (matches) {
            saveAttemptState(0, 0L)
        } else {
            val failures = status.failedAttempts + 1
            if (failures >= PinPolicy.MAX_ATTEMPTS) {
                saveAttemptState(0, clock() + PinPolicy.LOCKOUT_MS)
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
        settingsDao.insertSetting(SettingsEntity("pin_hash", CryptoUtils.hashPin(newPin)))
        true
    }

    /** Called only by the successful Android biometric prompt callback. */
    suspend fun completeBiometricUnlock(): Boolean = authenticationMutex.withLock {
        if (!hasPin() || !isBiometricEnabled() || readAttemptStatus().remainingSeconds > 0) {
            return@withLock false
        }
        saveAttemptState(0, 0L)
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
        return sharedPreferences.getBoolean("biometric_enabled", false)
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("biometric_enabled", enabled).apply()
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

    suspend fun getSupabaseCredentials(): Pair<String?, String?> {
        val url = settingsDao.getSettingValue("supabase_url")
        val key = settingsDao.getSettingValue("supabase_key")
        return Pair(url, key)
    }

    suspend fun saveSupabaseCredentials(url: String, key: String) {
        settingsDao.insertSetting(SettingsEntity("supabase_url", url))
        settingsDao.insertSetting(SettingsEntity("supabase_key", key))
    }
}

class CommunicationRepository(
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsDao: SettingsDao,
    private val userProfileDao: UserProfileDao,
    private val callLogDao: CallLogDao
) {
    val contacts: Flow<List<ContactEntity>> = contactDao.getAllContacts()
    val conversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()
    val callLogs: Flow<List<CallLogEntity>> = callLogDao.getAllCallLogs()

    suspend fun registerUser(phoneNumber: String, displayName: String): Boolean {
        if (phoneNumber.isBlank() || displayName.isBlank()) return false
        
        // Save to formal UserProfileEntity
        val profile = UserProfileEntity(
            phoneNumber = normalizePhoneNumber(phoneNumber),
            displayName = displayName
        )
        userProfileDao.insertProfile(profile)
        
        // Keep settings flag for backward compatibility
        settingsDao.insertSetting(SettingsEntity("user_registered", "true"))
        return true
    }

    suspend fun getUserProfile(): Pair<String?, String?> {
        val profile = userProfileDao.getUserProfile()
        if (profile != null) {
            return Pair(profile.phoneNumber, profile.displayName)
        }
        // Fallback to settings if migrating
        val phone = settingsDao.getSettingValue("user_phone")
        val name = settingsDao.getSettingValue("user_name")
        return Pair(phone, name)
    }

    suspend fun isUserRegistered(): Boolean {
        return settingsDao.getSettingValue("user_registered") == "true"
    }

    suspend fun logout() {
        userProfileDao.deleteProfile()
        settingsDao.deleteSetting("user_registered")
        settingsDao.deleteSetting("user_phone")
        settingsDao.deleteSetting("user_name")
    }

    suspend fun getSupabaseCredentials(): Pair<String?, String?> {
        val url = settingsDao.getSettingValue("supabase_url")
        val key = settingsDao.getSettingValue("supabase_key")
        return Pair(url, key)
    }

    suspend fun saveSupabaseCredentials(url: String, key: String) {
        settingsDao.insertSetting(SettingsEntity("supabase_url", url))
        settingsDao.insertSetting(SettingsEntity("supabase_key", key))
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    suspend fun addContact(name: String, phoneNumber: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        val contact = ContactEntity(phoneNumber = normalized, name = name)
        contactDao.insertContact(contact)
    }

    suspend fun deleteContact(phoneNumber: String) {
        contactDao.deleteContact(phoneNumber)
    }

    suspend fun lookupContactByPhone(phone: String): ContactEntity? {
        val normalized = normalizePhoneNumber(phone)
        return contactDao.getContactByPhone(normalized)
    }

    suspend fun getConversationById(id: String): ConversationEntity? {
        return conversationDao.getConversationById(id)
    }

    suspend fun createConversation(partnerPhone: String, partnerName: String): String {
        val normalized = normalizePhoneNumber(partnerPhone)
        val convId = UUID.randomUUID().toString()
        val conversation = ConversationEntity(
            id = convId,
            partnerPhone = normalized,
            partnerName = partnerName
        )
        conversationDao.insertConversation(conversation)
        return convId
    }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        mediaUrl: String? = null,
        onTypingStateChange: (suspend (Boolean) -> Unit)? = null
    ) {
        val profile = getUserProfile()
        val senderPhone = profile.first ?: "+905000000000"
        val senderName = profile.second ?: "User"
        
        val messageId = UUID.randomUUID().toString()
        val conversation = conversationDao.getConversationById(conversationId) ?: return

        // Encrypt the message content using AES-GCM
        val encryptedContent = CryptoUtils.encrypt(text, conversationId)

        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderPhone = senderPhone,
            senderName = senderName,
            content = encryptedContent,
            mediaUrl = mediaUrl,
            status = "SENDING",
            timestamp = System.currentTimeMillis()
        )
        
        messageDao.insertMessage(message)

        // Update last message in conversation
        val updatedConv = conversation.copy(
            lastMessageText = if (mediaUrl != null && text.isBlank()) "📷 Fotoğraf" else text,
            lastMessageTime = System.currentTimeMillis()
        )
        conversationDao.insertConversation(updatedConv)

        // Simulate network delivery flow: SENDING -> SENT -> DELIVERED -> READ
        kotlinx.coroutines.delay(400)
        messageDao.updateMessageStatus(messageId, "SENT")
        
        kotlinx.coroutines.delay(600)
        messageDao.updateMessageStatus(messageId, "DELIVERED")

        // Auto reply if this is a simulation partner or echo test (and NOT an automated call system log)
        val isSystemCallLog = text.startsWith("📞")
        if (!isSystemCallLog && (conversation.partnerPhone.startsWith("+90599") || conversation.partnerPhone.contains("1234"))) {
            kotlinx.coroutines.delay(800)
            messageDao.updateMessageStatus(messageId, "READ")
            
            // Trigger typing indicator
            onTypingStateChange?.invoke(true)
            kotlinx.coroutines.delay(1200)
            onTypingStateChange?.invoke(false)

            // Generate standard automatic reply
            val replyText = if (mediaUrl != null) {
                "LANU Güvenli Yankı: Gönderdiğiniz medya (${mediaUrl.take(20)}...) E2EE ile doğrulandı."
            } else {
                "LANU Güvenli Yankı: \"$text\" uçtan uca şifrelemeyle alındı ve doğrulandı."
            }
            val encryptedReply = CryptoUtils.encrypt(replyText, conversationId)
            val replyMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderPhone = conversation.partnerPhone,
                senderName = conversation.partnerName,
                content = encryptedReply,
                status = "READ",
                timestamp = System.currentTimeMillis()
            )
            messageDao.insertMessage(replyMessage)
            conversationDao.insertConversation(
                updatedConv.copy(
                    lastMessageText = replyText,
                    lastMessageTime = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun addReaction(messageId: String, emoji: String) {
        messageDao.updateMessageReaction(messageId, emoji)
    }

    suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    suspend fun editMessage(messageId: String, conversationId: String, newText: String) {
        val encrypted = CryptoUtils.encrypt(newText, conversationId)
        messageDao.updateMessageContent(messageId, encrypted)
    }

    suspend fun clearConversationMessages(conversationId: String) {
        messageDao.deleteAllMessagesForConversation(conversationId)
        val conv = conversationDao.getConversationById(conversationId)
        if (conv != null) {
            conversationDao.insertConversation(conv.copy(lastMessageText = "", lastMessageTime = System.currentTimeMillis()))
        }
    }

    suspend fun addCallLog(partnerName: String, partnerPhone: String, isVideo: Boolean, durationSeconds: Int, isOutgoing: Boolean) {
        val log = CallLogEntity(
            partnerName = partnerName,
            partnerPhone = partnerPhone,
            isVideo = isVideo,
            durationSeconds = durationSeconds,
            isOutgoing = isOutgoing,
            timestamp = System.currentTimeMillis()
        )
        callLogDao.insertCallLog(log)
    }

    suspend fun deleteCallLog(id: Long) {
        callLogDao.deleteCallLog(id)
    }

    suspend fun clearAllCallLogs() {
        callLogDao.clearAllCallLogs()
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversation(id)
    }

    fun normalizePhoneNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (phone.startsWith("+")) {
            "+" + digits
        } else if (digits.length == 10 && digits.startsWith("5")) {
            "+90$digits"
        } else if (digits.length == 11 && digits.startsWith("05")) {
            "+90" + digits.substring(1)
        } else {
            "+" + digits
        }
    }
}
