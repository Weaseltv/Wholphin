package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

data class QuickConnectCode(
    val code: String,
    val secret: String,
)

/** What polling a Quick Connect secret said (contract 12.3) */
enum class QuickConnectPoll {
    WAITING,
    AUTHENTICATED,

    /** The server no longer knows the secret: the code expired (or was used up); a new one is needed */
    EXPIRED,
}

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

    /**
     * Signs in with an approved [secret]. [onIssued] gets the result as soon as it is decoded, before any cancellable
     * return (review round 2): a caller that is cancelled on the way back still knows the token it now owns. Bounded by
     * the HTTP client's own timeouts (review round 3), so a caller can run it without cancellation and never needs a
     * coroutine timeout, which could drop a response that already arrived.
     */
    suspend fun authenticate(
        serverUrl: String,
        secret: String,
        onIssued: (AuthenticationResult) -> Unit = {},
    ): AuthenticationResult

    /**
     * Polls [secret] for the pairing screen (contract 12.3). Jellyfin answers 404 once the code expired or was used
     * ([QuickConnectPoll.EXPIRED]); anything else that fails (network, other statuses) throws.
     */
    suspend fun poll(
        serverUrl: String,
        secret: String,
    ): QuickConnectPoll

    /**
     * Ends the session of [accessToken] (`POST /Sessions/Logout` with that token), eg an adult's sign-in the pairing
     * refused (contract 12.3). Best effort with a short timeout; never throws.
     */
    suspend fun signOut(
        serverUrl: String,
        accessToken: String,
    )
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
            onIssued: (AuthenticationResult) -> Unit,
        ): AuthenticationResult =
            withContext(WholphinDispatchers.IO) {
                jellyfin
                    .createApi(
                        baseUrl = serverUrl,
                        httpClientOptions =
                            HttpClientOptions(
                                requestTimeout = AUTHENTICATE_TIMEOUT,
                                connectTimeout = AUTHENTICATE_TIMEOUT,
                                socketTimeout = AUTHENTICATE_TIMEOUT,
                            ),
                    ).userApi
                    .authenticateWithQuickConnect(QuickConnectDto(secret = secret))
                    .content
                    .also(onIssued)
            }

        override suspend fun poll(
            serverUrl: String,
            secret: String,
        ): QuickConnectPoll =
            withContext(WholphinDispatchers.IO) {
                try {
                    val state by jellyfin.createApi(serverUrl).quickConnectApi.getQuickConnectState(secret = secret)
                    if (state.authenticated) QuickConnectPoll.AUTHENTICATED else QuickConnectPoll.WAITING
                } catch (ex: InvalidStatusException) {
                    // "Unknown secret": Jellyfin drops a request once it expires (10 minutes) or signs in
                    if (ex.status == 404) QuickConnectPoll.EXPIRED else throw ex
                }
            }

        override suspend fun signOut(
            serverUrl: String,
            accessToken: String,
        ) {
            try {
                withTimeoutOrNull(SIGN_OUT_TIMEOUT) {
                    withContext(WholphinDispatchers.IO) {
                        jellyfin
                            .createApi(
                                baseUrl = serverUrl,
                                accessToken = accessToken,
                                httpClientOptions =
                                    HttpClientOptions(
                                        requestTimeout = SIGN_OUT_TIMEOUT,
                                        connectTimeout = SIGN_OUT_TIMEOUT,
                                        socketTimeout = SIGN_OUT_TIMEOUT,
                                    ),
                            ).sessionApi
                            .reportSessionEnded()
                    }
                } ?: Timber.w("Signing a refused sign-in out timed out")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't sign a refused sign-in out")
            }
        }

        private companion object {
            val SIGN_OUT_TIMEOUT = 5.seconds

            /** The sign-in's HTTP timeouts; its caller doesn't cancel it (review round 3) */
            val AUTHENTICATE_TIMEOUT = 15.seconds
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class KidQuickConnectModule {
    @Binds
    abstract fun kidQuickConnect(impl: JellyfinKidQuickConnect): KidQuickConnect
}
