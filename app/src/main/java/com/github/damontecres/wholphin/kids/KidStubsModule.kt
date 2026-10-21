package com.github.damontecres.wholphin.kids

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Day-0 placeholder so tracks C and D compile against KidContracts.kt independently.
 * Track C's KidSessionGateImpl replaced the session-gate stub; track D replaces StubKidProfilesApi.
 * Delete the stub (and its @Binds) when the real implementation lands.
 */

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
abstract class KidProfilesApiStubModule {
    @Binds
    abstract fun kidProfilesApi(impl: StubKidProfilesApi): KidProfilesApi
}
