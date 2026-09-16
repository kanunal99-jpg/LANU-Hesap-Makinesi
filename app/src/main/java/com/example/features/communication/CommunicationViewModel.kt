package com.example.features.communication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.ContactEntity
import com.example.core.database.ConversationEntity
import com.example.core.database.MessageEntity
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CommunicationViewModel(private val repository: CommunicationRepository) : ViewModel() {

    val contacts = repository.contacts
    val conversations = repository.conversations
    val callLogs = repository.callLogs

    private val _isRegistered = MutableStateFlow(false)
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _userPhone = MutableStateFlow("")
    val userPhone: StateFlow<String> = _userPhone.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _activeConversation = MutableStateFlow<ConversationEntity?>(null)
    val activeConversation: StateFlow<ConversationEntity?> = _activeConversation.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val activeMessages: StateFlow<List<MessageEntity>> = _activeMessages.asStateFlow()

    private val _isPartnerTyping = MutableStateFlow(false)
    val isPartnerTyping: StateFlow<Boolean> = _isPartnerTyping.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _networkState = MutableStateFlow(NetworkState.ONLINE)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val _supabaseUrl = MutableStateFlow("")
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseKey = MutableStateFlow("")
    val supabaseKey: StateFlow<String> = _supabaseKey.asStateFlow()

    private var messagesCollectorJob: Job? = null

    enum class NetworkState {
        ONLINE,
        OFFLINE,
        RECONNECTING
    }

    init {
        checkRegistration()
        seedSampleContacts()
        loadSupabaseCredentials()
    }

    fun loadSupabaseCredentials() {
        viewModelScope.launch {
            val creds = repository.getSupabaseCredentials()
            _supabaseUrl.value = creds.first ?: ""
            _supabaseKey.value = creds.second ?: ""
        }
    }

    fun saveSupabaseCredentials(url: String, key: String) {
        viewModelScope.launch {
            repository.saveSupabaseCredentials(url.trim(), key.trim())
            _supabaseUrl.value = url.trim()
            _supabaseKey.value = key.trim()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun checkRegistration() {
        viewModelScope.launch {
            val registered = repository.isUserRegistered()
            _isRegistered.value = registered
            if (registered) {
                val profile = repository.getUserProfile()
                _userPhone.value = profile.first ?: ""
                _userName.value = profile.second ?: ""
            }
        }
    }

    private fun seedSampleContacts() {
        viewModelScope.launch {
            // Seed sample contacts if empty to make it highly usable
            repository.contacts.first().let { currentList ->
                if (currentList.isEmpty()) {
                    repository.addContact("LANU Secure Echo Node", "+905991112233")
                    repository.addContact("Principal Architect Lead", "+905994445566")
                    repository.addContact("Security Operations Center", "+905997778899")
                }
            }
        }
    }

    fun register(phone: String, name: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val success = repository.registerUser(phone, name)
            if (success) {
                _isRegistered.value = true
                _userPhone.value = phone
                _userName.value = name
                onComplete()
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.logout()
            _isRegistered.value = false
            _userPhone.value = ""
            _userName.value = ""
            onComplete()
        }
    }

    fun addContact(name: String, phone: String) {
        viewModelScope.launch {
            repository.addContact(name, phone)
        }
    }

    fun deleteContact(phone: String) {
        viewModelScope.launch {
            repository.deleteContact(phone)
        }
    }

    fun selectConversation(convId: String) {
        messagesCollectorJob?.cancel()
        messagesCollectorJob = viewModelScope.launch {
            val conv = repository.getConversationById(convId)
                ?: repository.conversations.first().find { it.id == convId }
            _activeConversation.value = conv
            if (conv != null) {
                // Collect messages for this conversation
                repository.getMessages(convId).collectLatest { msgs ->
                    _activeMessages.value = msgs
                }
            }
        }
    }

    fun startChat(partnerPhone: String, partnerName: String, onChatIdReady: (String) -> Unit) {
        viewModelScope.launch {
            val normalizedPhone = partnerPhone.replace(" ", "").replace("-", "")
            val list = repository.conversations.first()
            val existing = list.find { it.partnerPhone.replace(" ", "") == normalizedPhone }
            if (existing != null) {
                selectConversation(existing.id)
                onChatIdReady(existing.id)
            } else {
                val newId = repository.createConversation(partnerPhone, partnerName)
                selectConversation(newId)
                onChatIdReady(newId)
            }
        }
    }

    fun sendMessage(text: String, isSelfDestruct: Boolean = false, destructDelaySeconds: Int = 10) {
        val conv = _activeConversation.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(
                conversationId = conv.id,
                text = text,
                onTypingStateChange = { typing ->
                    _isPartnerTyping.value = typing
                }
            )
            
            // If self-destruct is enabled, find the message and schedule auto-deletion
            if (isSelfDestruct) {
                delay((destructDelaySeconds + 2) * 1000L)
                val latestMsgs = repository.getMessages(conv.id).first()
                val sentMsg = latestMsgs.lastOrNull { it.senderPhone == _userPhone.value }
                if (sentMsg != null) {
                    repository.deleteMessage(sentMsg.id)
                }
            }
        }
    }

    fun sendMediaMessage(caption: String, mediaUrl: String) {
        val conv = _activeConversation.value ?: return
        viewModelScope.launch {
            repository.sendMessage(
                conversationId = conv.id,
                text = caption,
                mediaUrl = mediaUrl,
                onTypingStateChange = { typing ->
                    _isPartnerTyping.value = typing
                }
            )
        }
    }

    fun insertSystemMessage(partnerName: String, text: String) {
        viewModelScope.launch {
            val list = repository.conversations.first()
            val existing = list.find { it.partnerName == partnerName }
            if (existing != null) {
                repository.sendMessage(existing.id, text)
            }
        }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            repository.addReaction(messageId, emoji)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            repository.clearConversationMessages(conversationId)
            repository.deleteConversation(conversationId)
            if (_activeConversation.value?.id == conversationId) {
                _activeConversation.value = null
                _activeMessages.value = emptyList()
            }
        }
    }

    fun editMessage(messageId: String, newText: String) {
        val conv = _activeConversation.value ?: return
        if (newText.isBlank()) return
        viewModelScope.launch {
            repository.editMessage(messageId, conv.id, newText)
        }
    }

    fun clearConversationMessages(conversationId: String) {
        viewModelScope.launch {
            repository.clearConversationMessages(conversationId)
        }
    }

    fun addCallLog(partnerName: String, partnerPhone: String, isVideo: Boolean, durationSeconds: Int, isOutgoing: Boolean) {
        viewModelScope.launch {
            repository.addCallLog(partnerName, partnerPhone, isVideo, durationSeconds, isOutgoing)
        }
    }

    fun deleteCallLog(id: Long) {
        viewModelScope.launch {
            repository.deleteCallLog(id)
        }
    }

    fun clearAllCallLogs() {
        viewModelScope.launch {
            repository.clearAllCallLogs()
        }
    }

    fun simulateNetworkLost() {
        viewModelScope.launch {
            _networkState.value = NetworkState.OFFLINE
            delay(3000)
            _networkState.value = NetworkState.RECONNECTING
            delay(2000)
            _networkState.value = NetworkState.ONLINE
        }
    }

    class Factory(private val repository: CommunicationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CommunicationViewModel(repository) as T
        }
    }
}
