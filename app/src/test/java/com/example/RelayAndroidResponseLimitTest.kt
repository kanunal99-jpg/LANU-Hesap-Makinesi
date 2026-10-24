package com.example

import com.example.core.messaging.RelayException
import com.example.core.messaging.RelayResponseReader
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class RelayAndroidResponseLimitTest {
    @Test fun `exact cap is accepted and next streamed byte is rejected`() {
        assertEquals("12345678", RelayResponseReader.read(ByteArrayInputStream("12345678".toByteArray()), maxBytes = 8))
        try {
            RelayResponseReader.read(ByteArrayInputStream("123456789".toByteArray()), maxBytes = 8)
            fail("Oversized chunked/decompressed response must fail")
        } catch (e: RelayException) { assertEquals(503, e.status) }
    }

    @Test fun `declared oversized response is rejected without consuming its body`() {
        val stream = object : InputStream() { override fun read(): Int = error("Must not read") }
        try { RelayResponseReader.read(stream, declaredBytes = 9, maxBytes = 8); fail("Must reject header") }
        catch (e: RelayException) { assertEquals(503, e.status) }
    }

    @Test fun `malformed UTF8 and stalled providers fail with no payload echo`() {
        try {
            RelayResponseReader.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), maxBytes = 8)
            fail("Invalid UTF8 must fail")
        } catch (e: RelayException) { assertEquals("Sunucu yanıtı okunamadı.", e.message) }
        val stream = object : InputStream() {
            var reads = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return 0 }
        }
        try { RelayResponseReader.read(stream, maxBytes = 8); fail("Must not spin") }
        catch (_: RelayException) { assertEquals(3, stream.reads) }
    }
}
