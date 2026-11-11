package com.example.core.calls

/**
 * Android can preempt AudioRecord/Camera2 while another SIM/VoIP app owns the
 * hardware. Errors from that ownership handover are not proof that E2EE ICE
 * or the peer session failed. Never suppress genuine errors outside hold.
 */
internal object CallInterruptionFailurePolicy {
    fun shouldReportAudioError(closing: Boolean, focusHeld: Boolean): Boolean =
        !closing && !focusHeld

    fun shouldQuarantineOemCapture(closing: Boolean, focusHeld: Boolean,
                                   usingHardware: Boolean, hasPermission: Boolean): Boolean =
        shouldReportAudioError(closing, focusHeld) && usingHardware && hasPermission
}
