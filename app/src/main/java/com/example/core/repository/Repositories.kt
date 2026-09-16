package com.example.core.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.core.crypto.CryptoUtils
import com.example.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class CalculatorRepository(private val historyDao: CalculationHistoryDao, private val settingsDao: SettingsDao) {
    val history: Flow<List<CalculationHistoryEntity>> = historyDao.getAllHistory()
    suspend fun getDecimalPrecision(): Int = settingsDao.getSettingValue("decimal_precision")?.toIntOrNull() ?: 6
    private val _memoryValue = MutableStateFlow(0.0)
    val memoryValue: StateFlow<Double> = _memoryValue.asStateFlow()
    suspend fun saveToHistory(expression: String, result: String) { if (expression.isNotBlank() && result != "Error") historyDao.insertHistory(CalculationHistoryEntity(expression = expression, result = result)) }
    suspend fun deleteHistoryItem(id: Long) = historyDao.deleteHistory(id)
    suspend fun clearHistory() = historyDao.clearAllHistory()
    suspend fun deleteHistoryOlderThan(timestamp: Long) = historyDao.deleteHistoryOlderThan(timestamp)
    fun setMemory(value: Double) { _memoryValue.value = value }
    fun addToMemory(value: Double) { _memoryValue.value += value }
    fun subtractFromMemory(value: Double) { _memoryValue.value -= value }
    fun clearMemory() { _memoryValue.value = 0.0 }
}

class SecurityRepository(private val context: Context, private val settingsDao: SettingsDao) {
    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "secure_settings_prefs", masterKey, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) { context.getSharedPreferences("secure_settings_prefs_fallback", Context.MODE_PRIVATE) }
    }
    private val _isLockedState = MutableStateFlow(true)
    val isLockedState: StateFlow<Boolean> = _isLockedState.asStateFlow()
    suspend fun hasPin(): Boolean = settingsDao.getSettingValue("pin_hash") != null
    suspend fun setupPin(pin: String): Boolean { if (pin.length < 4) return false; settingsDao.insertSetting(SettingsEntity("pin_hash", CryptoUtils.hashPin(pin))); _isLockedState.value = false; return true }
    suspend fun verifyPin(pin: String): Boolean { val stored = settingsDao.getSettingValue("pin_hash") ?: return false; val ok = stored == CryptoUtils.hashPin(pin); if (ok) { _isLockedState.value = false; updateLastActiveTime() }; return ok }
    suspend fun changePin(currentPin: String, newPin: String): Boolean = newPin.length >= 4 && verifyPin(currentPin) && setupPin(newPin)
    suspend fun isScreenProtectionEnabled(): Boolean = settingsDao.getSettingValue("screen_protection_enabled") != "false"
    suspend fun setScreenProtectionEnabled(enabled: Boolean) = settingsDao.insertSetting(SettingsEntity("screen_protection_enabled", enabled.toString()))
    suspend fun getLockTimeout(): Long = settingsDao.getSettingValue("lock_timeout")?.toLongOrNull() ?: 0L
    suspend fun setLockTimeout(timeoutMs: Long) = settingsDao.insertSetting(SettingsEntity("lock_timeout", timeoutMs.toString()))
    suspend fun updateLastActiveTime() = settingsDao.insertSetting(SettingsEntity("last_active_time", System.currentTimeMillis().toString()))
    suspend fun handleAppBackgrounded() = settingsDao.insertSetting(SettingsEntity("backgrounded_time", System.currentTimeMillis().toString()))
    suspend fun handleAppForegrounded() { if (!hasPin()) { _isLockedState.value = false; return }; val b = settingsDao.getSettingValue("backgrounded_time")?.toLongOrNull(); val t = getLockTimeout(); _isLockedState.value = b == null || t < 0 || System.currentTimeMillis() - b > t }
    fun lock() { _isLockedState.value = true }
    suspend fun isBiometricEnabled(): Boolean = sharedPreferences.getBoolean("biometric_enabled", false)
    suspend fun setBiometricEnabled(enabled: Boolean) = sharedPreferences.edit().putBoolean("biometric_enabled", enabled).apply()
    suspend fun getThemePreference(): String = settingsDao.getSettingValue("theme_preference") ?: "system"
    suspend fun setThemePreference(theme: String) = settingsDao.insertSetting(SettingsEntity("theme_preference", theme))
    suspend fun getButtonColorTheme(): String = settingsDao.getSettingValue("button_color_theme") ?: "emerald"
    suspend fun setButtonColorTheme(theme: String) = settingsDao.insertSetting(SettingsEntity("button_color_theme", theme))
    suspend fun getDecimalPrecision(): Int = settingsDao.getSettingValue("decimal_precision")?.toIntOrNull() ?: 6
    suspend fun setDecimalPrecision(precision: Int) = settingsDao.insertSetting(SettingsEntity("decimal_precision", precision.toString()))
    suspend fun getSupabaseCredentials(): Pair<String?, String?> = Pair(settingsDao.getSettingValue("supabase_url"), settingsDao.getSettingValue("supabase_key"))
    suspend fun saveSupabaseCredentials(url: String, key: String) { settingsDao.insertSetting(SettingsEntity("supabase_url", url)); settingsDao.insertSetting(SettingsEntity("supabase_key", key)) }
}

