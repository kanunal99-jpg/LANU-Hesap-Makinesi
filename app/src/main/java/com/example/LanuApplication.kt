package com.example

import android.app.Application
import com.example.core.di.AppContainer
import com.example.core.diagnostics.LocalCrashHandler
import com.example.core.media.PrivateMediaCacheRecovery
import com.example.core.security.LegacyInsecurePreferencesCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LanuApplication : Application() {
    @Inject lateinit var container: AppContainer
    private val credentialMigrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Install before other startup work. Only sanitized app source frames are
        // stored locally; original Android crash reporting remains in the chain.
        LocalCrashHandler.install(this)
        // After a crash, no later session must inherit verified plaintext.
        // Only LANU temporary media prefixes are touched; Room, PIN and
        // existing 18 KB attachments remain unchanged.
        runCatching { PrivateMediaCacheRecovery.purge(cacheDir) }
        LegacyInsecurePreferencesCleanup.clean(this)
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
