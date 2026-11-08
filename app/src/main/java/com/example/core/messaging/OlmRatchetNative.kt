package com.example.core.messaging

import org.json.JSONObject

/** Real vodozemac0.11.2 Olm Double Ratchet bridge. No automatic wire activation. */
internal object OlmRatchetNative {
    const val ENGINE = "vodozemac-0.11.2/olm2"
    init { System.loadLibrary("lanu_ratchet") }
    private external fun execute(request: String, storageKey: ByteArray): String

    fun process(request: JSONObject, storageKey: ByteArray): JSONObject {
        require(storageKey.size == 32 && storageKey.any { it != 0.toByte() }) {
            "Güvenli oturum anahtarı kullanılamıyor; kayıtlar korunuyor."
        }
        val raw = request.toString()
        require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
        val response = execute(raw, storageKey)
        check(response.length <= 2 * 1024 * 1024) { "Güvenli oturum yanıtı sınırı aşıldı." }
        // Native builds only a fixed-depth result. Peer plaintext remains an
        // escaped String; it must not be trusted as another JSON object here.
        return JSONObject(response)
    }
}
