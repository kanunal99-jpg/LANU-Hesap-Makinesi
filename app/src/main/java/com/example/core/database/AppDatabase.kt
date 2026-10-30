package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CalculationHistoryEntity::class,
        UserProfileEntity::class,
        SettingsEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        CallLogEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun calculationHistoryDao(): CalculationHistoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun settingsDao(): SettingsDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun callLogDao(): CallLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lanu_calculator_db"
                )
                // The AndroidX default corruption callback can DELETE a database.
                // Never discard PIN, encrypted messages or identity-linked settings:
                // preserve all database/WAL files for an explicitly verified recovery.
                .openHelperFactory(PreserveCorruptRoomFactory())
                // Fail closed on unknown schema versions: silently deleting chat history
                // and identity-related settings is unacceptable for a secure messenger.
                // Add explicit Room migrations for future schema upgrades.
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
