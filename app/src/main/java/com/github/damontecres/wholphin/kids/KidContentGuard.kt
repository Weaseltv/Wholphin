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
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

        /** How long one box set's traversal may take (fail closed); exposed for tests */
        internal var boxSetTime: Duration = BOX_SET_TIME

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

        /**
         * A request with the content rules applied for [policy]. [pinned] is the unfiltered client fixed to the
         * credentials [policy] was chosen for; the request and every lookup for it use it (review round 1).
         */
        suspend fun request(
            policy: Policy,
            pinned: CoroutineContextApiClient.Pinned,
            epoch: Long?,
            method: HttpMethod,
            pathTemplate: String,
            pathParameters: Map<String, Any?>,
            queryParameters: Map<String, Any?>,
            requestBody: Any?,
        ): RawResponse {
            val client = pinned.client
            val loader = Loader(client, policy)
            pathItemId(method, pathTemplate, pathParameters)?.let { id ->
                val fresh = isPlaybackPath(pathTemplate)
                if (!isVisible(policy, loader, id, fresh)) {
                    Timber.i("Kid content rules: refused %s for a hidden item", pathTemplate)
                    throw InvalidStatusException(404)
                }
            }
            val response = client.request(method, pathTemplate, pathParameters, withRuleFields(queryParameters), requestBody)
            return stamped(filterResponse(policy, loader, pathTemplate, response), pinned.accessToken, policy, epoch)
        }

        /** The session epoch right now ([ServerRepository.sessionEpoch]); read it before anything it should cover */
        fun epoch(): Long = serverRepository.get().sessionEpoch.value

        /** A request, lookup or decision outlived the session (epoch) it was made for: nothing of it may be used */
        class StaleSessionException : ApiClientException("The session changed while this was being decided")

        /** Playback of [itemId] allowed under session [epoch]; see [isCurrent] */
        data class PlaybackPermit(
            val itemId: UUID,
            val epoch: Long,
        )

        /**
         * Review round 2: whether [permit] still holds, ie the session hasn't changed since it was granted. Checked right
         * before a stream is installed or handed on.
         */
        fun isCurrent(permit: PlaybackPermit?): Boolean = permit != null && (!BuildConfig.KID_PROFILES_ENABLED || permit.epoch == epoch())

        /**
         * [response] marked with the session it was made for: its credentials and, for a filtered one, the rules
         * (review round 1). See [sessionOf].
         */
        fun stamped(
            response: RawResponse,
            accessToken: String?,
            policy: Policy?,
            epoch: Long?,
        ): RawResponse {
            val stamp = mutableMapOf(HEADER_SESSION to listOf(credentialStamp(accessToken)))
            if (policy != null) stamp[HEADER_RULES] = listOf(rulesTag(policy))
            if (epoch != null) stamp[HEADER_EPOCH] = listOf(epoch.toString())
            return RawResponse(response.body, response.status, response.headers + stamp)
        }

        /** Which session a client or a response belongs to (review round 1): null when kid profiles are off */
        data class Session(
            val credentials: String,
            /** The profile, when known: the current session's, if these are its credentials */
            val userId: UUID?,
            /** The rules the session's lists are filtered by ([rulesTag]), null for adults */
            val rules: String?,
            /** The session epoch (review round 2) */
            val epoch: Long?,
        ) {
            /** Whether [response] (a [sessionOf] of headers) was made for this session, in its epoch */
            fun made(response: Session?): Boolean =
                response != null &&
                    response.credentials == credentials &&
                    response.rules == rules &&
                    response.epoch == epoch
        }

        /** The session [api] makes requests for right now, pinned like a request is */
        suspend fun sessionOf(api: ApiClient): Session? {
            if (!BuildConfig.KID_PROFILES_ENABLED) return null
            // The epoch first: anything read after it is at least as new
            val epoch = epoch()
            val pinned = pinnedOf(api)
            val policy = policyFor(pinned.accessToken)
            val userId =
                serverRepository
                    .get()
                    .current.value
                    ?.user
                    ?.takeIf { it.accessToken == pinned.accessToken }
                    ?.id
            return Session(credentialStamp(pinned.accessToken), userId, policy?.let(::rulesTag), epoch)
        }

        /** The session a response with [headers] was made for, or null if it isn't stamped */
        fun sessionOf(headers: Map<String, List<String>>): Session? {
            if (!BuildConfig.KID_PROFILES_ENABLED) return null
            val credentials = header(headers, HEADER_SESSION) ?: return null
            return Session(credentials, null, header(headers, HEADER_RULES), header(headers, HEADER_EPOCH)?.toLongOrNull())
        }

        private fun pinnedOf(api: ApiClient): CoroutineContextApiClient.Pinned =
            (api as? CoroutineContextApiClient)?.pinned() ?: CoroutineContextApiClient.Pinned(api, api.accessToken)

        /**
         * Whether the item [itemId] may start playing for [api]'s user: always a fresh check, never cached, bound to the
         * session epoch it was made in (review round 2). Allowed for adults. Callers keep the permit and check it with
         * [isCurrent] right before the stream is installed.
         */
        suspend fun playbackPermit(
            api: ApiClient,
            itemId: UUID,
        ): PlaybackPermit? {
            val epoch = epoch()
            val pinned = pinnedOf(api)
            val policy = policyFor(pinned.accessToken)
            val allowed =
                policy == null ||
                    isVisible(policy, Loader(pinned.client, policy), KidJson.normalizeId(itemId.toString()), fresh = true)
            return PlaybackPermit(itemId, epoch).takeIf { allowed && isCurrent(it) }
        }

        /** [playbackPermit], still current */
        suspend fun isPlaybackAllowed(
            api: ApiClient,
            itemId: UUID,
        ): Boolean = isCurrent(playbackPermit(api, itemId))

        /**
         * The subset of [itemIds] the current session may see (all of them for adults), for item lists the app kept
         * from earlier (eg a search index): [api] is the session's client
         */
        suspend fun visibleIds(
            api: ApiClient,
            itemIds: Collection<UUID>,
            fresh: Boolean = false,
        ): Set<UUID> {
            val epoch = epoch()
            val visible = visibleIdsNow(api, itemIds, fresh)
            // Review round 2: decided in another session than the current one, it shows nothing
            return if (!BuildConfig.KID_PROFILES_ENABLED || epoch() == epoch) visible else emptySet()
        }

        private suspend fun visibleIdsNow(
            api: ApiClient,
            itemIds: Collection<UUID>,
            fresh: Boolean,
        ): Set<UUID> {
            val pinned = pinnedOf(api)
            val policy = policyFor(pinned.accessToken) ?: return itemIds.toSet()
            if (itemIds.isEmpty()) return emptySet()
            val loader = Loader(pinned.client, policy)
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

        /** Review round 2: the shared budget of one box set's traversal */
        private inner class Traversal(
            val deadlineMillis: Long,
        ) {
            var requestsLeft = BOX_SET_REQUESTS
            var cutOffs = 0

            /** One more request, or [BudgetSpent] (counted as a cut-off) */
            fun spend() {
                if (requestsLeft <= 0 || clock() > deadlineMillis) {
                    cutOffs++
                    throw BudgetSpent()
                }
                requestsLeft--
            }
        }

        private class BudgetSpent : IllegalStateException("Box set traversal budget spent")

        private class Entry<T>(
            val value: T,
            val atMillis: Long,
        )

        /** Lookups for one user; replaced whenever another user's request comes along (so on every user switch) */
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
            /** The box sets being decided above this lookup, for nested box sets */
            private val ancestors: Set<String> = emptySet(),
            /** The box set traversal this lookup is part of, whose budget it spends */
            private val traversal: Traversal? = null,
            /** False while a box set's own children are decided: box sets among them are tried afterwards */
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
                    // A failed batch throws: the filter hides everything it was for. So does one past a box set
                    // traversal's budget
                    traversal?.spend()
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

            /**
             * Pages through the box set's children until one is visible or there are none left, stopping at the first
             * visible one. Its other children are decided first (from the page itself, plus series lookups); box sets
             * among them are only tried after that, one at a time, down to [MAX_BOX_SET_DEPTH] levels. A box set that
             * contains itself (a cycle), sits deeper, or would go past the traversal's budget ([BOX_SET_REQUESTS]
             * requests, [BOX_SET_TIME]) counts as not visible (fail closed). Verdicts are cached for [CACHE_TTL], except
             * a "not visible" that a cut-off decided.
             */
            override suspend fun boxSetHasVisibleChild(
                id: String,
                maxLevel: Int?,
                fresh: Boolean,
            ): Boolean {
                if (maxLevel == null || !nestedBoxSets) return false
                val topLevel = this.traversal == null
                val traversal = this.traversal ?: Traversal(clock() + boxSetTime.inWholeMilliseconds)
                if (id in ancestors || ancestors.size >= MAX_BOX_SET_DEPTH) {
                    traversal.cutOffs++
                    return false
                }
                // Whether a child is visible depends on the level too
                val cacheKey = "$id:$maxLevel"
                if (!fresh) userCache.boxSets.valid(cacheKey)?.let { return it.value }
                val cutOffsBefore = traversal.cutOffs
                val visible =
                    try {
                        if (topLevel) {
                            // Review round 3: the whole traversal, permit waits and network calls included, is bounded in
                            // wall time; running out hides the box set. A cancellation from outside still propagates
                            withTimeoutOrNull(boxSetTime) { searchBoxSet(id, maxLevel, fresh, traversal) } ?: run {
                                traversal.cutOffs++
                                false
                            }
                        } else {
                            searchBoxSet(id, maxLevel, fresh, traversal)
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        traversal.cutOffs++
                        if (ex !is BudgetSpent) Timber.w(ex, "Kid content rules: box set lookup failed")
                        false
                    }
                if (visible || traversal.cutOffs == cutOffsBefore) userCache.boxSets[cacheKey] = Entry(visible, clock())
                return visible
            }

            private suspend fun searchBoxSet(
                id: String,
                maxLevel: Int,
                fresh: Boolean,
                traversal: Traversal,
            ): Boolean {
                val below = ancestors + id
                val ownChildren = Loader(client, policy, below, traversal, nestedBoxSets = false)
                val nestedLoader = Loader(client, policy, below, traversal)
                val nested = ArrayList<String>()
                var startIndex = 0
                for (page in 0 until MAX_BOX_SET_PAGES) {
                    traversal.spend()
                    val response =
                        lookupPermits.withPermit {
                            client.request(
                                HttpMethod.GET,
                                "/Items",
                                emptyMap(),
                                mapOf(
                                    "parentId" to id,
                                    "fields" to RULE_FIELDS,
                                    "startIndex" to startIndex,
                                    "limit" to BOX_SET_PAGE,
                                    "enableImages" to false,
                                    "enableUserData" to false,
                                ),
                                null,
                            )
                        }
                    val root = Json.parseToJsonElement(response.body.decodeToString())
                    val children = KidJson.itemsOf(root)
                    val total = ((root as? JsonObject)?.get("TotalRecordCount") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    // The children that aren't box sets, from this page alone
                    val filtered = KidResponseFilter(maxLevel, ownChildren, fresh).filter(root)
                    if (filtered.root?.let { KidJson.itemsOf(it).isNotEmpty() } == true) return true
                    children.filter { KidJson.factsOf(it).type == KidContentRules.BOX_SET }.mapNotNullTo(nested) { KidJson.idOf(it) }
                    startIndex += BOX_SET_PAGE
                    if (children.size < BOX_SET_PAGE || (total != null && startIndex >= total)) break
                }
                // Then the box sets inside it, until one is visible
                return nested.any { nestedLoader.boxSetHasVisibleChild(it, maxLevel, fresh) }
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
            /** How long lookups decide lists; playback never uses them (review round 1: 2 minutes) */
            val CACHE_TTL = 2.minutes
            private const val MAX_CONCURRENT_LOOKUPS = 4
            private const val LOOKUP_BATCH = 40
            private const val BOX_SET_PAGE = 100

            /** Box set children are paged this far at most (fail closed beyond) */
            private const val MAX_BOX_SET_PAGES = 50

            /** Box sets inside box sets are followed this deep at most (fail closed beyond) */
            private const val MAX_BOX_SET_DEPTH = 4

            /** Requests one box set's traversal may make, nested box sets and series lookups included (fail closed) */
            internal const val BOX_SET_REQUESTS = 20

            /** How long one box set's traversal may take by default (fail closed) */
            private val BOX_SET_TIME = 5.seconds

            /** Marks a filtered response (see [ApiRequestPager][com.github.damontecres.wholphin.util.ApiRequestPager]) */
            const val HEADER_FILTERED = "x-weaselplex-kid-filtered"

            /** How many entries the filter removed from a filtered response's list */
            const val HEADER_REMOVED = "x-weaselplex-kid-removed"

            /** The credentials a response was made with (a digest, see [credentialStamp]) */
            const val HEADER_SESSION = "x-weaselplex-session"

            /** The session epoch a response was made in, for the session's own client (review round 2) */
            const val HEADER_EPOCH = "x-weaselplex-epoch"

            /** The rules a filtered response was filtered by ([rulesTag]) */
            const val HEADER_RULES = "x-weaselplex-kid-rules"

            /** Bump when the rules change, so lists saved under older rules aren't shown */
            const val RULES_VERSION = "kid-rules-v1"

            fun rulesTag(policy: Policy): String = "$RULES_VERSION:${policy.maxLevel}"

            /** A digest of [accessToken], never the token itself */
            fun credentialStamp(accessToken: String?): String {
                if (accessToken == null) return "none"
                val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.encodeToByteArray())
                return digest.take(12).joinToString("") { "%02x".format(it) }
            }

            private fun header(
                headers: Map<String, List<String>>,
                name: String,
            ): String? =
                headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()

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

/** Kid content rules: whether this failure (or its cause) is a load that outlived its session, to drop without an error */
fun Throwable.isStaleSession(): Boolean = generateSequence(this) { it.cause }.take(8).any { it is KidContentGuard.StaleSessionException }
