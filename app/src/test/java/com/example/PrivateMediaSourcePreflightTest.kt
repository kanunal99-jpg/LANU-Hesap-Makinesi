package com.example

import com.example.core.media.PrivateMediaSourcePreflight
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream

class PrivateMediaSourcePreflightTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    private fun jpeg() = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe0.toByte(),3,4,5)
    private fun png() = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,3,4)
    private fun pdf() = "%PDF-1.7\nhello".toByteArray()
    private fun video() = byteArrayOf(0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,1,7,8)

    @Test fun validSignaturesRemainByteForByteUnchanged() {
        for ((mime,data) in listOf("image/jpeg" to jpeg(),"image/png" to png(),"application/pdf" to pdf(),"video/mp4" to video())) {
            PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(data),mime,
                sanitizedImage=mime.startsWith("image/"),
                userApprovedOriginalVideo=mime=="video/mp4").use {
                assertArrayEquals(data,it.readBytes())
            }
        }
    }

    @Test fun spoofedOrTruncatedDataIsRejected() {
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(jpeg()),"application/pdf") }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(pdf()),"video/mp4",userApprovedOriginalVideo=true) }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(png()),"image/jpeg",sanitizedImage=true) }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(byteArrayOf()),"application/pdf") }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream("%PDF-1-X".toByteArray()),"application/pdf") }
    }

    @Test fun adversarialMp4HeadersCannotSpoofAnFtypBox() {
        for (bytes in listOf(
            video().also { it[3] = 17 }, // ftyp box size must be 4-byte aligned
            video().also { it[8] = 32; it[9] = 32; it[10] = 32; it[11] = 32 },
            video().also { it[8] = 33; it[9] = 64; it[10] = 35; it[11] = 36 }
        )) {
            var closed = false
            val source = object : ByteArrayInputStream(bytes) {
                override fun close() { closed = true; super.close() }
            }
            rejected {
                PrivateMediaSourcePreflight.inspect(
                    source, "video/mp4", userApprovedOriginalVideo = true
                )
            }
            assertTrue("Invalid provider stream must be closed", closed)
        }
    }

    @Test fun photosRequireSanitizationAndVideoRequiresUserApproval() {
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(jpeg()),"image/jpeg") }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(png()),"image/png") }
        rejected { PrivateMediaSourcePreflight.inspect(ByteArrayInputStream(video()),"video/mp4") }
    }

    @Test fun splitProviderReadsDoNotConsumeMediaHeaders() {
        val bytes=pdf()
        val source=object: ByteArrayInputStream(bytes) {
            override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,minOf(len,1))
        }
        PrivateMediaSourcePreflight.inspect(source,"application/pdf").use {
            assertArrayEquals(bytes,it.readBytes())
        }
    }

    @Test fun rejectedMimeAndConsentChecksAlsoCloseProviderStreams() {
        for ((mime, approvedPhoto, approvedVideo) in listOf(
            Triple("application/x-executable", true, true),
            Triple("image/jpeg", false, false),
            Triple("video/mp4", false, false)
        )) {
            var closed = false
            val stream = object : ByteArrayInputStream(jpeg()) {
                override fun close() { closed = true; super.close() }
            }
            rejected {
                PrivateMediaSourcePreflight.inspect(
                    stream, mime,
                    sanitizedImage = approvedPhoto,
                    userApprovedOriginalVideo = approvedVideo
                )
            }
            assertTrue("Rejected external provider must be closed", closed)
        }
    }

    @Test fun invalidStreamClosesOriginalProvider() {
        var closed=false
        val stream=object:ByteArrayInputStream(byteArrayOf(1,2,3,4,5,6,7,8)){
            override fun close(){closed=true;super.close()}
        }
        rejected { PrivateMediaSourcePreflight.inspect(stream,"application/pdf") }
        assertTrue(closed)
    }
}
