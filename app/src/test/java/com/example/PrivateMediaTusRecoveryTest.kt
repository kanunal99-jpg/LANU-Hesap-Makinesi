package com.example

import com.example.core.media.PrivateMediaStorageClient
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PrivateMediaTusRecoveryTest {
    @get:Rule val files=TemporaryFolder()
    private val endpoint="https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay"
    private val id="a".repeat(32)
    private val grant="https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=fixture.jwt_token"
    private fun response(request:Request,code:Int,headers:Map<String,String> = emptyMap()):Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(ByteArray(0).toResponseBody()).apply {headers.forEach { (k,v)->header(k,v) }}.build()
    @Test fun uncertainPartialPatchUsesHeadThenSendsOnlyUncommittedBytes()=runBlocking {
        val chunk=PrivateMediaStorageClient.TUS_CHUNK_BYTES
        val input=ByteArray(chunk+123){(it%251).toByte()};val file=files.newFile().also {it.writeBytes(input)}
        val stored=Buffer();var patches=0;var heads=0
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            val r=chain.request();assertEquals("fixture.jwt_token",r.header("x-signature"));assertNull(r.header("x-upsert"))
            when(r.method) {
                "POST" -> response(r,201,mapOf("Location" to "/storage/v1/upload/resumable/fixture_upload"))
                "HEAD" -> {heads++;response(r,200,mapOf("Upload-Offset" to stored.size.toString()))}
                "PATCH" -> {
                    assertEquals(stored.size.toString(),r.header("Upload-Offset"))
                    val bytes=Buffer().also {r.body!!.writeTo(it)}.readByteArray();patches++
                    if(patches==1) {stored.write(bytes,0,chunk/2);throw IOException("synthetic lost partial acknowledgement")}
                    stored.write(bytes);response(r,204,mapOf("Upload-Offset" to stored.size.toString()))
                }
                else -> error("unexpected method")
            }
        }.build()
        val progress=mutableListOf<Long>()
        PrivateMediaStorageClient(endpoint,client).upload(file,id,input.size.toLong(),grant,"fixture.jwt_token") {done,total ->
            assertEquals(input.size.toLong(),total);progress.add(done)
        }
        assertEquals(2,patches);assertEquals(1,heads)
        assertArrayEquals(input,stored.readByteArray())
        assertEquals(listOf(0L,(chunk/2).toLong(),input.size.toLong()),progress)
    }
    @Test fun impossibleHeadOffsetNeverSkipsUnsentBytes()=runBlocking {
        val chunk=PrivateMediaStorageClient.TUS_CHUNK_BYTES;val file=files.newFile().also {it.writeBytes(ByteArray(chunk+123))}
        var patches=0
        val client=OkHttpClient.Builder().addInterceptor {chain -> val r=chain.request();when(r.method) {
            "POST" -> response(r,201,mapOf("Location" to "/storage/v1/upload/resumable/fixture_upload"))
            "PATCH" -> {patches++;throw IOException("synthetic lost acknowledgement")}
            "HEAD" -> response(r,200,mapOf("Upload-Offset" to (chunk+1L).toString()))
            else -> error("unexpected method")
        }}.build()
        assertTrue(runCatching {PrivateMediaStorageClient(endpoint,client).upload(file,id,file.length(),grant,"fixture.jwt_token")}.isFailure)
        assertEquals(1,patches)
    }
    @Test fun onlyUnsupportedCreateCanRequestStandardUploadFallback()=runBlocking {
        val file=files.newFile().also {it.writeBytes(ByteArray(32))}
        for(code in listOf(405,403,409)) {
            val client=OkHttpClient.Builder().addInterceptor {chain ->response(chain.request(),code)}.build()
            val error=runCatching {PrivateMediaStorageClient(endpoint,client).upload(file,id,file.length(),grant,"fixture.jwt_token")}.exceptionOrNull()
            if(code==405) assertTrue(error is PrivateMediaStorageClient.TusUnavailable)
            else assertFalse(error is PrivateMediaStorageClient.TusUnavailable)
        }
    }
}
