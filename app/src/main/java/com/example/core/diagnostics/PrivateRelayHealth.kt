package com.example.core.diagnostics

/**
 * RAM-only health counters for the encrypted relay. The telemetry schema is
 * ONLY fixed enum categories plus bounded counters. It deliberately stores no
 * URL, action, peer, identity, message, token, IP, exception, stack or timestamp.
 * Nothing is sent to a server; process restart resets all counters.
 */
object PrivateRelayHealth {
    enum class Outcome(val label: String) {
        SUCCESS("Başarılı"),
        NETWORK("Ağ bağlantısı"),
        AUTHORIZATION("Kimlik doğrulama/yetki"),
        RATE_LIMIT("İstek sınırlama"),
        CONFLICT("Kayıt çakışması"),
        RESPONSE("Geçersiz sunucu yanıtı"),
        OTHER("Diğer sunucu yanıtı")
    }

    private val counters = IntArray(Outcome.values().size)
    private const val MAX_COUNT = 9999

    @Synchronized fun record(outcome: Outcome) {
        val index = outcome.ordinal
        counters[index] = (counters[index] + 1).coerceAtMost(MAX_COUNT)
    }

    fun recordHttp(status: Int) {
        val outcome = when {
            status in 200..299 -> Outcome.SUCCESS
            status == 401 || status == 403 -> Outcome.AUTHORIZATION
            status == 429 -> Outcome.RATE_LIMIT
            status == 409 -> Outcome.CONFLICT
            else -> Outcome.OTHER
        }
        record(outcome)
    }

    @Synchronized fun snapshot(): Map<Outcome, Int> =
        Outcome.values().associateWith { counters[it.ordinal] }

    @Synchronized fun clear() {
        counters.fill(0)
    }

    fun safeSummary(): String = snapshot().entries
        .filter { it.value > 0 }
        .joinToString(separator = "\n") { (reason, count) -> "${reason.label}: $count" }
        .ifEmpty { "Bu oturumda sunucu bağlantısı denemesi yok." }
}
