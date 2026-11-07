package com.example

import android.content.*
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.media.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PrivateMediaProviderRuntimeTest {
    private abstract class Provider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private fun context(cache: File, provider: ContentProvider): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ContentResolver.wrap(provider)
        return object : ContextWrapper(base) {
            override fun getCacheDir() = cache
            override fun getContentResolver() = resolver
        }
    }

    @Test fun documentEncryptionVerifiedExportAndDescriptorClosureUseRealAndroidProvider() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "lanu_ci_media_${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(root, "source.txt").apply { writeBytes(ByteArray(150000) { (it % 127).toByte() }) }
        val destination = File(root, "export.txt")
        val descriptors = mutableListOf<FileDescriptor>()
        val provider = object : Provider() {
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
                val pfd = if (mode == "r") ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                    else ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                descriptors.add(pfd.fileDescriptor)
                return AssetFileDescriptor(pfd, 0, if (mode == "r") source.length() else AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        }
        val ctx = context(root, provider)
        var encrypted: PrivateMediaCrypto.EncryptedFile? = null
        var verified: File? = null
        try {
            encrypted = PrivateMediaDocumentPreparer.encryptDocument(ctx, Uri.parse("content://lanu-ci/source"))
            val dir = PrivateMediaDocumentPreparer.privateDirectory(ctx)
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("lanu-staged-") })
            verified = PrivateMediaCrypto.decryptToVerifiedPrivateFile(encrypted.ciphertextFile,
                encrypted.manifest, encrypted.keyMaterial, dir)
            PrivateMediaProviderIo.exportVerifiedFile(ctx, Uri.parse("content://lanu-ci/export"), verified)
            assertArrayEquals(source.readBytes(), destination.readBytes())
            assertEquals(2, descriptors.size)
            assertTrue(descriptors.all { !it.valid() })
        } finally {
            encrypted?.destroyKey(); encrypted?.ciphertextFile?.delete(); verified?.delete()
            root.deleteRecursively()
        }
    }

    @Test fun cancellationReachesProviderWhileItsDescriptorIsStillOpening() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val provider = object : Provider() {
            override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? {
                checkNotNull(signal).setOnCancelListener { cancelled.countDown() }
                entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                throw OperationCanceledException()
            }
        }
        val ctx = context(base.cacheDir, provider)
        val job = launch(Dispatchers.Default) {
            PrivateMediaProviderIo.readPhoto(ctx, Uri.parse("content://lanu-ci/wait"), 100)
            fail("Cancelled provider cannot return data")
        }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
        withTimeout(5000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(0L, cancelled.count)
    }
}
