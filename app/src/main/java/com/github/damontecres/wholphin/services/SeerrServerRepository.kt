package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.SeerrQuickConnectException
import com.github.damontecres.wholphin.api.seerr.model.AuthJellyfinPostRequest
import com.github.damontecres.wholphin.api.seerr.model.AuthLocalPostRequest
import com.github.damontecres.wholphin.api.seerr.model.PublicSettings
import com.github.damontecres.wholphin.api.seerr.model.User
import com.github.damontecres.wholphin.data.SeerrServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.data.model.SeerrPermission
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import com.github.damontecres.wholphin.data.model.hasPermission
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.setup.seerr.createSeerrApiUrl
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Manages saves/loading Seerr servers from the local DB. Also will update the current [SeerrApi] as needed.
 */
@Singleton
class SeerrServerRepository
    @Inject
    constructor(
        private val seerrApi: SeerrApi,
        private val seerrServerDao: SeerrServerDao,
        private val serverRepository: ServerRepository,
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val _connection =
            MutableStateFlow<SeerrConnectionStatus>(SeerrConnectionStatus.NotConfigured)
        val connection: StateFlow<SeerrConnectionStatus> = _connection

        val current: Flow<CurrentSeerr?> =
            _connection.map { (it as? SeerrConnectionStatus.Success)?.current }
        val currentServer: Flow<SeerrServer?> =
            connection.map { (it as? SeerrConnectionStatus.Success)?.current?.server }
        val currentUser: Flow<SeerrUser?> =
            connection.map { (it as? SeerrConnectionStatus.Success)?.current?.user }
        val currentUserId: Flow<Int?> = current.map { it?.config?.id }

        /**
         * Whether Seerr integration is currently active of not
         */
        val active: Flow<Boolean> =
            connection.map { it is SeerrConnectionStatus.Success && seerrApi.active }

        fun clear() {
            _connection.update { SeerrConnectionStatus.NotConfigured }
            seerrApi.update("", null)
        }

        fun error(
            server: SeerrServer,
            user: SeerrUser,
            exception: Exception,
        ) {
            _connection.update { SeerrConnectionStatus.Error(server, user, exception) }
            seerrApi.update("", null)
        }

        suspend fun set(
            server: SeerrServer,
            user: SeerrUser,
            userConfig: SeerrUserConfig,
        ) {
            val publicSettings = seerrApi.api.settingsApi.settingsPublicGet()
            _connection.update {
                SeerrConnectionStatus.Success(
                    CurrentSeerr(server, user, userConfig, publicSettings),
                )
            }
        }

        suspend fun addAndChangeServer(
            url: String,
            apiKey: String,
        ) {
            var server = seerrServerDao.getServer(url)
            if (server == null) {
                seerrServerDao.addServer(SeerrServer(url = url))
                server = seerrServerDao.getServer(url)
            }
            server?.server?.let { server ->
                serverRepository.currentUser?.let { jellyfinUser ->
                    // TODO test api key
                    val user =
                        SeerrUser(
                            jellyfinUserRowId = jellyfinUser.rowId,
                            serverId = server.id,
                            authMethod = SeerrAuthMethod.API_KEY,
                            username = null,
                            password = null,
                            credential = apiKey,
                        )
                    seerrServerDao.addUser(user)

                    seerrApi.update(server.url, apiKey)
                    val userConfig = seerrApi.api.usersApi.authMeGet()
                    set(server, user, userConfig)
                }
            }
        }

        suspend fun addAndChangeServer(
            url: String,
            authMethod: SeerrAuthMethod,
            username: String,
            password: String,
        ) {
            var server = seerrServerDao.getServer(url)
            if (server == null) {
                seerrServerDao.addServer(SeerrServer(url = url))
                server = seerrServerDao.getServer(url)
            }
            server?.server?.let { server ->
                serverRepository.currentUser?.let { jellyfinUser ->
                    // TODO Need to update server early so that cookies are saved
                    seerrApi.update(server.url, null)
                    val userConfig = seerrLogin(seerrApi.api, authMethod, username, password)

                    val user =
                        SeerrUser(
                            jellyfinUserRowId = jellyfinUser.rowId,
                            serverId = server.id,
                            authMethod = authMethod,
                            username = username,
                            password = password,
                            credential = null,
                        )
                    seerrServerDao.addUser(user)
                    set(server, user, userConfig)
                }
            }
        }

        /**
         * WeaselFin: connect this Jellyfin user to the pinned Seerr server with no
         * interaction at all, reusing the Jellyfin session they just signed in with.
         *
         * Called only when the user has no Seerr account configured yet. Returns false
         * when nothing is pinned (every upstream flavor), so the normal manual setup
         * flow is untouched.
         *
         * Failures are logged and surfaced through [error]; they never block sign-in,
         * because a customer must still be able to watch when the request service is
         * down.
         */
        suspend fun provisionPinnedServer(): Boolean {
            val pinned = BuildConfig.DEFAULT_SEERR_URL
            if (pinned.isBlank()) return false
            val jellyfinUser = serverRepository.currentUser ?: return false

            val url = createSeerrApiUrl(pinned)
            var stored = seerrServerDao.getServer(url)
            if (stored == null) {
                seerrServerDao.addServer(SeerrServer(url = url))
                stored = seerrServerDao.getServer(url)
            }
            val server = stored?.server ?: return false

            return try {
                seerrApi.update(server.url, null)
                val userConfig =
                    seerrQuickConnectLogin(seerrApi.api) { code ->
                        serverRepository.authorizeQuickConnect(code)
                    }
                val seerrUser =
                    SeerrUser(
                        jellyfinUserRowId = jellyfinUser.rowId,
                        serverId = server.id,
                        authMethod = SeerrAuthMethod.QUICK_CONNECT,
                        username = null,
                        // Nothing to store: the session came from Jellyfin, and a new
                        // one is minted the same way on every sign-in.
                        password = null,
                        credential = null,
                    )
                seerrServerDao.addUser(seerrUser)
                set(server, seerrUser, userConfig)
                Timber.i("Connected to the pinned Seerr server silently")
                true
            } catch (ex: Exception) {
                Timber.w(ex, "Silent Seerr connect failed for %s", server.url)
                false
            }
        }

        suspend fun testConnection(
            authMethod: SeerrAuthMethod,
            url: String,
            username: String?,
            passwordOrApiKey: String,
        ): LoadingState {
            val apiKey = passwordOrApiKey.takeIf { authMethod == SeerrAuthMethod.API_KEY }
            val api =
                SeerrApiClient(
                    createSeerrApiUrl(url),
                    apiKey,
                    okHttpClient
                        .newBuilder()
                        .connectTimeout(2.seconds)
                        .readTimeout(6.seconds)
                        .build(),
                )
            seerrLogin(api, authMethod, username, passwordOrApiKey)
            return LoadingState.Success
        }

        suspend fun removeServerForCurrentUser(): Boolean {
            val user =
                when (val conn = connection.first()) {
                    SeerrConnectionStatus.NotConfigured -> return false
                    is SeerrConnectionStatus.Error -> conn.user
                    is SeerrConnectionStatus.Success -> conn.current.user
                }
            val rows = seerrServerDao.deleteUser(user)
            clear()
            return rows > 0
        }
    }

