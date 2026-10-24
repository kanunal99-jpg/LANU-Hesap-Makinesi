package com.example.core.di

import android.content.Context
import com.example.BuildConfig
import com.example.core.database.AppDatabase
import com.example.core.messaging.EncryptedMessagingIdentityStore
import com.example.core.messaging.HttpMessagingRelay
import com.example.core.messaging.MessagingIdentityStore
import com.example.core.messaging.MessagingRelay
import com.example.core.repository.CalculatorRepository
import com.example.core.repository.CommunicationRepository
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApplicationModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.getDatabase(context)
    @Provides @Singleton fun identity(@ApplicationContext context: Context): MessagingIdentityStore = EncryptedMessagingIdentityStore(context)
    @Provides @Singleton fun relay(): MessagingRelay = HttpMessagingRelay(BuildConfig.LANU_RELAY_URL)
    @Provides @Singleton fun calculator(db: AppDatabase) = CalculatorRepository(db.calculationHistoryDao(), db.settingsDao())
    @Provides @Singleton fun security(@ApplicationContext context: Context, db: AppDatabase) = SecurityRepository(context, db.settingsDao())
    @Provides @Singleton fun events(@ApplicationContext context: Context) = SecurityEventTracker(context)
    @Provides @Singleton fun communication(db: AppDatabase, identity: MessagingIdentityStore, relay: MessagingRelay) = CommunicationRepository(
        db.contactDao(), db.conversationDao(), db.messageDao(), db.settingsDao(), db.userProfileDao(), db.callLogDao(), identity, relay, db
    )
}
