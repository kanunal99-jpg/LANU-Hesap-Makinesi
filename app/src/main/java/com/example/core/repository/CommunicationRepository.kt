package com.example.core.repository

import androidx.room.withTransaction
import com.example.core.crypto.CryptoUtils
import com.example.core.database.*
import com.example.core.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class CommunicationRepository(
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val settingsDao: SettingsDao,
    private val userProfileDao: UserProfileDao,
    private val callLogDao: CallLogDao,
    private val identityStore: MessagingIdentityStore,
    private val relay: MessagingRelay,
    private val database: AppDatabase
) {
    val contacts = contactDao.getAllContacts().map { list -> list.filter { UserCodes.normalize(it.phoneNumber).isNotBlank() } }
    val conversations = conversationDao.getAllConversations()
    val callLogs = callLogDao.getAllCallLogs()
    private val identityMutex = Mutex()
    private val syncMutex = Mutex()
    private val _identity = MutableStateFlow<MessagingIdentity?>(null)
    val identity: StateFlow<MessagingIdentity?> = _identity.asStateFlow()
    val relayConfigured get() = relay.configured

    /**
     * A missing Keystore identity with existing communication data is NOT a fresh
     * install: minting a new identity would silently orphan encrypted messages.
     */
    private suspend fun hasLinkedCommunicationData(): Boolean =
        conversationDao.getAllConversations().first().isNotEmpty() ||
            contactDao.getAllContacts().first().isNotEmpty() ||
            messageDao.countMessages() > 0 ||
            callLogDao.countCallLogs() > 0 ||
            settingsDao.getSettingValue("lanu_remote_registered") != null

    private suspend fun hasExistingAccountEvidence(): Boolean =
        hasLinkedCommunicationData() ||
            userProfileDao.getUserProfile()?.let { UserCodes.normalize(it.phoneNumber).isNotBlank() } == true

    /** Phone-number profile from pre-E2EE builds may have plaintext legacy history.
     * Only that narrowly identifiable migration is permitted to create new keys.
     * Modern LANU contacts, conversations, or E2EE messages must not be rebound.
     */
    private suspend fun canMigrateLegacyPhoneProfile(): Boolean {
        val profile = userProfileDao.getUserProfile() ?: return false
        if (UserCodes.normalize(profile.phoneNumber).isNotBlank() ||
            settingsDao.getSettingValue("lanu_remote_registered") != null ||
            messageDao.countModernEncryptedMessages() > 0) return false
        if (contactDao.getAllContacts().first().any { UserCodes.normalize(it.phoneNumber).isNotBlank() }) return false
        if (conversationDao.getAllConversations().first().any { UserCodes.normalize(it.partnerPhone).isNotBlank() }) return false
        return true
    }

    suspend fun ensureIdentity(): MessagingIdentity = identityMutex.withLock {
        val current = _identity.value ?: withContext(Dispatchers.IO) {
            identityStore.read() ?: run {
                check(!hasExistingAccountEvidence() || canMigrateLegacyPhoneProfile()) {
                    "Önceki LANU kimliği bulunamadı. Sohbetler ve kayıtlar korunuyor; kimlik yedeğini geri yükleyin."
                }
                UserCodes.create().also(identityStore::save)
            }
        }
        val old = userProfileDao.getUserProfile()
        if (old?.phoneNumber != current.code) {
            if (old != null && UserCodes.normalize(old.phoneNumber).isBlank()) messageDao.migrateSender(old.phoneNumber, current.id)
            userProfileDao.replaceProfile(UserProfileEntity(current.code, old?.displayName ?: "LANU Kullanıcısı"))
        }
        _identity.value = current
        current
    }

    suspend fun getUserProfile(): Pair<String?, String?> {
        val own = ensureIdentity()
        return own.code to userProfileDao.getUserProfile()?.displayName
    }
    suspend fun isUserRegistered(): Boolean { ensureIdentity(); return true }
    suspend fun registerUser(phoneNumber: String, displayName: String): Boolean {
        // Legacy callers can update a display name, never set a phone/code identity.
        if (displayName.isBlank()) return false
        val own = ensureIdentity()
        userProfileDao.replaceProfile(UserProfileEntity(own.code, displayName.trim().take(60)))
        return true
    }
    suspend fun logout() { /* Identity intentionally survives leaving the private space. */ }

    private suspend fun registerRemote(): MessagingIdentity {
        val own = ensureIdentity()
        val response = relay.request(own, "register", JSONObject().put("code", own.code).put("nonce", own.nonce).put("publicKey", own.publicKey))
        val code = response.getString("code")
        val nonce = response.getString("nonce")
        check(UserCodes.matches(code, own.publicKey, nonce)) { "Sunucu kimliği doğrulanamadı" }
        if (code != own.code || nonce != own.nonce) {
            val recovered = own.copy(code = code, nonce = nonce)
            withContext(Dispatchers.IO) { identityStore.save(recovered) }
            _identity.value = recovered
            userProfileDao.replaceProfile(UserProfileEntity(code, userProfileDao.getUserProfile()?.displayName ?: "LANU Kullanıcısı"))
        }
        settingsDao.insertSetting(SettingsEntity("lanu_remote_registered", "true"))
        return _identity.value!!
    }

    suspend fun rotateCode(): MessagingIdentity = syncMutex.withLock {
        val own = ensureIdentity()
        val nonce = UserCodes.newNonce()
        val changed = own.copy(nonce = nonce, code = UserCodes.code(own.publicKey, nonce))
        if (relay.configured || settingsDao.getSettingValue("lanu_remote_registered") == "true") {
            registerRemote()
            relay.request(_identity.value!!, "rotate", JSONObject().put("nonce", nonce).put("expectedCode", _identity.value!!.code))
        }
        withContext(Dispatchers.IO) { identityStore.save(changed) }
        _identity.value = changed
        userProfileDao.replaceProfile(UserProfileEntity(changed.code, userProfileDao.getUserProfile()?.displayName ?: "LANU Kullanıcısı"))
        changed
    }

    suspend fun exportIdentity(password: String): String = withContext(Dispatchers.Default) {
        IdentityBackup.export(ensureIdentity(), password)
    }
    suspend fun restoreIdentity(backup: String, password: String): MessagingIdentity = syncMutex.withLock {
        val restored = withContext(Dispatchers.Default) { IdentityBackup.restore(backup, password) }
        identityMutex.withLock {
            // A missing identity must not be auto-generated during explicit recovery.
            val previous = _identity.value ?: withContext(Dispatchers.IO) { identityStore.read() }
            // Explicit user backup restore may replace an empty account profile;
            // bound messages/contacts/conversations/calls are never crossed.
            val hasData = hasLinkedCommunicationData()
            val currentProfile = userProfileDao.getUserProfile()
            if (previous != null) {
                require(previous.id == restored.id || !hasData) {
                    "Bu cihazda başka kimliğe ait kayıtlar var. Kimlikler birbirinin üzerine yazılamaz."
                }
            } else if (hasData) {
                // Without the original private key we cannot prove ownership from an
                // anonymous conversation row. A matching saved LANU code is required.
                require(currentProfile != null && currentProfile.phoneNumber == restored.code) {
                    "Mevcut veriler farklı veya belirsiz bir kimliğe ait; otomatik üzerine yazma reddedildi."
                }
            }
            withContext(Dispatchers.IO) { identityStore.save(restored) }
            _identity.value = restored
            userProfileDao.replaceProfile(UserProfileEntity(
                restored.code, currentProfile?.displayName ?: "LANU Kullanıcısı"))
            restored
        }
    }

    suspend fun addContact(name: String, code: String) {
        val own = ensureIdentity()
        val normalized = UserCodes.normalize(code)
        require(normalized.isNotBlank()) { "Geçerli bir LANU kullanıcı kodu girin." }
        require(normalized != own.code) { "Kendi kodunuzu kişi olarak ekleyemezsiniz." }
        contactDao.insertContact(ContactEntity(normalized, name.trim().take(60).ifBlank { normalized }, statusText = "Karşılıklı kayıt bekleniyor"))
    }
    suspend fun deleteContact(code: String) {
        val peer = savedPeer(code)
        if (relay.configured && peer != null) {
            relay.request(registerRemote(), "removeContact", JSONObject().put("peerId", peer.getString("id")))
        }
        contactDao.deleteContact(code)
        settingsDao.deleteSetting("lanu_peer_code_$code")
    }
    suspend fun lookupContactByPhone(code: String) = contactDao.getContactByPhone(UserCodes.normalize(code))
    suspend fun getConversationById(id: String) = conversationDao.getConversationById(id)
    /** UI-only unread reset; server READ receipts still require active conversation sync. */
    suspend fun markConversationOpened(id: String) = conversationDao.clearUnreadCount(id)
    fun normalizePhoneNumber(code: String) = UserCodes.normalize(code)
    private suspend fun savedPeer(code: String): JSONObject? = settingsDao.getSettingValue("lanu_peer_code_$code")?.let(::JSONObject)
    private suspend fun peerForConversation(id: String): JSONObject? = settingsDao.getSettingValue("lanu_peer_conv_$id")?.let(::JSONObject)

    private suspend fun savePeer(peer: JSONObject, originalCode: String? = null) {
        val code = peer.getString("code")
        val publicKey = peer.getString("publicKey")
        require(UserCodes.matches(code, publicKey, peer.getString("nonce"))) { "Kişi kodu ile güvenlik anahtarı eşleşmiyor." }
        val peerId = peer.getString("id")
        require(peerId == UserCodes.id(publicKey)) { "Kişi kimliği doğrulanamadı." }
        val oldById = settingsDao.getSettingValue("lanu_peer_id_$peerId")?.let(::JSONObject)
        require(oldById == null || oldById.getString("publicKey") == publicKey) { "Kişinin güvenlik anahtarı değişti." }
        val convId = UserCodes.conversationId(ensureIdentity().id, peerId)
        database.withTransaction {
            settingsDao.insertSetting(SettingsEntity("lanu_peer_id_$peerId", peer.toString()))
        settingsDao.insertSetting(SettingsEntity("lanu_peer_code_$code", peer.toString()))
        if (originalCode != null) settingsDao.insertSetting(SettingsEntity("lanu_peer_code_$originalCode", peer.toString()))
        settingsDao.insertSetting(SettingsEntity("lanu_peer_conv_$convId", peer.toString()))
        val saved = contactDao.getContactByPhone(code) ?: originalCode?.let { contactDao.getContactByPhone(it) }
        if (saved != null) {
            contactDao.insertContact(saved.copy(phoneNumber = code, statusText = if (peer.optBoolean("mutual")) "Karşılıklı kayıtlı" else "Karşı tarafın kaydı bekleniyor"))
            if (saved.phoneNumber != code) contactDao.deleteContact(saved.phoneNumber)
        }
        conversationDao.getConversationById(convId)?.let { conversationDao.insertConversation(it.copy(partnerPhone = code)) }
        }
    }

    private suspend fun resolveContact(code: String): JSONObject {
        val own = registerRemote()
        val peer = relay.request(own, "addContact", JSONObject().put("code", code)).getJSONObject("peer")
        savePeer(peer, code)
        return peer
    }
    suspend fun createConversation(partnerPhone: String, partnerName: String): String = syncMutex.withLock {
        val code = UserCodes.normalize(partnerPhone)
        require(code.isNotBlank()) { "Bu eski kişi için yeni LANU kodunu kaydedin." }
        val peer = savedPeer(code) ?: resolveContact(code)
        val id = UserCodes.conversationId(ensureIdentity().id, peer.getString("id"))
        database.withTransaction {
            // Only an explicit user action can reopen a previously deleted thread.
            settingsDao.deleteSetting("lanu_hidden_conv_$id")
            if (conversationDao.getConversationById(id) == null) {
                conversationDao.insertConversation(ConversationEntity(id, peer.getString("code"), partnerName))
            }
        }
        id
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> = messageDao.getMessagesForConversation(conversationId).map { list ->
        val own = ensureIdentity()
        val peer = peerForConversation(conversationId)
        list.map { message ->
            val plain = try {
                if (message.content.startsWith("lanu2:") && peer != null) PeerMessageCrypto.decrypt(own, peer.getString("id"), peer.getString("publicKey"), message.id, message.senderPhone, message.content)
                else if (!message.content.startsWith("lanu2:")) CryptoUtils.decrypt(message.content, conversationId)
                else "Mesaj anahtarı bulunamadı"
            } catch (_: Exception) { "Mesaj doğrulanamadı veya çözülemedi" }
            message.copy(content = plain)
        }
    }

    suspend fun sendMessage(conversationId: String, text: String, mediaUrl: String? = null, onTypingStateChange: (suspend (Boolean) -> Unit)? = null): String {
        require(mediaUrl == null) { "Medya aktarımı henüz etkin değil; metin mesajı gönderebilirsiniz." }
        require(text.isNotBlank() && text.length <= 8000) { "Mesaj 1–8000 karakter olmalıdır." }
        val own = ensureIdentity()
        val peer = peerForConversation(conversationId) ?: throw IllegalStateException("Kişinin kodunu sunucudan doğrulayın.")
        val id = UUID.randomUUID().toString()
        val encrypted = PeerMessageCrypto.encrypt(own, peer.getString("id"), peer.getString("publicKey"), id, text)
        messageDao.insertMessage(MessageEntity(id, conversationId, own.id, userProfileDao.getUserProfile()?.displayName ?: "Ben", encrypted, status = "PENDING"))
        conversationDao.getConversationById(conversationId)?.let { conversationDao.insertConversation(it.copy(lastMessageText = "Şifreli mesaj", lastMessageTime = System.currentTimeMillis())) }
        return id
    }

    suspend fun synchronize(activeConversationId: String? = null) = syncMutex.withLock {
        val own = registerRemote()
        for (contact in contacts.first()) {
            if (savedPeer(contact.phoneNumber) == null) {
                try { resolveContact(contact.phoneNumber) } catch (e: RelayException) { if (e.status != 404) throw e }
            }
        }
        for (message in messageDao.getPendingMessages()) {
            val peer = peerForConversation(message.conversationId) ?: continue
            try {
                val response = relay.request(own, "send", JSONObject().put("messageId", message.id).put("peerId", peer.getString("id")).put("content", message.content))
                messageDao.updateMessageStatus(message.id, response.optString("status", "SENT"))
            } catch (e: RelayException) {
                if (e.status == 409) messageDao.updateMessageStatus(message.id, "WAITING") else throw e
            }
        }
        val response = relay.request(own, "sync")
        val peers = response.getJSONArray("contacts")
        for (i in 0 until peers.length()) savePeer(peers.getJSONObject(i), peers.getJSONObject(i).optString("codeAtAdd"))
        val events = response.getJSONArray("events")
        val ack = JSONArray()
        val read = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val message = event.getJSONObject("message")
            val id = message.getString("id")
            val senderId = message.getString("senderId")
            val peerId = if (senderId == own.id) message.getString("recipientId") else senderId
            val convId = UserCodes.conversationId(own.id, peerId)
            // The original thread may have been deleted before this event was downloaded.
            // A messageId tombstone only protects messages that were already on disk.
            val threadDeleted = settingsDao.getSettingValue("lanu_hidden_conv_$convId") == "true"
            // Message, unread count and replay revision are one durable unit.
            // A crash before commit must not leave a message without its unread badge.
            database.withTransaction {
                val revision = message.getLong("revision")
                val previous = settingsDao.getSettingValue("lanu_message_revision_$id")?.toLongOrNull() ?: 0L
                val hidden = settingsDao.getSettingValue("lanu_hidden_$id") == "true"
                if (revision > previous) {
                    if (message.optBoolean("deleted") || hidden || threadDeleted) messageDao.deleteMessage(id)
                    else {
                        val peer = settingsDao.getSettingValue("lanu_peer_id_$peerId")?.let(::JSONObject) ?: throw IllegalStateException("Gönderenin anahtarı bulunamadı")
                        // Never persist or acknowledge an unreadable/tampered encrypted message.
                        // The relay cannot authenticate E2EE ciphertext; only the recipient can.
                        try {
                            PeerMessageCrypto.decrypt(own, peerId, peer.getString("publicKey"), id, senderId, message.getString("content"))
                        } catch (e: Exception) {
                            throw SecurityException("Şifreli mesaj doğrulanamadı. Teslim/okundu onayı gönderilmedi.", e)
                        }
                        settingsDao.insertSetting(SettingsEntity("lanu_peer_conv_$convId", peer.toString()))
                        if (conversationDao.getConversationById(convId) == null) {
                            val contact = contactDao.getContactByPhone(peer.getString("code"))
                            conversationDao.insertConversation(ConversationEntity(convId, peer.getString("code"), contact?.name ?: peer.getString("code")))
                        }
                        // A replayed or revised event must not inflate the unread badge.
                        val firstLocalReceipt = messageDao.getMessage(id) == null
                        messageDao.insertMessage(MessageEntity(id, convId, senderId, if (senderId == own.id) "Ben" else peer.getString("code"), message.getString("content"),
                            timestamp = message.getLong("createdAt"), status = message.getString("status"), reaction = message.optString("reaction").ifBlank { null }))
                        conversationDao.getConversationById(convId)?.let { conversationDao.insertConversation(it.copy(lastMessageText = "Şifreli mesaj", lastMessageTime = maxOf(it.lastMessageTime, message.getLong("createdAt")))) }
                        if (senderId != own.id && activeConversationId != convId && firstLocalReceipt) {
                            conversationDao.incrementUnreadCount(convId)
                        }
                    }
                    settingsDao.insertSetting(SettingsEntity("lanu_message_revision_$id", revision.toString()))
                }
            }
            ack.put(event.getString("id"))
            if (senderId != own.id && activeConversationId == convId &&
                !message.optBoolean("deleted") && !threadDeleted) read.put(id)
        }
        if (ack.length() > 0 || read.length() > 0) relay.request(own, "ack", JSONObject().put("eventIds", ack).put("readIds", read))
        // Also mark previously delivered messages when their conversation is opened later.
        if (activeConversationId != null) {
            val ids = JSONArray()
            messageDao.getMessagesForConversation(activeConversationId).first().filter { it.senderPhone != own.id && it.status != "READ" }.take(200).forEach { ids.put(it.id) }
            if (ids.length() > 0) relay.request(own, "ack", JSONObject().put("eventIds", JSONArray()).put("readIds", ids))
        }
    }

    suspend fun addReaction(messageId: String, emoji: String) {
        relay.request(registerRemote(), "react", JSONObject().put("messageId", messageId).put("reaction", emoji))
    }
    suspend fun hideMessageLocally(messageId: String) = syncMutex.withLock {
        val message = messageDao.getMessage(messageId) ?: return@withLock
        // Timer hides only messages accepted by the relay. Pending sends survive.
        if (message.status in setOf("PENDING", "WAITING", "SENDING")) return@withLock
        database.withTransaction {
            settingsDao.insertSetting(SettingsEntity("lanu_hidden_$messageId", "true"))
            messageDao.deleteMessage(messageId)
        }
    }

    suspend fun deleteMessage(messageId: String) = syncMutex.withLock {
        val own = ensureIdentity()
        val message = messageDao.getMessage(messageId) ?: return@withLock
        if (message.senderPhone == own.id && message.content.startsWith("lanu2:") &&
            message.status !in setOf("PENDING", "WAITING", "SENDING")
        ) {
            // The server request must succeed before the local copy disappears.
            relay.request(registerRemote(), "delete", JSONObject().put("messageId", messageId))
        }
        database.withTransaction {
            // Always preserve the local tombstone. Server acknowledgements and
            // delayed sync/revision events must never resurrect manually erased content.
            settingsDao.insertSetting(SettingsEntity("lanu_hidden_$messageId", "true"))
            messageDao.deleteMessage(messageId)
        }
    }
    suspend fun editMessage(messageId: String, conversationId: String, newText: String) {
        require(newText.isNotBlank() && newText.length <= 8000)
        val own = ensureIdentity()
        val peer = peerForConversation(conversationId) ?: error("Kişi bulunamadı")
        val encrypted = PeerMessageCrypto.encrypt(own, peer.getString("id"), peer.getString("publicKey"), messageId, newText)
        relay.request(registerRemote(), "edit", JSONObject().put("messageId", messageId).put("content", encrypted))
    }
    suspend fun clearConversationMessages(conversationId: String) {
        database.withTransaction {
            val messages = messageDao.getMessagesForConversation(conversationId).first()
            for (message in messages) {
                // Keep tombstones: a delayed relay event must never resurrect an erased message.
                settingsDao.insertSetting(SettingsEntity("lanu_hidden_${message.id}", "true"))
            }
            messageDao.deleteAllMessagesForConversation(conversationId)
            conversationDao.getConversationById(conversationId)?.let {
                conversationDao.insertConversation(it.copy(lastMessageText = "", unreadCount = 0))
            }
        }
    }

    suspend fun deleteConversation(id: String) = syncMutex.withLock {
        database.withTransaction {
            // This tombstone must precede deleting the metadata, so an unseen old
            // relay event cannot silently recreate the user-deleted thread.
            settingsDao.insertSetting(SettingsEntity("lanu_hidden_conv_$id", "true"))
            for (message in messageDao.getMessagesForConversation(id).first()) {
                settingsDao.insertSetting(SettingsEntity("lanu_hidden_${message.id}", "true"))
            }
            messageDao.deleteAllMessagesForConversation(id)
            conversationDao.deleteConversation(id)
        }
    }
    suspend fun addCallLog(partnerName: String, partnerPhone: String, isVideo: Boolean, durationSeconds: Int, isOutgoing: Boolean) {
        callLogDao.insertCallLog(CallLogEntity(partnerName = partnerName, partnerPhone = partnerPhone, isVideo = isVideo, durationSeconds = durationSeconds, isOutgoing = isOutgoing))
    }
    suspend fun deleteCallLog(id: Long) { callLogDao.deleteCallLog(id) }
    suspend fun clearAllCallLogs() { callLogDao.clearAllCallLogs() }
}
