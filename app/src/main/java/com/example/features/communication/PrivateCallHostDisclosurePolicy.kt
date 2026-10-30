package com.example.features.communication

/**
 * Private call diagnostics and outgoing confirmation belong only to the
 * authenticated private area. A consented incoming call may still be answered
 * on the calculator surface, but must not reveal private peer/contact details.
 */
internal object PrivateCallHostDisclosurePolicy {
    fun mayShowOutgoingConfirmation(privateAreaVisible: Boolean): Boolean =
        privateAreaVisible

    fun mayShowIdleNotice(privateAreaVisible: Boolean, phase: NativeCallPhase): Boolean =
        privateAreaVisible && phase == NativeCallPhase.IDLE

    /** Incoming calls have separate user-granted call consent. An outgoing
     * request never survives private-area lock, even if Android delivers an
     * earlier microphone/camera permission result after that lock. */
    fun mayCompletePermissionRequest(privateAreaVisible: Boolean, incoming: Boolean): Boolean =
        incoming || privateAreaVisible
}
