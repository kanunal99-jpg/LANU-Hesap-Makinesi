package com.example.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calculation_history")
data class CalculationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val phoneNumber: String, // Normalized E.164
    val displayName: String,
    val avatarUrl: String? = null,
    val registeredAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val phoneNumber: String, // Normalized E.164 (primary lookup identity)
    val name: String,
    val isBlocked: Boolean = false,
    val avatarUrl: String? = null,
    val statusText: String? = "Hi, I am using LANU Calculator"
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String, // UUID string
    val partnerPhone: String,
    val partnerName: String,
    val lastMessageText: String = "",
    val lastMessageTime: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    val unreadCount: Int = 0
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String, // UUID string
    val conversationId: String,
    val senderPhone: String,
    val senderName: String,
    val content: String, // AES/Signal encrypted ciphertext (if E2EE) or plain if server fallback
    val mediaUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // PENDING, SENDING, SENT, DELIVERED, READ
    val reaction: String? = null
)

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val partnerName: String,
    val partnerPhone: String,
    val isVideo: Boolean,
    val durationSeconds: Int,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

