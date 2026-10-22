package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.JellyfinServerDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidContentRules.Decision
import com.github.damontecres.wholphin.kids.KidContentRules.ItemFacts
import com.github.damontecres.wholphin.util.CoroutineContextApiClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * The kid content rules (contract section 10) at the one choke point every Jellyfin SDK call goes through
 * ([CoroutineContextApiClient.request]). For a kid or unknown session it asks for the metadata the rules need, drops
 * hidden items from every response, answers 404 for a hidden item (by path or as the whole response) and re-checks
 * playback by item id. Adults and upstream builds go straight through: one in-memory comparison per request.
 *
 * Lookups for missing metadata go through the unfiltered client of the request, as the same user, and are cached per
 * user for [CACHE_TTL] (never for playback). Anything that can't be loaded is hidden (fail closed).
 */
@Singleton
class KidContentGuard
    @Inject
    constructor(
        private val serverRepository: dagger.Lazy<ServerRepository>,
        private val serverDao: JellyfinServerDao,
        private val protectionStore: KidProtectionStore,
    ) {
        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        private val lookupPermits = Semaphore(MAX_CONCURRENT_LOOKUPS)

        @Volatile
        private var cache: UserCache? = null

        /** Who a request is for: [maxLevel] is the profile's maximum level, null when its ceiling is unknown */
        data class Policy(
            val userKey: String,
            val maxLevel: Int?,
        )

        /**
         * The filter for a client holding [accessToken], or null if it isn't filtered (an adult, an upstream build, no
         * credentials). The shared session's user is matched in memory; another client's saved profile is looked up by
         * its credentials. Credentials no saved profile has are treated as an unknown ceiling on a protected TV.
         */
        suspend fun policyFor(accessToken: String?): Policy? {
            if (!BuildConfig.KID_PROFILES_ENABLED || accessToken == null) return null
            val current = serverRepository.get().current.value
            if (current != null && current.user.accessToken == accessToken) return policyOf(current.user)
            val row = withContext(WholphinDispatchers.IO) { serverDao.getUserByAccessToken(accessToken) }
            if (row != null) return policyOf(row)
            return if (protectionStore.isProtected()) Policy("token:${accessToken.hashCode()}", null) else null
        }

        /** The current session's filter, null for adults (see [policyFor]) */
        suspend fun currentPolicy(): Policy? =
            policyFor(
                serverRepository
                    .get()
                    .current.value
                    ?.user
                    ?.accessToken,
            )

        private fun policyOf(user: JellyfinUser): Policy? {
            if (user.profileKind == ProfileKind.ADULT) return null
            return Policy("${user.serverId}:${user.id}", KidContentRules.maxLevelFor(user.profileCeiling))
        }

        /** [client]'s request with the content rules applied for [policy]; [client] is the unfiltered client */
        suspend fun request(
            policy: Policy,
            client: ApiClient,
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            val loader = Loader(client, policy)
            pathItemId(method, pathTemplate, pathParameters)?.let { id ->
                val fresh = isPlaybackPath(pathTemplate)
                if (!isVisible(policy, loader, id, fresh)) {
                    Timber.i("Kid content rules: refused %s for a hidden item", pathTemplate)
                    throw InvalidStatusException(404)
                }
            }
            val response = client.request(method, pathTemplate, pathParameters, withRuleFields(queryParameters), requestBody)
            return filterResponse(policy, loader, pathTemplate, response)
        }

        /**
         * Whether the item [itemId] may start playing for [api]'s user: always a fresh check, never cached. True for
         * adults. Used right before a stream starts.
         */
        suspend fun isPlaybackAllowed(
            api: ApiClient,
            itemId: UUID,
        ): Boolean {
            val client = (api as? CoroutineContextApiClient)?.unfiltered ?: api
            val policy = policyFor(api.accessToken) ?: return true
            return isVisible(policy, Loader(client, policy), KidJson.normalizeId(itemId.toString()), fresh = true)
        }

        /**
         * The subset of [itemIds] the current session may see (all of them for adults), for item lists the app kept
         * from earlier (eg a search index): [api] is the session's client
         */
        suspend fun visibleIds(
            api: ApiClient,
            itemIds: Collection<UUID>,
            fresh: Boolean = false,
        ): Set<UUID> {
            val policy = policyFor(api.accessToken) ?: return itemIds.toSet()
            if (itemIds.isEmpty()) return emptySet()
            val client = (api as? CoroutineContextApiClient)?.unfiltered ?: api
            val loader = Loader(client, policy)
            val byId = itemIds.associateBy { KidJson.normalizeId(it.toString()) }
            val facts =
                try {
                    loader.items(byId.keys, fresh)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    // Fail closed
                    Timber.w(ex, "Kid content rules: lookup failed")
                    return emptySet()
                }
            val filter = KidResponseFilter(policy.maxLevel, loader, fresh)
            val visible = HashSet<UUID>()
            for ((id, uuid) in byId) {
                val item = facts[id] ?: continue
                if (filter.decide(item) == Decision.Show) visible.add(uuid)
            }
            return visible
        }

        /** Forgets every cached lookup (eg a user switch); also happens on its own when the user changes */
        fun clearCache() {
            cache = null
        }

        private suspend fun isVisible(
            policy: Policy,
            loader: Loader,
            id: String,
            fresh: Boolean,
        ): Boolean {
            val facts = loader.item(id, fresh) ?: return false
            return KidResponseFilter(policy.maxLevel, loader, fresh).decide(facts) == Decision.Show
        }

        private suspend fun filterResponse(
            policy: Policy,
            loader: Loader,
            pathTemplate: String,
            response: RawResponse,
        ): RawResponse {
            val body = response.body
            if (!looksLikeJson(response, body)) return response
            val root =
                try {
                    Json.parseToJsonElement(body.decodeToString())
                } catch (ex: Exception) {
                    // Never the unfiltered body
                    throw ApiClientException("Kid content rules couldn't read a server response", ex)
                }
            val result = KidResponseFilter(policy.maxLevel, loader).filter(root)
            val filtered = result.root ?: throw InvalidStatusException(404)
            loader.remember(filtered)
            val scrubbed = if (pathTemplate in FILTER_PATHS) scrubHorrorGenres(filtered) else null
            val out = scrubbed ?: filtered
            val headers =
                response.headers +
                    mapOf(
                        HEADER_FILTERED to listOf("1"),
                        HEADER_REMOVED to listOf(result.removedFromList.toString()),
                    )
            val outBody =
                if (result.changed ||
                    scrubbed != null
                ) {
                    Json.encodeToString(JsonElement.serializer(), out).encodeToByteArray()
                } else {
                    body
                }
            return RawResponse(outBody, response.status, headers)
        }

        private fun userCache(policy: Policy): UserCache {
            val existing = cache
            if (existing != null && existing.userKey == policy.userKey) return existing
            return synchronized(this) {
                cache?.takeIf { it.userKey == policy.userKey } ?: UserCache(policy.userKey).also { cache = it }
            }
        }

        private class Entry<T>(
            val value: T,
            val atMillis: Long,
        )

        /** Lookups for one user; replaced whenever another user's request comes along */
        private class UserCache(
            val userKey: String,
        ) {
            /** Item facts by normalized id; a null value: the server didn't return the item to this user */
            val facts = ConcurrentHashMap<String, Entry<ItemFacts?>>()
            val boxSets = ConcurrentHashMap<String, Entry<Boolean>>()
        }

        private fun <T> ConcurrentHashMap<String, Entry<T>>.valid(key: String): Entry<T>? =
            this[key]?.takeIf { clock() - it.atMillis < CACHE_TTL.inWholeMilliseconds }

        /** Lookups through [client], the request's unfiltered client, as [policy]'s user */
        private inner class Loader(
            private val client: ApiClient,
            private val policy: Policy,
            private val nestedBoxSets: Boolean = true,
        ) : KidFactsLoader {
            private val userCache get() = userCache(policy)

            /** One item with every field (works for any item type), or null if the server doesn't return it */
            suspend fun item(
                id: String,
                fresh: Boolean,
            ): ItemFacts? {
                if (!fresh) userCache.facts.valid(id)?.let { return it.value }
                val facts =
                    try {
                        val response =
                            lookupPermits.withPermit {
                                client.request(HttpMethod.GET, "/Items/{itemId}", mapOf("itemId" to id), emptyMap(), null)
                            }
                        val root = Json.parseToJsonElement(response.body.decodeToString())
                        (root as? JsonObject)?.takeIf(KidJson::isItem)?.let(KidJson::factsOf)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: InvalidStatusException) {
                        if (ex.status != 404) Timber.w("Kid content rules: item lookup failed (%s)", ex.status)
                        // Not found for this user: hidden (a 404 is remembered, other failures aren't)
                        if (ex.status == 404) userCache.facts[id] = Entry(null, clock())
                        return null
                    } catch (ex: Exception) {
                        Timber.w(ex, "Kid content rules: item lookup failed")
                        return null
                    }
                userCache.facts[id] = Entry(facts, clock())
                return facts
            }

            override suspend fun items(
                ids: Collection<String>,
                fresh: Boolean,
            ): Map<String, ItemFacts> {
                val result = HashMap<String, ItemFacts>()
                val missing = ArrayList<String>()
                ids.forEach { id ->
                    val cached = if (fresh) null else userCache.facts.valid(id)
                    when {
                        cached == null -> missing.add(id)
                        cached.value != null -> result[id] = cached.value
                    }
                }
                missing.chunked(LOOKUP_BATCH).forEach { chunk ->
                    // A failed batch throws: the filter hides everything it was for
                    val response =
                        lookupPermits.withPermit {
                            client.request(
                                HttpMethod.GET,
                                "/Items",
                                emptyMap(),
                                mapOf(
                                    "ids" to chunk,
                                    "fields" to RULE_FIELDS,
                                    "enableImages" to false,
                                    "enableUserData" to false,
                                ),
                                null,
                            )
                        }
                    val found =
                        KidJson
                            .itemsOf(Json.parseToJsonElement(response.body.decodeToString()))
                            .map(KidJson::factsOf)
                            .filter { it.id != null }
                            .associateBy { it.id!! }
                    val now = clock()
                    chunk.forEach { id ->
                        val facts = found[id]
                        userCache.facts[id] = Entry(facts, now)
                        if (facts != null) result[id] = facts
                    }
                }
                return result
            }

            override suspend fun boxSetHasVisibleChild(
                id: String,
                maxLevel: Int?,
                fresh: Boolean,
            ): Boolean {
                // A box set inside a box set counts as not visible: no recursion
                if (!nestedBoxSets || maxLevel == null) return false
                // Whether a child is visible depends on the level too
                val cacheKey = "$id:$maxLevel"
                if (!fresh) userCache.boxSets.valid(cacheKey)?.let { return it.value }
                val response =
                    lookupPermits.withPermit {
                        client.request(
                            HttpMethod.GET,
                            "/Items",
                            emptyMap(),
                            mapOf(
                                "parentId" to id,
                                "fields" to RULE_FIELDS,
                                "limit" to BOX_SET_CHILDREN,
                                "enableImages" to false,
                                "enableUserData" to false,
                            ),
                            null,
                        )
                    }
                val root = Json.parseToJsonElement(response.body.decodeToString())
                val filtered = KidResponseFilter(maxLevel, Loader(client, policy, nestedBoxSets = false), fresh).filter(root)
                val visible = filtered.root?.let { KidJson.itemsOf(it).isNotEmpty() } == true
                userCache.boxSets[cacheKey] = Entry(visible, clock())
                return visible
            }

            /** Keeps the complete facts of the items a filtered response showed, for later path checks */
            fun remember(root: JsonElement) {
                val now = clock()

                fun visit(element: JsonElement) {
                    when (element) {
                        is JsonObject -> {
                            if (KidJson.isItem(element)) {
                                val facts = KidJson.factsOf(element)
                                if (facts.id != null && facts.genres != null && facts.lockedFields != null) {
                                    userCache.facts[facts.id] = Entry(facts, now)
                                }
                            }
                            element.values.forEach(::visit)
                        }

                        is JsonArray -> {
                            element.forEach(::visit)
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                visit(root)
            }
        }

        companion object {
            val CACHE_TTL = 10.minutes
            private const val MAX_CONCURRENT_LOOKUPS = 4
            private const val LOOKUP_BATCH = 40
            private const val BOX_SET_CHILDREN = 200

            /** Marks a filtered response (see [ApiRequestPager][com.github.damontecres.wholphin.util.ApiRequestPager]) */
            const val HEADER_FILTERED = "x-weaselplex-kid-filtered"

            /** How many entries the filter removed from a filtered response's list */
            const val HEADER_REMOVED = "x-weaselplex-kid-removed"

            /**
             * How many entries the kid content rules removed from the list of a response with [headers], or null if
             * the response wasn't filtered (adults, upstream builds)
             */
            fun removedBy(headers: Map<String, List<String>>): Int? {
                if (!BuildConfig.KID_PROFILES_ENABLED) return null
                headers.keys.firstOrNull { it.equals(HEADER_FILTERED, ignoreCase = true) } ?: return null
                val removed = headers.entries.firstOrNull { it.key.equals(HEADER_REMOVED, ignoreCase = true) }?.value
                return removed?.firstOrNull()?.toIntOrNull() ?: 0
            }

            val RULE_FIELDS = listOf(ItemFields.GENRES, ItemFields.SETTINGS)

            /** `/Items/Filters` lists genres by name; the horror genre is hidden there too */
            private val FILTER_PATHS = setOf("/Items/Filters", "/Items/Filters2")

            private val PLAYBACK_PATHS = listOf("/Items/{itemId}/PlaybackInfo", "/Videos/{itemId}/", "/Audio/{itemId}/")

            /** Path parameters naming the item a request is about */
            private val ITEM_PATH_PARAMETERS = listOf("itemId", "seriesId")

            /**
             * The item a request is about, if its path names one. Fetching an item itself is decided on the response
             * instead (one request, and the item is the response).
             */
            internal fun pathItemId(
                method: HttpMethod,
                pathTemplate: String,
                pathParameters: Map<String, Any?>,
            ): String? {
                if (method == HttpMethod.GET && pathTemplate == "/Items/{itemId}") return null
                val value = ITEM_PATH_PARAMETERS.firstNotNullOfOrNull { pathParameters[it] } ?: return null
                return KidJson.normalizeId(value.toString())
            }

            internal fun isPlaybackPath(pathTemplate: String): Boolean = PLAYBACK_PATHS.any { pathTemplate.startsWith(it) }

            /** [queryParameters] asking for the rules' fields, when the endpoint takes `fields`; otherwise unchanged */
            internal fun withRuleFields(queryParameters: Map<String, Any?>): Map<String, Any?> {
                if (!queryParameters.containsKey("fields")) return queryParameters
                val existing =
                    when (val value = queryParameters["fields"]) {
                        null -> emptyList()
                        is Collection<*> -> value.filterNotNull()
                        else -> listOf(value)
                    }
                val names = existing.map { (it as? ItemFields)?.serialName ?: it.toString() }.toSet()
                val added = RULE_FIELDS.filterNot { it.serialName in names }
                if (added.isEmpty()) return queryParameters
                return queryParameters + ("fields" to existing + added)
            }

            private fun looksLikeJson(
                response: RawResponse,
                body: ByteArray,
            ): Boolean {
                val contentType =
                    response.headers.entries
                        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                        ?.value
                        ?.firstOrNull()
                if (contentType?.contains("json", ignoreCase = true) == true) return body.isNotEmpty()
                val first = body.firstOrNull { !it.toInt().toChar().isWhitespace() } ?: return false
                return first == '{'.code.toByte() || first == '['.code.toByte()
            }

            /** `/Items/Filters` without genres containing "horror"; null if there were none */
            internal fun scrubHorrorGenres(root: JsonElement): JsonElement? {
                if (root !is JsonObject) return null
                val genres = root["Genres"] as? JsonArray ?: return null
                val kept =
                    genres.filterNot { genre ->
                        val name =
                            when (genre) {
                                is JsonPrimitive -> genre.contentOrNull
                                is JsonObject -> (genre["Name"] as? JsonPrimitive)?.contentOrNull
                                else -> null
                            }
                        name?.contains("horror", ignoreCase = true) == true
                    }
                if (kept.size == genres.size) return null
                return JsonObject(root + ("Genres" to JsonArray(kept)))
            }
        }
    }
