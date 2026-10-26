@file:UseSerializers(UUIDSerializer::class)

package com.github.damontecres.wholphin.services

import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * A WeaselPlex tour offer owed to the person who just signed in with the QR code or the pairing code
 * (guided-walkthrough plan 4.1). One sign-in = one [generation].
 */
@Serializable
data class OwedOffer(
    val generation: String,
    val serverId: UUID,
    val userId: UUID,
    val at: Long,
) {
    fun matches(current: CurrentUser): Boolean = serverId == current.server.id && userId == current.user.id
}

/**
 * @param ready the startup read of a saved offer has finished (or failed); until then nothing is shown and update prompts
 * held by a restored latch wait
 */
data class WalkthroughState(
    val ready: Boolean = false,
    val owed: OwedOffer? = null,
)

/**
 * The WeaselPlex tour (guided-walkthrough plan 4.1): the offer owed after a QR / pairing-code sign-in, its persistence
 * across a restart, and the live menu step's rail focus.
 *
 * Every change runs under a short, non-suspending lock and only signals the single serial writer, so callers stay
 * synchronous. The writer runs after the startup read, checks the revision inside the DataStore transform (an older save
 * never commits after a newer one) and removes the key for "nothing owed".
 */
@Singleton
class WalkthroughService
    @Inject
    constructor(
        private val keyValueService: KeyValueService,
        private val serverRepository: ServerRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private val signal = Channel<Unit>(Channel.CONFLATED)

        private val _state = MutableStateFlow(WalkthroughState())
        val state: StateFlow<WalkthroughState> = _state.asStateFlow()

        /** Bumped by every mutation; guarded by [lock] */
        private var revision = 0L

        /** The latest non-null signed-in server/user; guarded by [lock] */
        private var lastIdentity: Pair<UUID, UUID>? = null

        init {
            scope.launch {
                serverRepository.current.collect { current ->
                    // null (startup, the chooser) never clears: a restart can still deliver the offer
                    current ?: return@collect
                    synchronized(lock) {
                        lastIdentity = current.server.id to current.user.id
                        val owed = _state.value.owed
                        if (owed != null && !owed.matches(current)) {
                            Timber.i("Tour offer dropped: another profile is signed in")
                            mutateLocked(null)
                        }
                    }
                }
            }
            scope.launch {
                val disk =
                    runCatching { keyValueService.get<OwedOffer>(KEY).first() }
                        .onFailure { Timber.w(it, "Could not read the saved tour offer") }
                        .getOrNull()
                synchronized(lock) {
                    if (revision == 0L) {
                        val identity = lastIdentity
                        val keep = disk?.takeIf { identity == null || (it.serverId to it.userId) == identity }
                        if (disk != null && keep == null) {
                            // Saved for another profile: drop it and have the writer remove it
                            revision++
                            signal.trySend(Unit)
                        }
                        _state.update { it.copy(ready = true, owed = keep) }
                    } else {
                        _state.update { it.copy(ready = true) }
                    }
                }
                writeLoop()
            }
        }

        /** A QR / pairing-code sign-in that is entering the app. Synchronous and IO-free; never throws. */
        fun offerAfterSignIn(current: CurrentUser) {
            try {
                synchronized(lock) {
                    mutateLocked(
                        OwedOffer(
                            generation = UUID.randomUUID().toString(),
                            serverId = current.server.id,
                            userId = current.user.id,
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
                Timber.i("Tour offered after sign-in")
            } catch (ex: Exception) {
                Timber.w(ex, "Could not owe the tour offer")
            }
        }

        /** Clears the owed offer only if it is still [generation] */
        fun clear(generation: String) {
            synchronized(lock) {
                if (_state.value.owed?.generation == generation) mutateLocked(null)
            }
        }

        /** The app left its signed-in content (profile list, server list, Loading): nothing is owed any more */
        fun onLeftAppContent() {
            synchronized(lock) { mutateLocked(null) }
        }

        /**
         * The offer may be shown now: still owed, for [current], at most 60 minutes old and the person hasn't chosen
         * "Don't ask again" on this TV. Clears it otherwise.
         */
        suspend fun canLatch(
            offer: OwedOffer,
            current: CurrentUser,
        ): Boolean {
            if (state.value.owed?.generation != offer.generation) return false
            val fresh = System.currentTimeMillis() - offer.at in 0..OFFER_LIFETIME_MS
            val optedOut =
                runCatching { keyValueService.get(offer.userId, OPT_OUT_KEY, false).first() }
                    .onFailure { Timber.w(it, "Could not read the tour opt-out") }
                    .getOrDefault(false)
            if (!offer.matches(current) || !fresh || optedOut) {
                Timber.i("Tour offer not shown: matches=%s, fresh=%s, optedOut=%s", offer.matches(current), fresh, optedOut)
                clear(offer.generation)
                return false
            }
            Timber.i("Tour offer latched")
            return true
        }

        /** "Don't ask again" for [userId] on this TV; Settings → Help still replays the tour */
        fun optOut(userId: UUID) {
            scope.launch {
                runCatching { keyValueService.save(userId, OPT_OUT_KEY, true) }
                    .onFailure { Timber.w(it, "Could not save the tour opt-out") }
            }
        }

        // ---- Card 2's live menu step (plan 4.4) ----

        @Volatile
        private var liveMenuStepActive = false

        private val _railFocus = MutableStateFlow<String?>(null)

        /** The rail row that has focus while the live menu step is up; only the tour page reads it */
        val railFocus: StateFlow<String?> = _railFocus.asStateFlow()

        fun setLiveMenuStepActive(active: Boolean) {
            liveMenuStepActive = active
            if (!active) _railFocus.value = null
        }

        fun reportRailFocus(
            id: String,
            hasFocus: Boolean,
        ) {
            if (!liveMenuStepActive) return
            if (hasFocus) {
                _railFocus.value = id
            } else {
                _railFocus.compareAndSet(id, null)
            }
        }

        /** Must hold [lock]. Publishes with copy so [WalkthroughState.ready] never drops back. */
        private fun mutateLocked(owed: OwedOffer?) {
            revision++
            _state.update { it.copy(owed = owed) }
            signal.trySend(Unit)
        }

        private suspend fun writeLoop() {
            val key = stringPreferencesKey(KEY)
            var retried = false
            for (unit in signal) {
                val (rev, owed) = synchronized(lock) { revision to _state.value.owed }
                try {
                    keyValueService.dataStore.updateData { prefs ->
                        if (synchronized(lock) { revision } != rev) {
                            // A newer change is waiting: the next pass writes it
                            prefs
                        } else {
                            prefs.toMutablePreferences().apply {
                                if (owed == null) {
                                    remove(key)
                                } else {
                                    set(key, keyValueService.json.encodeToString(OwedOffer.serializer(), owed))
                                }
                            }
                        }
                    }
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                    Timber.w(ex, "Could not save the tour offer")
                    if (!retried) {
                        retried = true
                        signal.trySend(Unit)
                        continue
                    }
                }
                retried = false
            }
        }

        companion object {
            private const val KEY = "walkthrough_owed"
            private const val OPT_OUT_KEY = "walkthrough_opt_out"
            private val OFFER_LIFETIME_MS = 60.minutes.inWholeMilliseconds
        }
    }
