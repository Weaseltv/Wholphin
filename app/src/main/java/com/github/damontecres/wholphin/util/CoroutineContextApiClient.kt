package com.github.damontecres.wholphin.util

import com.github.damontecres.wholphin.kids.KidContentGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.ApiClientFactory
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketConnection
import org.jellyfin.sdk.api.sockets.SocketConnectionFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import kotlin.coroutines.CoroutineContext

/**
 * Wraps [ApiClient.request] with the given [CoroutineContext]
 *
 * WeaselFin: every Jellyfin SDK call goes through [request], so the kid content rules ([KidContentGuard], contract
 * section 10) apply here for kid and unknown sessions. Adults go straight through.
 *
 * Kid content rules (review round 1): with [pin], every request is pinned to the credentials of the moment it starts.
 * Its policy, the request itself and the rules' lookups all use one client made for those credentials, so a session
 * switch meanwhile can never send a request decided for one profile with another profile's credentials.
 */
class CoroutineContextApiClient(
    private val client: ApiClient,
    private val coroutineContext: CoroutineContext = WholphinDispatchers.IO,
    private val contentGuard: KidContentGuard? = null,
    private val pin: ((Credentials) -> ApiClient)? = null,
) : ApiClient() {
    /** The credentials a request is pinned to */
    data class Credentials(
        val baseUrl: String?,
        val accessToken: String?,
        val clientInfo: ClientInfo,
        val deviceInfo: DeviceInfo,
    )

    /** A client fixed to [accessToken], without the kid content rules: for one request and its rules' lookups */
    class Pinned(
        val client: ApiClient,
        val accessToken: String?,
    )

    /**
     * Review round 2: whether this is a session's client (its credentials were ever updated, as the shared client's are
     * on every switch). Its requests are bound to the session epoch they start in; one that ends in another epoch fails
     * with [KidContentGuard.StaleSessionException], so no response ever crosses a session change.
     */
    @Volatile
    private var sessionBound = false

    /** Replaced as a whole on [update], so a request reads one consistent set */
    @Volatile
    private var credentials = Credentials(client.baseUrl, client.accessToken, client.clientInfo, client.deviceInfo)

    /** A few clients pinned to recent credentials, so pinning costs a lookup */
    private val pinnedClients =
        object : LinkedHashMap<Credentials, ApiClient>(PINNED_CLIENTS, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Credentials, ApiClient>?): Boolean = size > PINNED_CLIENTS
        }

    /** A client for the credentials of this moment (the live wrapped client when nothing pins) */
    internal fun pinned(): Pinned {
        val pin = pin ?: return Pinned(client, client.accessToken)
        val now = credentials
        val pinnedClient = synchronized(pinnedClients) { pinnedClients.getOrPut(now) { pin(now) } }
        return Pinned(pinnedClient, now.accessToken)
    }

    override val baseUrl: String?
        get() = if (pin != null) credentials.baseUrl else client.baseUrl
    override val accessToken: String?
        get() = if (pin != null) credentials.accessToken else client.accessToken
    override val clientInfo: ClientInfo
        get() = client.clientInfo
    override val deviceInfo: DeviceInfo
        get() = client.deviceInfo
    override val httpClientOptions: HttpClientOptions
        get() = client.httpClientOptions
    override val webSocket: SocketApi
        get() = client.webSocket

    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) {
        client.update(baseUrl, accessToken, clientInfo, deviceInfo)
        credentials = Credentials(baseUrl, accessToken, clientInfo, deviceInfo)
        sessionBound = true
    }

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse =
        withContext(coroutineContext) {
            val guard = contentGuard
            if (guard == null) {
                client.request(
                    method,
                    pathTemplate,
                    pathParameters,
                    queryParameters,
                    requestBody,
                )
            } else {
                // The epoch before the credentials: if they change after it is read, so does the epoch
                val epoch = if (sessionBound) guard.epoch() else null
                val pinned = pinned()
                val policy = guard.policyFor(pinned.accessToken)
                val response =
                    if (policy == null) {
                        val response = pinned.client.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
                        guard.stamped(response, pinned.accessToken, null, epoch)
                    } else {
                        guard.request(policy, pinned, epoch, method, pathTemplate, pathParameters, queryParameters, requestBody)
                    }
                if (epoch != null && guard.epoch() != epoch) throw KidContentGuard.StaleSessionException()
                response
            }
        }

    private companion object {
        const val PINNED_CLIENTS = 4
    }
}

class CoroutineContextApiClientFactory(
    private val factory: OkHttpFactory,
    private val coroutineContext: CoroutineContext = WholphinDispatchers.IO,
    private val contentGuard: KidContentGuard? = null,
) : ApiClientFactory,
    SocketConnectionFactory {
    override fun create(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
        httpClientOptions: HttpClientOptions,
        socketConnectionFactory: SocketConnectionFactory,
    ): ApiClient =
        CoroutineContextApiClient(
            factory.create(baseUrl, accessToken, clientInfo, deviceInfo, httpClientOptions, socketConnectionFactory),
            coroutineContext,
            contentGuard,
            pin =
                contentGuard?.let {
                    { pinned ->
                        factory.create(
                            pinned.baseUrl,
                            pinned.accessToken,
                            pinned.clientInfo,
                            pinned.deviceInfo,
                            httpClientOptions,
                            socketConnectionFactory,
                        )
                    }
                },
        )

    override fun create(
        clientOptions: HttpClientOptions,
        scope: CoroutineScope,
    ): SocketConnection = factory.create(clientOptions, scope)
}
