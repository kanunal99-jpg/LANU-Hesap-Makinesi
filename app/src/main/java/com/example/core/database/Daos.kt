package com.example.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CalculationHistoryDao {
    @Query("SELECT * FROM calculation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<CalculationHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: CalculationHistoryEntity)

    @Query("DELETE FROM calculation_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM calculation_history")
    suspend fun clearAllHistory()
    
    @Query("DELETE FROM calculation_history WHERE timestamp < :timestamp")
    suspend fun deleteHistoryOlderThan(timestamp: Long)

    @Query("SELECT id FROM calculation_history ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET 50")
    suspend fun getHistoryIdsToPrune(): List<Long>

    @Query("DELETE FROM calculation_history WHERE id IN (:ids)")
    suspend fun deleteHistoryByIds(ids: List<Long>)

    @Transaction
    suspend fun insertAndPruneHistory(history: CalculationHistoryEntity) {
        // Insert and retention must commit together. A failure to prune cannot
        // leave unbounded records, nor can it discard the previous 50 entries.
        insertHistory(history)
        getHistoryIdsToPrune().takeIf { it.isNotEmpty() }?.let { deleteHistoryByIds(it) }
    }
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles LIMIT 1")
    suspend fun getUserProfile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfileEntity)

    @Query("DELETE FROM user_profiles")
    suspend fun deleteProfile()

    @Transaction
    suspend fun replaceProfile(profile: UserProfileEntity) {
        deleteProfile()
        insertProfile(profile)
    }
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getSettingValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SettingsEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)

    @Transaction
    suspend fun persistPinGuard(failures: Int, until: Long, strike: Int) {
        // The deadline and its escalation tier must commit or roll back together.
        insertSetting(SettingsEntity("pin_attempt_state", "$failures:$until"))
        insertSetting(SettingsEntity("pin_lockout_strikes", strike.toString()))
    }
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getContactByPhone(phone: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE phoneNumber = :phone")
    suspend fun deleteContact(phone: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :id")
    suspend fun incrementUnreadCount(id: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnreadCount(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countMessages(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE content LIKE 'lanu2:%'")
    suspend fun countModernEncryptedMessages(): Int

    // The relay submits queued messages in bounded batches. Keep durable rows
    // for later sync passes; deterministic ID ordering handles equal timestamps.
    @Query("SELECT * FROM messages WHERE status IN ('PENDING', 'WAITING') ORDER BY timestamp ASC, id ASC LIMIT 200")
    suspend fun getPendingMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessage(id: String): MessageEntity?

    @Query("UPDATE messages SET senderPhone = :newId WHERE senderPhone = :oldId")
    suspend fun migrateSender(oldId: String, newId: String)

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesForConversation(convId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :messageId")
    suspend fun updateMessageReaction(messageId: String, reaction: String?)

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteAllMessagesForConversation(convId: String)
}

@Dao
interface CallLogDao {
    @Query("SELECT COUNT(*) FROM call_logs")
    suspend fun countCallLogs(): Int

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllCallLogs(): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(callLog: CallLogEntity)

    @Query("DELETE FROM call_logs WHERE id = :id")
    suspend fun deleteCallLog(id: Long)

    @Query("DELETE FROM call_logs")
    suspend fun clearAllCallLogs()
}

