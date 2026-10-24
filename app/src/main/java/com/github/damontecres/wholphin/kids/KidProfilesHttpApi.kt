package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The website's TV kid routes (runbooks/kid-profiles/contract.md, sections 3 and 5) over the
 * plain OkHttp client: no Jellyfin auth interceptor, the parent's own Room token as a Bearer.
 * Tokens are never logged.
 */
@Singleton
class KidProfilesHttpApi internal constructor(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val clientHeader: String,
    private val newRequestId: () -> String,
) : KidProfilesApi {
    @Inject
    constructor(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) : this(
        okHttpClient = okHttpClient,
        baseUrl = BuildConfig.WEASELPLEX_WEBSITE_URL,
        clientHeader = "android-tv/${BuildConfig.VERSION_NAME}",
        newRequestId = { UUID.randomUUID().toString() },
    )

    override suspend fun list(parentAccessToken: String): KidApiResult<KidList> =
        call(parentAccessToken, KIDS_PATH, body = null) { data ->
            val list = json.decodeFromJsonElement<KidListJson>(data)
            KidList(kids = list.kids.map { it.toKidProfile() }, limit = list.limit)
        }

    override suspend fun create(
        parentAccessToken: String,
        idempotencyKey: String,
        displayName: String,
        level: KidLevel,
    ): KidApiResult<KidProfile> =
        call(
            parentAccessToken,
            KIDS_PATH,
            body = json.encodeToString(CreateBody(displayName, level.wireId)),
            idempotencyKey = idempotencyKey,
        ) { data -> json.decodeFromJsonElement<KidEnvelope>(data).kid.toKidProfile() }

    override suspend fun connect(
        parentAccessToken: String,
        kidId: String,
        quickConnectCode: String,
    ): KidApiResult<KidProfile> =
        call(
            parentAccessToken,
            "$KIDS_PATH/$kidId/connect",
            body = json.encodeToString(ConnectBody(quickConnectCode)),
        ) { data -> json.decodeFromJsonElement<KidEnvelope>(data).kid.toKidProfile() }

    private suspend fun <T> call(
        token: String,
        path: String,
        body: String?,
        idempotencyKey: String? = null,
        parse: (JsonElement) -> T,
    ): KidApiResult<T> {
        val url = baseUrl.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}$path".toHttpUrlOrNull() }
        // Builds without the website have no kid routes, the same as the website's flag being off.
        if (url == null) return KidApiResult.Problem(404, KidProblemCodes.KIDS_UNAVAILABLE)
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("X-Request-Id", newRequestId())
                .header("X-WeaselPlex-Client", clientHeader)
                .apply {
                    if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
                    if (body == null) get() else post(body.toRequestBody(JSON_MEDIA_TYPE))
                }.build()
        return try {
            withContext(WholphinDispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response -> handle(response, parse) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.w("Kid profiles request %s failed: %s", path, ex.javaClass.simpleName)
            KidApiResult.NetworkError(ex)
        }
    }

    private fun <T> handle(
        response: Response,
        parse: (JsonElement) -> T,
    ): KidApiResult<T> {
        val text = response.body.string()
        if (response.isSuccessful) {
            val data =
                json.parseToJsonElement(text).jsonObject["data"]
                    ?: throw IllegalStateException("Kid profiles response had no data")
            return KidApiResult.Ok(parse(data))
        }
        val problem = runCatching { json.decodeFromString<ProblemJson>(text) }.getOrNull()
        val retryAfterMillis =
            problem?.retryAfterMs
                ?: response
                    .header("Retry-After")
                    ?.trim()
                    ?.toLongOrNull()
                    ?.let { it * 1000 }
        val code =
            problem?.title?.takeIf { it.isNotBlank() }
                ?: when {
                    // A missing route (website not deployed yet) reads as the flag being off.
                    response.code == 404 -> KidProblemCodes.KIDS_UNAVAILABLE

                    response.code == 429 -> KidProblemCodes.RATE_LIMITED

                    response.code >= 500 -> KidProblemCodes.INTERNAL_ERROR

                    else -> "HTTP_${response.code}"
                }
        Timber.i("Kid profiles problem %s (%d)", code, response.code)
        return KidApiResult.Problem(response.code, code, retryAfterMillis)
    }

    @Serializable
    private data class CreateBody(
        val displayName: String,
        val level: String,
    )

    @Serializable
    private data class ConnectBody(
        val code: String,
    )

    @Serializable
    internal data class KidJson(
        val id: String,
        val displayName: String,
        val level: String,
        val state: String,
        val mediaUserId: String? = null,
        val mediaUserName: String? = null,
    ) {
        // An unknown level only labels the row on the TV (the server policy decides what the kid
        // sees), so it reads as the most restrictive one.
        fun toKidProfile() =
            KidProfile(
                id = id,
                displayName = displayName,
                level = KidLevel.fromWireId(level) ?: KidLevel.KIDS,
                state = state,
                mediaUserId = mediaUserId,
                mediaUserName = mediaUserName,
            )
    }

    @Serializable
    private data class KidListJson(
        val kids: List<KidJson>,
        val limit: Int = DEFAULT_KID_LIMIT,
    )

    @Serializable
    private data class KidEnvelope(
        val kid: KidJson,
    )

    @Serializable
    private data class ProblemJson(
        val title: String? = null,
        val status: Int? = null,
        val retryAfterMs: Long? = null,
    )

    companion object {
        const val KIDS_PATH = "/api/v1/weaselplex/tv/kids"
        const val DEFAULT_KID_LIMIT = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

/** Problem `title` codes from contract section 3.2 that the TV acts on. */
object KidProblemCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val INVALID_JSON = "INVALID_JSON"
    const val INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY"
    const val QUICK_CONNECT_INVALID_CODE = "QUICK_CONNECT_INVALID_CODE"
    const val AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED"
    const val ACCESS_REQUIRED = "WEASELPLEX_ACCESS_REQUIRED"
    const val KID_CALLER_FORBIDDEN = "KID_CALLER_FORBIDDEN"
    const val KIDS_UNAVAILABLE = "WEASELPLEX_KIDS_UNAVAILABLE"
    const val KID_NOT_FOUND = "KID_NOT_FOUND"
    const val KID_LIMIT_REACHED = "KID_LIMIT_REACHED"
    const val KID_NAME_TAKEN = "KID_NAME_TAKEN"
    const val KID_NOT_ACTIVE = "KID_NOT_ACTIVE"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val KID_PROVISIONING_FAILED = "KID_PROVISIONING_FAILED"
    const val MEDIA_PROVIDER_UNAVAILABLE = "MEDIA_ACCESS_PROVIDER_UNAVAILABLE"
    const val WEASELPLEX_UNAVAILABLE = "WEASELPLEX_UNAVAILABLE"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KidProfilesApiModule {
    @Binds
    abstract fun kidProfilesApi(impl: KidProfilesHttpApi): KidProfilesApi
}
