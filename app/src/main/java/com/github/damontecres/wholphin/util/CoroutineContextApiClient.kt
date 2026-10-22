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
 */
class CoroutineContextApiClient(
    private val client: ApiClient,
    private val coroutineContext: CoroutineContext = WholphinDispatchers.IO,
    private val contentGuard: KidContentGuard? = null,
) : ApiClient() {
    /** The wrapped client, without the kid content rules: only for the rules' own lookups */
    internal val unfiltered: ApiClient get() = client

    override val baseUrl: String?
        get() = client.baseUrl
    override val accessToken: String?
        get() = client.accessToken
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
    }

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse =
        withContext(coroutineContext) {
            val policy = contentGuard?.policyFor(client.accessToken)
            if (policy == null) {
                client.request(
                    method,
                    pathTemplate,
                    pathParameters,
                    queryParameters,
                    requestBody,
                )
            } else {
                contentGuard.request(policy, client, method, pathTemplate, pathParameters, queryParameters, requestBody)
            }
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
        )

    override fun create(
        clientOptions: HttpClientOptions,
        scope: CoroutineScope,
    ): SocketConnection = factory.create(clientOptions, scope)
}
