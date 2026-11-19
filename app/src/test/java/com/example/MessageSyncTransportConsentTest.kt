package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.messaging.*
import com.example.core.network.CancellableHttp
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MessageSyncTransportConsentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun setup() {
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(MessageWakePreferences.beginEnrollment(context)); assertTrue(MessageWakePreferences.finishEnrollment(context))
    }
    @After fun cleanup() {
        MessageWakePreferences.disableAndQueueRemoteRevoke(context)
        context.getSharedPreferences("lanu_message_wake_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun optOutAfterCallConstructionButBeforeSocketEnqueueStartsNoHttp() = runBlocking<Unit> {
        val starts = AtomicInteger()
        val client = OkHttpClient.Builder().eventListenerFactory {
            // Runs while constructing the Call, after the earlier permission check.
            assertTrue(MessageWakePreferences.disableAndQueueRemoteRevoke(context))
            object : EventListener() { override fun callStart(call: Call) { starts.incrementAndGet() } }
        }.build()
        try {
            withContext(MessageSyncSession { MessageWakePreferences.isEnabled(context) }) {
                CancellableHttp.withResponse(Request.Builder().url("http://127.0.0.1:9/").build(), client) {
                    fail("No request or response is permitted"); Unit
                }
            }
            fail("Revocation must propagate")
        } catch (_: MessageSyncRevoked) { }
        finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        assertEquals(0, starts.get())
    }
}
