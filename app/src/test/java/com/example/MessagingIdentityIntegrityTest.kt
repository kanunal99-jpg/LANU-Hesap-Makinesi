package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.messaging.EncryptedMessagingIdentityStore
import com.example.core.messaging.MessagingIdentity
import com.example.core.messaging.UserCodes
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessagingIdentityIntegrityTest {
    private fun record(identity: MessagingIdentity): String = JSONObject()
        .put("id", identity.id).put("code", identity.code)
        .put("nonce", identity.nonce).put("publicKey", identity.publicKey)
        .put("privateKey", identity.privateKey).toString()

    @Test fun `matching key pair survives encrypted store roundtrip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-identity-integrity-" + System.nanoTime()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val valid = UserCodes.create()
            assertTrue(UserCodes.isConsistentIdentity(valid))
            val first = EncryptedMessagingIdentityStore(context, prefs)
            first.save(valid)
            assertEquals(valid, EncryptedMessagingIdentityStore(context, prefs).read())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun `mismatched private key is rejected on read without erasing recoverable identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-identity-corrupt-" + System.nanoTime()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val first = UserCodes.create()
            val another = UserCodes.create()
            val damaged = first.copy(privateKey = another.privateKey)
            assertEquals(first.id, damaged.id)
            assertEquals(first.code, damaged.code)
            assertFalse(UserCodes.isConsistentIdentity(damaged))
            val raw = record(damaged)
            assertTrue(prefs.edit().putString("identity", raw).commit())
            try {
                EncryptedMessagingIdentityStore(context, prefs).read()
                fail("A mismatched key pair must fail closed")
            } catch (_: IllegalArgumentException) { }
            assertEquals("Corrupted record must remain intact for explicit recovery",
                raw, prefs.getString("identity", null))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun `invalid private key or altered account code cannot overwrite a valid identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-identity-no-overwrite-" + System.nanoTime()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val existing = UserCodes.create()
            val store = EncryptedMessagingIdentityStore(context, prefs)
            store.save(existing)
            val previouslySaved = prefs.getString("identity", null)
            for (invalid in listOf(
                existing.copy(privateKey = "not-a-private-key"),
                existing.copy(privateKey = UserCodes.create().privateKey),
                existing.copy(code = UserCodes.create().code)
            )) {
                try {
                    store.save(invalid)
                    fail("An invalid identity must not overwrite a recoverable one")
                } catch (_: IllegalArgumentException) { }
                assertEquals(previouslySaved, prefs.getString("identity", null))
                assertEquals(existing, store.read())
            }
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
