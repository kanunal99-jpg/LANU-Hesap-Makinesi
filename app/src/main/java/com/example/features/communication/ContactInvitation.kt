package com.example.features.communication

import com.example.core.messaging.UserCodes

/** Explicit import within the unlocked UI only. No exported deep link or automatic contact trust. */
object ContactInvitation {
    private const val PREFIX = "lanu-contact:v1:"
    fun encode(code: String): String = PREFIX + UserCodes.normalize(code).also { require(it.isNotBlank()) }
    fun decode(text: String): String {
        require(text.length <= 256)
        val raw = text.trim()
        val code = if (raw.startsWith(PREFIX)) raw.removePrefix(PREFIX) else raw
        return UserCodes.normalize(code).also { require(it.isNotBlank()) }
    }
}
