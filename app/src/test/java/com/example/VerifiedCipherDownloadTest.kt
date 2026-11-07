package com.example

import com.example.core.media.VerifiedCipherDownload
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class VerifiedCipherDownloadTest {
    private val bytes = ByteArray(100_000) { (it % 251).toByte() }
    private fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }
    private val type = "application/octet-stream".toMediaType()
    private fun response(request: Request, code: Int, content: ByteArray, range: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", "application/octet-stream").body(content.toResponseBody(type)).apply {
                if (range != null) header("Content-Range", range)
            }.build()
    // Unknown-length early EOF models a connection lost after durable bytes.
    private fun partial(request: Request): Response {
        val part = bytes.copyOfRange(0, 32768).toResponseBody(type)
        val body = object : ResponseBody() {
            override fun contentType() = type
            override fun contentLength() = -1L
            override fun source() = part.source()
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("test").header("Content-Type", "application/octet-stream").body(body).build()
    }

    @Test fun interruptedDownloadRequestsOnlyMissingCiphertextAndVerifiesWholeHash() = runBlocking {
        val dir = Files.createTempDirectory("cipher-range-test").toFile()
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            ranges += chain.request().header("Range")
            assertEquals("identity", chain.request().header("Accept-Encoding"))
            if (ranges.size == 1) partial(chain.request())
            else response(chain.request(), 206, bytes.copyOfRange(32768, bytes.size), "bytes 32768-99999/100000")
        }.build()
        try {
            val file = VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), hash(bytes), dir, client)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf(null, "bytes=32768-"), ranges)
        } finally { dir.deleteRecursively() }
    }

    @Test fun serverWithoutRangeSupportRestartsInsteadOfAppendingDuplicateBytes() = runBlocking {
        val dir = Files.createTempDirectory("cipher-restart-test").toFile()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (++requests == 1) partial(chain.request()) else response(chain.request(), 200, bytes)
        }.build()
        try {
            val file = VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), hash(bytes), dir, client)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(2, requests)
        } finally { dir.deleteRecursively() }
    }

    @Test fun forgedRangeIsNeverAppendedOrRetriedAndPartialFileIsDeleted() = runBlocking {
        val dir = Files.createTempDirectory("cipher-forged-test").toFile()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (++requests == 1) partial(chain.request())
            else response(chain.request(), 206, bytes.copyOfRange(32768, bytes.size), "bytes 1-99999/100000")
        }.build()
        try {
            assertTrue(runCatching {
                VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), hash(bytes), dir, client)
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(2, requests)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptCiphertextOrExpiredGrantNeverBecomesVerifiedFile() = runBlocking {
        for (code in listOf(200, 403)) {
            val dir = Files.createTempDirectory("cipher-reject-test").toFile()
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                response(chain.request(), code, bytes)
            }.build()
            try {
                assertTrue(runCatching {
                    VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), "0".repeat(64), dir, client)
                }.isFailure)
                assertEquals(1, requests)
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun repeatedNetworkFailuresHaveFiniteRetriesAndLeaveNoCacheFile() = runBlocking {
        val dir = Files.createTempDirectory("cipher-offline-test").toFile()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor {
            requests++
            throw java.io.IOException("offline")
        }.build()
        try {
            assertTrue(runCatching {
                VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), hash(bytes), dir, client)
            }.isFailure)
            assertEquals(3, requests)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun localFileWriteFailureDoesNotTriggerNetworkRetries() = runBlocking {
        val dir = Files.createTempDirectory("cipher-disk-test").toFile()
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            // A local filesystem error is distinct from a broken download.
            val target = dir.listFiles()!!.single()
            check(target.delete()); check(target.mkdir())
            response(chain.request(), 200, bytes)
        }.build()
        try {
            assertTrue(runCatching {
                VerifiedCipherDownload.download("https://local.invalid/cipher", bytes.size.toLong(), hash(bytes), dir, client)
            }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, requests)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }

}
