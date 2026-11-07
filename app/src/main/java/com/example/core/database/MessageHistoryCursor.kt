package com.example.core.database

/** Keyset position only: no ciphertext or plaintext is retained in paging keys. */
data class MessageHistoryCursor(val timestamp: Long, val id: String) {
    override fun toString(): String = "MessageHistoryCursor(redacted)"
}
