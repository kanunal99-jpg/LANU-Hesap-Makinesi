package com.example.features.communication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CommunicationViewModel(private val repository: CommunicationRepository) : ViewModel() {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall.asStateFlow()

    private var messagesCollectorJob: Job? = null
    private var incomingCallMonitorJob: Job? = null
    private var incomingCallCursor = nowIsoUtc()

    fun connectRealtime() {
        repository.connectRealtime()
        incomingCallMonitorJob?.cancel()
        incomingCallMonitorJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                if (_incomingCall.value == null && _conversations.value.isNotEmpty()) {
                    repository.findIncomingCallSince(_conversations.value.first(), incomingCallCursor)?.let { call ->
                        incomingCallCursor = call.createdAt
                        _incomingCall.value = call
                    }
                }
                delay(1000)
            }
        }
    }

    fun acceptIncomingCall() {
        _incomingCall.value = null
        incomingCallCursor = nowIsoUtc()
    }

    fun rejectIncomingCall() {
        viewModelScope.launch {
            _incomingCall.value?.let { call ->
                repository.rejectCall(call.conversationId)
            }
            _incomingCall.value = null
            incomingCallCursor = nowIsoUtc()
        }
    }

    fun selectConversation(conversation: Conversation) {
        repository.selectConversation(conversation)
        messagesCollectorJob?.cancel()
        messagesCollectorJob = viewModelScope.launch {
            repository.messages.collect { _messages.value = it }
        }
    }

    fun startChat(userId: String) = viewModelScope.launch {
        repository.startChat(userId)
        _conversations.value = repository.conversations.value
    }

    fun sendMessage(text: String) = viewModelScope.launch { repository.sendMessage(text) }
    fun sendMedia(uri: String, mimeType: String) = viewModelScope.launch { repository.sendMedia(uri, mimeType) }
    fun addReaction(messageId: String, reaction: String) = viewModelScope.launch { repository.addReaction(messageId, reaction) }
    fun logCall(conversationId: String, type: String) = viewModelScope.launch { repository.logCall(conversationId, type) }
    fun deleteCallLog(id: Long) { viewModelScope.launch { repository.deleteCallLog(id) } }
    fun clearAllCallLogs() { viewModelScope.launch { repository.clearAllCallLogs() } }
    fun simulateNetworkLost() = connectRealtime()

    override fun onCleared() {
        repository.closeRealtime()
        messagesCollectorJob?.cancel()
        incomingCallMonitorJob?.cancel()
        super.onCleared()
    }

    class Factory(private val repository: CommunicationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CommunicationViewModel(repository) as T
    }

    companion object {
        private fun nowIsoUtc(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
    }
}
