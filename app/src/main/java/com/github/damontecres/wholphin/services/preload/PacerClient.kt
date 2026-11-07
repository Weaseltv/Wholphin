package com.github.damontecres.wholphin.services.preload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Auth is captured for this playback; never use a newly selected account's token. */
internal class PacerClient(
    client: OkHttpClient,
    private val baseUrl: HttpUrl,
    val authorization: String,
) {
    private val control = client.newBuilder()
        .callTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    // No total deadline: a paced 8 MiB response can legitimately take many minutes.
    val video = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun hello(): Boolean = try {
        control.newCall(request("hello").get().build()).execute().use { response ->
            response.code == 200 &&
                Json.parseToJsonElement(response.body.string()).jsonObject["version"]?.jsonPrimitive?.content == "1"
        }
    } catch (_: Exception) {
        false
    }

    fun report(body: String) {
        try {
            control.newCall(request("report")
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().close()
        } catch (_: IOException) {
            // Reports are advisory. No toast, log noise, or playback failure.
        }
    }

    private fun request(endpoint: String) = Request.Builder()
        .url(baseUrl.newBuilder().encodedPath(baseUrl.encodedPath.trimEnd('/') + "/weaselplex/pacer/v1/$endpoint").query(null).build())
        .header("Authorization", authorization)
}

internal object PreloadPolicy {
    const val CHUNK = 8L * 1024 * 1024
    const val FRAGMENT = 256 * 1024
    const val MINIMUM = 300L * 1000 * 1000
    fun budget(free: Long, total: Long): Long =
        minOf(4_000_000_000L, (free - maxOf(1_500_000_000L, total / 10)).coerceAtLeast(0))

    fun averageBitrate(size: Long, durationSec: Double): Long =
        if (size > 0 && durationSec > 0) (size * 8.0 / durationSec).toLong() else 0

    fun directAhead(bytes: Long, size: Long, durationSec: Double, positionSec: Double): Long =
        if (size > 0 && durationSec > 0) {
            minOf(0.9 * bytes.coerceAtLeast(0) / (size / durationSec), (durationSec - positionSec).coerceAtLeast(0.0)).toLong()
        } else 0
}
