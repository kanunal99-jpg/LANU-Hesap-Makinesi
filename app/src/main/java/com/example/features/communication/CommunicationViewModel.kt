package com.example.features.communication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.delivery.DeliveryCoordinator
import com.example.core.media.InlineMediaCodec
import com.example.core.database.ConversationEntity
import com.example.core.database.MessageEntity
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CommunicationViewModel(
    private val repository: CommunicationRepository,
    private val deliveryCoordinator: DeliveryCoordinator? = null
) : ViewModel() {
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
    private val _draftText = MutableStateFlow("")
    val draftText = _draftText.asStateFlow()
    private var draftConversationId: String? = null
    private var draftVersion = 0L
    private var refreshJob: Job? = null

    fun updateDraft(text: String) {
        if (text.length > 8000) {
            _errorMessage.value = "Mesaj en fazla 8000 karakter olabilir."
            return
        }
        val id = draftConversationId ?: return
        draftVersion++
        _draftText.value = text
        operation { repository.saveDraft(id, text) }
    }

    fun textQueued(text: String, conversationId: String? = null) {
        // Repository cleared the durable draft for the original conversation.
        // A late completion from a previous chat must NOT erase a new chat's
        // composer merely because both users typed the same message.
        if (conversationId != null && draftConversationId != conversationId) return
        if (_draftText.value == text) _draftText.value = ""
    }

    /** Volatile-only, never persisted. Survives system Photo Picker's secure lock.
     * PIN reauthentication is still required before preview or sending.
     */
    data class PreparedInlineMedia(val conversationId: String, val envelope: String, val expiresAtMillis: Long)
    private val _preparedInlineMedia = MutableStateFlow<PreparedInlineMedia?>(null)
    val preparedInlineMedia = _preparedInlineMedia.asStateFlow()
    private var mediaExpiryJob: Job? = null

    fun stashPreparedMedia(conversationId: String, envelope: String) {
        // Reject malformed media and cross-thread payloads before retaining even in RAM.
        InlineMediaCodec.decode(envelope)
        require(conversationId.isNotBlank()) { "Ek dosya için sohbet seçilemedi." }
        mediaExpiryJob?.cancel()
        val prepared = PreparedInlineMedia(
            conversationId,
            envelope,
            System.currentTimeMillis() + 5 * 60_000L
        )
        _preparedInlineMedia.value = prepared
        mediaExpiryJob = viewModelScope.launch {
            delay(5 * 60_000L)
            // Stale expiry must not delete a newer draft.
            if (_preparedInlineMedia.value === prepared) {
                _preparedInlineMedia.value = null
            }
        }
    }

    fun pendingMediaFor(conversationId: String): String? {
        val current = _preparedInlineMedia.value ?: return null
        if (current.expiresAtMillis < System.currentTimeMillis()) {
            _preparedInlineMedia.value = null
            return null
        }
        return current.takeIf { it.conversationId == conversationId }?.envelope
    }

    fun clearPreparedMedia() {
        mediaExpiryJob?.cancel()
        mediaExpiryJob = null
        _preparedInlineMedia.value = null
    }

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
    /**
     * Best-effort background handoff after the encrypted Room transaction.
     * No background network is requested without the owner's stored consent.
     * The foreground sync and 15-minute periodic retry remain independent.
     */
    private fun requestOutboxDrain() {
        val coordinator = deliveryCoordinator ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { coordinator.enqueuePendingOutbox() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Foreground/periodic sync remains available. */ }
        }
    }

    fun clearError() { _errorMessage.value = null }

    /** #224 private-area, non-destructive staging readiness flow. */
    enum class AccountDeletionPreflight {
        IDLE, CHECKING, METADATA_READY, READY_TO_CONFIRM, STORAGE_PENDING, UNAVAILABLE
    enum class AccountServerErasure {
        IDLE, SENDING, SERVER_ERASED_LOCAL_RETAINED, RESULT_UNKNOWN
    private val _accountDeletionPreflight = MutableStateFlow(AccountDeletionPreflight.IDLE)
    val accountDeletionPreflight = _accountDeletionPreflight.asStateFlow()
    private val _accountServerErasure = MutableStateFlow(AccountServerErasure.IDLE)
    val accountServerErasure = _accountServerErasure.asStateFlow()
    private var accountDeletionPreflightJob: Job? = null

    fun checkAccountDeletionPreflight() {
        if (accountDeletionPreflightJob?.isActive == true) return
        _accountDeletionPreflight.value = AccountDeletionPreflight.CHECKING
        accountDeletionPreflightJob = viewModelScope.launch {
            try {
                val readiness = repository.checkAccountDeletionPreflight()
                _accountDeletionPreflight.value = when {
                    !readiness.storageReady -> AccountDeletionPreflight.STORAGE_PENDING
                    readiness.deletionEnabled -> AccountDeletionPreflight.READY_TO_CONFIRM
                    else -> AccountDeletionPreflight.METADATA_READY
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Do not display backend payloads, identity codes or network URLs.
                _accountDeletionPreflight.value = AccountDeletionPreflight.UNAVAILABLE
            }

    fun requestStagedAccountErasure(
        enteredCode: String, enteredPhrase: String, backupAcknowledged: Boolean
    ) {
        if (_accountServerErasure.value != AccountServerErasure.IDLE) return
        if (!AccountDeletionConsentPolicy.accepted(
                _userPhone.value, enteredCode, enteredPhrase, backupAcknowledged,
                _accountDeletionPreflight.value == AccountDeletionPreflight.READY_TO_CONFIRM
            )) return
        _accountServerErasure.value = AccountServerErasure.SENDING
        viewModelScope.launch {
            try {
                repository.requestSignedAccountErasure(enteredCode, confirmed = true)
                _accountServerErasure.value = AccountServerErasure.SERVER_ERASED_LOCAL_RETAINED
                stopSync("account-erasure")
            } catch (e: CancellationException) {
                _accountServerErasure.value = AccountServerErasure.RESULT_UNKNOWN
                throw e
            } catch (_: Exception) {
                // The relay may have committed before connection loss.
                // Never claim failure or automatically replay destructive work.
                _accountServerErasure.value = AccountServerErasure.RESULT_UNKNOWN
            }

    fun clearAccountDeletionPreflight() {
        accountDeletionPreflightJob?.cancel()
        accountDeletionPreflightJob = null
        _accountDeletionPreflight.value = AccountDeletionPreflight.IDLE
        // Intentionally do NOT reset SENDING/UNKNOWN/SERVER_ERASED.
        // A second attempt could erase a re-created identity.
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
            var failures = 0
            while (isActive && syncOwners.isNotEmpty()) {
                synchronize()
                // Foreground-only adaptive backoff. A failed network must not
                // be polled every five seconds or wake the phone in background.
                failures = if (_networkState.value == NetworkState.ONLINE) 0
                    else (failures + 1).coerceAtMost(4)
                delay(if (failures == 0) 5000L
                    else minOf(60_000L, 5000L * (1 shl failures)))
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
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { synchronize() }
    }
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
        // Clear the previous peer synchronously. Loading the next conversation
        // is suspendable and must not leave an old recipient target available.
        _activeConversation.value = null
        _activeMessages.value = emptyList()
        draftConversationId = id
        draftVersion = 0L
        _draftText.value = ""
        messagesCollectorJob = viewModelScope.launch {
            try {
                val loaded = repository.getConversationById(id)
                if (draftConversationId != id || !isActive) return@launch
                _activeConversation.value = loaded
                val draft = repository.loadDraft(id)
                if (draftConversationId == id && draftVersion == 0L) _draftText.value = draft
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
        // A chat may be closed before its Room load resolves. Cancel the
        // still-pending selection, but never let a late pause from an older
        // screen close a different private conversation.
        if (ChatComposePolicy.shouldClose(id, draftConversationId, _activeConversation.value?.id)) {
            messagesCollectorJob?.cancel()
            messagesCollectorJob = null
            _activeConversation.value = null
            _activeMessages.value = emptyList()
            draftConversationId = null
            draftVersion = 0L
            _draftText.value = ""
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
        expectedConversationId: String? = null,
        onQueued: () -> Unit = {}
    ) {
        val conversation = _activeConversation.value
        if (conversation == null ||
            (expectedConversationId != null &&
                (conversation.id != expectedConversationId || draftConversationId != expectedConversationId))) {
            _errorMessage.value = "Sohbet değişti veya yüklenmedi. Mesaj gönderilmedi."
            return
        }
        if (_isSending.value || text.isBlank() || text.length > 8000) return
        _isSending.value = true
        operation {
            try {
                // Clear the composer only after encrypted content is safely queued in Room.
                val messageId = repository.sendMessage(conversation.id, text)
                onQueued()
                requestOutboxDrain()
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
                refresh()
            } finally {
                _isSending.value = false
            }
        }
    }
    /** Only verified small inline attachments; no public URLs or plaintext storage. */
    fun sendInlineAttachment(
        envelope: String,
        expectedConversationId: String? = null,
        onQueued: () -> Unit = {}
    ) {
        val conversation = _activeConversation.value
        if (conversation == null ||
            (expectedConversationId != null &&
                (conversation.id != expectedConversationId || draftConversationId != expectedConversationId))) {
            _errorMessage.value = "Sohbet değişti veya yüklenmedi. Ek dosya gönderilmedi."
            return
        }
        if (_isSending.value) {
            _errorMessage.value = "Önceki mesaj hazırlanıyor; tekrar deneyin."
            return
        }
        _isSending.value = true
        operation {
            try {
                // Saving encrypted content in Room must succeed before the draft disappears.
                repository.sendInlineAttachment(conversation.id, envelope)
                if (_preparedInlineMedia.value?.envelope == envelope) clearPreparedMedia()
                onQueued()
                requestOutboxDrain()
                refresh()
            } finally {
                _isSending.value = false
            }
        }
    }
    fun sendMediaMessage(caption: String, mediaUrl: String) {
        _errorMessage.value = "Büyük medya aktarımı henüz etkin değil. Küçük fotoğraf/belge seçebilirsiniz."
    }
    fun insertSystemMessage(partnerName: String, text: String) { /* Call UI demos never create a delivered remote message. */ }
    fun addReaction(messageId: String, emoji: String) = operation { repository.addReaction(messageId, emoji); synchronize() }
    fun deleteMessage(messageId: String) = operation { repository.deleteMessage(messageId); synchronize() }
    fun retryMessage(messageId: String) = operation {
        repository.retryMessage(messageId)
        requestOutboxDrain()
        refresh()
    }
    /**
     * Requeue one bounded batch before starting network synchronization.
     * Individual retries can otherwise race their own refresh jobs, sending
     * only the first item immediately and leaving the others until next poll.
     * The repository validates identity/FAILED state under its sync mutex.
     */
    fun retryFailedMessages(messageIds: List<String>, expectedConversationId: String) = operation {
        // A menu callback can queue a coroutine just before the user switches chats.
        // Do not retry messages from a stale screen, even if they belong to this user.
        if (expectedConversationId.isBlank() ||
            _activeConversation.value?.id != expectedConversationId) return@operation
        var requeued = 0
        for (id in messageIds.distinct().take(ChatFailedRetryPolicy.MAX_BATCH)) {
            if (_activeConversation.value?.id != expectedConversationId) break
            try {
                // Repository rechecks sender, FAILED status and conversation while
                // holding the sync mutex; stale IDs cannot cross chat boundaries.
                repository.retryMessage(id, expectedConversationId)
                requeued++
            } catch (_: IllegalArgumentException) {
                // Status may have changed to SENT/PENDING after menu opened.
                // Never requeue already accepted or someone else's message.
            }
        }
        if (requeued > 0 && _activeConversation.value?.id == expectedConversationId) {
            requestOutboxDrain()
            synchronize()
        }
    }
    fun editMessage(messageId: String, text: String) {
        val conversation = _activeConversation.value ?: return
        operation { repository.editMessage(messageId, conversation.id, text); synchronize() }
    }
    fun clearConversationMessages(id: String) = operation { repository.clearConversationMessages(id) }
    fun deleteConversation(id: String) = operation { repository.clearConversationMessages(id); repository.deleteConversation(id); leaveConversation(id) }
    fun addCallLog(name: String, code: String, video: Boolean, duration: Int, outgoing: Boolean) = operation { repository.addCallLog(name, code, video, duration, outgoing) }
    fun deleteCallLog(id: Long) = operation { repository.deleteCallLog(id) }
    fun clearAllCallLogs() = operation { repository.clearAllCallLogs() }
    class Factory(
        private val repository: CommunicationRepository,
        private val deliveryCoordinator: DeliveryCoordinator? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CommunicationViewModel(repository, deliveryCoordinator) as T
    }
}
