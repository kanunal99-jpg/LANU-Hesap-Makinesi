package com.example

import com.example.core.media.PrivateMediaProviderIo
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class PrivateMediaProviderIoTest {
    @Test fun oversizedSourceIsNeverWrittenPastBudget() {
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            PrivateMediaProviderIo.copyBounded(ByteArrayInputStream(ByteArray(101)), output, 100) { }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(output.size() <= 100)
    }

    @Test fun stalledProviderIsRejectedRatherThanSpinning() {
        var reads = 0
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return 0 }
        }
        assertTrue(runCatching {
            PrivateMediaProviderIo.copyBounded(input, ByteArrayOutputStream(), 100) { }
        }.isFailure)
        assertEquals(3, reads)
    }

    @Test fun cancellationAfterReadPreventsWritingPlaintextToProvider() {
        var checkpoints = 0
        val output = ByteArrayOutputStream()
        assertTrue(runCatching {
            PrivateMediaProviderIo.copyBounded(ByteArrayInputStream(byteArrayOf(1)), output, 100) {
                if (++checkpoints == 2) throw CancellationException("closed")
            }
        }.exceptionOrNull() is CancellationException)
        assertEquals(0, output.size())
    }

    @Test fun shortReadsRoundTripAndTemporaryCopyBufferIsWiped() {
        var retained: ByteArray? = null
        val original = ByteArray(1000) { (it % 127).toByte() }
        val input = object : ByteArrayInputStream(original) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                retained = b
                return super.read(b, off, minOf(len, 7))
            }
        }
        val output = ByteArrayOutputStream()
        assertEquals(1000L, PrivateMediaProviderIo.copyBounded(input, output, 1000) { })
        assertArrayEquals(original, output.toByteArray())
        assertTrue(retained!!.all { it == 0.toByte() })
    }
}
