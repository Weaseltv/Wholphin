package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.jellyfin.sdk.model.api.AuthenticationResult

/*
 * Frozen seam between the kid-profiles tracks (runbooks/kid-profiles/contract.md, section 8).
 * Track C implements [KidSessionGate]; track D implements [KidProfilesApi] and the screens.
 * Change these signatures only through the orchestrator.
 */

/** Kid levels; [wireId] is the website's `level` value, [ceiling] the Jellyfin MaxParentalRating. */
enum class KidLevel(
    val wireId: String,
    val ceiling: Int,
) {
    KIDS("kids", 10),
    OLDER_KIDS("older_kids", 14),
    TEENS("teens", 17),
    ;

    companion object {
        fun fromWireId(wireId: String): KidLevel? = entries.firstOrNull { it.wireId == wireId }
    }
}

/** What a profile may see. Ordered: any [Rating] < [Unlimited]; [Unknown] is never trusted. */
sealed interface ProfileCeiling {
    data class Rating(
        val maxParentalRating: Int,
    ) : ProfileCeiling

    data object Unlimited : ProfileCeiling

    data object Unknown : ProfileCeiling
}

/** Classification of a saved profile, from the server policy (contract section 1). */
enum class ProfileKind { ADULT, KID, UNKNOWN }

/** Short-lived parental authorization (plan 4.1). */
data class ParentUnlock(
    val userRowId: Int,
    val atMillis: Long,
)

/** Result of evaluating a switch to [target] through the single switch gate (plan 4.2). */
sealed interface GateDecision {
    /** Switch may proceed (PIN prompt for PIN profiles is still shown by the chooser). */
    data object Allow : GateDecision

    /** An adult PIN ([ParentUnlock]) is required for this one switch. */
    data object NeedsParentUnlock : GateDecision

    /** Pinless adult on a protected device: needs another adult's unlock or a fresh Quick Connect. */
    data object LockedAdult : GateDecision
}

sealed interface PinCheckResult {
    data object Correct : PinCheckResult

    data class Wrong(
        val attemptsBeforeLockout: Int,
    ) : PinCheckResult

    data class LockedOut(
        val retryAtMillis: Long,
    ) : PinCheckResult
}

/** Implemented by track C. The only way screens sign a kid in or check kid state. */
interface KidSessionGate {
    /** True while the current session is a kid or unknown (fail closed). */
    val isKidSession: StateFlow<Boolean>

    /** Durable "kid protection" flag for this device (plan 4.2). */
    val isDeviceProtected: Flow<Boolean>

    val parentUnlock: StateFlow<ParentUnlock?>

    fun profileKind(user: JellyfinUser): ProfileKind

    fun ceilingOf(user: JellyfinUser): ProfileCeiling

    suspend fun evaluate(target: JellyfinUser): GateDecision

    /** Checks [pin] for an adult [user] with attempt backoff; [PinCheckResult.Correct] grants a [ParentUnlock]. */
    suspend fun verifyParentPin(
        user: JellyfinUser,
        pin: String,
    ): PinCheckResult

    fun clearParentUnlock()

    /**
     * Publishes a kid session from a completed Quick Connect for that kid. Persists kid protection
     * and the kid's ceiling before publishing (plan 4.2). [displayName] comes from the website.
     */
    suspend fun signInKid(
        serverUrl: String,
        authenticationResult: AuthenticationResult,
        displayName: String?,
    ): CurrentUser
}

/** Website kid routes (contract section 5). Implemented by track D. */
interface KidProfilesApi {
    suspend fun list(parentAccessToken: String): KidApiResult<KidList>

    suspend fun create(
        parentAccessToken: String,
        idempotencyKey: String,
        displayName: String,
        level: KidLevel,
    ): KidApiResult<KidProfile>

    suspend fun connect(
        parentAccessToken: String,
        kidId: String,
        quickConnectCode: String,
    ): KidApiResult<KidProfile>
}

data class KidProfile(
    val id: String,
    val displayName: String,
    val level: KidLevel,
    val state: String,
    val mediaUserId: String?,
    val mediaUserName: String?,
)

data class KidList(
    val kids: List<KidProfile>,
    val limit: Int,
)

sealed interface KidApiResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : KidApiResult<T>

    /** [code] is the problem `title` from contract section 3.2; [status] the HTTP status. */
    data class Problem(
        val status: Int,
        val code: String,
        val retryAfterMillis: Long? = null,
    ) : KidApiResult<Nothing>

    data class NetworkError(
        val cause: Throwable,
    ) : KidApiResult<Nothing>
}
