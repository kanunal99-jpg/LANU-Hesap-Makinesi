package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.diagnostics.LocalCrashReport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Do not invoke the real uncaught handler in tests: it must terminate Android. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCrashReportTest {
    private fun simulatedCrash(): IllegalStateException =
        IllegalStateException("PIN=9437 SECRET_CHAT_MESSAGE=private.example.com token=abc").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.example.features.communication.ChatDetailScreenKt", "sendMessage", "ChatDetailScreen.kt", 272),
                StackTraceElement("com.example.core.repository.CommunicationRepository", "synchronize", "CommunicationRepository.kt", 299),
                StackTraceElement("com.external.SecretLeak", "method", "PIN-9437.kt", 432)
            )
        }

    @Test fun reportExcludesExceptionMessageAndExternalFrameworkFrames() {
        val record = LocalCrashReport.fromThrowable(simulatedCrash(), timestamp = 1_700_000_000_000L)
        val report = record.sanitizedSummary()
        assertEquals("IllegalStateException", record.type)
        assertEquals(2, record.appFrames.size)
        assertTrue(report.contains("ChatDetailScreen.kt:272"))
        assertTrue(report.contains("CommunicationRepository.kt:299"))
        assertFalse(report.contains("9437"))
        assertFalse(report.contains("SECRET_CHAT_MESSAGE"))
        assertFalse(report.contains("private.example.com"))
        assertFalse(report.contains("token=abc"))
        assertFalse(report.contains("com.external"))
    }

    @Test fun localStoreSurvivesReadAndSupportsExplicitClearWithoutChatData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            LocalCrashReport.clear(context)
            assertNull(LocalCrashReport.read(context))
            LocalCrashReport.save(context, simulatedCrash())
            val record = LocalCrashReport.read(context)
            assertNotNull(record)
            assertEquals("IllegalStateException", record!!.type)
            assertEquals(2, record.appFrames.size)
            val file = File(context.noBackupFilesDir, "lanu_crash_code_frames_v1.json")
            val bytes = file.readText()
            assertFalse(bytes.contains("9437"))
            assertFalse(bytes.contains("private.example.com"))
            assertFalse(bytes.contains("SECRET_CHAT_MESSAGE"))
            assertFalse(bytes.contains("com.external.SecretLeak"))
            assertTrue(LocalCrashReport.clear(context))
            assertNull(LocalCrashReport.read(context))
            assertFalse(file.exists())
        } finally { LocalCrashReport.clear(context) }
    }

    @Test fun expiredRecordsAreNotShownAndTamperingIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            LocalCrashReport.clear(context)
            LocalCrashReport.save(context, simulatedCrash())
            val record = LocalCrashReport.read(context)!!
            assertNull(LocalCrashReport.read(context, now = record.timestamp + 8 * 24 * 60 * 60 * 1000L))
            val file = File(context.noBackupFilesDir, "lanu_crash_code_frames_v1.json")
            file.writeText("{\"version\":1,\"time\":" + record.timestamp +
                ",\"type\":\"RuntimeException\",\"appFrames\":[\"com.example.Foo.test(File.kt:10)\",\"PIN=9437\"]}")
            assertNull(LocalCrashReport.read(context))
            file.writeBytes(ByteArray(4000))
            assertNull(LocalCrashReport.read(context))
        } finally { LocalCrashReport.clear(context) }
    }

    @Test fun frameCountAndCorruptMetadataCannotOverflowDiagnostics() {
        val cause = RuntimeException("secret").apply {
            stackTrace = Array(20) { index ->
                StackTraceElement("com.example.Crash", "fn", "Crash.kt", index + 1)
            }
        }
        val record = LocalCrashReport.fromThrowable(cause)
        assertEquals(6, record.appFrames.size)
        assertFalse(record.sanitizedSummary().contains("secret"))
    }
    @Test fun zeroProgressLocalFileReadCannotSpinForever() {
        var calls = 0
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                calls++
                return 0
            }
        }
        assertNull(LocalCrashReport.readBounded(stalled))
        assertEquals(3, calls)
    }

    @Test fun intermittentZeroReadsMayRecoverButOversizedReportIsRejected() {
        val text = "{\\"version\\":1}"
        val data = text.toByteArray(Charsets.UTF_8)
        val input = object : InputStream() {
            private var stalls = 0
            private val source = ByteArrayInputStream(data)
            override fun read(): Int = source.read()
            override fun read(dst: ByteArray, offset: Int, length: Int): Int {
                if (stalls++ < 2) return 0
                return source.read(dst, offset, length)
            }
        }
        assertArrayEquals(data, LocalCrashReport.readBounded(input))
        assertNull(LocalCrashReport.readBounded(
            ByteArrayInputStream(ByteArray(3073))))
    }

}
