package com.github.damontecres.wholphin.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
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

        private val _connected = MutableStateFlow(readConnected())

        /** Whether the TV has a default network, following every change; true when that can't be told */
        val connected: StateFlow<Boolean> = _connected

        /** How many times the network came back after being lost (review round 1: a load compares it, [mark]) */
        private val comebacks = MutableStateFlow(0L)

        init {
            // Followed for the app's lifetime (a singleton), so a comeback is never missed between a load and its error
            try {
                connectivity?.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (!_connected.getAndUpdate { true }) comebacks.update { it + 1 }
                        }

                        override fun onLost(network: Network) {
                            // A switch to another network can report the old one lost after the new one is available
                            val active = runCatching { connectivity?.activeNetwork }.getOrNull()
                            _connected.value = active != null && active != network && readConnected()
                        }
                    },
                )
            } catch (ex: Exception) {
                Timber.w(ex, "Can't follow the network state")
                _connected.value = true
            }
        }

        private fun readConnected(): Boolean {
            val manager = connectivity ?: return true
            return try {
                val network = manager.activeNetwork ?: return false
                manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (ex: SecurityException) {
                Timber.w(ex, "Can't read the network state")
                true
            }
        }

        fun isConnected(): Boolean = _connected.value

        /** Waits up to [timeout] for a network; whether there is one */
        suspend fun awaitConnected(timeout: Duration = WAKE_WAIT): Boolean =
            isConnected() || withTimeoutOrNull(timeout) { connected.first { it } } ?: false

        /** Taken when a load starts: [awaitComebackSince] then tells whether the network came back since */
        fun mark(): Long = comebacks.value

        /**
         * Returns once the network has come back since [mark] was taken, at once if it already has (eg while the failed
         * request was still timing out). A server that is down while the network stays up never reloads by itself.
         */
        suspend fun awaitComebackSince(mark: Long) {
            comebacks.first { it > mark }
            connected.first { it }
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
