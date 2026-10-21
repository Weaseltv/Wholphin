package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.isIdentity
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.hilt.DefaultCoroutineScope
import com.github.damontecres.wholphin.services.hilt.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.AuthenticationResult
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A switch refused by the gate; [decision] says what the chooser has to ask for */
class ProfileGateException(
    val decision: GateDecision,
) : IllegalStateException("Profile switch needs $decision")

/**
 * Track C's [KidSessionGate] (plan 4.1, 4.2): the single switch gate, [ParentUnlock], PIN backoff and kid
 * sign-in. Everything is a no-op unless [BuildConfig.KID_PROFILES_ENABLED] and the device is protected.
 */
@Singleton
class KidSessionGateImpl
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val serverDao: JellyfinServerDao,
        private val store: KidProtectionStore,
        @param:DefaultCoroutineScope private val scope: CoroutineScope,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : KidSessionGate {
        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        private val pinMutex = Mutex()

        /** Guards granting and clearing the unlock, so clearing one particular unlock never takes a newer one */
        private val unlockLock = Any()
        private var unlockExpiryJob: Job? = null

        override val isKidSession: StateFlow<Boolean> =
            DerivedStateFlow(serverRepository.current) { current ->
                BuildConfig.KID_PROFILES_ENABLED && current != null && current.user.profileKind != ProfileKind.ADULT
            }

        override val isDeviceProtected: Flow<Boolean> =
            store.isProtected.map { it && BuildConfig.KID_PROFILES_ENABLED }

        private val _parentUnlock = MutableStateFlow<ParentUnlock?>(null)
        override val parentUnlock: StateFlow<ParentUnlock?> = _parentUnlock

        override fun profileKind(user: JellyfinUser): ProfileKind = user.profileKind

        override fun ceilingOf(user: JellyfinUser): ProfileCeiling = user.profileCeiling

        /** The [ParentUnlock] if it is still within [UNLOCK_DURATION] */
        fun validUnlock(): ParentUnlock? {
            val unlock = _parentUnlock.value ?: return null
            if (clock() - unlock.atMillis >= UNLOCK_DURATION.inWholeMilliseconds) {
                _parentUnlock.compareAndSet(unlock, null)
                return null
            }
            return unlock
        }

        override fun validUnlockFor(
            parentRowId: Int,
            purpose: UnlockPurpose,
        ): ParentUnlock? = validUnlock()?.takeIf { it.userRowId == parentRowId && it.purpose == purpose }

        override suspend fun evaluate(target: JellyfinUser): GateDecision = evaluate(target) {}

        /** [evaluate], telling [onUnlock] about the unlock for this switch it saw */
        private suspend fun evaluate(
            target: JellyfinUser,
            onUnlock: (ParentUnlock) -> Unit,
        ): GateDecision {
            if (!BuildConfig.KID_PROFILES_ENABLED || !store.isProtected()) {
                // Never admitted a kid, or protection explicitly turned off
                return GateDecision.Allow
            }
            // Only an unlock granted for switching to this very profile counts
            val unlock = validUnlock()?.takeIf { it.purpose == UnlockPurpose.Switch(target.rowId) }?.also(onUnlock)
            if (target.profileKind == ProfileKind.ADULT && !target.hasPin) {
                // A pinless adult is locked on a protected device, whatever the ceilings say. Its own
                // (impossible) unlock doesn't count; another adult's does.
                return if (unlock != null && unlock.userRowId != target.rowId) GateDecision.Allow else GateDecision.LockedAdult
            }
            if (target.profileCeiling.isAtMost(store.sessionCeiling())) {
                return GateDecision.Allow
            }
            return if (unlock != null) GateDecision.Allow else GateDecision.NeedsParentUnlock
        }

        override suspend fun verifyParentPin(
            user: JellyfinUser,
            pin: String,
            purpose: UnlockPurpose?,
        ): PinCheckResult = checkPin(user, pin, requireAdult = true, purpose = purpose)

        /**
         * Checks the PIN of any saved profile with the same device-wide backoff. Only an adult's correct PIN
         * grants a [ParentUnlock]; a kid's opens just their own profile.
         */
        suspend fun verifyProfilePin(
            user: JellyfinUser,
            pin: String,
        ): PinCheckResult = checkPin(user, pin, requireAdult = false, purpose = null)

        private suspend fun checkPin(
            user: JellyfinUser,
            pin: String,
            requireAdult: Boolean,
            purpose: UnlockPurpose?,
        ): PinCheckResult =
            pinMutex.withLock {
                val now = clock()
                val backoff = store.pinBackoff()
                if (backoff.lockedUntilMillis > now) {
                    return@withLock PinCheckResult.LockedOut(backoff.lockedUntilMillis)
                }
                // Compare against the saved row, never a possibly stale copy held by a screen
                val row = withContext(ioDispatcher) { serverDao.getUser(user.serverId, user.id) } ?: user
                val isAdult = row.profileKind == ProfileKind.ADULT
                val correct = row.hasPin && row.pin == pin && (isAdult || !requireAdult)
                if (correct) {
                    if (isAdult) {
                        // Only a verified adult PIN clears the device-wide guess counter; a kid opening their
                        // own profile must not wipe the failed parent guesses before it
                        store.setPinBackoff(PinBackoff())
                        grantUnlock(row, purpose)
                    }
                    PinCheckResult.Correct
                } else {
                    val failures = backoff.failures + 1
                    if (failures >= MAX_ATTEMPTS) {
                        val lockouts = backoff.lockouts + 1
                        val lockedUntil = now + lockoutDuration(lockouts)
                        store.setPinBackoff(PinBackoff(0, lockouts, lockedUntil))
                        PinCheckResult.LockedOut(lockedUntil)
                    } else {
                        store.setPinBackoff(backoff.copy(failures = failures))
                        PinCheckResult.Wrong(MAX_ATTEMPTS - failures)
                    }
                }
            }

        override fun clearParentUnlock() {
            synchronized(unlockLock) {
                unlockExpiryJob?.cancel()
                unlockExpiryJob = null
                _parentUnlock.value = null
            }
        }

        /**
         * Clears the unlock only if it is [unlock] itself (the same grant), so an operation spends just the
         * authorization it owns, never one granted meanwhile for a newer selection (review round 3)
         */
        fun clearParentUnlock(unlock: ParentUnlock) {
            synchronized(unlockLock) {
                if (_parentUnlock.value === unlock) clearParentUnlock()
            }
        }

        /** Clears the unlock only if it was granted for [purpose] */
        fun clearParentUnlock(purpose: UnlockPurpose) {
            synchronized(unlockLock) {
                if (_parentUnlock.value?.purpose == purpose) clearParentUnlock()
            }
        }

        private fun grantUnlock(
            user: JellyfinUser,
            purpose: UnlockPurpose?,
        ) {
            val unlock = ParentUnlock(user.rowId, clock(), purpose)
            synchronized(unlockLock) {
                _parentUnlock.value = unlock
                unlockExpiryJob?.cancel()
                unlockExpiryJob =
                    scope.launch {
                        delay(UNLOCK_DURATION)
                        _parentUnlock.compareAndSet(unlock, null)
                    }
            }
        }

        /**
         * A completed adult Quick Connect sign-in counts as parental authorization (plan 4.2). Also used for a
         * first PIN created in an adult's own session, but only while the device is not protected (plan 4.1).
         */
        suspend fun grantUnlockForFreshAuth(user: JellyfinUser) {
            if (BuildConfig.KID_PROFILES_ENABLED && user.profileKind == ProfileKind.ADULT) {
                pinMutex.withLock { store.setPinBackoff(PinBackoff()) }
                grantUnlock(user, null)
            }
        }

        suspend fun grantUnlockForFirstPin(user: JellyfinUser) {
            if (BuildConfig.KID_PROFILES_ENABLED && user.profileKind == ProfileKind.ADULT && !store.isProtected()) {
                grantUnlock(user, null)
            }
        }

        override suspend fun signInKid(
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            displayName: String?,
        ): CurrentUser {
            // The unlock that started this sign-in; only that same unexpired add-kid unlock may publish it
            val unlock = validUnlock()
            if (BuildConfig.KID_PROFILES_ENABLED && unlock?.purpose != UnlockPurpose.AddKid) {
                // Only the add-kid flow, under a parent's unexpired unlock, signs a kid in
                throw ProfileGateException(GateDecision.NeedsParentUnlock)
            }
            // changeUser persists kid protection and the kid's ceiling before publishing (plan 4.2)
            val current =
                serverRepository.changeUser(
                    serverUrl = serverUrl,
                    authenticationResult = authenticationResult,
                    existingUser = null,
                    displayName = displayName,
                    beforePublish = { user ->
                        if (user.profileKind == ProfileKind.ADULT) {
                            throw IllegalStateException("signInKid was given an adult")
                        }
                        requireUnlock(unlock)
                    },
                    // Checked again in the publishing step itself, after the switch was persisted: an unlock that
                    // runs out while it is journalled, saved or dispatched to the main thread still publishes nothing
                    authorize = { requireUnlock(unlock) },
                )
            clearParentUnlock()
            return current
        }

        /**
         * The unlock expired (or was replaced/cleared) since [unlock] started a sign-in: publish nothing, the flow
         * asks for the parent's PIN again (plan 4.1, 4.3). Doesn't suspend, so it can run in the publishing step.
         */
        private fun requireUnlock(unlock: ParentUnlock?) {
            if (BuildConfig.KID_PROFILES_ENABLED && validUnlock() != unlock) {
                throw ProfileGateException(GateDecision.NeedsParentUnlock)
            }
        }

        /**
         * Switches to a saved profile through the gate. Throws [ProfileGateException] when refused, before
         * anything is published. The gate is checked again with the freshly fetched policy, so a level raised
         * on the server since the row was saved can't slip through. The [ParentUnlock] granted for this switch is
         * used up, whether the switch succeeds or fails, so it can't carry over to another profile; an unlock
         * granted meanwhile for another selection is left alone, even when this switch ends late (cancelled).
         */
        suspend fun switchTo(
            server: JellyfinServer,
            user: JellyfinUser,
        ): CurrentUser {
            val owned = mutableSetOf<ParentUnlock>()
            try {
                val decision = evaluate(user) { owned += it }
                if (decision != GateDecision.Allow) throw ProfileGateException(decision)
                return serverRepository.changeUser(server, user, beforePublish = { refreshed ->
                    val again = evaluate(refreshed) { owned += it }
                    if (again != GateDecision.Allow) throw ProfileGateException(again)
                })
            } finally {
                owned.forEach { clearParentUnlock(it) }
            }
        }

        /**
         * Gated replacement for [ServerRepository.restoreSession] on the auto-restore paths (cold start, intents).
         * Reads the saved row first and never restores a PIN/login-protected or gated profile.
         *
         * @return the restored session, or null if the profile is unknown, protected or gated
         */
        suspend fun restoreGated(
            serverId: UUID?,
            userId: UUID?,
        ): CurrentUser? {
            if (serverId == null || userId == null) return null
            if (serverRepository.current.value.isIdentity(serverId, userId)) {
                // Still this profile's session once the session lock is held: just its credentials again
                serverRepository.restoreCurrentSession(serverId, userId)?.let { return it }
                // Another session was published meanwhile: this profile is restored gated, like any other
            }
            val serverUsers = withContext(ioDispatcher) { serverDao.getServer(serverId) } ?: return null
            val row = serverUsers.users.firstOrNull { it.id == userId } ?: return null
            if (row.isProtected) {
                Timber.i("Not restoring protected profile %s", row.rowId)
                return null
            }
            return try {
                switchTo(serverUsers.server, row)
            } catch (ex: ProfileGateException) {
                Timber.i("Not restoring gated profile %s: %s", row.rowId, ex.decision)
                null
            }
        }

        /**
         * "Turn off kid protection on this TV" (plan 4.2): only behind a [ParentUnlock] and with no kid rows left
         *
         * @return whether protection was turned off
         */
        suspend fun turnOffProtection(): Boolean {
            if (validUnlock()?.purpose != UnlockPurpose.TurnOffProtection) return false
            if (withContext(ioDispatcher) { serverDao.countKidUsers() } > 0) return false
            store.clearProtection()
            clearParentUnlock()
            return true
        }

        suspend fun canTurnOffProtection(): Boolean =
            BuildConfig.KID_PROFILES_ENABLED &&
                store.isProtected() &&
                withContext(ioDispatcher) { serverDao.countKidUsers() } == 0

        companion object {
            val UNLOCK_DURATION = 5.minutes
            const val MAX_ATTEMPTS = 5
            private val FIRST_LOCKOUT = 30.seconds
            private val MAX_LOCKOUT = 1.hours

            /** 30 s for the first lockout, doubling after each, capped at an hour */
            fun lockoutDuration(lockouts: Int): Long {
                val shift = (lockouts - 1).coerceIn(0, 20)
                return (FIRST_LOCKOUT.inWholeMilliseconds shl shift).coerceAtMost(MAX_LOCKOUT.inWholeMilliseconds)
            }
        }
    }

/**
 * A [StateFlow] computed from [source] on read, so it never lags the session it describes (a Seerr connect
 * triggered by the new session must already see it as a kid).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KidSessionGateModule {
    @Binds
    abstract fun kidSessionGate(impl: KidSessionGateImpl): KidSessionGate
}
