package com.example.core.di

import com.example.core.repository.CalculatorRepository
import com.example.core.repository.CommunicationRepository
import com.example.core.repository.SecurityRepository
import com.example.core.security.SecurityEventTracker
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility facade for existing ViewModel factories; Hilt owns each singleton. */
@Singleton
class AppContainer @Inject constructor(
    val calculatorRepository: CalculatorRepository,
    val securityRepository: SecurityRepository,
    val securityEventTracker: SecurityEventTracker,
    val communicationRepository: CommunicationRepository
)
