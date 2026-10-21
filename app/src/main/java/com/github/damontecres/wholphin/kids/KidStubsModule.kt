package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.jellyfin.sdk.model.api.AuthenticationResult
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Day-0 placeholders so tracks C and D compile against KidContracts.kt independently.
 * Track C replaces StubKidSessionGate; track D replaces StubKidProfilesApi. Delete each stub
 * (and its @Binds) when the real implementation lands.
 */

@Singleton
class StubKidSessionGate
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
    ) : KidSessionGate {
        override val isKidSession: StateFlow<Boolean> = MutableStateFlow(false)
        override val isDeviceProtected: Flow<Boolean> = flowOf(false)
        override val parentUnlock: StateFlow<ParentUnlock?> = MutableStateFlow(null)

        override fun profileKind(user: JellyfinUser): ProfileKind = ProfileKind.ADULT

        override fun ceilingOf(user: JellyfinUser): ProfileCeiling = ProfileCeiling.Unlimited

        override suspend fun evaluate(target: JellyfinUser): GateDecision = GateDecision.Allow

        override suspend fun verifyParentPin(
            user: JellyfinUser,
            pin: String,
        ): PinCheckResult = if (user.pin == pin) PinCheckResult.Correct else PinCheckResult.Wrong(Int.MAX_VALUE)

        override fun clearParentUnlock() = Unit

        override suspend fun signInKid(
            serverUrl: String,
            authenticationResult: AuthenticationResult,
            displayName: String?,
        ): CurrentUser = serverRepository.changeUser(serverUrl, authenticationResult, null)
    }

@Singleton
class StubKidProfilesApi
    @Inject
    constructor() : KidProfilesApi {
        private val unavailable = KidApiResult.Problem(404, "WEASELPLEX_KIDS_UNAVAILABLE")

        override suspend fun list(parentAccessToken: String) = unavailable

        override suspend fun create(
            parentAccessToken: String,
            idempotencyKey: String,
            displayName: String,
            level: KidLevel,
        ) = unavailable

        override suspend fun connect(
            parentAccessToken: String,
            kidId: String,
            quickConnectCode: String,
        ) = unavailable
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class KidSessionGateStubModule {
    @Binds
    abstract fun kidSessionGate(impl: StubKidSessionGate): KidSessionGate
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KidProfilesApiStubModule {
    @Binds
    abstract fun kidProfilesApi(impl: StubKidProfilesApi): KidProfilesApi
}
