package com.example

import com.example.core.media.PrivateMediaStorageClient
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaStorageClientTest {
    private val endpoint = "https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay"
    private val id = "a".repeat(32)
    @Test fun signedUploadAndDownloadRequirePrivateServerOwnedObjectIdentity() {
        val upload = "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=valid.jwt_token_12345"
        val download = "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/sign/lanu-private-media-v1/v1/$id.cipher?token=valid.jwt_token_12345"
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, upload, id, true))
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, download, id, false))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint, download, id, true))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint, upload, id, false))
    }
    @Test fun signedGrantRejectsAmbiguousOrNonCanonicalQueryOnBothRoutes() {
        val path = "/storage/v1/object/sign/lanu-private-media-v1/v1/$id.cipher"
        val goodToken = "valid.jwt_token_12345"
        val download = "https://talhknjcjaijqiqwdyhf.supabase.co$path?token=$goodToken"
        val upload = download.replace("/object/sign/", "/object/upload/sign/")
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, download, id, false))
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, upload, id, true))
        val hostile = listOf(
            "&token=$goodToken",
            "&download=1",
            "#fragment",
            "?token=$goodToken",
            "?download=1",
            "?token=" + "x".repeat(4097),
            "?token=" + "x".repeat(15),
            "?token=valid%2Ejwt_token_12345",
            "?token=valid.jwt_token_12345%0A",
            "?token=valid.jwt_token_12345%26download=1"
        )
        for (suffix in hostile) {
            val url = if (suffix.startsWith("&") || suffix.startsWith("#")) download + suffix
                else download.substringBefore("?") + suffix
            assertFalse("Download accepted $suffix",
                PrivateMediaStorageClient.safeSignedUrl(endpoint, url, id, false))
            val uploadUrl = url.replace("/object/sign/", "/object/upload/sign/")
            assertFalse("Upload accepted $suffix",
                PrivateMediaStorageClient.safeSignedUrl(endpoint, uploadUrl, id, true))
        }
    }

    @Test fun uploadTokenMustMatchExactlyOneAuthorizedGrantParameter() {
        val url = "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=correct.jwt_token"
        assertTrue(PrivateMediaStorageClient.validSignedToken(url, "correct.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url, "different.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url + "&token=correct.jwt_token", "correct.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url + "&download=other", "correct.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url.replace("token=correct.jwt_token", "download=correct.jwt_token"), "correct.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url.replace("correct.jwt_token", "%ZZ"), "correct.jwt_token"))
        assertFalse(PrivateMediaStorageClient.validSignedToken(url, "bad\nline"))
    }

    @Test fun bothSignedGrantHostsMapToTheSameTusStorageOrigin() {
        val project = "talhknjcjaijqiqwdyhf.supabase.co"
        val storage = "talhknjcjaijqiqwdyhf.storage.supabase.co"
        val signedStorageUrl = "https://$storage/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=correct.jwt_token"
        assertTrue(PrivateMediaStorageClient.safeSignedUrl(endpoint, signedStorageUrl, id, true))
        assertEquals(storage, PrivateMediaStorageClient.storageHost(project))
        assertEquals(storage, PrivateMediaStorageClient.storageHost(storage))
    }

    @Test fun maliciousUrlsCannotExfiltrateAnyCiphertext() {
        val path = "/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=valid.jwt_token_12345"
        val hostile = listOf(
            "http://talhknjcjaijqiqwdyhf.supabase.co$path",
            "https://evil.invalid$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co.evil.invalid$path",
            "https://evil.invalid@talhknjcjaijqiqwdyhf.supabase.co$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co:8443$path",
            "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/public/$id.cipher?token=valid.jwt_token_12345",
            "https://talhknjcjaijqiqwdyhf.supabase.co$path#fragment",
            "https://talhknjcjaijqiqwdyhf.supabase.co$path".replace(id,"0".repeat(32))
        )
        for (url in hostile) assertFalse(url, PrivateMediaStorageClient.safeSignedUrl(endpoint,url,id,true))
        assertFalse(PrivateMediaStorageClient.safeSignedUrl(endpoint,"https://talhknjcjaijqiqwdyhf.supabase.co$path","wrong",true))
    }

    @Test fun tusLocationMustStayOnExactOpaqueResumableUploadPath() {
        val client = PrivateMediaStorageClient(endpoint)
        val base = java.net.URI(
            "https://talhknjcjaijqiqwdyhf.supabase.co/storage/v1/object/upload/sign/lanu-private-media-v1/v1/$id.cipher?token=valid.jwt_token_12345"
        )
        val good = "/storage/v1/upload/resumable/opaque-id_123.xyz"
        assertEquals("https://talhknjcjaijqiqwdyhf.supabase.co$good",
            client.locationForTus(base, good))
        assertEquals("https://talhknjcjaijqiqwdyhf.storage.supabase.co$good",
            client.locationForTus(base,
                "https://talhknjcjaijqiqwdyhf.storage.supabase.co$good"))
        val bad = listOf(
            "$good?token=leak",
            "$good#fragment",
            "$good/nested",
            "/storage/v1/upload/resumable/%2Fadmin",
            "/storage/v1/upload/resumable/%2e%2e",
            "/storage/v1/upload/resumable/../admin",
            "/storage/v1/upload/resumable/",
            "/storage/v1/upload/resumable/${"x".repeat(513)}",
            "https://evil.invalid$good",
            "http://talhknjcjaijqiqwdyhf.supabase.co$good",
            "https://talhknjcjaijqiqwdyhf.supabase.co:8443$good",
            "https://user@talhknjcjaijqiqwdyhf.supabase.co$good"
        )
        for (location in bad) {
            try {
                client.locationForTus(base, location)
                fail("Untrusted TUS Location accepted: $location")
            } catch (_: IllegalArgumentException) {
                // Fail closed before the x-signature token reaches any request.
            }
        }
    }
}
