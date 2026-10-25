package com.example.core.calls

/**
 * Public, STUN-only ICE discovery. Different providers reduce a single DNS/
 * provider failure, without enabling billed TURN or exposing credentials.
 * Peer IP addresses may be visible to STUN providers and the other participant.
 * This list is best-effort, not a guarantee through restrictive NATs.
 */
internal object CallIceServerPolicy {
    val stunUrls = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    )
}
