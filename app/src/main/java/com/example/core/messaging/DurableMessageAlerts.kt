package com.example.core.messaging

import androidx.room.withTransaction
import com.example.core.database.AppDatabase
import com.example.core.database.SettingsEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Room journal contains only local identifiers, never content or sender keys. */
internal class DurableMessageAlerts(private val db: AppDatabase) {
    companion object {
        const val PREFIX = "lanu_pending_message_alert_"
        const val BATCH = "lanu_message_alert_batch_v1"
        const val SHOWN = "lanu_message_alert_shown_v1"
        private val publication = Mutex()
    }
    internal data class Batch(val token: String, val ids: List<String>) {
        fun encode(): String = JSONObject().put("token", token).put("ids", JSONArray(ids)).toString()
    }
    /** Caller MUST already own the message's Room transaction. */
    suspend fun enqueueInTransaction(id: String, conversationId: String) {
        db.settingsDao().insertSetting(SettingsEntity(PREFIX + id, conversationId))
    }
    private fun decode(raw: String?): Batch? = try {
        if (raw == null || raw.length > 32768) null else {
            val value = JSONObject(raw)
            val token = value.getString("token")
            val ids = value.getJSONArray("ids")
            if (UUID.fromString(token).toString() != token || ids.length() !in 1..200) null
            else Batch(token, List(ids.length()) { ids.getString(it) }.distinct())
        }
    } catch (_: org.json.JSONException) { null }
      catch (_: IllegalArgumentException) { null }

    private suspend fun eligible(ids: List<String>): List<String> =
        if (ids.isEmpty()) emptyList() else db.settingsDao()
            .getEligibleMessageAlertKeys(ids.map { PREFIX + it }).map { it.removePrefix(PREFIX) }

    /** Local maintenance is independent of notification consent and performs no network work. */
    suspend fun prune() = db.withTransaction {
        db.settingsDao().pruneInvalidMessageAlerts()
        val raw = db.settingsDao().getSettingValue(BATCH)
        val batch = decode(raw)
        if (raw != null && batch == null) db.settingsDao().deleteSetting(BATCH)
        batch?.let {
            val live = eligible(batch.ids)
            if (live.isEmpty()) db.settingsDao().deleteSetting(BATCH)
            else if (live != batch.ids) db.settingsDao().insertSetting(SettingsEntity(BATCH, batch.copy(ids = live).encode()))
        }
    }

    /** Persist the stable notification token BEFORE the Android side effect. */
    internal suspend fun prepare(): Batch? = db.withTransaction {
        // Clean the ENTIRE queue first, including rows beyond the 200-item batch.
        db.settingsDao().pruneInvalidMessageAlerts()
        decode(db.settingsDao().getSettingValue(BATCH))?.let { existing ->
            val live = eligible(existing.ids)
            if (live.isNotEmpty()) return@withTransaction existing.copy(ids = live)
            db.settingsDao().deleteSetting(BATCH)
        }
        val ids = db.settingsDao().getPendingMessageAlerts().map { it.key.removePrefix(PREFIX) }
        if (ids.isEmpty()) {
            db.settingsDao().deleteSetting(BATCH)
            null
        } else Batch(UUID.randomUUID().toString(), ids).also {
            db.settingsDao().insertSetting(SettingsEntity(BATCH, it.encode()))
        }
    }

    suspend fun publish(post: (String) -> Boolean): Boolean = publication.withLock {
        currentCoroutineContext().ensureActive()
        val batch = prepare() ?: return@withLock false
        db.withTransaction {
            currentCoroutineContext().ensureActive()
            if (decode(db.settingsDao().getSettingValue(BATCH))?.token != batch.token) return@withTransaction false
            // markConversationOpened/deletion and this last check are serialized
            // by Room. No suspension to HTTP occurs while this write lock is held.
            val live = eligible(batch.ids)
            val invalid = (batch.ids - live.toSet()).map { PREFIX + it }
            if (invalid.isNotEmpty()) db.settingsDao().deleteMessageAlertKeys(invalid)
            if (live.isEmpty()) {
                db.settingsDao().deleteSetting(BATCH)
                return@withTransaction false
            }
            if (!post(batch.token)) return@withTransaction false
            // A failed commit or process death leaves the prepared token durable.
            // Android's active token permits read-back without reposting.
            db.settingsDao().deleteMessageAlertKeys(live.map { PREFIX + it })
            db.settingsDao().insertSetting(SettingsEntity(SHOWN, batch.token))
            db.settingsDao().deleteSetting(BATCH)
            true
        }
    }
}
