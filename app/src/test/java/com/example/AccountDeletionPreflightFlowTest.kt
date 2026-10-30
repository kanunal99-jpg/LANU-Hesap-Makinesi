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
        var response = JSONObject().put("storageReady", true)
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
        assertTrue(target.checkAccountDeletionPreflight())
        response = JSONObject().put("storageReady", false)
        assertFalse(target.checkAccountDeletionPreflight())
        assertEquals(2, requests)
        assertEquals(0, db.messageDao().countMessages())
        assertNull(db.userProfileDao().getUserProfile())
        response = JSONObject().put("storageReady", "true")
        try {
            target.checkAccountDeletionPreflight()
            fail("Untrusted string result must fail closed")
        } catch (_: IllegalStateException) { }
        response = JSONObject().put("storageReady", true).put("deleted", true)
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
}
