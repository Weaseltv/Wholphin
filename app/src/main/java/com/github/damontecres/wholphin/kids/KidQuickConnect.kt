package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import javax.inject.Inject
import javax.inject.Singleton

data class QuickConnectCode(
    val code: String,
    val secret: String,
)

/**
 * Quick Connect for signing a kid in (contract 5.4 steps 4 and 6). Every call uses its own
 * unauthenticated Jellyfin client, never the shared ApiClient, so nothing about the current
 * session changes until the kid's sign-in is handed to [KidSessionGate.signInKid].
 */
interface KidQuickConnect {
    suspend fun initiate(serverUrl: String): QuickConnectCode

    suspend fun isAuthenticated(
        serverUrl: String,
        secret: String,
    ): Boolean

    suspend fun authenticate(
        serverUrl: String,
        secret: String,
    ): AuthenticationResult
}

@Singleton
class JellyfinKidQuickConnect
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
    ) : KidQuickConnect {
        override suspend fun initiate(serverUrl: String): QuickConnectCode =
            withContext(WholphinDispatchers.IO) {
                val result by jellyfin.createApi(serverUrl).quickConnectApi.initiateQuickConnect()
                QuickConnectCode(result.code, result.secret)
            }

        override suspend fun isAuthenticated(
            serverUrl: String,
            secret: String,
        ): Boolean =
            withContext(WholphinDispatchers.IO) {
                jellyfin
                    .createApi(serverUrl)
                    .quickConnectApi
                    .getQuickConnectState(secret = secret)
                    .content
                    .authenticated
            }

        override suspend fun authenticate(
            serverUrl: String,
            secret: String,
        ): AuthenticationResult =
            withContext(WholphinDispatchers.IO) {
                jellyfin
                    .createApi(serverUrl)
                    .userApi
                    .authenticateWithQuickConnect(QuickConnectDto(secret = secret))
                    .content
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class KidQuickConnectModule {
    @Binds
    abstract fun kidQuickConnect(impl: JellyfinKidQuickConnect): KidQuickConnect
}
