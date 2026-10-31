package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.messaging.MessagingIdentity
import com.example.core.messaging.MessagingIdentityStore
import com.example.core.messaging.MessagingRelay
import com.example.core.messaging.UserCodes
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #224: real repository-to-signed-relay wiring, with no account or Room erasure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDeletionPreflightFlowTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).build()
    }
    @After fun tearDown() { db.close() }

    private fun repository(owner: MessagingIdentity?, relay: MessagingRelay): CommunicationRepository {
        val vault = object : MessagingIdentityStore {
            override fun read(): MessagingIdentity? = owner
            override fun save(identity: MessagingIdentity): Unit =
                error("Read-only preflight must never generate or replace identity")
        }
        return CommunicationRepository(
            db.contactDao(), db.conversationDao(), db.messageDao(), db.settingsDao(),
            db.userProfileDao(), db.callLogDao(), vault, relay, db
        )
    }

    @Test fun `private preflight signs existing owner and preserves Room and identity`() = runBlocking {
        val owner = UserCodes.create()
        var response = JSONObject().put("storageReady", true).put("deletionEnabled", false)
        var requests = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                requests++
                assertEquals(owner, identity)
                assertEquals("checkAccountDeletion", action)
                assertEquals(1, data.length())
                assertEquals(owner.code, data.getString("expectedCode"))
                return response
            }
        }
        val target = repository(owner, relay)
        assertTrue(target.checkAccountDeletionPreflight().storageReady)
        response = JSONObject().put("storageReady", false).put("deletionEnabled", false)
        assertFalse(target.checkAccountDeletionPreflight().storageReady)
        assertEquals(2, requests)
        assertEquals(0, db.messageDao().countMessages())
        assertNull(db.userProfileDao().getUserProfile())
        response = JSONObject().put("storageReady", "true")
        try {
            target.checkAccountDeletionPreflight()
            fail("Untrusted string result must fail closed")
        } catch (_: IllegalStateException) { }
        response = JSONObject().put("storageReady", true).put("deletionEnabled", false).put("deleted", true)
        try {
            target.checkAccountDeletionPreflight()
            fail("Unrequested deletion receipt must not be accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(0, db.messageDao().countMessages())
    }

    @Test fun `missing identity never creates a new owner or sends a deletion request`() = runBlocking {
        var requests = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                requests++
                return JSONObject().put("storageReady", true)
            }
        }
        try {
            repository(null, relay).checkAccountDeletionPreflight()
            fail("Missing owner must fail closed")
        } catch (_: IllegalStateException) { }
        assertEquals(0, requests)
        assertNull(db.userProfileDao().getUserProfile())
    }

    @Test fun `unconfigured production relay cannot run preflight or touch identity`() = runBlocking {
        val relay = object : MessagingRelay {
            override val configured = false
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject =
                error("Unconfigured relay must not be called")
        }
        try {
            repository(null, relay).checkAccountDeletionPreflight()
            fail("Disabled relay must fail closed")
        } catch (_: IllegalStateException) { }
    }
    @Test fun `staged erasure requires fresh capability and preserves local data`() = runBlocking {
        val owner = UserCodes.create()
        var preflightEnabled = false
        var deleteCalls = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                assertEquals(owner, identity)
                return when (action) {
                    "checkAccountDeletion" -> {
                        assertEquals(owner.code, data.getString("expectedCode"))
                        JSONObject().put("storageReady", true).put("deletionEnabled", preflightEnabled)
                    }
                    "deleteAccount" -> {
                        deleteCalls++
                        assertEquals(2, data.length())
                        assertEquals("DELETE_ACCOUNT", data.getString("confirmation"))
                        assertEquals(owner.code, data.getString("expectedCode"))
                        JSONObject().put("deleted", true)
                    }
                    else -> error("Unexpected server operation")
                }
            }
        }
        val target = repository(owner, relay)
        try {
            target.requestSignedAccountErasure(owner.code, confirmed = true)
            fail("Inactive server feature must not erase anyone")
        } catch (_: IllegalStateException) {}
        assertEquals(0, deleteCalls)
        preflightEnabled = true
        assertTrue(target.requestSignedAccountErasure(owner.code, confirmed = true))
        assertEquals(1, deleteCalls)
        assertEquals("complete", db.settingsDao().getSettingValue("lanu_server_erasure_state"))
        assertEquals(0, db.messageDao().countMessages())
        try {
            target.synchronize()
            fail("Sync must never resurrect erased remote identity")
        } catch (_: IllegalStateException) {}
        assertEquals(1, deleteCalls)
    }

    @Test fun `ambiguous network result persists anti resurrection state without retry`() = runBlocking {
        val owner = UserCodes.create()
        var deleteCalls = 0
        val relay = object : MessagingRelay {
            override val configured = true
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                return when (action) {
                    "checkAccountDeletion" ->
                        JSONObject().put("storageReady", true).put("deletionEnabled", true)
                    "deleteAccount" -> {
                        deleteCalls++
                        throw java.io.IOException("synthetic response lost after commit")
                    }
                    else -> error("Request after deletion uncertainty")
                }
            }
        }
        val target = repository(owner, relay)
        try {
            target.requestSignedAccountErasure(owner.code, confirmed = true)
            fail("Ambiguous result cannot be reported as success")
        } catch (_: java.io.IOException) {}
        assertEquals("pending", db.settingsDao().getSettingValue("lanu_server_erasure_state"))
        assertEquals(1, deleteCalls)
        try {
            target.requestSignedAccountErasure(owner.code, confirmed = true)
            fail("Potentially committed deletion must not be replayed")
        } catch (_: IllegalStateException) {}
        try {
            target.synchronize()
            fail("Account registration after uncertain delete must be blocked")
        } catch (_: IllegalStateException) {}
        assertEquals(1, deleteCalls)
        assertEquals(0, db.messageDao().countMessages())
    }

}
