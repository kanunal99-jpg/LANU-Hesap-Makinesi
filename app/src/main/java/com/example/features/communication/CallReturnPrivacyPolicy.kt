package com.example.features.communication

/**
 * Finishing a public call must never implicitly authenticate private-area
 * navigation. The calculator remains accessible; private screens require a
 * new, explicit entry gesture and the owner's optional PIN preference.
 *
 * This decision is based on the authenticated in-memory call state, never
 * an incoming Intent or untrusted push notification.
 */
internal object CallReturnPrivacyPolicy {
    fun requiresLockedCalculator(phase: NativeCallPhase, callId: String): Boolean =
        callId.isNotBlank() &&
            (phase == NativeCallPhase.ENDED || phase == NativeCallPhase.FAILED)
}
