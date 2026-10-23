package com.example

import android.graphics.Bitmap
import com.example.core.media.InlineMediaCodec
import com.example.core.media.InlineMediaPreparer
import com.example.core.messaging.PeerMessageCrypto
import com.example.core.messaging.UserCodes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineMediaCodecTest {
    private fun pdf(): ByteArray = "%PDF-1.7\n1 0 obj\nendobj\n%%EOF".toByteArray()
    private fun sampleJpeg(): ByteArray = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(),
        0x01, 0x02, 0xff.toByte(), 0xd9.toByte()
    )

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Malformed or oversized attachment must fail closed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun pdfRoundTripAndPathCannotEscapeStorage() {
        val envelope = InlineMediaCodec.encode("../../Gizli\\belge.pdf", "application/pdf", pdf())
        val attachment = InlineMediaCodec.decode(envelope)
        assertEquals("belge.pdf", attachment.name)
        assertEquals("application/pdf", attachment.mime)
        assertArrayEquals(pdf(), attachment.bytes)
        assertTrue(envelope.length <= InlineMediaCodec.MAX_ENVELOPE_CHARS)
    }

    @Test fun jpegRoundTripAndUnsupportedMimeFails() {
        val envelope = InlineMediaCodec.encode("foto.jpg", "image/jpeg", sampleJpeg())
        assertArrayEquals(sampleJpeg(), InlineMediaCodec.decode(envelope).bytes)
        reject { InlineMediaCodec.encode("script.sh", "application/x-sh", "#!/bin/sh".toByteArray()) }
        reject { InlineMediaCodec.encode("lie.pdf", "application/pdf", sampleJpeg()) }
        reject { InlineMediaCodec.encode("lie.jpg", "image/jpeg", pdf()) }
    }

    @Test fun lengthAndBase64BoundsFailClosed() {
        reject {
            InlineMediaCodec.encode("big.txt", "text/plain",
                ByteArray(InlineMediaCodec.MAX_FILE_BYTES + 1) { 'X'.code.toByte() })
        }
        val valid = InlineMediaCodec.encode("b.txt", "text/plain", "Selam".toByteArray())
        reject { InlineMediaCodec.decode(valid + " ") }
        val j = JSONObject(valid.removePrefix("lanu-media-v1:"))
        j.put("sha256", "0".repeat(64))
        reject { InlineMediaCodec.decode("lanu-media-v1:" + j.toString()) }
        j.put("data", "!illegal")
        reject { InlineMediaCodec.decode("lanu-media-v1:" + j.toString()) }
    }

    @Test fun invalidUnicodeDocumentFailsBeforeEncryption() {
        reject { InlineMediaCodec.encode("invalid.txt", "text/plain", byteArrayOf(0xc3.toByte(), 0x28)) }
        reject { InlineMediaCodec.encode("nul.txt", "text/plain", byteArrayOf(0x00)) }
        assertEquals("merhaba", String(InlineMediaCodec.decode(
            InlineMediaCodec.encode("note.txt", "text/plain", "merhaba".toByteArray())
        ).bytes))
    }

    @Test fun jpegCompressionProducesMetadataFreeBoundedFrame() {
        val bitmap = Bitmap.createBitmap(1100, 720, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            val jpeg = InlineMediaPreparer.compactJpeg(bitmap)
            assertTrue(jpeg.size in 1..InlineMediaCodec.MAX_FILE_BYTES)
            assertTrue(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
            val doc = InlineMediaCodec.encode("pic.jpg", "image/jpeg", jpeg)
            assertArrayEquals(jpeg, InlineMediaCodec.decode(doc).bytes)
        } finally { bitmap.recycle() }
    }

    @Test fun sourceReaderRejectsOversizedDataBeforeDecoder() {
        val valid = ByteArray(512) { 42 }
        assertArrayEquals(valid, InlineMediaPreparer.readBoundedInput(
            ByteArrayInputStream(valid), 512
        ))
        reject {
            InlineMediaPreparer.readBoundedInput(
                ByteArrayInputStream(ByteArray(513) { 42 }), 512
            )
        }
    }

    @Test fun emptyOrMalformedMediaNeverQueueAsAnAttachment() {
        reject { InlineMediaCodec.encode("empty.pdf", "application/pdf", byteArrayOf()) }
        reject { InlineMediaCodec.decode("lanu-media-v1:" + "{}") }
    }

    @Test fun largestInlineDocumentSurvivesAuthenticPeerEncryption() {
        val sender = UserCodes.create()
        val receiver = UserCodes.create()
        val raw = ByteArray(InlineMediaCodec.MAX_FILE_BYTES) { 'A'.code.toByte() }
        val payload = InlineMediaCodec.encode("check.txt", "text/plain", raw)
        val id = UUID.randomUUID().toString()
        val ciphertext = PeerMessageCrypto.encrypt(sender, receiver.id, receiver.publicKey, id, payload)
        assertTrue(ciphertext.startsWith("lanu2:"))
        assertTrue(ciphertext.length <= 50_000)
        assertFalse(ciphertext.contains("check.txt"))
        val decrypted = PeerMessageCrypto.decrypt(receiver, sender.id, sender.publicKey, id, sender.id, ciphertext)
        assertArrayEquals(raw, InlineMediaCodec.decode(decrypted).bytes)
    }
}
