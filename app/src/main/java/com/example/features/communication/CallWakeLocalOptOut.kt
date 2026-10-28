package com.example.features.communication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The user's explicit call-alert opt-out must be durable before any Android
 * notification Binder call, WorkManager scheduling or Firebase work.
 *
 * Keep all of those potentially blocking OEM operations off Compose's main
 * thread. Once the user initiated an opt-out, do not abandon a committed local
 * revoke merely because navigation cancels the screen coroutine. The signed
 * remote revoke remains in the existing worker/network policy, not here.
 */
internal object CallWakeLocalOptOut {
    suspend fun persistAndPrepareRevoke(
        persistDisabledWithPendingRevoke: () -> Boolean,
        cancelPostedCall: () -> Unit,
        scheduleRemoteRevoke: () -> Unit,
        resetFirebaseAutoInit: () -> Unit
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val persisted = try {
            persistDisabledWithPendingRevoke()
        } catch (_: Exception) {
            false
        }
        if (!persisted) return@withContext false

        // Never let one broken OEM Binder/WorkManager/Firebase component
        // prevent the other independent cleanup/recovery attempts.
        try { cancelPostedCall() } catch (_: Exception) {}
        try { scheduleRemoteRevoke() } catch (_: Exception) {}
        try { resetFirebaseAutoInit() } catch (_: Exception) {}
        true
    }
}
