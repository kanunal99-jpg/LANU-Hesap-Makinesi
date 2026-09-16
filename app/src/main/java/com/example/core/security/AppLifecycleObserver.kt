package com.example.core.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * AppLifecycleObserver listens to application and activity lifecycle events.
 * When the app is put into the background (ON_PAUSE or ON_STOP), it invokes onAppBackgrounded(),
 * triggering AppLockViewModel to transition to 'LOCKED' state.
 * When the app returns to the foreground (ON_START / ON_RESUME), onAppForegrounded() is triggered.
 */
class AppLifecycleObserver(
    private val onAppBackgrounded: () -> Unit,
    private val onAppForegrounded: () -> Unit = {}
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        onAppBackgrounded()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        onAppForegrounded()
    }
}
