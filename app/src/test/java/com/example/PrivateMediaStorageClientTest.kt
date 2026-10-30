package com.example

import com.example.core.media.PrivateMediaStorageClient
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaStorageClientTest {
    private val endpoint = "https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay"
    private val id = "a".repeat(32)
    @Test fun signedUploadAndDownloadRequirePrivateServerOwnedObjectIdentity() {
        val upload = "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=opaque"
        val download = "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/sign/lanu-private-media-v1/v1/$id.cipher?token=opaque"
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, upload, id, true))
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, download, id, false))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint, download, id, true))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint, upload, id, false))
    }
    @Test fun maliciousUrlsCannotExfiltrateAnyCiphertext() {
        val path = "/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=opaque"
        val hostile = listOf(
            "http://talhknjcjaijqiqwdyhf.supabase.co$path",
            "https://evil.invalid$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co.evil.invalid$path",
            "https://evil.invalid@talhknjcjaijqiqwdyhf.supabase.co$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co:8443$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/public/$id.cipher?token=opaque",
            "https://talhknjcjaijqiqwdyhf.supabase.co$path#fragment",
            "https://talhknjcjaijqiqwdyhf.supabase.co$path".replace(id,"0".repeat(32))
        )
        for (url in hostile) assertFalse(url, PrivateMediaStorageClient.safeSignedUrl(endpoint,url,id,true))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint,"https://talhknjcjaijqiqwdyhf.supabase.co$path","wrong",true))
    }
}