class CommunicationRepository(
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsDao: SettingsDao,
    private val userProfileDao: UserProfileDao,
    private val callLogDao: CallLogDao,
    private val supabaseClient: SupabaseCommunicationClient
) {
    val contacts: Flow<List<ContactEntity>> = contactDao.getAllContacts()
    val conversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()
    val callLogs: Flow<List<CallLogEntity>> = callLogDao.getAllCallLogs()

    suspend fun registerUser(phoneNumber: String, displayName: String): Boolean {
        if (phoneNumber.isBlank() || displayName.isBlank()) return false
        val normalized = normalizePhoneNumber(phoneNumber)
        userProfileDao.insertProfile(UserProfileEntity(phoneNumber = normalized, displayName = displayName))
        settingsDao.insertSetting(SettingsEntity("user_registered", "true"))
        return runCatching {
            val s = supabaseClient.ensureAnonymousSession()
            supabaseClient.upsertProfile(s, normalized, displayName)
            settingsDao.insertSetting(SettingsEntity("supabase_user_id", s.userId))
            true
        }.getOrDefault(false)
    }

    suspend fun getUserProfile(): Pair<String?, String?> { val p = userProfileDao.getUserProfile(); return if (p != null) Pair(p.phoneNumber, p.displayName) else Pair(settingsDao.getSettingValue("user_phone"), settingsDao.getSettingValue("user_name")) }
    suspend fun isUserRegistered(): Boolean = settingsDao.getSettingValue("user_registered") == "true"
    suspend fun logout() { userProfileDao.deleteProfile(); settingsDao.deleteSetting("user_registered"); settingsDao.deleteSetting("user_phone"); settingsDao.deleteSetting("user_name"); settingsDao.deleteSetting("supabase_user_id") }
    suspend fun getSupabaseCredentials(): Pair<String?, String?> = Pair(settingsDao.getSettingValue("supabase_url"), settingsDao.getSettingValue("supabase_key"))
    suspend fun saveSupabaseCredentials(url: String, key: String) { settingsDao.insertSetting(SettingsEntity("supabase_url", url)); settingsDao.insertSetting(SettingsEntity("supabase_key", key)) }
    fun getMessages(conversationId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForConversation(conversationId)
    suspend fun addContact(name: String, phoneNumber: String) = contactDao.insertContact(ContactEntity(phoneNumber = normalizePhoneNumber(phoneNumber), name = name))
    suspend fun deleteContact(phoneNumber: String) = contactDao.deleteContact(phoneNumber)
    suspend fun lookupContactByPhone(phone: String): ContactEntity? = contactDao.getContactByPhone(normalizePhoneNumber(phone))
    suspend fun getConversationById(id: String): ConversationEntity? = conversationDao.getConversationById(id)

    suspend fun createConversation(partnerPhone: String, partnerName: String): String {
        val normalized = normalizePhoneNumber(partnerPhone)
        val local = ConversationEntity(id = UUID.randomUUID().toString(), partnerPhone = normalized, partnerName = partnerName)
        conversationDao.insertConversation(local)
        runCatching {
            val s = supabaseClient.ensureAnonymousSession()
            val remote = supabaseClient.createConversation(s, normalized)
            if (remote != local.id) {
                conversationDao.deleteConversation(local.id)
                conversationDao.insertConversation(local.copy(id = remote))
            }
            return remote
        }
        return local.id
    }

    suspend fun sendMessage(conversationId: String, text: String, mediaUrl: String? = null, onTypingStateChange: (suspend (Boolean) -> Unit)? = null) {
        val p = getUserProfile(); val senderPhone = p.first ?: return; val senderName = p.second ?: "User"; val conversation = conversationDao.getConversationById(conversationId) ?: return
        val messageId = UUID.randomUUID().toString(); val encrypted = CryptoUtils.encrypt(text, conversationId); val now = System.currentTimeMillis()
        messageDao.insertMessage(MessageEntity(messageId, conversationId, senderPhone, senderName, encrypted, mediaUrl, now, "SENDING"))
        conversationDao.insertConversation(conversation.copy(lastMessageText = if (mediaUrl != null && text.isBlank()) "📷 Fotoğraf" else text, lastMessageTime = now))
        try {
            val s = supabaseClient.ensureAnonymousSession()
            supabaseClient.upsertProfile(s, senderPhone, senderName)
            supabaseClient.sendMessage(s, conversationId, messageId, encrypted)
            messageDao.updateMessageStatus(messageId, "SENT")
        } catch (_: Exception) {
            messageDao.updateMessageStatus(messageId, "PENDING")
        }
    }

    suspend fun startRealtime(onConnectionState: (Boolean) -> Unit = {}) {
        val session = supabaseClient.ensureAnonymousSession()
        supabaseClient.subscribeToMessages(session, onMessage = { remote ->
            kotlinx.coroutines.runBlocking {
                val conversation = conversationDao.getConversationById(remote.conversationId) ?: return@runBlocking
                val profile = userProfileDao.getUserProfile()
                val isOwn = remote.senderId == session.userId
                val senderPhone = if (isOwn) profile?.phoneNumber ?: conversation.partnerPhone else conversation.partnerPhone
                val senderName = if (isOwn) profile?.displayName ?: "User" else conversation.partnerName
                val decrypted = runCatching { CryptoUtils.decrypt(remote.ciphertext, remote.conversationId) }.getOrElse { remote.ciphertext }
                messageDao.insertMessage(
                    MessageEntity(
                        id = remote.id,
                        conversationId = remote.conversationId,
                        senderPhone = senderPhone,
                        senderName = senderName,
                        content = remote.ciphertext,
                        timestamp = System.currentTimeMillis(),
                        status = if (isOwn) "SENT" else "DELIVERED"
                    )
                )
                conversationDao.insertConversation(conversation.copy(lastMessageText = decrypted, lastMessageTime = System.currentTimeMillis()))
            }
        }, onConnectionState = onConnectionState)
    }

    fun closeRealtime() = supabaseClient.closeRealtime()

    suspend fun addReaction(messageId: String, emoji: String) = messageDao.updateMessageReaction(messageId, emoji)
    suspend fun deleteMessage(messageId: String) = messageDao.deleteMessage(messageId)
    suspend fun editMessage(messageId: String, conversationId: String, newText: String) = messageDao.updateMessageContent(messageId, CryptoUtils.encrypt(newText, conversationId))
    suspend fun clearConversationMessages(conversationId: String) { messageDao.deleteAllMessagesForConversation(conversationId); conversationDao.getConversationById(conversationId)?.let { conversationDao.insertConversation(it.copy(lastMessageText = "", lastMessageTime = System.currentTimeMillis())) } }
    suspend fun addCallLog(partnerName: String, partnerPhone: String, isVideo: Boolean, durationSeconds: Int, isOutgoing: Boolean) = callLogDao.insertCallLog(CallLogEntity(partnerName = partnerName, partnerPhone = partnerPhone, isVideo = isVideo, durationSeconds = durationSeconds, isOutgoing = isOutgoing))
    suspend fun deleteCallLog(id: Long) = callLogDao.deleteCallLog(id)
    suspend fun clearAllCallLogs() = callLogDao.clearAllCallLogs()
    suspend fun deleteConversation(id: String) = conversationDao.deleteConversation(id)
    fun normalizePhoneNumber(phone: String): String { val d = phone.filter { it.isDigit() }; return if (phone.startsWith("+")) "+$d" else if (d.length == 10 && d.startsWith("5")) "+90$d" else if (d.length == 11 && d.startsWith("05")) "+90${d.substring(1)}" else "+$d" }
}
