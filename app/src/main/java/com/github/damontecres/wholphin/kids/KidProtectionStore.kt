package com.github.damontecres.wholphin.kids

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable kid-protection state for this TV (plan 4.2): the `kidProtection` flag, the session
 * ceiling and the PIN attempt backoff. Lives in the key-value DataStore so it survives restarts,
 * `switchServerOrUser` and removing kid rows.
 */
@Singleton
class KidProtectionStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val isProtected: Flow<Boolean> =
            dataStore.data.map { it[KEY_PROTECTION] == true }.distinctUntilChanged()

        suspend fun isProtected(): Boolean = isProtected.first()

        /**
         * The ceiling of the last profile a switch completed to. A missing key, or a switch that began but never
         * completed (process death mid-switch, or before a chooser flow entered what it published), reads as
         * [ProfileCeiling.Unknown], which the gate treats as gated.
         */
        suspend fun sessionCeiling(): ProfileCeiling {
            val prefs = dataStore.data.first()
            if (prefs[KEY_SWITCH_PENDING] == true) return ProfileCeiling.Unknown
            return decodeCeiling(prefs[KEY_SESSION_CEILING])
        }

        /**
         * Journals a switch about to be published: until [completeSwitch] or [abortSwitch] the session ceiling
         * reads as unknown, so an interruption never leaves the destination's (possibly higher) ceiling behind.
         * [protect] sets the protection flag in the same write, before a kid row is admitted.
         *
         * @return the id of this switch; only its owner can complete or abort it
         */
        suspend fun beginSwitch(protect: Boolean): String {
            val switchId = UUID.randomUUID().toString()
            dataStore.edit {
                it[KEY_SWITCH_PENDING] = true
                it[KEY_SWITCH_ID] = switchId
                if (protect) it[KEY_PROTECTION] = true
            }
            return switchId
        }

        /**
         * The switch [switchId] was published: [ceiling], the destination's, is the session ceiling from now on.
         * A stale continuation of a switch that is no longer the journalled one changes nothing.
         *
         * @return whether [switchId] was the journalled switch
         */
        suspend fun completeSwitch(
            switchId: String,
            ceiling: ProfileCeiling,
        ): Boolean {
            var owned = false
            dataStore.edit {
                if (it[KEY_SWITCH_ID] == switchId) {
                    owned = true
                    it[KEY_SESSION_CEILING] = encodeCeiling(ceiling)
                    it.clearJournal()
                }
            }
            return owned
        }

        /**
         * Review round 7: the switch [switchId] published a session that a chooser flow has yet to enter. The journal
         * stays open, so the session ceiling still reads unknown, and records [ceilingBefore] (the session ceiling from
         * before this switch) and [destination], the published profile's: [enterSwitch] applies [destination],
         * [withdrawSwitch] goes back to [ceilingBefore]. Should the process die first, the TV restarts gated.
         *
         * @return whether [switchId] was the journalled switch
         */
        suspend fun holdSwitch(
            switchId: String,
            ceilingBefore: ProfileCeiling,
            destination: ProfileCeiling,
        ): Boolean {
            var owned = false
            dataStore.edit {
                if (it[KEY_SWITCH_ID] == switchId) {
                    owned = true
                    it[KEY_SWITCH_HELD] = true
                    it.putOrRemove(KEY_SWITCH_CEILING_BEFORE, ceilingBefore)
                    it.putOrRemove(KEY_SWITCH_DESTINATION, destination)
                }
            }
            return owned
        }

        /**
         * Review round 7: the session the held switch [switchId] published was entered, so the destination ceiling
         * [holdSwitch] recorded is the session ceiling from now on and the journal closes. A switch that is no longer
         * the journalled one changes nothing.
         *
         * @return whether [switchId] was the journalled switch
         */
        suspend fun enterSwitch(switchId: String): Boolean {
            var owned = false
            dataStore.edit {
                if (it[KEY_SWITCH_ID] == switchId && it[KEY_SWITCH_HELD] == true) {
                    owned = true
                    it.putOrRemove(KEY_SESSION_CEILING, decodeCeiling(it[KEY_SWITCH_DESTINATION]))
                    it.clearJournal()
                }
            }
            return owned
        }

        /**
         * The switch [switchId] failed before anything was published: the previous session ceiling still applies.
         * Never ends a switch other than [switchId].
         *
         * @return whether [switchId] was the journalled switch
         */
        suspend fun abortSwitch(switchId: String): Boolean {
            var owned = false
            dataStore.edit {
                if (it[KEY_SWITCH_ID] == switchId) {
                    owned = true
                    it.clearJournal()
                }
            }
            return owned
        }

        /**
         * A published session was withdrawn before anyone used it (review round 5): the session ceiling goes back to
         * [ceilingBefore], the one from before that session's switch, unless the stored one is lower; it is never
         * raised. Unknown on either side, or ceilings that don't compare, leave it unknown (fail closed).
         *
         * Review round 7: [switchId] is that session's switch if it is still held ([holdSwitch]); its journal closes
         * here, with the lower of [ceilingBefore] and the one it recorded. Any other journalled switch stays open.
         */
        suspend fun withdrawSwitch(
            ceilingBefore: ProfileCeiling,
            switchId: String? = null,
        ) {
            dataStore.edit {
                val held = switchId != null && it[KEY_SWITCH_ID] == switchId && it[KEY_SWITCH_HELD] == true
                // A held switch's open journal is this very session's; the ceiling stored under it is from before it
                val stored =
                    if (it[KEY_SWITCH_PENDING] == true && !held) ProfileCeiling.Unknown else decodeCeiling(it[KEY_SESSION_CEILING])
                var restored = lowerOf(ceilingBefore, stored)
                if (held) {
                    restored = lowerOf(restored, decodeCeiling(it[KEY_SWITCH_CEILING_BEFORE]))
                    it.clearJournal()
                }
                it.putOrRemove(KEY_SESSION_CEILING, restored)
            }
        }

        /** Sets just the protection flag (a kid row is about to be written); a missing ceiling stays unknown */
        suspend fun protect() {
            dataStore.edit { it[KEY_PROTECTION] = true }
        }

        /**
         * The current session's ceiling changed (a policy refresh). Review round 7: while its switch is held
         * ([holdSwitch]), the destination it records changes instead, so entering applies the refreshed ceiling and a
         * withdrawal still goes back to the one from before the switch.
         */
        suspend fun setSessionCeiling(ceiling: ProfileCeiling) {
            dataStore.edit {
                if (it[KEY_SWITCH_HELD] == true) {
                    it.putOrRemove(KEY_SWITCH_DESTINATION, ceiling)
                } else {
                    it[KEY_SESSION_CEILING] = encodeCeiling(ceiling)
                }
            }
        }

        /**
         * Sets the protection flag (first kid admitted). [ceilingIfMissing] initialises the session
         * ceiling only when none was stored yet.
         */
        suspend fun markProtected(ceilingIfMissing: ProfileCeiling) {
            dataStore.edit {
                it[KEY_PROTECTION] = true
                if (it[KEY_SESSION_CEILING] == null) {
                    it[KEY_SESSION_CEILING] = encodeCeiling(ceilingIfMissing)
                }
            }
        }

        /**
         * "Turn off kid protection on this TV": the caller has checked ParentUnlock and that no kid rows remain.
         * [authorize] is asked a last time in the write itself (review round 5); if it says no, nothing changes.
         *
         * @return whether protection was cleared
         */
        suspend fun clearProtection(authorize: () -> Boolean = { true }): Boolean {
            var cleared = false
            dataStore.edit {
                cleared = authorize()
                if (!cleared) return@edit
                it.remove(KEY_PROTECTION)
                it.remove(KEY_SESSION_CEILING)
                it.clearJournal()
            }
            return cleared
        }

        suspend fun pinBackoff(): PinBackoff {
            val prefs = dataStore.data.first()
            return PinBackoff(
                failures = prefs[KEY_PIN_FAILURES] ?: 0,
                lockouts = prefs[KEY_PIN_LOCKOUTS] ?: 0,
                lockedUntilMillis = prefs[KEY_PIN_LOCKED_UNTIL] ?: 0L,
            )
        }

        suspend fun setPinBackoff(backoff: PinBackoff) {
            dataStore.edit {
                it[KEY_PIN_FAILURES] = backoff.failures
                it[KEY_PIN_LOCKOUTS] = backoff.lockouts
                it[KEY_PIN_LOCKED_UNTIL] = backoff.lockedUntilMillis
            }
        }

        companion object {
            private val KEY_PROTECTION = booleanPreferencesKey("kids.protection")
            private val KEY_SESSION_CEILING = stringPreferencesKey("kids.sessionCeiling")
            private val KEY_SWITCH_PENDING = booleanPreferencesKey("kids.switchPending")
            private val KEY_SWITCH_ID = stringPreferencesKey("kids.switchId")

            // Review round 7: a published switch held open until its chooser flow enters it, see [holdSwitch]
            private val KEY_SWITCH_HELD = booleanPreferencesKey("kids.switchHeld")
            private val KEY_SWITCH_CEILING_BEFORE = stringPreferencesKey("kids.switchCeilingBefore")
            private val KEY_SWITCH_DESTINATION = stringPreferencesKey("kids.switchDestination")
            private val KEY_PIN_FAILURES = intPreferencesKey("kids.pin.failures")
            private val KEY_PIN_LOCKOUTS = intPreferencesKey("kids.pin.lockouts")
            private val KEY_PIN_LOCKED_UNTIL = longPreferencesKey("kids.pin.lockedUntil")

            private const val UNLIMITED = "unlimited"

            internal fun encodeCeiling(ceiling: ProfileCeiling): String =
                when (ceiling) {
                    is ProfileCeiling.Rating -> ceiling.maxParentalRating.toString()

                    ProfileCeiling.Unlimited -> UNLIMITED

                    // Never stored on purpose, but reads back as unknown either way
                    ProfileCeiling.Unknown -> "unknown"
                }

            internal fun decodeCeiling(value: String?): ProfileCeiling =
                when {
                    value == null -> ProfileCeiling.Unknown
                    value == UNLIMITED -> ProfileCeiling.Unlimited
                    else -> value.toIntOrNull()?.let { ProfileCeiling.Rating(it) } ?: ProfileCeiling.Unknown
                }

            /** The lower of [a] and [b]; unknown if either is, or they don't compare (fail closed) */
            private fun lowerOf(
                a: ProfileCeiling,
                b: ProfileCeiling,
            ): ProfileCeiling =
                when {
                    a.isAtMost(b) -> a
                    b.isAtMost(a) -> b
                    else -> ProfileCeiling.Unknown
                }

            /** Stores [ceiling] under [key]; unknown is never stored, it is the missing key */
            private fun MutablePreferences.putOrRemove(
                key: Preferences.Key<String>,
                ceiling: ProfileCeiling,
            ) {
                if (ceiling == ProfileCeiling.Unknown) remove(key) else set(key, encodeCeiling(ceiling))
            }

            private fun MutablePreferences.clearJournal() {
                remove(KEY_SWITCH_PENDING)
                remove(KEY_SWITCH_ID)
                remove(KEY_SWITCH_HELD)
                remove(KEY_SWITCH_CEILING_BEFORE)
                remove(KEY_SWITCH_DESTINATION)
            }
        }
    }

