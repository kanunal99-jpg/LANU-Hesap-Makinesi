package com.example.core.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.core.calls.LanuCallSignalingClient
import com.example.core.crypto.CryptoUtils
import com.example.core.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    private val supabaseClient: SupabaseCommunicationClient,
    private val callSignalingClient: LanuCallSignalingClient
) {
    data class IncomingCallSignal(
        val conversationId: String,
        val partnerName: String,
        val isVideo: Boolean,
        val createdAt: String
    )

    val contacts: Flow<List<ContactEntity>> = contactDao.getAllContacts()
    val conversations: Flow<List<ConversationEntity>> = conversationDao.getAllConversations()
    val callLogs: Flow<List<CallLogEntity>> = callLogDao.getAllCallLogs()
    private val realtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    suspend fun logout() { supabaseClient.clearSession(); userProfileDao.deleteProfile(); settingsDao.deleteSetting("user_registered"); settingsDao.deleteSetting("user_phone"); settingsDao.deleteSetting("user_name"); settingsDao.deleteSetting("supabase_user_id") }
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
            if (remote != local.id) { conversationDao.deleteConversation(local.id); conversationDao.insertConversation(local.copy(id = remote)) }
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
            val s = supabaseClient.ensureAnonymousSession(); supabaseClient.upsertProfile(s, senderPhone, senderName); supabaseClient.sendMessage(s, conversationId, messageId, encrypted); messageDao.updateMessageStatus(messageId, "SENT")
        } catch (_: Exception) { messageDao.updateMessageStatus(messageId, "PENDING") }
    }

    suspend fun syncConversationMessages(conversationId: String) {
        val conversation = conversationDao.getConversationById(conversationId) ?: return
        val session = runCatching { supabaseClient.ensureAnonymousSession() }.getOrNull() ?: return
        val profile = userProfileDao.getUserProfile()
        runCatching { supabaseClient.listMessages(session, conversationId) }.getOrDefault(emptyList()).forEach { remote ->
            val isOwn = remote.senderId == session.userId
            val senderPhone = if (isOwn) profile?.phoneNumber ?: conversation.partnerPhone else conversation.partnerPhone
            val senderName = if (isOwn) profile?.displayName ?: "User" else conversation.partnerName
            val decrypted = CryptoUtils.decrypt(remote.ciphertext, remote.conversationId)
            val timestamp = parseRemoteTimestamp(remote.createdAt)
            messageDao.insertMessage(MessageEntity(remote.id, remote.conversationId, senderPhone, senderName, remote.ciphertext, timestamp = timestamp, status = if (isOwn) "SENT" else "DELIVERED"))
            conversationDao.insertConversation(conversation.copy(lastMessageText = decrypted, lastMessageTime = timestamp))
        }
    }

    suspend fun startRealtime(onConnectionState: (Boolean) -> Unit = {}) {
        val session = supabaseClient.ensureAnonymousSession()
        supabaseClient.subscribeToMessages(session, onMessage = { remote ->
            realtimeScope.launch {
                val conversation = conversationDao.getConversationById(remote.conversationId) ?: return@launch
                val profile = userProfileDao.getUserProfile()
                val isOwn = remote.senderId == session.userId
                val senderPhone = if (isOwn) profile?.phoneNumber ?: conversation.partnerPhone else conversation.partnerPhone
                val senderName = if (isOwn) profile?.displayName ?: "User" else conversation.partnerName
                val decrypted = CryptoUtils.decrypt(remote.ciphertext, remote.conversationId)
                val timestamp = parseRemoteTimestamp(remote.createdAt)
                messageDao.insertMessage(MessageEntity(remote.id, remote.conversationId, senderPhone, senderName, remote.ciphertext, timestamp = timestamp, status = if (isOwn) "SENT" else "DELIVERED"))
                conversationDao.insertConversation(conversation.copy(lastMessageText = decrypted, lastMessageTime = timestamp))
            }
        }, onConnectionState = onConnectionState)
    }

    suspend fun findIncomingCallSince(conversations: List<ConversationEntity>, since: String): IncomingCallSignal? {
        val ownId = callSignalingClient.currentUserId()
        for (conversation in conversations) {
            val signals = runCatching { callSignalingClient.poll(conversation.id, since) }.getOrDefault(emptyList())
            val offer = signals.asSequence()
                .filter { it.senderId != ownId && it.type == "offer" }
                .lastOrNull()
            if (offer != null) {
                return IncomingCallSignal(
                    conversationId = conversation.id,
                    partnerName = conversation.partnerName,
                    isVideo = !offer.payload.optString("sdp").contains("m=audio", ignoreCase = true) || offer.payload.optBoolean("video", false),
                    createdAt = offer.createdAt
                )
            }
        }
        return null
    }

    private fun parseRemoteTimestamp(value: String): Long {
        val formats = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
        return formats.asSequence().mapNotNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time }.getOrNull()
        }.firstOrNull() ?: System.currentTimeMillis()
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