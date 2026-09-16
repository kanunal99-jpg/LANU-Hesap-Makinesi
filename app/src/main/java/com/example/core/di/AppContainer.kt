package com.example.core.di

import android.content.Context
import com.example.core.database.AppDatabase
import com.example.core.repository.CalculatorRepository
import com.example.core.repository.CommunicationRepository
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker

class AppContainer(context: Context) {
    private val database: AppDatabase by lazy {
        AppDatabase.getDatabase(context)
    }

    val calculatorRepository: CalculatorRepository by lazy {
        CalculatorRepository(database.calculationHistoryDao(), database.settingsDao())
    }

    val securityRepository: SecurityRepository by lazy {
        SecurityRepository(context, database.settingsDao())
    }

    val securityEventTracker: SecurityEventTracker by lazy {
        SecurityEventTracker(context)
    }

    val communicationRepository: CommunicationRepository by lazy {
        CommunicationRepository(
            database.contactDao(),
            database.conversationDao(),
            database.messageDao(),
            database.settingsDao(),
            database.userProfileDao(),
            database.callLogDao()
        )
    }
}
