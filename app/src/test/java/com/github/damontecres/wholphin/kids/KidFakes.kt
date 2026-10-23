package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.AvatarCatalog
import com.github.damontecres.wholphin.services.AvatarGroup
import com.github.damontecres.wholphin.services.ProfileImageCache
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.UserImageUploader
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.AuthenticationResult
import java.util.UUID

// Test doubles for the kid-profiles seam (contract section 8).

/**
 * [AccountPins] whose account can't be reached (contract 11.2): pulls keep the cached PIN, and writes go straight to
 * [serverRepository], as before the account PIN
 */
fun localAccountPins(serverRepository: ServerRepository? = null): AccountPins =
    mockk {
        coEvery { refresh(any()) } coAnswers { firstArg() }
        coEvery { refreshAll(any()) } just Runs
        coEvery { set(any(), any(), any()) } coAnswers {
            serverRepository?.updateUserAuth(firstArg(), secondArg(), thirdArg())
            AccountPinWrite.Saved
        }
        coEvery { upload(any(), any(), any(), any()) } returns true
        // The account is reachable and has no PIN
        coEvery { readAccount(any(), any(), any()) } returns AccountPinValue(null, null)
    }

val testServer = JellyfinServer(UUID.randomUUID(), "WeaselPlex", "https://media.example", "10.11.0")

fun testUser(
    name: String,
    pin: String? = null,
    token: String? = "token-$name",
) = JellyfinUser(
    rowId = name.hashCode(),
    id = UUID.nameUUIDFromBytes(name.toByteArray()),
    name = name,
    serverId = testServer.id,
    accessToken = token,
    pin = pin,
)

fun testKid(
    name: String,
    id: String = "kid-$name",
    state: String = "active",
) = KidProfile(id, name, KidLevel.KIDS, state, mediaUserId = null, mediaUserName = name)

class FakeKidSessionGate : KidSessionGate {
    override val isKidSession = MutableStateFlow(false)
    override val isDeviceProtected = MutableStateFlow(false)
    override val parentUnlock = MutableStateFlow<ParentUnlock?>(null)
    val kinds = mutableMapOf<UUID, ProfileKind>()
    val pinResults = ArrayDeque<PinCheckResult>()
    val verified = mutableListOf<Pair<JellyfinUser, String>>()
    val signedIn = mutableListOf<Pair<AuthenticationResult, String?>>()
    var clearCount = 0

    /** Clock for unlock expiry; tests move it forward */
    var now = 0L

    /** Thrown by [signInKid] instead of signing in, eg the real gate's refusal */
    var signInFailure: Exception? = null

    override fun profileKind(user: JellyfinUser) = kinds[user.id] ?: ProfileKind.ADULT

    override fun ceilingOf(user: JellyfinUser): ProfileCeiling = ProfileCeiling.Unlimited

    override suspend fun evaluate(target: JellyfinUser) = GateDecision.Allow

    override suspend fun verifyParentPin(
        user: JellyfinUser,
        pin: String,
        purpose: UnlockPurpose?,
    ): PinCheckResult = verifyParentPin(user, pin, purpose) { it() }

    /** Awaited inside the check, eg to hold it while storage is "pending"; the check can't be cancelled, as in the real gate */
    var pendingCheck: CompletableDeferred<Unit>? = null

    override suspend fun verifyParentPin(
        user: JellyfinUser,
        pin: String,
        purpose: UnlockPurpose?,
        admit: (grant: () -> Unit) -> Unit,
    ): PinCheckResult =
        withContext(NonCancellable) {
            verified += user to pin
            pendingCheck?.await()
            val result = pinResults.removeFirstOrNull() ?: if (user.pin == pin) PinCheckResult.Correct else PinCheckResult.Wrong(3)
            if (result == PinCheckResult.Correct) admit { parentUnlock.value = ParentUnlock(user.rowId, now, purpose) }
            result
        }

    /** [checkOwnPin] calls; they never grant an unlock */
    val ownPinChecks = mutableListOf<Pair<JellyfinUser, String>>()

    override suspend fun checkOwnPin(
        user: JellyfinUser,
        pin: String,
    ): PinCheckResult {
        ownPinChecks += user to pin
        return pinResults.removeFirstOrNull() ?: if (user.pin == pin) PinCheckResult.Correct else PinCheckResult.Wrong(3)
    }

    override fun validUnlockFor(
        parentRowId: Int,
        purpose: UnlockPurpose,
    ): ParentUnlock? =
        parentUnlock.value?.takeIf {
            it.userRowId == parentRowId &&
                it.purpose == purpose &&
                now - it.atMillis < KidSessionGateImpl.UNLOCK_DURATION.inWholeMilliseconds
        }

    override fun clearParentUnlock() {
        clearCount++
        parentUnlock.value = null
    }

