package com.github.damontecres.wholphin.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the TV has a network (WeaselFin, owner 2026-10-25). Waking the Shield from sleep starts the app again before
 * its Wi-Fi or Ethernet is back, so the first requests failed and the page stayed on an error until the app was
 * reopened. Pages that load on start wait for a network first, and load again when it comes back after a failure.
 */
@Singleton
class NetworkAvailability
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        /** Whether the TV has a default network now; true when that can't be told */
        fun isConnected(): Boolean {
            val manager = connectivity ?: return true
            return try {
                val network = manager.activeNetwork ?: return false
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (ex: SecurityException) {
                Timber.w(ex, "Can't read the network state")
                true
            }
        }

        /** [isConnected] now and on every change of the default network */
        val connected: Flow<Boolean> =
            callbackFlow {
                val manager = connectivity
                if (manager == null) {
                    trySend(true)
                    awaitClose {}
                    return@callbackFlow
                }
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(true)
                        }

                        override fun onLost(network: Network) {
                            trySend(false)
                        }
                    }
                trySend(isConnected())
                val registered =
                    try {
                        manager.registerDefaultNetworkCallback(callback)
                        true
                    } catch (ex: SecurityException) {
                        Timber.w(ex, "Can't follow the network state")
                        trySend(true)
                        false
                    }
                awaitClose { if (registered) manager.unregisterNetworkCallback(callback) }
            }.conflate().distinctUntilChanged()

        /** Waits up to [timeout] for a network; whether there is one */
        suspend fun awaitConnected(timeout: Duration = WAKE_WAIT): Boolean =
            isConnected() || withTimeoutOrNull(timeout) { connected.first { it } } ?: false

        /** Returns once the network is back after being lost, now or later: for a page whose load failed */
        suspend fun awaitReconnect() {
            connected.dropWhile { it }.first { it }
        }

        /**
         * [block] once a network is there (up to [WAKE_WAIT]), tried again a few times while it fails for want of a
         * network: right after waking, the network can be up a moment before the server can be reached.
         */
        suspend fun <T> whenConnected(block: suspend () -> T): T {
            awaitConnected()
            var attempt = 1
            while (true) {
                try {
                    return block()
                } catch (ex: Exception) {
                    if (attempt >= ATTEMPTS || !ex.isNetworkFailure()) throw ex
                    Timber.i("Network request failed (attempt %d), trying again: %s", attempt, ex.localizedMessage)
                }
                delay(RETRY_DELAY * attempt)
                attempt++
                awaitConnected()
            }
        }

        companion object {
            /** How long a page waits for the network on start; the Shield's Wi-Fi is usually back within a few seconds */
            val WAKE_WAIT = 15.seconds
            private const val ATTEMPTS = 3
            private val RETRY_DELAY = 2.seconds
        }
    }

/** Whether [this] failed for want of a network (no route, DNS, a timeout) rather than an answer from the server */
fun Throwable.isNetworkFailure(): Boolean {
    for (cause in generateSequence(this) { it.cause }.take(8)) {
        if (cause is InvalidStatusException) return false
        if (cause is IOException || cause is TimeoutException) return true
    }
    return false
}
