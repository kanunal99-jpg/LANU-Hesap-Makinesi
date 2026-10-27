package com.example

import android.app.Application
import com.example.core.di.AppContainer
import com.example.core.diagnostics.LocalCrashHandler
import com.example.core.calls.CallWakeTokenRefreshWorker
import com.example.core.messaging.MessageWakeTokenRefreshWorker
import com.example.core.media.PrivateMediaCacheRecovery
import com.example.core.security.LegacyInsecurePreferencesCleanup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.work.Configuration
import com.example.core.delivery.DeliveryCoordinator

@HiltAndroidApp
class LanuApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
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
        credentialMigrationScope.launch {
            // Only a prior explicit call-alert opt-in can schedule token
            // re-registration. No token, identity or secret enters WorkManager.
            // KEEP prevents ordinary app relaunch from cancelling a retry.
            runCatching { CallWakeTokenRefreshWorker.enqueue(this@LanuApplication, replace = false) }
            runCatching { MessageWakeTokenRefreshWorker.enqueue(this@LanuApplication, replace = false) }
        }
        credentialMigrationScope.launch {
            // No identity is generated and no network work is requested unless
            // the owner explicitly enabled background delivery in the private UI.
            try { DeliveryCoordinator(this@LanuApplication).reconcile() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Foreground messaging remains available. */ }
        }
    }
}

