package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.core.media.PrivateMediaCrypto
import com.example.core.media.PrivatePhotoStaging
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PrivatePhotoStagingTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun jpegRecompressionAndPrivateEncryptedRoundTrip() {
        val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val out=ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))
        bitmap.recycle()
        val original=out.toByteArray()
        val dir=temp.newFolder()
        val result=PrivatePhotoStaging.stageSelectedBytes(original,dir)
        try {
            assertEquals("image/jpeg",result.manifest.mime)
            val clear=PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                result.ciphertextFile,result.manifest,result.keyMaterial,dir)
            try {
                val bytes=clear.readBytes()
                val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                assertNotNull(decoded)
                assertEquals(640,decoded!!.width)
                assertEquals(480,decoded.height)
                decoded.recycle()
            } finally { clear.delete() }
        } finally {
            result.destroyKey()
            assertTrue(result.keyMaterial.all { it==0.toByte() })
            result.ciphertextFile.delete()
        }
    }

    @Test fun invalidPhotoIsRejectedWithoutCreatingEncryptedFiles() {
        val dir=temp.newFolder()
        try {
            PrivatePhotoStaging.stageSelectedBytes(byteArrayOf(1,2,3,4),dir)
            fail("Invalid photo must fail")
        } catch (_: IllegalArgumentException) {}
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
