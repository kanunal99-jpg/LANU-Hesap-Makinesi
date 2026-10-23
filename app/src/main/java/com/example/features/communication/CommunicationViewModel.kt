package com.example.features.communication

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.ConversationEntity
import com.example.core.database.MessageEntity
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CommunicationViewModel(private val repository: CommunicationRepository) : ViewModel() {
    val contacts = repository.contacts
    val conversations = repository.conversations
    val callLogs = repository.callLogs
    private val _isRegistered = MutableStateFlow(false)
    val isRegistered = _isRegistered.asStateFlow()
    private val _userPhone = MutableStateFlow("") // Legacy UI name; value is a LANU public code.
    val userPhone = _userPhone.asStateFlow()
    private val _userId = MutableStateFlow("")
    val userId = _userId.asStateFlow()
    private val _userName = MutableStateFlow("")
    val userName = _userName.asStateFlow()
    private val _activeConversation = MutableStateFlow<ConversationEntity?>(null)
    val activeConversation = _activeConversation.asStateFlow()
    private val _activeMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val activeMessages = _activeMessages.asStateFlow()
    val isPartnerTyping = MutableStateFlow(false).asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _isSending = MutableStateFlow(false)
    val isSending = _isSending.asStateFlow()
    private val _loadedMedia = MutableStateFlow<Map<String, CommunicationRepository.ReceivedMedia>>(emptyMap())
    val loadedMedia = _loadedMedia.asStateFlow()
    private val _downloadingMediaId = MutableStateFlow<String?>(null)
    val downloadingMediaId = _downloadingMediaId.asStateFlow()
    enum class NetworkState { ONLINE, OFFLINE, RECONNECTING, UNCONFIGURED }
    private val _networkState = MutableStateFlow(if (repository.relayConfigured) NetworkState.OFFLINE else NetworkState.UNCONFIGURED)
    val networkState = _networkState.asStateFlow()
    private var messagesCollectorJob: Job? = null
    private var syncJob: Job? = null
    private val syncOwners = mutableSetOf<String>()

    private fun operation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = e.message ?: "İşlem tamamlanamadı. Lütfen tekrar deneyin."
            }
        }
    }
    fun clearError() { _errorMessage.value = null }
    fun checkRegistration() = operation {
        val own = repository.ensureIdentity()
        _userPhone.value = own.code
        _userId.value = own.id
        _userName.value = repository.getUserProfile().second ?: "LANU Kullanıcısı"
        _isRegistered.value = true
    }
    fun startSync(owner: String) {
        syncOwners.add(owner)
        checkRegistration()
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            while (isActive && syncOwners.isNotEmpty()) {
                synchronize()
                delay(5000L)
            }
        }
    }
    fun stopSync(owner: String) {
        syncOwners.remove(owner)
        if (syncOwners.isEmpty()) { syncJob?.cancel(); syncJob = null }
    }
    private suspend fun synchronize() {
        if (repository.relayConfigured && _networkState.value != NetworkState.ONLINE) {
            _networkState.value = NetworkState.RECONNECTING
        }
        try {
            repository.synchronize(_activeConversation.value?.id)
            _networkState.value = NetworkState.ONLINE
            repository.identity.value?.let { _userPhone.value = it.code; _userId.value = it.id }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            _networkState.value = if (repository.relayConfigured) NetworkState.OFFLINE else NetworkState.UNCONFIGURED
            if (repository.relayConfigured) _errorMessage.value = e.message ?: "Sunucuya bağlanılamadı. Mesajlar bekliyor."
        }
    }
    fun refresh() = operation { synchronize() }
    fun updateDisplayName(name: String) = operation {
        require(name.isNotBlank()) { "Görünen adı girin." }
        repository.registerUser("", name)
        _userName.value = name.trim().take(60)
    }
    fun rotateCode() {
        if (_busy.value) return
        _busy.value = true
        operation {
            try { _userPhone.value = repository.rotateCode().code }
            finally { _busy.value = false }
        }
    }
    fun exportIdentity(password: String, writer: suspend (String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        operation { try { writer(repository.exportIdentity(password)) } finally { _busy.value = false } }
    }
    fun restoreIdentity(password: String, reader: suspend () -> String) {
        if (_busy.value) return
        _busy.value = true
        operation {
            try {
                val own = repository.restoreIdentity(reader(), password)
                _userPhone.value = own.code; _userId.value = own.id
            } finally { _busy.value = false }
        }
    }
    fun addContact(name: String, code: String) = operation { repository.addContact(name, code); synchronize() }
    fun deleteContact(code: String) = operation { repository.deleteContact(code) }
    fun selectConversation(id: String) {
        messagesCollectorJob?.cancel()
        messagesCollectorJob = viewModelScope.launch {
            try {
                _activeConversation.value = repository.getConversationById(id)
                repository.markConversationOpened(id)
                repository.getMessages(id).collectLatest { _activeMessages.value = it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // An uncaught coroutine failure terminates the Android process.
                // Do not log peer IDs, messages, or encryption material.
                android.util.Log.e("LANU-Messaging", "Conversation load failed: ${e.javaClass.simpleName}")
                _errorMessage.value = "Sohbet yüklenemedi. Tekrar deneyin."
            }
        }
    }
    fun leaveConversation(id: String) {
        // Keep decrypted attachment bytes only in volatile memory for the active chat.
        _loadedMedia.value = emptyMap()
        _downloadingMediaId.value = null

        if (_activeConversation.value?.id == id) {
            messagesCollectorJob?.cancel()
            _activeConversation.value = null
            _activeMessages.value = emptyList()
        }
    }
    fun startChat(code: String, name: String, onReady: (String) -> Unit) = operation {
        val id = repository.createConversation(code, name)
        selectConversation(id)
        onReady(id)
    }
    fun sendMessage(
        text: String,
        isSelfDestruct: Boolean = false,
        destructDelaySeconds: Int = 10,
        onQueued: () -> Unit = {}
    ) {
        val conversation = _activeConversation.value
        if (conversation == null) {
            _errorMessage.value = "Sohbet henüz açılmadı. Mesaj saklanmadı."
            return
        }
        if (_isSending.value) return
        _isSending.value = true
        operation {
            try {
                // Clear the composer only after encrypted content is safely queued in Room.
                val messageId = repository.sendMessage(conversation.id, text)
                onQueued()
                // The timer is *only* a local hide, never remote deletion.
                if (isSelfDestruct) viewModelScope.launch {
                    try {
                        delay(destructDelaySeconds.coerceAtLeast(1) * 1000L)
                        repository.hideMessageLocally(messageId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        android.util.Log.e("LANU-Messaging", "Local hide failed: ${e.javaClass.simpleName}")
                        _errorMessage.value = "Mesajın otomatik gizlenmesi tamamlanamadı."
                    }
                }
                synchronize()
            } finally {
                _isSending.value = false
            }
        }
    }
    fun sendMedia(context: Context, uri: Uri, photo: Boolean) {
        if (_isSending.value) return
        _isSending.value = true
        operation {
            try {
                repository.sendMedia(_activeConversation.value?.id
                    ?: error("Sohbet henüz seçilmedi."), context, uri, photo)
                synchronize()
            } finally {
                _isSending.value = false
            }
        }
    }

    fun downloadMedia(conversationId: String, messageId: String) {
        if (_loadedMedia.value.containsKey(messageId) || _downloadingMediaId.value != null) return
        _downloadingMediaId.value = messageId
        operation {
            try {
                val received = repository.receiveMedia(conversationId, messageId)
                // Never persist decrypted private media to the public Downloads directory.
                if (_activeConversation.value?.id == conversationId) {
                    _loadedMedia.value = _loadedMedia.value + (messageId to received)
                }
            } finally {
                _downloadingMediaId.value = null
            }
        }
    }
    fun insertSystemMessage(partnerName: String, text: String) { /* Call UI demos never create a delivered remote message. */ }
    fun addReaction(messageId: String, emoji: String) = operation { repository.addReaction(messageId, emoji); synchronize() }
    fun deleteMessage(messageId: String) = operation { repository.deleteMessage(messageId); synchronize() }
    fun editMessage(messageId: String, text: String) {
        val conversation = _activeConversation.value ?: return
        operation { repository.editMessage(messageId, conversation.id, text); synchronize() }
    }
    fun clearConversationMessages(id: String) = operation { repository.clearConversationMessages(id) }
    fun deleteConversation(id: String) = operation { repository.clearConversationMessages(id); repository.deleteConversation(id); leaveConversation(id) }
    fun addCallLog(name: String, code: String, video: Boolean, duration: Int, outgoing: Boolean) = operation { repository.addCallLog(name, code, video, duration, outgoing) }
    fun deleteCallLog(id: Long) = operation { repository.deleteCallLog(id) }
    fun clearAllCallLogs() = operation { repository.clearAllCallLogs() }
    class Factory(private val repository: CommunicationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CommunicationViewModel(repository) as T
    }
}
