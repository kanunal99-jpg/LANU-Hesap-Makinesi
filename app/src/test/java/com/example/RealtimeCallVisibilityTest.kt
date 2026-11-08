package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.messaging.*
import com.example.core.repository.CommunicationRepository
import com.example.features.communication.NativeCallViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RealtimeCallVisibilityTest {
    @Test fun callAuthorizationAloneDoesNotOpenSocketAndVisibilityExitCancelsSubscription() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        var own = UserCodes.create()
        val subscribers = AtomicInteger()
        val relay = object : MessagingRelay {
            override val configured = true
            override val realtimeAvailable = true
            override fun realtimeEvents(identity: MessagingIdentity) = callbackFlow<String> {
                subscribers.incrementAndGet()
                awaitClose { subscribers.decrementAndGet() }
            }
            override suspend fun request(identity: MessagingIdentity, action: String, data: JSONObject): JSONObject = when (action) {
                "register" -> JSONObject().put("code", own.code).put("nonce", own.nonce)
                "sync" -> JSONObject().put("contacts", JSONArray()).put("events", JSONArray()).put("calls", JSONArray())
                "callSync" -> JSONObject().put("version", 1).put("signals", JSONArray())
                else -> error("Unexpected test action")
            }
        }
        val vault = object : MessagingIdentityStore {
            override fun read() = own
            override fun save(identity: MessagingIdentity) { own = identity }
        }
        val repo = CommunicationRepository(db.contactDao(), db.conversationDao(), db.messageDao(), db.settingsDao(),
            db.userProfileDao(), db.callLogDao(), vault, relay, db)
        val vm = NativeCallViewModel(context, repo)
        suspend fun count(expected: Int) = withTimeout(3000L) { while (subscribers.get() != expected) delay(10L) }
        try {
            vm.activate(); vm.setRealtimeVisible(false)
            assertEquals(0, subscribers.get())
            vm.setRealtimeVisible(true); count(1)
            vm.setRealtimeVisible(false); count(0)
            vm.setRealtimeVisible(true); count(1)
            vm.deactivate(); count(0)
        } finally { vm.deactivate(); db.close(); Dispatchers.resetMain() }
    }
}
