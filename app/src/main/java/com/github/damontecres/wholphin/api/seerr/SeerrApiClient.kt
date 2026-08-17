package com.github.damontecres.wholphin.api.seerr

import com.github.damontecres.wholphin.api.seerr.infrastructure.ApiClient
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

class SeerrApiClient(
    val baseUrl: String,
    private val apiKey: String?,
    okHttpClient: OkHttpClient,
) {
    private val cookieJar = SeerrCookieJar()

    private val client =
        okHttpClient
            .newBuilder()
            .cookieJar(cookieJar)
            .addInterceptor {
                Timber.d("SeerrApiClient: ${it.request().method} ${it.request().url}")
                it.proceed(
                    it
                        .request()
                        .newBuilder()
                        .apply {
                            if (apiKey.isNotNullOrBlank()) header("X-Api-Key", apiKey)
                        }.build(),
                )
            }.build()

    val hasValidCredentials: Boolean
        get() =
            apiKey.isNotNullOrBlank() ||
                cookieJar.hasValidCredentials(baseUrl)

    private fun <T : ApiClient> create(initializer: (String, Call.Factory) -> T): Lazy<T> =
        lazy {
            initializer.invoke(baseUrl, client)
        }

    val authApi by create(::AuthApi)
    val blacklistApi by create(::BlacklistApi)
    val collectionApi by create(::CollectionApi)
    val issueApi by create(::IssueApi)
    val mediaApi by create(::MediaApi)
    val moviesApi by create(::MoviesApi)
    val otherApi by create(::OtherApi)
    val overrideruleApi by create(::OverrideruleApi)
    val personApi by create(::PersonApi)
    val publicApi by create(::PublicApi)
    val requestApi by create(::RequestApi)
    val searchApi by create(::SearchApi)
    val serviceApi by create(::ServiceApi)
    val settingsApi by create(::SettingsApi)
    val tmdbApi by create(::TmdbApi)
    val tvApi by create(::TvApi)
    val usersApi by create(::UsersApi)
    val watchlistApi by create(::WatchlistApi)

    /**
     * WeaselFin: Seerr's Quick Connect login endpoints.
     *
     * These exist in stock Seerr (server/routes/auth.ts) but are absent from the
     * generated client, whose OpenAPI spec predates them. They are therefore called
     * directly — deliberately through the SAME OkHttp instance as everything else, so
     * the session cookie Seerr sets on success lands in the shared cookie jar and every
     * later call is authenticated exactly as if the user had typed a password.
     *
     * Timeouts are inherited from the shared client, so a server that never answers
     * fails rather than hanging.
     */
    fun quickConnectInitiate(): SeerrQuickConnect {
        val req =
            okhttp3.Request
                .Builder()
                .url("${baseUrl.removeSuffix("/")}/api/v1/auth/jellyfin/quickconnect/initiate")
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw SeerrQuickConnectException(
                    when (resp.code) {
                        403 -> "Quick Connect is not enabled on the media server."
                        else -> "Could not start Quick Connect (HTTP ${resp.code})."
                    },
                )
            }
            return json.decodeFromString<SeerrQuickConnect>(body)
        }
    }

    /**
     * Exchanges an approved secret for a Seerr session. The cookie jar captures the
     * session cookie; nothing is persisted by the caller.
     */
    fun quickConnectAuthenticate(secret: String) {
        val payload = json.encodeToString(SeerrQuickConnectSecret(secret))
        val req =
            okhttp3.Request
                .Builder()
                .url("${baseUrl.removeSuffix("/")}/api/v1/auth/jellyfin/quickconnect/authenticate")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SeerrQuickConnectException(
                    when (resp.code) {
                        403 -> "This account is not permitted to use the request service."
                        else -> "Quick Connect sign-in was rejected (HTTP ${resp.code})."
                    },
                )
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
}

@Serializable
data class SeerrQuickConnect(
    val code: String,
    val secret: String,
)

@Serializable
private data class SeerrQuickConnectSecret(
    val secret: String,
)

/** Carries a message fit to show a customer on a TV, never a raw stack trace. */
class SeerrQuickConnectException(
    message: String,
) : Exception(message)

private class SeerrCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        cookies
            .filter { it.name == "connect.sid" }
            .groupBy { it.domain }
            .forEach { (domain, cookies) ->
                this.cookies[domain] = cookies
            }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = this.cookies[url.host].orEmpty()

    fun hasValidCredentials(baseUrl: String): Boolean =
        baseUrl.toHttpUrlOrNull()?.host?.let { domain ->
            cookies[domain]?.any { it.expiresAt > System.currentTimeMillis() }
        } == true
}