/**
 * A [SeerrUser] config
 */
typealias SeerrUserConfig = User

sealed interface SeerrConnectionStatus {
    data object NotConfigured : SeerrConnectionStatus

    data class Error(
        val server: SeerrServer,
        val user: SeerrUser,
        val ex: Exception,
    ) : SeerrConnectionStatus

    data class Success(
        val current: CurrentSeerr,
    ) : SeerrConnectionStatus
}

data class CurrentSeerr(
    val server: SeerrServer,
    val user: SeerrUser,
    val config: SeerrUserConfig,
    val serverConfig: PublicSettings,
) {
    val request4kMovieEnabled: Boolean
        get() =
            (serverConfig.movie4kEnabled ?: false) &&
                config.hasPermission(SeerrPermission.REQUEST_4K_MOVIE)

    val request4kTvEnabled: Boolean
        get() =
            (serverConfig.series4kEnabled ?: false) &&
                config.hasPermission(SeerrPermission.REQUEST_4K_TV)
}

/**
 * WeaselFin: the whole silent Quick Connect exchange.
 *
 * 1. ask Seerr to start Quick Connect  -> it returns a code and a secret
 * 2. approve that code against Jellyfin using the token this device ALREADY holds
 * 3. hand the secret back to Seerr     -> it issues a normal per-user session
 *
 * 🛑 SECURITY: [authorizeCode] is only ever called with the code returned by step 1
 * in this same invocation. The code is never read from user input, an intent, a
 * notification or storage, and never leaves this function — so the app cannot be
 * induced to approve an attacker's Quick Connect request.
 *
 * The whole exchange is bounded by [QUICK_CONNECT_TIMEOUT_MS] so a server that
 * accepts the connection but never answers surfaces an error instead of hanging a
 * TV on a spinner.
 */
