package com.example

import com.example.core.update.OtaStreamReadPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class OtaStreamReadPolicyTest {
    @Test fun zeroProgressStreamIsRejectedInsteadOfLoopingForever() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        try {
            OtaStreamReadPolicy.read(stalled, ByteArray(32))
            fail("Non-progressing OTA stream must be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("ilerlemiyor"))
        }
    }

    @Test fun positiveReadsAndEofKeepTheirOriginalMeaning() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val buffer = ByteArray(8)
        assertEquals(3, OtaStreamReadPolicy.read(input, buffer))
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.copyOf(3))
        assertEquals(-1, OtaStreamReadPolicy.read(input, buffer))
    }

    @Test fun upstreamIoErrorsAreNotSilenced() {
        val failed = object : InputStream() {
            override fun read(): Int = throw IOException("disk failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw IOException("disk failure")
        }
        try {
            OtaStreamReadPolicy.read(failed, ByteArray(8))
            fail("Upstream IOException must propagate")
        } catch (e: IOException) {
            assertEquals("disk failure", e.message)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBufferIsNotAccepted() {
        OtaStreamReadPolicy.read(ByteArrayInputStream(byteArrayOf(1)), ByteArray(0))
    }
}
