package com.example.core.diagnostics

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Strictly local crash evidence. No exception messages, causes, chat text,
 * PIN, device identifiers, URLs, or stack frames outside our app package.
 * Android's original uncaught-exception handler always runs afterward.
 */
object LocalCrashReport {
    private const val REPORT_FILE = "lanu_crash_code_frames_v1.json"
    private const val MAX_REPORT_BYTES = 3072
    private const val MAX_FRAMES = 6
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val safeClassName = Regex("[A-Za-z0-9_.$]{1,140}")
    private val safeIdentifier = Regex("[A-Za-z0-9_]{1,90}")
    private val safeFileName = Regex("[A-Za-z0-9_.-]{1,90}")
    private val safeFrame = Regex("com\\.example\\.[A-Za-z0-9_.$]{1,140}\\.[A-Za-z0-9_]{1,90}\\([A-Za-z0-9_.-]{1,90}:[0-9]{1,6}\\)")

    data class Record(val timestamp: Long, val type: String, val appFrames: List<String>) {
        fun sanitizedSummary(): String = buildString {
            append("LANU yerel çökme tanılaması\n")
            append("Zaman: ").append(timestamp).append('\n')
            append("Tür: ").append(type).append('\n')
            append(appFrames.joinToString("\n").ifBlank { "Uygulama kaynak satırı bulunamadı." })
        }
    }

    /** Pure formatter: the secret-containing exception message is never read. */
    fun fromThrowable(error: Throwable, timestamp: Long = System.currentTimeMillis()): Record {
        val type = error.javaClass.simpleName.takeIf { it.matches(safeIdentifier) } ?: "Throwable"
        val frames = error.stackTrace.asSequence()
            .filter { it.className.startsWith("com.example.") }
            .filter { it.className.matches(safeClassName) && it.methodName.matches(safeIdentifier) }
            .take(MAX_FRAMES)
            .map { frame ->
                val file = frame.fileName?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?.takeIf { it.matches(safeFileName) } ?: "Unknown"
                val line = frame.lineNumber.coerceIn(0, 999_999)
                frame.className + "." + frame.methodName + "(" + file + ":" + line + ")"
            }
            .toList()
        return Record(timestamp, type, frames)
    }

    fun save(context: Context, error: Throwable) {
        try {
            val report = fromThrowable(error)
            val json = JSONObject()
                .put("version", 1)
                .put("time", report.timestamp)
                .put("type", report.type)
                .put("appFrames", JSONArray(report.appFrames)).toString()
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_REPORT_BYTES) return
            val atomic = AtomicFile(file(context))
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (_: Throwable) {
                atomic.failWrite(stream)
            }
        } catch (_: Throwable) {
            // Best-effort only; always delegate Android's normal crash handler.
        }
    }

    fun read(context: Context, now: Long = System.currentTimeMillis()): Record? =
        runCatching {
            val target = file(context)
            if (!target.isFile || target.length() !in 1..MAX_REPORT_BYTES.toLong()) return@runCatching null
            val bytes = AtomicFile(target).openRead().use { readBounded(it) }
                ?: return@runCatching null
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optInt("version") != 1) return@runCatching null
            val timestamp = json.optLong("time", -1L)
            if (timestamp < 1 || now < timestamp || now - timestamp > MAX_AGE_MS) return@runCatching null
            val type = json.optString("type")
            if (!type.matches(safeIdentifier)) return@runCatching null
            val arr = json.optJSONArray("appFrames") ?: return@runCatching null
            if (arr.length() > MAX_FRAMES) return@runCatching null
            val frames = (0 until arr.length()).map { arr.getString(it) }
            if (frames.any { !it.matches(safeFrame) }) return@runCatching null
            Record(timestamp, type, frames)
        }.getOrNull()

    /**
     * Only 3 KiB of local metadata may be read. Even an abnormal or mocked
     * filesystem stream repeatedly returning zero bytes cannot spin the
     * PIN-gated diagnostics screen forever. No data is uploaded or logged.
     */
    internal fun readBounded(input: java.io.InputStream): ByteArray? {
        val bytes = ByteArray(MAX_REPORT_BYTES + 1)
        var count = 0
        var stalled = 0
        while (count < bytes.size) {
            val n = input.read(bytes, count, bytes.size - count)
            if (n < 0) break
            if (n == 0) {
                if (++stalled >= 3) return null
                continue
            }
            stalled = 0
            count += n
        }
        return if (count <= MAX_REPORT_BYTES) bytes.copyOf(count) else null
    }

    fun clear(context: Context): Boolean = runCatching {
        // AtomicFile.delete removes both the main file and its recovery backup.
        AtomicFile(file(context)).delete()
        true
    }.getOrDefault(false)

    private fun file(context: Context): File = File(context.noBackupFilesDir, REPORT_FILE)
}
