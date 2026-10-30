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
}
