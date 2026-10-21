package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.AvatarCatalog
import com.github.damontecres.wholphin.services.ProfileImageCache
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.services.UserImageUploader
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.model.api.AuthenticationResult
import java.util.UUID

/** Test doubles for the kid-profiles seam (contract section 8). */
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
    ): PinCheckResult {
        verified += user to pin
        val result = pinResults.removeFirstOrNull() ?: if (user.pin == pin) PinCheckResult.Correct else PinCheckResult.Wrong(3)
        if (result == PinCheckResult.Correct) parentUnlock.value = ParentUnlock(user.rowId, now, purpose)
        return result
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
    ids: List<String> = listOf("placeholder-cat", "placeholder-fox"),
) : AvatarCatalog {
    private val all = ids.map { Avatar(it) }

    override fun avatars() = all

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
