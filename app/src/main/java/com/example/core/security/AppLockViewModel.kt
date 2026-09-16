package com.example.core.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.features.security.SecurityViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the authentication and lock state of the application.
 */
enum class AppLockState {
    LOCKED,
    UNLOCKED
}

/**
 * AppLockViewModel coordinates the application lock state ('LOCKED' / 'UNLOCKED').
 * It interoperates directly with SecurityViewModel to ensure consistent security across the app.
 */
class AppLockViewModel(
    private val securityViewModel: SecurityViewModel? = null
) : ViewModel() {

    private val _lockState = MutableStateFlow(AppLockState.LOCKED)
    val lockState: StateFlow<AppLockState> = _lockState.asStateFlow()

    fun lockApp() {
        _lockState.value = AppLockState.LOCKED
        securityViewModel?.lock()
    }

    fun unlockApp() {
        _lockState.value = AppLockState.UNLOCKED
        securityViewModel?.unlock()
    }

    fun setLockState(state: AppLockState) {
        _lockState.value = state
        if (state == AppLockState.LOCKED) {
            securityViewModel?.lock()
        } else {
            securityViewModel?.unlock()
        }
    }

    class Factory(
        private val securityViewModel: SecurityViewModel
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppLockViewModel(securityViewModel) as T
        }
    }
}
