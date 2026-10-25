package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackgroundIdentityPreservationTest {
    @Test fun backgroundCannotGenerateAnIdentityEvenOnAnEmptyDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            var saves = 0
            var requests = 0
            val repository = CommunicationRepository(db.contactDao(), db.conversationDao(), db.messageDao(),
                db.settingsDao(), db.userProfileDao(), db.callLogDao(), object : MessagingIdentityStore {
                    override fun read(): MessagingIdentity? = null
                    override fun save(identity: MessagingIdentity) { saves++ }
                }, object : MessagingRelay {
                    override val configured = true
                    override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject {
                        requests++
                        error("Must never reach the network")
                    }
                }, db)
            assertFalse(repository.hasStoredIdentity())
            try { repository.synchronize(requireExistingIdentity = true); fail("A missing identity must be refused") }
            catch (_: IllegalStateException) { }
            assertEquals(0, saves)
            assertEquals(0, requests)
            assertNull(repository.identity.value)
            assertNull(db.userProfileDao().getUserProfile())
        } finally { db.close() }
    }
}
