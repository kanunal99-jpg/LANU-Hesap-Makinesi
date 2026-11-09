package com.example.core.diagnostics

/** Opt-in RAM-only durations. No content, IDs, URLs, wall-clock time or uploads. */
object PrivateConnectionMetrics {
    enum class Stage(val label: String) {
        MESSAGE_SEND("Mesaj sunucu kabulü"), SYNC("Eşitleme HTTP"),
        CALL_SIGNAL("Arama sinyali HTTP"), CALL_SETUP("Kabul → çift yönlü ses"),
        CALL_RECOVERY("Kopma → çift yönlü ses"), TURN_ALLOCATION("TURN tahsisi")
    }
    data class Summary(val samples: Int, val p50Ms: Long?, val p95Ms: Long?, val failed: Int)
    private val values = Stage.values().associateWith { ArrayDeque<Long>() }
    private val failures = IntArray(Stage.values().size)
    private var generation = 0L
    private var enabled = false
    @Synchronized fun setEnabled(value: Boolean) { clear(); enabled = value }
    @Synchronized fun isEnabled() = enabled
    /** Generation binds completion to the same opted-in measurement session. */
    @Synchronized fun token(): Long? = if (enabled) generation else null
    @Synchronized fun record(stage: Stage, token: Long?, startedMs: Long, finishedMs: Long, success: Boolean) {
        if (!enabled || token == null || token != generation || startedMs < 0 || finishedMs < startedMs) return
        val elapsed = finishedMs - startedMs
        if (elapsed > 180_000L) return
        if (!success) { failures[stage.ordinal] = (failures[stage.ordinal] + 1).coerceAtMost(9999); return }
        values.getValue(stage).let { if (it.size == 128) it.removeFirst(); it.addLast(elapsed) }
    }
    @Synchronized fun clear() { generation++; values.values.forEach { it.clear() }; failures.fill(0) }
    @Synchronized fun summary(stage: Stage): Summary {
        val sorted = values.getValue(stage).sorted()
        fun percentile(p: Int): Long? = if (sorted.isEmpty()) null else sorted[((sorted.size * p + 99) / 100 - 1).coerceAtLeast(0)]
        return Summary(sorted.size, percentile(50), percentile(95), failures[stage.ordinal])
    }
    @Synchronized fun safeSummary() = Stage.values().joinToString("\n") { stage ->
        val s = summary(stage)
        "${stage.label}: n=${s.samples}, P50=${s.p50Ms?.let { "$it ms" } ?: "—"}, P95=${s.p95Ms?.let { "$it ms" } ?: "—"}, tamamlanmayan=${s.failed}"
    }
}