    override suspend fun signInKid(
        serverUrl: String,
        authenticationResult: AuthenticationResult,
        displayName: String?,
    ): CurrentUser {
        signInFailure?.let { throw it }
        signedIn += authenticationResult to displayName
        val dto = authenticationResult.user!!
        return CurrentUser(
            testServer,
            JellyfinUser(id = dto.id, name = displayName, serverId = testServer.id, accessToken = authenticationResult.accessToken),
        )
    }
}

class FakeKidProfilesApi : KidProfilesApi {
    val listResults = ArrayDeque<KidApiResult<KidList>>()
    val createResults = ArrayDeque<KidApiResult<KidProfile>>()
    val connectResults = ArrayDeque<KidApiResult<KidProfile>>()
    val listCalls = mutableListOf<String>()
    val createCalls = mutableListOf<CreateCall>()
    val connectCalls = mutableListOf<Triple<String, String, String>>()

    data class CreateCall(
        val token: String,
        val key: String,
        val name: String,
        val level: KidLevel,
    )

    override suspend fun list(parentAccessToken: String): KidApiResult<KidList> {
        listCalls += parentAccessToken
        return listResults.removeFirstOrNull() ?: KidApiResult.Ok(KidList(emptyList(), 5))
    }

    override suspend fun create(
        parentAccessToken: String,
        idempotencyKey: String,
        displayName: String,
        level: KidLevel,
    ): KidApiResult<KidProfile> {
        createCalls += CreateCall(parentAccessToken, idempotencyKey, displayName, level)
        return createResults.removeFirstOrNull() ?: KidApiResult.Ok(testKid(displayName))
    }

    override suspend fun connect(
        parentAccessToken: String,
        kidId: String,
        quickConnectCode: String,
    ): KidApiResult<KidProfile> {
        connectCalls += Triple(parentAccessToken, kidId, quickConnectCode)
        return connectResults.removeFirstOrNull() ?: KidApiResult.Ok(testKid("Emma", id = kidId))
    }
}

class FakeKidQuickConnect : KidQuickConnect {
    var initiated = 0
    var pollsUntilAuthenticated = 1
    var pollsSeen = 0
    var initiateError: Exception? = null
    val kidUserId: UUID = UUID.randomUUID()

    /** Runs on every poll (eg to let the parent unlock expire mid-flow) */
    var onPoll: () -> Unit = {}

    override suspend fun initiate(serverUrl: String): QuickConnectCode {
        initiateError?.let { throw it }
        initiated++
        return QuickConnectCode("00000$initiated", "secret-$initiated")
    }

    override suspend fun isAuthenticated(
        serverUrl: String,
        secret: String,
    ): Boolean {
        onPoll()
        return ++pollsSeen >= pollsUntilAuthenticated
    }

    override suspend fun authenticate(
        serverUrl: String,
        secret: String,
    ) = AuthenticationResult(
        user =
            org.jellyfin.sdk.model.api.UserDto(
                id = kidUserId,
                hasPassword = false,
                hasConfiguredPassword = false,
                hasConfiguredEasyPassword = false,
                name = "Emma",
            ),
        accessToken = "kid-token",
        serverId = testServer.id.toString(),
    )
}

class FakeAvatarCatalog(
    kids: List<String> = listOf("bee-blue", "fox-green"),
    adults: List<String> = listOf("alien-black", "wolf-crimson"),
) : AvatarCatalog {
    private val sets =
        mapOf(
            AvatarGroup.KIDS to kids.map { Avatar(it, AvatarGroup.KIDS) },
            AvatarGroup.ADULTS to adults.map { Avatar(it, AvatarGroup.ADULTS) },
        )

    override fun avatars(group: AvatarGroup) = sets[group].orEmpty()

    override suspend fun pngBytes(avatar: Avatar) = avatar.id.encodeToByteArray()
}

class FakeUserImageUploader : UserImageUploader {
    val uploads = mutableListOf<Pair<ProfilePictureTarget, ByteArray>>()
    val deletes = mutableListOf<ProfilePictureTarget>()
    var failWith: Exception? = null

    override suspend fun upload(
        target: ProfilePictureTarget,
        png: ByteArray,
    ) {
        failWith?.let { throw it }
        uploads += target to png
    }

    override suspend fun delete(target: ProfilePictureTarget) {
        failWith?.let { throw it }
        deletes += target
    }
}

class FakeProfileImageCache : ProfileImageCache {
    override val versions = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    val chosen = mutableMapOf<UUID, String?>()

    override fun chosenAvatar(userId: UUID) = chosen[userId]

    override fun onPictureChanged(
        userId: UUID,
        avatarId: String?,
    ) {
        chosen[userId] = avatarId
        versions.value = versions.value + (userId to (versions.value[userId] ?: 0) + 1)
    }
}
