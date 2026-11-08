package com.example.core.messaging

import androidx.room.withTransaction
import com.example.core.database.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.WeakHashMap

/**
 * Durable acceptance point: journal BEFORE Room commit; state and message in
 * ONE transaction; finalize afterward. Recovery replays the exact saved bytes,
 * never asks the ratchet to encrypt again with an old state.
 */
internal class RatchetTransactionStore(
    private val database: AppDatabase,
    private val journal: RatchetJournalStorage,
    private val box: RatchetBox,
    private val checkpoint: (Checkpoint) -> Unit = {}
) {
    enum class Checkpoint { PREPARED, ROOM_COMMITTED, FINALIZED }
    class Change(val state: String, val message: MessageEntity? = null,
        val previousMessageDigest: String? = null, val settings: Map<String,String> = emptyMap(),
        val preview: Preview? = null) {
        override fun toString() = "RatchetChange(redacted)"
    }
    class Preview(val text: String, val at: Long, val unreadIncrement: Boolean = false) {
        override fun toString() = "RatchetPreview(redacted)"
    }
    private val mutex = synchronized(locks) { locks.getOrPut(database) { Mutex() } }
    companion object {
        private val locks = WeakHashMap<AppDatabase, Mutex>()
        const val STATE_KEY = "lanu_dr_private_state_v1"
        private val HEX = Regex("[a-f0-9]{64}")
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun nullable(value: JSONObject, key: String): String? = if (value.isNull(key)) null else value.getString(key)
    private fun currentDigest(value: String?) = value?.let(::digest)
    private fun anchor(current: String?): JSONObject = JSONObject().put("version",1).put("current",current ?: JSONObject.NULL)
    private fun readAnchor(state: String?): JSONObject {
        val saved = journal.read()
        if (saved == null) {
            check(state == null) { "Güvenli oturumun bağımsız kaydı eksik; eski duruma dönülmedi." }
            return anchor(null)
        }
        val raw = box.open("journal",saved)
        check(raw.length <= 6 * 1024 * 1024)
        val result = JSONObject(raw)
        check(result.getInt("version") == 1 && result.keys().asSequence().all { it in setOf("version","current","pending") })
        nullable(result,"current")?.let { check(HEX.matches(it)) }
        return result
    }
    private fun save(anchor: JSONObject) { journal.write(box.seal("journal",anchor.toString())) }

    suspend fun recover() {
        requireOwnTransaction()
        mutex.withLock {
        try { requireOwnTransaction(); recoverLocked() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw IllegalStateException("Güvenli oturum kurtarılamadı; kayıtlar korundu.") }
    }
    }
    private fun requireOwnTransaction() {
        check(!database.inTransaction()) { "Güvenli oturum bağımsız işlem gerektirir." }
    }
    private suspend fun recoverLocked() {
        database.withTransaction {
            val state = database.settingsDao().getSettingValue(STATE_KEY)
            val saved = readAnchor(state)
            val pending = saved.optJSONObject("pending")
            if (pending == null) {
                check(currentDigest(state) == nullable(saved,"current")) { "Güvenli oturum geri sarılmış; kayıtlar korundu." }
                return@withTransaction
            }
            val next = pending.getString("state")
            check(next.length in 1..4 * 1024 * 1024)
            box.open("state",next) // Authenticate proposed state before any replay.
            val now = currentDigest(state)
            check(now == nullable(saved,"current") || now == digest(next)) { "Güvenli oturum sürümü uyuşmuyor." }
            if (now == nullable(saved,"current")) apply(pending)
        }
        // Re-read after COMMIT; never finalize a rolled-back transaction.
        val current = database.settingsDao().getSettingValue(STATE_KEY)
        val saved = readAnchor(current)
        val pending = saved.optJSONObject("pending") ?: return
        check(currentDigest(current) == digest(pending.getString("state")))
        save(anchor(currentDigest(current)))
    }

    suspend fun <T> transact(transition: suspend (String?) -> Pair<Change,T>): T {
        requireOwnTransaction()
        return mutex.withLock {
        try {
            requireOwnTransaction()
            recoverLocked()
            val result = database.withTransaction {
                val before = database.settingsDao().getSettingValue(STATE_KEY)
                val saved = readAnchor(before)
                check(!saved.has("pending") && currentDigest(before) == nullable(saved,"current"))
                val (change, output) = transition(before?.let { box.open("state",it) })
                require(change.state.length in 1..2 * 1024 * 1024)
                val next = box.seal("state",change.state)
                val prepared = mutation(next, change)
                save(anchor(currentDigest(before)).put("pending",prepared))
                checkpoint(Checkpoint.PREPARED)
                apply(prepared)
                output
            }
            checkpoint(Checkpoint.ROOM_COMMITTED)
            recoverLocked()
            checkpoint(Checkpoint.FINALIZED)
            result
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw IllegalStateException("Güvenli oturum işlemi tamamlanamadı; mevcut kayıtlar korunuyor.") }
    }

    }

    private fun mutation(state: String, change: Change): JSONObject {
        val settings = JSONObject()
        check(change.settings.size <= 32)
        change.settings.forEach { (key,value) ->
            require(allowedSetting(key) && value.length <= 100000)
            settings.put(key,value)
        }
        val result = JSONObject().put("state",state).put("settings",settings)
        change.previousMessageDigest?.let { require(HEX.matches(it)); result.put("previous",it) }
        change.preview?.let { preview ->
            require(change.message != null && preview.text.length <= 512 && preview.at >= 0)
            result.put("preview",JSONObject().put("text",preview.text).put("at",preview.at)
                .put("unread",preview.unreadIncrement))
        }
        change.message?.let { message ->
            require(message.id.length in 1..128 && message.conversationId.length in 1..128 &&
                HEX.matches(message.senderPhone) && message.senderName.length <= 256 &&
                message.content.length in 1..100000 && message.mediaUrl == null &&
                message.status in setOf("PENDING","SENDING","SENT","DELIVERED","READ") &&
                message.timestamp >= 0 && (message.reaction?.length ?: 0) <= 128)
            require(message.content.startsWith("lanu3-local:") || message.content.startsWith("lanu2:"))
            result.put("message",JSONObject().put("id",message.id).put("conversation",message.conversationId)
                .put("sender",message.senderPhone).put("name",message.senderName).put("content",message.content)
                .put("status",message.status).put("at",message.timestamp).put("reaction",message.reaction ?: JSONObject.NULL))
        }
        return result
    }
    private fun allowedSetting(key: String): Boolean =
        key == "lanu_dr_public_bundle_v1" || key.matches(Regex("lanu_dr_pin_[a-f0-9]{64}")) ||
            key.matches(Regex("lanu_dr_wire_[a-f0-9-]{36}")) ||
            key.matches(Regex("lanu_message_revision_[a-f0-9-]{36}"))

    private suspend fun apply(pending: JSONObject) {
        val state = pending.getString("state")
        val settings = pending.getJSONObject("settings")
        check(settings.length() <= 32)
        settings.keys().asSequence().forEach { key ->
            check(allowedSetting(key) && settings.getString(key).length <= 100000)
        }
        pending.optJSONObject("message")?.let { row ->
            val message = MessageEntity(row.getString("id"),row.getString("conversation"),row.getString("sender"),
                row.getString("name"),row.getString("content"),timestamp=row.getLong("at"),
                status=row.getString("status"),reaction=nullable(row,"reaction"))
            // Revalidate a decrypted/recovered journal using the exact creation rules.
            mutation(state,Change("verified",message,nullable(pending,"previous")))
            check(database.conversationDao().getConversationById(message.conversationId) != null)
            val old = database.messageDao().getMessage(message.id)
            if (old != null) {
                check(old.conversationId == message.conversationId && old.senderPhone == message.senderPhone)
                check(old.content == message.content || nullable(pending,"previous") == digest(old.content))
            } else check(!pending.has("previous"))
            if (old?.content != message.content) database.messageDao().insertMessage(message)
            pending.optJSONObject("preview")?.let { preview ->
                val text = preview.getString("text"); val at = preview.getLong("at")
                check(text.length <= 512 && at >= 0)
                val conversation = checkNotNull(database.conversationDao().getConversationById(message.conversationId))
                val newer = at >= conversation.lastMessageTime
                val unread = if (old == null && preview.getBoolean("unread"))
                    (conversation.unreadCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    else conversation.unreadCount
                database.conversationDao().insertConversation(conversation.copy(
                    lastMessageText=if(newer) text else conversation.lastMessageText,
                    lastMessageTime=if(newer) at else conversation.lastMessageTime, unreadCount=unread))
            }
        }
        for (key in settings.keys()) database.settingsDao().insertSetting(SettingsEntity(key,settings.getString(key)))
        database.settingsDao().insertSetting(SettingsEntity(STATE_KEY,state))
    }
}
