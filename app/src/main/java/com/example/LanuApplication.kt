package com.example

import android.app.Application
import com.example.core.di.AppContainer
import com.example.core.diagnostics.LocalCrashHandler
import com.example.core.security.LegacyInsecurePreferencesCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LanuApplication : Application() {
    lateinit var container: AppContainer
    private val credentialMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Install before other startup work. Only sanitized app source frames are
        // stored locally; original Android crash reporting remains in the chain.
        LocalCrashHandler.install(this)
        LegacyInsecurePreferencesCleanup.clean(this)
        container = AppContainer(this)
        // Older installs may contain a Supabase key in Room. Migrate on launch so it
        // does not remain there until a legacy UI happens to request credentials.
        // Keystore errors must never erase originals or crash application startup.
        credentialMigrationScope.launch {
            try {
                container.securityRepository.getSupabaseCredentials()
            } catch (_: Exception) {
                // Credentials remain unavailable; the recoverable legacy rows are kept.
                // Never log secrets or attempt a plaintext storage fallback.
            }
        }
    }
}
