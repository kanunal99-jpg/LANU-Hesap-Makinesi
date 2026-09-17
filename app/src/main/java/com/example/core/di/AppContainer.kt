package com.example.core.di

import android.content.Context
import com.example.core.database.AppDatabase
import com.example.core.repository.CalculatorRepository
import com.example.core.repository.CommunicationRepository
import com.example.core.repository.SecurityRepository
import com.example.core.repository.SupabaseCommunicationClient
import com.example.core.security.SecurityEventTracker

class AppContainer(context: Context) {
    private val database: AppDatabase by lazy { AppDatabase.getDatabase(context) }

    val supabaseBaseUrl: String = "https://jolfbmwxmsamzqtxassg.supabase.co"

    val supabasePublishableKey: String = intArrayOf(
        115,98,95,112,117,98,108,105,115,104,97,98,108,101,95,56,
        107,100,83,101,115,98,103,73,105,73,51,86,56,84,106,109,
        99,77,99,90,65,95,52,70,80,56,104,84,107,77
    ).map { it.toChar() }.joinToString("")

    private val supabaseCommunicationClient: SupabaseCommunicationClient by lazy {
        SupabaseCommunicationClient(
            context = context,
            baseUrl = supabaseBaseUrl,
            publishableKey = supabasePublishableKey
        )
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
            database.callLogDao(),
            supabaseCommunicationClient
        )
    }
}
