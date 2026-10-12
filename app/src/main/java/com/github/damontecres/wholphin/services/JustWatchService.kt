package com.github.damontecres.wholphin.services

import android.util.Base64
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/** The ranked quick lists on a streaming service collection. */
enum class StreamingQuickList(
    @param:StringRes val title: Int,
) {
    POPULAR(R.string.quick_list_popular),
    TRENDING(R.string.quick_list_trending),
    TOP_10(R.string.quick_list_top_10),

    /** Newest releases first: the collection's own order, and the default. */
    NEW(R.string.quick_list_new),
}

/** A title in a JustWatch ranking, identified the way Jellyfin stores it. */
data class RankedTitle(
    val isMovie: Boolean,
    val tmdbId: String,
)

/**
 * Reads US streaming rankings from JustWatch's public GraphQL API: the same Popular, Trending and
 * daily Top 10 lists its website shows for each service. Results are kept for a few hours.
 */
@Singleton
class JustWatchService
    @Inject
    constructor(
        @param:StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        private val cache = ConcurrentHashMap<Pair<List<String>, StreamingQuickList>, Pair<Long, List<RankedTitle>>>()

        /** The titles ranked by [list] on the JustWatch [packages] (one service), best first. */
        suspend fun ranked(
            packages: List<String>,
            list: StreamingQuickList,
        ): List<RankedTitle> =
            withContext(Dispatchers.IO) {
                val key = packages to list
                cache[key]?.let { (time, titles) ->
                    if (System.currentTimeMillis() - time < CACHE_TIME.inWholeMilliseconds) return@withContext titles
                }
                val titles =
                    when (list) {
                        StreamingQuickList.POPULAR -> popularTitles(packages, "POPULAR")
                        StreamingQuickList.TRENDING -> popularTitles(packages, "TRENDING")
                        StreamingQuickList.TOP_10 -> dailyChart(packages)
                        StreamingQuickList.NEW -> emptyList()
                    }
                Timber.d("JustWatch %s for %s: %s titles", list, packages, titles.size)
                cache[key] = System.currentTimeMillis() to titles
                titles
            }

        /**
         * JustWatch's cursors are the base64 offset ("MTAw" is "100"), so all pages are requested
         * at the same time instead of one after another.
         */
        private suspend fun popularTitles(
            packages: List<String>,
            sortBy: String,
        ): List<RankedTitle> =
            coroutineScope {
                (0 until POPULAR_PAGES)
                    .map { page ->
                        async {
                            val cursor = if (page == 0) null else Base64.encodeToString("${page * PAGE_SIZE}".toByteArray(), Base64.NO_WRAP)
                            query(
                                POPULAR_QUERY,
                                buildJsonObject {
                                    put("country", COUNTRY)
                                    put("language", LANGUAGE)
                                    put("first", PAGE_SIZE)
                                    put("after", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("sortBy", sortBy)
                                    put("filter", buildJsonObject { put("packages", packages.toJsonArray()) })
                                },
                            ).obj("popularTitles")
                                .array("edges")
                                .mapNotNull { it.jsonObject.obj("node").toRankedTitle() }
                        }
                    }.awaitAll()
                    .flatten()
            }

        /** JustWatch's daily chart: separate movie and show charts merged by their shared rank. */
        private fun dailyChart(packages: List<String>): List<RankedTitle> =
            listOf("MOVIE", "SHOW")
                .flatMap { objectType ->
                    query(
                        CHART_QUERY,
                        buildJsonObject {
                            put("country", COUNTRY)
                            put("language", LANGUAGE)
                            put("first", PAGE_SIZE)
                            put(
                                "filter",
                                buildJsonObject {
                                    put("category", "DAILY_POPULARITY_SAME_CONTENT_TYPE")
                                    put("objectType", objectType)
                                    put("packages", packages.toJsonArray())
                                },
                            )
                        },
                    ).obj("streamingCharts")
                        .array("edges")
                        .mapNotNull { edge ->
                            val rank =
                                edge.jsonObject
                                    .obj("streamingChartInfo")["rank"]
                                    ?.jsonPrimitive
                                    ?.intOrNull ?: return@mapNotNull null
                            edge.jsonObject
                                .obj("node")
                                .toRankedTitle()
                                ?.let { rank to it }
                        }
                }.sortedBy { it.first }
                .map { it.second }

        private fun query(
            query: String,
            variables: JsonObject,
        ): JsonObject {
            val body =
                buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                }.toString().toRequestBody(JSON_TYPE)
            val request =
                Request
                    .Builder()
                    .url(ENDPOINT)
                    .post(body)
                    .build()
            okHttpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw IOException("JustWatch returned HTTP ${response.code}")
                val json = Json.parseToJsonElement(text).jsonObject
                json["errors"]?.jsonArray?.firstOrNull()?.let {
                    throw IOException("JustWatch error: ${it.jsonObject["message"]?.jsonPrimitive?.contentOrNull}")
                }
                return json.obj("data")
            }
        }

        private fun JsonObject.toRankedTitle(): RankedTitle? {
            val isMovie =
                when (this["objectType"]?.jsonPrimitive?.contentOrNull) {
                    "MOVIE" -> true
                    "SHOW" -> false
                    else -> return null
                }
            val tmdbId =
                obj("content")
                    .obj("externalIds")["tmdbId"]
                    ?.jsonPrimitive
                    ?.contentOrNull ?: return null
            return RankedTitle(isMovie, tmdbId)
        }

        private fun JsonObject.obj(name: String): JsonObject = (this[name] as? JsonObject) ?: JsonObject(emptyMap())

        private fun JsonObject.array(name: String): List<JsonElement> = (this[name] as? JsonArray).orEmpty()

        private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

        companion object {
            private const val ENDPOINT = "https://apis.justwatch.com/graphql"
            private const val COUNTRY = "US"
            private const val LANGUAGE = "en"
            private const val PAGE_SIZE = 100
            private const val POPULAR_PAGES = 3
            private val CACHE_TIME = 3.hours
            private val JSON_TYPE = "application/json".toMediaType()

            private const val POPULAR_QUERY =
                "query(\$country: Country!, \$language: Language!, \$first: Int!, \$after: String, " +
                    "\$sortBy: PopularTitlesSorting!, \$filter: TitleFilter) { " +
                    "popularTitles(country: \$country, first: \$first, after: \$after, sortBy: \$sortBy, filter: \$filter) { " +
                    "pageInfo { hasNextPage endCursor } " +
                    "edges { node { objectType content(country: \$country, language: \$language) { externalIds { tmdbId } } } } } }"

            private const val CHART_QUERY =
                "query(\$country: Country!, \$language: Language!, \$first: Int!, \$filter: StreamingChartsFilter) { " +
                    "streamingCharts(country: \$country, first: \$first, filter: \$filter) { " +
                    "edges { streamingChartInfo { rank } " +
                    "node { objectType content(country: \$country, language: \$language) { externalIds { tmdbId } } } } } }"
        }
    }