/** Device-wide PIN attempt state: [failures] since the last lockout, [lockouts] so far */
data class PinBackoff(
    val failures: Int = 0,
    val lockouts: Int = 0,
    val lockedUntilMillis: Long = 0L,
)

/** Kid classification of a saved profile from its Room columns (contract section 1) */
val JellyfinUser.profileKind: ProfileKind
    get() =
        when (policyState) {
            PolicyState.ADULT -> ProfileKind.ADULT
            PolicyState.KID -> ProfileKind.KID
            PolicyState.UNKNOWN -> ProfileKind.UNKNOWN
        }

/** A profile's ceiling: its rating for kids, unlimited for adults, unknown otherwise */
val JellyfinUser.profileCeiling: ProfileCeiling
    get() =
        when (policyState) {
            PolicyState.ADULT -> ProfileCeiling.Unlimited
            PolicyState.KID -> maxParentalRating?.let { ProfileCeiling.Rating(it) } ?: ProfileCeiling.Unknown
            PolicyState.UNKNOWN -> ProfileCeiling.Unknown
        }

/**
 * Whether [this] ceiling allows at most what [other] allows. Unknown on either side is never
 * "at most" (fail closed).
 */
fun ProfileCeiling.isAtMost(other: ProfileCeiling): Boolean =
    when {
        this is ProfileCeiling.Unknown || other is ProfileCeiling.Unknown -> false
        other is ProfileCeiling.Unlimited -> true
        this is ProfileCeiling.Unlimited -> false
        else -> (this as ProfileCeiling.Rating).maxParentalRating <= (other as ProfileCeiling.Rating).maxParentalRating
    }
