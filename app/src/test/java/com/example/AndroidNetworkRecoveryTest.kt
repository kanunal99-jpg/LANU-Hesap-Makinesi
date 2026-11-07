package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.AndroidNetworkRecovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 24, 34])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidNetworkRecoveryTest {
    private fun capabilities(vararg values: Int): NetworkCapabilities = NetworkCapabilities().also { result ->
        values.forEach { shadowOf(result).addCapability(it) }
    }

    @Test fun coldOwnerValidatedOnlyDedupeAndCancellationUnregisterOnOldAndNewAndroid() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val before = shadowOf(manager).networkCallbacks.toSet()
        val changes = AndroidNetworkRecovery.changes(context)
        assertEquals(before, shadowOf(manager).networkCallbacks.toSet())
        var events = 0
        val job = launch { changes.collect { events++ } }
        runCurrent()
        val callback = (shadowOf(manager).networkCallbacks.toSet() - before).single()
        val network = ShadowNetwork.newInstance(701)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        runCurrent(); assertEquals(0, events)
        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        runCurrent(); assertEquals(0, events)
        val validated = capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(network, validated)
        runCurrent(); assertEquals(1, events)
        repeat(100) { callback.onCapabilitiesChanged(network, validated) }
        runCurrent(); assertEquals(1, events)
        callback.onLost(network)
        callback.onCapabilitiesChanged(network, validated)
        runCurrent(); assertEquals(2, events)
        job.cancelAndJoin()
        assertEquals(before, shadowOf(manager).networkCallbacks.toSet())
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(702), validated)
        runCurrent(); assertEquals(2, events)
    }

    @Test fun unavailableNetworkServiceEndsHintsWithoutCrashingTimerFallbackOwner() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unavailable = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = throw SecurityException("unavailable")
        }
        var events = 0
        val job = launch { AndroidNetworkRecovery.changes(unavailable).collect { events++ } }
        runCurrent(); job.join()
        assertTrue(job.isCompleted)
        assertEquals(0, events)
    }
}
