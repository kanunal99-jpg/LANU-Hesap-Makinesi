package com.example.core.calls

/**
 * One outgoing ICE restart per authenticated call session. This guard is
 * instance-scoped: a newly accepted call receives a fresh budget, but a
 * reconnecting/old call cannot create an unbounded SDP renegotiation loop.
 *
 * The foreground owner still verifies the session, peer and RTP evidence;
 * this class never opens a socket, media source or private screen.
 */
class CallReconnectBudget(private val maxAttempts: Int = 1) {
    init { require(maxAttempts in 1..2) }
    private var attempts = 0

    @Synchronized fun claim(): Boolean {
        if (attempts >= maxAttempts) return false
        attempts++
        return true
    }

    @Synchronized fun remaining(): Int = maxAttempts - attempts
}