private const val QUICK_CONNECT_TIMEOUT_MS = 20_000L

suspend fun seerrQuickConnectLogin(
    client: SeerrApiClient,
    authorizeCode: suspend (String) -> Boolean,
): User =
    withTimeoutOrNull(QUICK_CONNECT_TIMEOUT_MS) {
        val started = client.quickConnectInitiate()
        require(started.code.isNotBlank() && started.secret.isNotBlank()) {
            "Media server returned an incomplete Quick Connect response."
        }
        Timber.i("Seerr Quick Connect initiated; approving our own code")

        if (!authorizeCode(started.code)) {
            throw SeerrQuickConnectException(
                "Could not approve the request service automatically.",
            )
        }
        client.quickConnectAuthenticate(started.secret)
        client.usersApi.authMeGet()
    } ?: throw SeerrQuickConnectException(
        "The request service did not respond. Please try again.",
    )

suspend fun seerrLogin(
    client: SeerrApiClient,
    authMethod: SeerrAuthMethod,
    username: String?,
    password: String?,
    authorizeQuickConnect: (suspend (String) -> Boolean)? = null,
): User =
    when (authMethod) {
        SeerrAuthMethod.LOCAL -> {
            client.authApi.authLocalPost(
                AuthLocalPostRequest(
                    email = username ?: "",
                    password = password ?: "",
                ),
            )
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.JELLYFIN -> {
            client.authApi.authJellyfinPost(
                AuthJellyfinPostRequest(
                    username = username ?: "",
                    password = password ?: "",
                ),
            )
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.API_KEY -> {
            client.usersApi.authMeGet()
        }

        SeerrAuthMethod.QUICK_CONNECT -> {
            // Requires a live Jellyfin session to approve with; callers that cannot
            // supply one fail loudly here rather than silently falling back to a
            // password prompt the customer has no password for.
            val authorize =
                authorizeQuickConnect
                    ?: throw SeerrQuickConnectException(
                        "Sign in to the media server first.",
                    )
            seerrQuickConnectLogin(client, authorize)
        }
    }

fun CurrentSeerr?.imageUrlBuilder(
    imageType: ImageType,
    path: String?,
): String? {
    if (this == null) return null
    val cacheImages = serverConfig.cacheImages == true
    val base =
        if (cacheImages) {
            server.url.removeSuffix("/") + "/imageproxy/tmdb"
        } else {
            "https://image.tmdb.org"
        }
    val prefix =
        when (imageType) {
            ImageType.PRIMARY -> "/t/p/w500"
            ImageType.BACKDROP -> "/t/p/w1920_and_h1080_multi_faces"
            else -> throw IllegalArgumentException("Image type not supported: $imageType")
        }
    return "${base}${prefix}$path"
}
