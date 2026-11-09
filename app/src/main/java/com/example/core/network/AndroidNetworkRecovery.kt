package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Cold, foreground-collected recovery hints; never an ONLINE/server-success claim. */
internal object AndroidNetworkRecovery {
    fun changes(context: Context): Flow<Unit> = callbackFlow {
        val manager = try {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: RuntimeException) { null }
        if (manager == null) { close(); return@callbackFlow }
        val lock = Any()
        val validated = mutableSetOf<Network>()
        var closed = false
        var currentDefault: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A default callback stops tracking the previous default;
                // availability alone still never means validated internet.
                if (Build.VERSION.SDK_INT >= 24) synchronized(lock) {
                    if (!closed) { currentDefault = network; validated.clear() }
                }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val changed = synchronized(lock) {
                    if (closed || (Build.VERSION.SDK_INT >= 24 && currentDefault != network)) false
                    else if (usable) {
                        val added = validated.add(network)
                        // API23 can report several available networks. Keep the
                        // hint state bounded even under pathological callbacks.
                        if (validated.size > 16) validated.remove(validated.first())
                        added
                    }
                    else validated.remove(network) && validated.isNotEmpty()
                }
                if (changed) trySend(Unit)
            }
            override fun onLost(network: Network) {
                val remainingRoute = synchronized(lock) {
                    if (closed) false else {
                        if (currentDefault == network) currentDefault = null
                        validated.remove(network) && validated.isNotEmpty()
                    }
                }
                if (remainingRoute) trySend(Unit)
            }
        }
        var attempted = false
        try {
            attempted = true
            if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
            else manager.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        } catch (_: RuntimeException) {
            // Permission/vendor/quota failures retain the independent timer.
            close()
        }
        awaitClose {
            synchronized(lock) { closed = true; currentDefault = null; validated.clear() }
            if (attempted) try { manager.unregisterNetworkCallback(callback) }
                catch (_: RuntimeException) { /* No identifiers or error text logged. */ }
        }
    }.buffer(Channel.CONFLATED)
}
