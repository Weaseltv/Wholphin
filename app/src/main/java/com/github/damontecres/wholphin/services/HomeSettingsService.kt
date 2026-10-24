package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ApprovedHomeLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.data.model.createStudioDestination
import com.github.damontecres.wholphin.preferences.DefaultUserConfiguration
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.HomeItemFields
import com.github.damontecres.wholphin.ui.ProgramItemFields
import com.github.damontecres.wholphin.ui.components.getGenreImageMap
import com.github.damontecres.wholphin.ui.components.isKidOrUnknown
import com.github.damontecres.wholphin.ui.formatTypeName
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.playback.getTypeFor
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.util.ResArgStringProvider
import com.github.damontecres.wholphin.ui.util.ResProviderStringProvider
import com.github.damontecres.wholphin.ui.util.ResStringProvider
import com.github.damontecres.wholphin.ui.util.StringProvider
import com.github.damontecres.wholphin.ui.util.StringStringProvider
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.GetArtistsHandler
import com.github.damontecres.wholphin.util.GetGenresRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetLiveTvChannelsRequestHandler
import com.github.damontecres.wholphin.util.GetPersonsHandler
import com.github.damontecres.wholphin.util.GetProgramsDtoHandler
import com.github.damontecres.wholphin.util.GetRecordingsRequestHandler
import com.github.damontecres.wholphin.util.GetStudiosRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.HomeRowLoadingState.Success
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetStudiosRequest
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Handles getting home page settings and data
 */
@Singleton
class HomeSettingsService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val userPreferencesService: UserPreferencesService,
        private val navDrawerService: NavDrawerService,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
        private val suggestionService: SuggestionService,
        private val displayPreferencesService: DisplayPreferencesService,
        private val streamingOrderService: StreamingOrderService,
        private val justWatchRowService: JustWatchRowService,
        private val kidHomeCache: KidHomeCache,
    ) {
        @OptIn(ExperimentalSerializationApi::class)
        val jsonParser =
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                allowTrailingComma = true
            }

        /**
         * The current home page settings
         */
        val currentSettings = MutableStateFlow(HomePageResolvedSettings.EMPTY)

        /**
         * Kid content rules (PR #97 review round 1): whether [settings] belong to the session as it is now, read in
         * one snapshot (the epoch first, then the session it covers). A kid or unknown session only takes the kids
         * Home made for it under this epoch; an adult session never takes a kids Home, nor one made for another user
         * or epoch. Home checks this before it shows settings and again whenever the session changes.
         */
        fun isCurrent(settings: HomePageResolvedSettings): Boolean {
            if (settings == HomePageResolvedSettings.EMPTY) return false
            val epoch = serverRepository.sessionEpoch.value
            val user = serverRepository.current.value?.user
            val owner = settings.owner
            return if (isKidOrUnknown(user)) {
                owner != null && owner.kid && owner.userId == user?.id && owner.epoch == epoch
            } else {
                owner == null || (!owner.kid && owner.userId == user?.id && owner.epoch == epoch)
            }
        }

        /** Whether [owner] is the session right now: same user, same epoch, and a kids Home only for a kid session */
        private fun isCurrent(owner: HomeSettingsOwner): Boolean {
            val epoch = serverRepository.sessionEpoch.value
            val user = serverRepository.current.value?.user ?: return false
            return owner.epoch == epoch && owner.userId == user.id && owner.kid == isKidOrUnknown(user)
        }

        /**
         * [currentSettings] again whenever they or the session change, so Home can drop settings that stopped being
         * [isCurrent] and pick up a kids Home's collection rows as they arrive
         */
        val settingsUpdates: Flow<HomePageResolvedSettings>
            get() =
                combine(currentSettings, serverRepository.sessionEpoch, serverRepository.current) { settings, _, _ -> settings }

        /** The first settings that are [isCurrent], re-checked as the session changes */
        suspend fun awaitCurrentSettings(): HomePageResolvedSettings = settingsUpdates.first { isCurrent(it) }

        /** Publish [settings] only while their owner is still the session */
        private fun publish(settings: HomePageResolvedSettings) {
            val owner = settings.owner ?: return
            if (isCurrent(owner)) {
                currentSettings.value = settings
            } else {
                Timber.i("Home settings for an earlier session dropped")
            }
        }

        /** Exposed for tests */
        internal var clock: () -> Long = System::currentTimeMillis

        /** The kids collections last found, for whom and when ([KidHomeCollections.DISCOVERY_INTERVAL]) */
        private data class KidDiscovery(
            val owner: HomeSettingsOwner,
            val collections: List<BaseItemDto>,
            val foundAt: Long,
        )

        @Volatile
        private var kidDiscovery: KidDiscovery? = null
        private val kidDiscoveryMutex = Mutex()

        /** Where the kids Home loads run: not the listener's, so a repeat emission for the same session reuses one */
        internal var kidScope: CoroutineScope = CoroutineScope(SupervisorJob() + WholphinDispatchers.IO)

        /** The kids Home's rows as they arrive, for whom ([KidHomeContent]); Home shows them with [currentSettings] */
        val kidHome = MutableStateFlow<KidHomeContent?>(null)

        private data class KidLoad(
            val owner: HomeSettingsOwner,
            val job: Job,
        )

        private val kidLoadLock = Any()
        private var kidLoad: KidLoad? = null

        /** Kid settings publishes: the saved collection list never replaces one just found */
        private val kidPublishLock = Any()

        /** What a kids Home row fetch needs, read once the session is settled */
        private class KidRowContext(
            val prefs: HomePagePreferences,
            val userDto: UserDto,
            val libraries: Deferred<List<Library>>,
        )

        /**
         * Saves a [HomePageSettings] to the server for the user under the display preference ID
         *
         * @see loadFromServer
         */
        suspend fun saveToServer(
            userId: UUID,
            settings: HomePageSettings,
            displayPreferencesId: String = DisplayPreferencesService.DEFAULT_DISPLAY_PREF_ID,
        ) {
            displayPreferencesService.updateDisplayPreferences(userId, displayPreferencesId) {
                put(
                    CUSTOM_PREF_ID,
                    jsonParser.encodeToString(
                        if (BuildConfig.FLAVOR ==
                            "weaselfin"
                        ) {
                            settings.copy(layoutDefaultsRevision = ApprovedHomeLayout.REVISION)
                        } else {
                            settings
                        },
                    ),
                )
            }
        }

        /**
         * Reads a [HomePageSettings] from the server for the user and display preference ID
         *
         * Returns null if there is none saved
         *
         * @see saveToServer
         */
        suspend fun loadFromServer(
            userId: UUID,
            displayPreferencesId: String = DisplayPreferencesService.DEFAULT_DISPLAY_PREF_ID,
        ): HomePageSettings? =
            displayPreferencesService
                .getDisplayPreferences(userId, displayPreferencesId)
                .customPrefs[CUSTOM_PREF_ID]
                ?.let {
                    val jsonElement = jsonParser.parseToJsonElement(it)
                    decode(jsonElement)
                }

        /**
         * Computes the filename for locally saved [HomePageSettings]
         */
        private fun filename(userId: UUID) = "${CUSTOM_PREF_ID}_${userId.toServerString()}.json"

        /**
         * Save the [HomePageSettings] for the user locally on the device
         *
         * @see loadFromLocal
         */
        @OptIn(ExperimentalSerializationApi::class)
        suspend fun saveToLocal(
            userId: UUID,
            settings: HomePageSettings,
        ) {
            val dir = File(context.filesDir, CUSTOM_PREF_ID)
            dir.mkdirs()
            File(dir, filename(userId)).outputStream().use {
                jsonParser.encodeToStream(
                    if (BuildConfig.FLAVOR ==
                        "weaselfin"
                    ) {
                        settings.copy(layoutDefaultsRevision = ApprovedHomeLayout.REVISION)
                    } else {
                        settings
                    },
                    it,
                )
            }
        }

        /**
         * Reads [HomePageSettings] for the user if it exists
         *
         * @see saveToLocal
         */
        @OptIn(ExperimentalSerializationApi::class)
        suspend fun loadFromLocal(userId: UUID): HomePageSettings? {
            val dir = File(context.filesDir, CUSTOM_PREF_ID)
            val file = File(dir, filename(userId))
            return if (file.exists()) {
                val fileContents = file.readText()
                val jsonElement = jsonParser.parseToJsonElement(fileContents)
                decode(jsonElement)
            } else {
                null
            }
        }

        /**
         * Decodes [HomePageSettings] from a [JsonElement] skipping any unknown/unparsable rows
         *
         * This is public only for testing
         */
        fun decode(element: JsonElement): HomePageSettings {
            val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull
            if (version == null || version > SUPPORTED_HOME_PAGE_SETTINGS_VERSION) {
                throw UnsupportedHomeSettingsVersionException(version)
            }
            val rowsElement = element.jsonObject["rows"]?.jsonArray
            val rows =
                rowsElement
                    ?.mapNotNull { row ->
                        try {
                            jsonParser.decodeFromJsonElement<HomeRowConfig>(row)
                        } catch (ex: Exception) {
                            Timber.w(ex, "Unknown row %s", row)
                            // TODO maybe use placeholder instead of null?
                            null
                        }
                    }.orEmpty()
            return HomePageSettings(
                rows,
                version,
                layoutDefaultsRevision = element.jsonObject["layoutDefaultsRevision"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        /**
         * Loads [HomePageSettings] into [currentSettings]
         *
         * First checks locally, then on the server, and finally creates a default if needed
         *
         * Does not persist either the server nor default
         */
        suspend fun loadCurrentSettings(
            userId: UUID,
            user: JellyfinUser? = null,
        ) {
            // One snapshot of the session (the epoch first). [user] is the saved profile that asked for this load: a
            // policy refresh writes it before the session changes, so a kid on either side means the kids Home.
            val epoch = serverRepository.sessionEpoch.value
            val session = serverRepository.current.value?.user
            if (session == null || session.id != userId) {
                Timber.i("Home settings not loaded for a user who isn't signed in")
                return
            }
            val kid = isKidOrUnknown(session) || (user != null && isKidOrUnknown(user))
            val owner = HomeSettingsOwner(userId, epoch, kid)
            if (kid) {
                loadKidSettings(owner)
                return
            }
            Timber.v("Getting setting for %s", userId)
            // User local then server/remote otherwise create a default
            val settings =
                try {
                    val local = loadFromLocal(userId)
                    Timber.v("Found local? %s", local != null)
                    local
                } catch (ex: Exception) {
                    Timber.w(ex, "Error loading local settings")
                    // TODO show toast?
                    null
                } ?: try {
                    val remote = loadFromServer(userId)
                    Timber.v("Found remote? %s", remote != null)
                    remote
                } catch (ex: Exception) {
                    Timber.w(ex, "Error loading remote settings")
                    null
                }
            val resolvedSettings =
                if (settings != null) {
                    Timber.v("Found settings")
                    // Resolve
                    val resolvedRows =
                        settings.rows.mapIndexed { index, config ->
                            resolve(index, config)
                        }
                    HomePageResolvedSettings(resolvedRows)
                } else {
                    createDefault(userId)
                }

            val homeSettings =
                if (BuildConfig.FLAVOR == "weaselfin") {
                    applyApprovedLayoutOnce(
                        userId,
                        settings?.layoutDefaultsRevision ?: 0,
                        StreamingCollections.withStreamingRow(resolvedSettings),
                    )
                } else {
                    resolvedSettings
                }
            publish(homeSettings.copy(owner = owner))
            if (BuildConfig.FLAVOR == "weaselfin") syncApprovedLayoutToServer(userId, homeSettings)
        }

        /**
         * A kids (or unknown) profile's Home ([KidHomeCollections]): never the saved (adult) rows. Owner 2026-10-23
         * (speed): as soon as the session is ready everything starts at once, without waiting on each other: the saved
         * Home from last time ([KidHomeCache], re-checked), the collection list, Next Up & Continue Watching, Watch
         * Again, and each collection's first page the moment the list returns. Rows land in [kidHome] as they arrive.
         * A repeat load for the same session (eg its saved profile row changed) reuses the one already made.
         */
        private suspend fun loadKidSettings(owner: HomeSettingsOwner) {
            HomeTiming.mark(owner.userId, "kids Home load started")
            val known = kidDiscovery?.takeIf { it.owner == owner }?.collections.orEmpty()
            publish(kidSettings(owner, known))
            val job =
                synchronized(kidLoadLock) {
                    // The same session's load, running or done (one that stopped short, eg before the session
                    // settled, starts again)
                    kidLoad
                        ?.takeIf {
                            it.owner == owner &&
                                (it.job.isActive || kidHome.value?.let { c -> c.owner == owner && c.loaded } == true)
                        }?.job
                        ?: kidScope.launch { runKidLoad(owner, initial = true) }.also {
                            kidLoad?.job?.cancel()
                            kidLoad = KidLoad(owner, it)
                        }
                }
            job.join()
        }

        private fun kidSettings(
            owner: HomeSettingsOwner,
            collections: List<BaseItemDto>,
        ) = KidHomeCollections.settings(owner, context.getString(R.string.kid_home_watch_again), collections)

        /**
         * On entering Home: Next Up & Continue Watching and Watch Again again, and the collections (new or removed
         * ones, or after a failed look) with their first pages, at most once per [KidHomeCollections.DISCOVERY_INTERVAL]
         * after one that worked. Only for the kids Home of the session as it is now, and not while one is loading.
         */
        fun refreshKidHome() {
            val owner = currentSettings.value.owner ?: return
            if (!owner.kid || !isCurrent(owner)) return
            synchronized(kidLoadLock) {
                val running = kidLoad
                if (running != null && running.owner == owner && running.job.isActive) return
                kidLoad = KidLoad(owner, kidScope.launch { runKidLoad(owner, initial = false) })
            }
        }

        /** Stops the kids Home load (eg its screen went away for good) */
        fun cancelKidLoad() {
            synchronized(kidLoadLock) {
                kidLoad?.job?.cancel()
                kidLoad = null
            }
        }

        /**
         * One kids Home load ([loadKidRows]): it stops as soon as [owner] isn't the session any more (another profile,
         * an adult, signed out, a new epoch). A failure outside the rows ends it as loaded (with [KidHomeContent.failed])
         * so the loading picture goes. A watchdog lets the loading picture go too if nothing has shown after
         * [KID_LOAD_TIMEOUT] ([KidHomeSlow]); it never cancels rows still loading, which fill in when they arrive.
         */
        private suspend fun runKidLoad(
            owner: HomeSettingsOwner,
            initial: Boolean,
        ) {
            try {
                coroutineScope {
                    val watchdog =
                        launch {
                            delay(KID_LOAD_TIMEOUT)
                            val settings = currentSettings.value.takeIf { it.owner == owner }
                            updateKidHome(owner) {
                                if (settings != null && it.hasContent(settings)) it else it.copy(failed = it.failed ?: KidHomeSlow())
                            }
                        }
                    try {
                        untilStale(owner) { loadKidRows(owner, initial) }
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Kids Home load failed")
                updateKidHome(owner) { it.copy(loaded = true, failed = ex) }
            }
        }

        /** [block], cancelled the moment [owner] stops being the session */
        private suspend fun <T> untilStale(
            owner: HomeSettingsOwner,
            block: suspend () -> T,
        ): T =
            coroutineScope {
                val body = async { block() }
                val watch =
                    launch {
                        combine(serverRepository.sessionEpoch, serverRepository.current) { _, _ -> isCurrent(owner) }.first { !it }
                        Timber.i("Kids Home load for an earlier session stopped")
                        body.cancel()
                    }
                try {
                    body.await()
                } finally {
                    watch.cancel()
                }
            }

        private suspend fun loadKidRows(
            owner: HomeSettingsOwner,
            initial: Boolean,
        ) {
            val userDto = serverRepository.currentUserDto?.takeIf { it.id == owner.userId && isCurrent(owner) } ?: return
            if (initial) kidHome.update { if (it?.owner == owner) it else KidHomeContent(owner) }
            updateKidHome(owner) { it.copy(loaded = false, failed = null) }
            val prefs = userPreferencesService.getCurrent().appPreferences.homePagePreferences
            val rows = kidSettings(owner, emptyList()).rows
            coroutineScope {
                val libraries =
                    async {
                        try {
                            navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: Exception) {
                            Timber.w(ex, "Kids Home: libraries")
                            emptyList()
                        }
                    }
                val rowContext = KidRowContext(prefs, userDto, libraries)
                if (initial) {
                    launch { paintSavedKidHome(owner, prefs) }
                } else {
                    updateKidHome(owner) { it.copy(cacheRead = true) }
                }
                rows.forEach { row -> launch { fetchKidRow(owner, row, rowContext) } }
                launch { discoverKidCollections(owner, rowContext) }
            }
            updateKidHome(owner) { it.copy(loaded = true) }
            HomeTiming.mark(owner.userId, "kids Home rows all loaded")
            saveKidHome(owner)
        }

        /** The saved Home from last time, for this profile and ceiling only, each row re-checked before it shows */
        private suspend fun paintSavedKidHome(
            owner: HomeSettingsOwner,
            prefs: HomePagePreferences,
        ) {
            val user =
                serverRepository.current.value
                    ?.user
                    ?.takeIf { it.id == owner.userId }
            try {
                user ?: return
                val rules = kidHomeCache.rules() ?: return
                val saved = kidHomeCache.read(user, rules) ?: return
                val collections =
                    saved.collections.map { BaseItemDto(id = UUID.fromString(it.id), type = BaseItemKind.BOX_SET, name = it.name) }
                synchronized(kidPublishLock) {
                    if (kidHome.value?.takeIf { it.owner == owner }?.discovered != true) publish(kidSettings(owner, collections))
                }
                coroutineScope {
                    kidSettings(owner, collections).rows.forEach { display ->
                        val savedRow = KidHomeCache.rowKey(display.config)?.let { saved.rows[it] } ?: return@forEach
                        launch {
                            val items = kidHomeCache.visibleNow(savedRow.items.map { BaseItem(it.dto, it.useSeriesForPrimary) })
                            if (items.isEmpty()) return@launch
                            val row =
                                Success(
                                    display.title,
                                    items,
                                    display.config.viewOptions,
                                    rowType = display.config,
                                    showViewMore = items.size >= prefs.maxItemsPerRow,
                                )
                            // In place of a pending or failed row, never of one fetched fresh
                            updateKidHome(owner) {
                                if (display.config in it.succeeded) it else it.copy(rows = it.rows + (display.config to row))
                            }
                            HomeTiming.mark(owner.userId, "kids Home first saved row")
                        }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // Anything wrong with the saved Home (eg a bad id in it) is a miss, and the file goes
                Timber.w(ex, "Saved kids Home unusable; removed")
                user?.let { kidHomeCache.delete(it.serverId, it.id) }
            } finally {
                updateKidHome(owner) { it.copy(cacheRead = true) }
            }
        }

        private suspend fun fetchKidRow(
            owner: HomeSettingsOwner,
            display: HomeRowConfigDisplay,
            rowContext: KidRowContext,
        ) {
            val row = display.config
            val result =
                try {
                    val libraries = if (row is HomeRowConfig.ContinueWatchingCombined) rowContext.libraries.await() else emptyList()
                    fetchDataForRow(
                        row = row,
                        scope = kidScope,
                        prefs = rowContext.prefs,
                        userDto = rowContext.userDto,
                        libraries = libraries,
                        limit = rowContext.prefs.maxItemsPerRow,
                        isRefresh = false,
                    )
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: InvalidStatusException) {
                    if (ex.status == 404) Success(display.title, emptyList()) else HomeRowLoadingState.Error(display.title, exception = ex)
                } catch (ex: Exception) {
                    Timber.w(ex, "Kids Home row %s", row)
                    HomeRowLoadingState.Error(display.title, exception = ex)
                }
            updateKidHome(owner) {
                if (result is HomeRowLoadingState.Error) {
                    // A failed attempt keeps what the row shows (eg the saved row), and is tried again next time
                    val keep = it.rows[row] is Success
                    it.copy(rows = if (keep) it.rows else it.rows + (row to result), succeeded = it.succeeded - row)
                } else {
                    it.copy(rows = it.rows + (row to result), succeeded = it.succeeded + row)
                }
            }
            if (result is Success && result.items.isNotEmpty()) HomeTiming.mark(owner.userId, "kids Home first fetched row")
        }

        /** [block] on [owner]'s kids Home rows, only while [owner] is the session */
        private fun updateKidHome(
            owner: HomeSettingsOwner,
            block: (KidHomeContent) -> KidHomeContent,
        ) {
            kidHome.update { current ->
                if (!isCurrent(owner)) current else block(current?.takeIf { it.owner == owner } ?: KidHomeContent(owner))
            }
        }

        /** This load's Home for next time: the collection list just found and every row fetched fresh */
        private suspend fun saveKidHome(owner: HomeSettingsOwner) {
            val content = kidHome.value?.takeIf { it.owner == owner } ?: return
            val discovery = kidDiscovery?.takeIf { it.owner == owner } ?: return
            val user =
                serverRepository.current.value
                    ?.user
                    ?.takeIf { it.id == owner.userId } ?: return
            val rules = kidHomeCache.rules() ?: return
            if (!isCurrent(owner)) return
            val rows =
                kidSettings(owner, discovery.collections)
                    .rows
                    .mapNotNull { display ->
                        val key = KidHomeCache.rowKey(display.config) ?: return@mapNotNull null
                        val row = content.rows[display.config] as? Success ?: return@mapNotNull null
                        if (display.config !in content.succeeded) return@mapNotNull null
                        key to
                            KidHomeCache.SavedRow(row.items.filterNotNull().map { KidHomeCache.SavedItem(it.data, it.useSeriesForPrimary) })
                    }.toMap()
            kidHomeCache.write(
                KidHomeCache.Saved(
                    serverId = user.serverId.toString(),
                    userId = user.id.toString(),
                    profileRowId = user.rowId,
                    rules = rules,
                    savedAt = clock(),
                    collections = discovery.collections.map { KidHomeCache.SavedCollection(it.id.toString(), it.name.orEmpty()) },
                    rows = rows,
                ),
                // Checked again inside the cache's lock, right before the file is written: a profile removed (its
                // session ended) meanwhile is never saved back
                stillValid = {
                    isCurrent(owner) && serverRepository.current.value
                        ?.user
                        ?.rowId == user.rowId
                },
            )
        }

        /**
         * The collection list ([KidHomeCollections.fetch]), at most once per [KidHomeCollections.DISCOVERY_INTERVAL]
         * after one that worked, then (with [rowContext]) each collection's first page, all at once
         */
        private suspend fun discoverKidCollections(
            owner: HomeSettingsOwner,
            rowContext: KidRowContext?,
        ) {
            if (!isCurrent(owner)) return
            val found =
                kidDiscoveryMutex.withLock {
                    try {
                        val last = kidDiscovery
                        if (last != null && last.owner == owner &&
                            clock() - last.foundAt < KidHomeCollections.DISCOVERY_INTERVAL.inWholeMilliseconds
                        ) {
                            return@withLock null
                        }
                        val collections =
                            try {
                                KidHomeCollections.fetch(api, owner.userId)
                            } catch (ex: CancellationException) {
                                throw ex
                            } catch (ex: Exception) {
                                // Keep the rows shown; the next visit to Home tries again
                                Timber.w(ex, "Couldn't load the kids collections")
                                return@withLock null
                            }
                        if (!isCurrent(owner)) return
                        kidDiscovery = KidDiscovery(owner, collections, clock())
                        synchronized(kidPublishLock) {
                            publish(kidSettings(owner, collections))
                            updateKidHome(owner) { it.copy(discovered = true) }
                        }
                        collections
                    } finally {
                        updateKidHome(owner) { it.copy(discoveryDone = true) }
                    }
                }
            if (found != null) HomeTiming.mark(owner.userId, "kids collections found")
            rowContext ?: return
            // A list just found: every collection's first page. Otherwise (not due yet, or it failed): the known
            // collections whose row hasn't loaded yet, or failed (review round 2)
            val rows =
                if (found != null) {
                    kidSettings(owner, found).rows.drop(2)
                } else {
                    val succeeded =
                        kidHome.value
                            ?.takeIf { it.owner == owner }
                            ?.succeeded
                            .orEmpty()
                    currentSettings.value
                        .takeIf { it.owner == owner }
                        ?.rows
                        ?.drop(2)
                        .orEmpty()
                        .filter { it.config !in succeeded }
                }
            coroutineScope {
                rows.forEach { row -> launch { fetchKidRow(owner, row, rowContext) } }
            }
        }

        /** Apply the owner-approved layout once; later user edits are retained. */
        private suspend fun applyApprovedLayoutOnce(
            userId: UUID,
            revision: Int,
            settings: HomePageResolvedSettings,
        ): HomePageResolvedSettings {
            if (revision >= ApprovedHomeLayout.REVISION) return settings
            val arranged = if (revision < ApprovedHomeLayout.ARRANGED_ROWS_REVISION) arrangeRows(userId, settings) else settings
            val matched =
                HomePageResolvedSettings(
                    arranged.rows.map { row ->
                        row.copy(
                            config =
                                row.config.updateViewOptions(
                                    ApprovedHomeLayout.upgrade(
                                        row.config.viewOptions,
                                        revision,
                                        StreamingCollections.isStreamingRow(row.config),
                                    ),
                                ),
                        )
                    },
                )
            saveToLocal(userId, HomePageSettings(matched.rows.map { it.config }, SUPPORTED_HOME_PAGE_SETTINGS_VERSION))
            return matched
        }

        /** Put the rows in WeaselPlex's order ([WeaselHomeRows.arrange]), keeping each existing row's ID and title. */
        private suspend fun arrangeRows(
            userId: UUID,
            settings: HomePageResolvedSettings,
        ): HomePageResolvedSettings {
            val libraries = homeLibraries(userId)
            var nextId = (settings.rows.maxOfOrNull { it.id } ?: -1) + 1
            val existing = settings.rows.toMutableList()
            return HomePageResolvedSettings(
                WeaselHomeRows.arrange(settings.rows.map { it.config }, libraries).map { config ->
                    existing.firstOrNull { it.config == config }?.also { existing.remove(it) } ?: resolve(nextId++, config)
                },
            )
        }

        private suspend fun homeLibraries(userId: UUID): List<Library> {
            val userDto = serverRepository.currentUserDto?.takeIf { it.id == userId }
            return navDrawerService.getAllUserLibraries(userId, userDto?.tvAccess ?: false)
        }

        /** Save approved defaults to both Alpha and customer server-backed Home preferences. */
        private suspend fun syncApprovedLayoutToServer(
            userId: UUID,
            settings: HomePageResolvedSettings,
        ) {
            val migrations = context.getSharedPreferences("home_layout_server_migrations", Context.MODE_PRIVATE)
            for (client in listOf(DisplayPreferencesService.DEFAULT_CLIENT, "Wholphin").distinct()) {
                val key = "approved_layout_${ApprovedHomeLayout.REVISION}_${userId}_$client"
                if (migrations.getBoolean(key, false)) continue
                try {
                    val libraries = homeLibraries(userId)
                    displayPreferencesService.updateDisplayPreferences(userId, client = client) {
                        val existing = get(CUSTOM_PREF_ID)?.let { decode(jsonParser.parseToJsonElement(it)) }
                        val base = existing ?: HomePageSettings(settings.rows.map { it.config }, SUPPORTED_HOME_PAGE_SETTINGS_VERSION)
                        if (base.layoutDefaultsRevision < ApprovedHomeLayout.REVISION) {
                            val arranged =
                                if (existing != null && base.layoutDefaultsRevision < ApprovedHomeLayout.ARRANGED_ROWS_REVISION) {
                                    WeaselHomeRows.arrange(base.rows, libraries)
                                } else {
                                    base.rows
                                }
                            val rows =
                                arranged.map { row ->
                                    val options =
                                        ApprovedHomeLayout.upgrade(
                                            row.viewOptions,
                                            if (existing == null) ApprovedHomeLayout.REVISION else base.layoutDefaultsRevision,
                                            StreamingCollections.isStreamingRow(row),
                                        )
                                    row.updateViewOptions(options)
                                }
                            put(
                                CUSTOM_PREF_ID,
                                jsonParser.encodeToString(base.copy(rows = rows, layoutDefaultsRevision = ApprovedHomeLayout.REVISION)),
                            )
                        }
                    }
                    migrations.edit().putBoolean(key, true).apply()
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.w(ex, "Unable to save approved Home defaults for %s", client)
                }
            }
        }

        /**
         * Resolve the settings and set them to be the current settings
         */
        suspend fun updateCurrent(settings: HomePageSettings) {
            val resolvedRows =
                settings.rows.mapIndexed { index, config ->
                    resolve(index, config)
                }
            val resolvedSettings = HomePageResolvedSettings(resolvedRows)
            currentSettings.update {
                if (BuildConfig.FLAVOR == "weaselfin") {
                    StreamingCollections.withStreamingRow(resolvedSettings)
                } else {
                    resolvedSettings
                }
            }
        }

        /**
         * Create a default [HomePageResolvedSettings] using the available libraries
         */
        suspend fun createDefault(userId: UUID): HomePageResolvedSettings {
            Timber.v("Creating default settings")
            val user = serverRepository.currentUser?.takeIf { it.id == userId }
            val userDto = serverRepository.currentUserDto?.takeIf { it.id == userId }
            val libraries =
                if (user != null) {
                    navDrawerService.getFilteredUserLibraries(user, userDto?.tvAccess ?: false)
                } else {
                    navDrawerService.getAllUserLibraries(userId, userDto?.tvAccess ?: false)
                }

            val includedIds =
                libraries
                    .sortedBy { homeLibraryOrder(it.name) }
                    .mapIndexed { index, library ->
                        val parentId = library.itemId
                        val title = getRecentlyAddedTitle(library.name)
                        if (library.collectionType == CollectionType.LIVETV) {
                            HomeRowConfigDisplay(
                                id = index,
                                title = ResStringProvider(R.string.watch_live),
                                config = HomeRowConfig.TvPrograms(),
                            )
                        } else {
                            val viewOptions = viewOptionsForCollectionType(library.collectionType)
                            HomeRowConfigDisplay(
                                id = index,
                                title = title,
                                config =
                                    HomeRowConfig.RecentlyAdded(
                                        parentId = parentId,
                                        viewOptions = viewOptions,
                                    ),
                            )
                        }
                    }
            val continueWatchingRow =
                listOf(
                    HomeRowConfigDisplay(
                        id = includedIds.size + 1,
                        title = ResStringProvider(R.string.combine_continue_next),
                        config = HomeRowConfig.ContinueWatchingCombined(),
                    ),
                )
            val rowConfig = continueWatchingRow + includedIds
            if (BuildConfig.FLAVOR == "weaselfin") {
                return HomePageResolvedSettings(
                    WeaselHomeRows.arrange(rowConfig.map { it.config }, libraries).mapIndexed { index, config ->
                        rowConfig.firstOrNull { it.config == config }?.copy(id = index) ?: resolve(index, config)
                    },
                )
            }
            return HomePageResolvedSettings(rowConfig)
        }

        /**
         * Create home page settings from the user's web UI home page settings
         */
        suspend fun parseFromWebConfig(userId: UUID): HomePageResolvedSettings? {
            val customPrefs =
                displayPreferencesService
                    .getDisplayPreferences(
                        displayPreferencesId = "usersettings",
                        userId = userId,
                        client = "emby",
                    ).customPrefs
            val userDto by api.userApi.getUserById(userId)
            val config = userDto.configuration ?: DefaultUserConfiguration
            val libraries =
                navDrawerService
                    .getAllUserLibraries(userId, userDto.tvAccess)
                    .sortedBy { homeLibraryOrder(it.name) }
                    .filterNot {
                        it.itemId in config.latestItemsExcludes
                    }

            return if (customPrefs.isNotEmpty()) {
                var id = 0
                val rowConfigs =
                    (0..9)
                        .mapNotNull { idx ->
                            val sectionType =
                                HomeSectionType.fromString(customPrefs["homesection$idx"]?.lowercase())
                            Timber.v(
                                "sectionType=$sectionType, %s",
                                customPrefs["homesection$idx"]?.lowercase(),
                            )
                            val config =
                                when (sectionType) {
                                    HomeSectionType.ACTIVE_RECORDINGS -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.active_recordings),
                                            config = HomeRowConfig.Recordings(),
                                        )
                                    }

                                    HomeSectionType.RESUME -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.continue_watching),
                                            config = HomeRowConfig.ContinueWatching(),
                                        )
                                    }

                                    HomeSectionType.NEXT_UP -> {
                                        HomeRowConfigDisplay(
                                            id = id++,
                                            title = ResStringProvider(R.string.next_up),
                                            config = HomeRowConfig.NextUp(),
                                        )
                                    }

                                    HomeSectionType.LIVE_TV -> {
                                        if (userDto.tvAccess) {
                                            HomeRowConfigDisplay(
                                                id = id++,
                                                title = ResStringProvider(R.string.watch_live),
                                                config = HomeRowConfig.TvPrograms(),
                                            )
                                        } else {
                                            null
                                        }
                                    }

                                    HomeSectionType.LATEST_MEDIA -> {
                                        // Handled below
                                        null
                                    }

                                    // Unsupported
                                    HomeSectionType.RESUME_AUDIO,
                                    HomeSectionType.RESUME_BOOK,
                                    -> {
                                        null
                                    }

                                    HomeSectionType.SMALL_LIBRARY_TILES,
                                    HomeSectionType.LIBRARY_BUTTONS,
                                    HomeSectionType.NONE,
                                    null,
                                    -> {
                                        null
                                    }
                                }
                            if (sectionType == HomeSectionType.LATEST_MEDIA) {
                                libraries.map { library ->
                                    val viewOptions =
                                        viewOptionsForCollectionType(library.collectionType)
                                    HomeRowConfigDisplay(
                                        id = id++,
                                        title =
                                            ResArgStringProvider(
                                                R.string.recently_added_in,
                                                library.name ?: "",
                                            ),
                                        config =
                                            HomeRowConfig.RecentlyAdded(
                                                parentId = library.itemId,
                                                viewOptions = viewOptions,
                                            ),
                                    )
                                }
                            } else if (config != null) {
                                listOf(config)
                            } else {
                                null
                            }
                        }.flatten()
                HomePageResolvedSettings(rowConfigs)
            } else {
                null
            }
        }

        /**
         * Converts a [HomeRowConfig] into [HomeRowConfigDisplay] for UI purposes
         */
        suspend fun resolve(
            id: Int,
            config: HomeRowConfig,
        ): HomeRowConfigDisplay =
            when (config) {
                is HomeRowConfig.ByParent -> {
                    val name = getItemName(null, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        name,
                        config,
                    )
                }

                is HomeRowConfig.ContinueWatching -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.continue_watching),
                        config,
                    )
                }

                is HomeRowConfig.ContinueWatchingCombined -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.combine_continue_next),
                        config,
                    )
                }

                is HomeRowConfig.Genres -> {
                    val title = getItemName(R.string.genres_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.Studios -> {
                    val title = getItemName(R.string.studios_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.GetItems -> {
                    val title =
                        if (BuildConfig.FLAVOR == "weaselfin") {
                            StreamingCollections.title(config)
                        } else {
                            config.name
                        }
                    HomeRowConfigDisplay(id, StringStringProvider(title), config)
                }

                is HomeRowConfig.NextUp -> {
                    HomeRowConfigDisplay(
                        id,
                        ResStringProvider(R.string.next_up),
                        config,
                    )
                }

                is HomeRowConfig.RecentlyAdded -> {
                    val title = getItemName(R.string.recently_added_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.RecentlyReleased -> {
                    val title = getItemName(R.string.recently_released_in, config.parentId)
                    HomeRowConfigDisplay(
                        id,
                        title,
                        config,
                    )
                }

                is HomeRowConfig.RecentlyAddedCombined -> {
                    HomeRowConfigDisplay(id, ResStringProvider(R.string.home_recently_added_full), config)
                }

                is HomeRowConfig.JustWatch -> {
                    HomeRowConfigDisplay(id, ResStringProvider(config.list.fullTitle), config)
                }

                is HomeRowConfig.Favorite -> {
                    val name =
                        ResProviderStringProvider(
                            R.string.favorite_items_title,
                            ResStringProvider(formatTypeName(config.kind)),
                        )
                    HomeRowConfigDisplay(id, name, config)
                }

                is HomeRowConfig.Recordings -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.active_recordings),
                        config,
                    )
                }

                is HomeRowConfig.TvPrograms -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.watch_live),
                        config,
                    )
                }

                is HomeRowConfig.TvChannels -> {
                    HomeRowConfigDisplay(
                        id = id,
                        title = ResStringProvider(R.string.channels),
                        config,
                    )
                }

                is HomeRowConfig.Suggestions -> {
                    val title = getItemName(R.string.suggestions_for, config.parentId)
                    HomeRowConfigDisplay(
                        id = id,
                        title = title,
                        config,
                    )
                }
            }

        private suspend fun getItemName(
            @StringRes stringRes: Int?,
            itemId: UUID,
            default: StringProvider = StringStringProvider(""),
        ): StringProvider =
            try {
                api.userLibraryApi
                    .getItem(
                        userId = serverRepository.currentUser?.id,
                        itemId = itemId,
                    ).content.name
                    ?.let {
                        if (stringRes == null) {
                            StringStringProvider(it)
                        } else {
                            ResArgStringProvider(stringRes, it)
                        }
                    } ?: default
            } catch (ex: Exception) {
                Timber.e(ex, "Could not get name for %s", itemId)
                ResStringProvider(R.string.unknown)
            }

        private val mediaLibraries = java.util.concurrent.ConcurrentHashMap<Pair<UUID, UUID>, UUID>()

        /** Resolve real library membership; media type alone cannot distinguish Sports or Comedy. */
        suspend fun resolveItemLibraries(
            items: List<BaseItem>,
            userId: UUID,
            libraries: List<Library>,
        ): List<BaseItem> {
            if (BuildConfig.FLAVOR != "weaselfin") return items
            val libraryIds = libraries.map { it.itemId }.toSet()
            return coroutineScope {
                items
                    .map { item ->
                        async {
                            val ancestorId = item.data.seriesId ?: item.data.parentId ?: item.id
                            val key = userId to ancestorId
                            val direct = listOfNotNull(item.data.parentId, ancestorId).firstOrNull { it in libraryIds }
                            val cached = mediaLibraries[key]?.takeIf { it in libraryIds }
                            val libraryId =
                                direct ?: cached ?: try {
                                    api.libraryApi
                                        .getAncestors(itemId = ancestorId, userId = userId)
                                        .content
                                        .firstOrNull { it.id in libraryIds }
                                        ?.id
                                        ?.also { mediaLibraries[key] = it }
                                } catch (ex: CancellationException) {
                                    throw ex
                                } catch (ex: Exception) {
                                    Timber.w(ex, "Unable to resolve item library")
                                    null
                                }
                            item.copy(libraryId = libraryId)
                        }
                    }.awaitAll()
            }
        }

        /**
         * Fetch the data from the server for a given [HomeRowConfig]
         */
        suspend fun fetchDataForRow(
            row: HomeRowConfig,
            scope: CoroutineScope,
            prefs: HomePagePreferences,
            userDto: UserDto,
            libraries: List<Library>,
            limit: Int = prefs.maxItemsPerRow,
            isRefresh: Boolean,
            usePaging: Boolean = false,
        ): HomeRowLoadingState =
            when (row) {
                is HomeRowConfig.ContinueWatching -> {
                    val resume =
                        latestNextUpService.getResume(
                            userDto.id,
                            limit,
                            true,
                            row.viewOptions.useSeries,
                        )

                    Success(
                        title = ResStringProvider(R.string.continue_watching),
                        items = resolveItemLibraries(resume, userDto.id, libraries),
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = resume.size >= limit,
                    )
                }

                is HomeRowConfig.NextUp -> {
                    val nextUp =
                        latestNextUpService.getNextUp(
                            userDto.id,
                            limit,
                            prefs.enableRewatchingNextUp,
                            false,
                            prefs.maxDaysNextUp,
                            row.viewOptions.useSeries,
                        )

                    Success(
                        title = ResStringProvider(R.string.next_up),
                        items = nextUp,
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = nextUp.size >= limit,
                    )
                }

                is HomeRowConfig.ContinueWatchingCombined -> {
                    // Resume and Next Up at once (owner, 2026-10-23: Resume takes seconds, Next Up a moment)
                    val (resume, nextUp) =
                        coroutineScope {
                            val resume =
                                async {
                                    latestNextUpService.getResume(
                                        userDto.id,
                                        limit,
                                        true,
                                        row.viewOptions.useSeries,
                                    )
                                }
                            val nextUp =
                                async {
                                    latestNextUpService.getNextUp(
                                        userDto.id,
                                        limit,
                                        prefs.enableRewatchingNextUp,
                                        false,
                                        prefs.maxDaysNextUp,
                                        row.viewOptions.useSeries,
                                    )
                                }
                            resume.await() to nextUp.await()
                        }
                    val combined = latestNextUpService.buildCombined(resume, nextUp)

                    Success(
                        title = ResStringProvider(R.string.continue_watching),
                        items = resolveItemLibraries(combined.take(limit), userDto.id, libraries),
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = combined.size >= limit,
                    )
                }

                is HomeRowConfig.Genres -> {
                    val request =
                        GetGenresRequest(
                            parentId = row.parentId,
                            userId = userDto.id,
                            limit = limit,
                        )
                    val items =
                        GetGenresRequestHandler
                            .execute(api, request)
                            .content.items
                    val genreIds = items.map { it.id }
                    val genreImages =
                        getGenreImageMap(
                            api = api,
                            userId = serverRepository.currentUser?.id,
                            scope = scope,
                            imageUrlService = imageUrlService,
                            genres = genreIds,
                            parentId = row.parentId,
                            includeItemTypes = null,
                            cardWidthPx = null,
                            useCache = isRefresh,
                            kidSession = isKidOrUnknown(serverRepository.currentUser),
                        )
                    val library =
                        libraries
                            .firstOrNull { it.itemId == row.parentId }

                    val title =
                        library?.name?.let { ResArgStringProvider(R.string.genres_in, it) }
                            ?: ResStringProvider(R.string.genres)
                    val genres =
                        items.map {
                            BaseItem(
                                it,
                                false,
                                genreImages[it.id],
                                createGenreDestination(
                                    genreId = it.id,
                                    genreName = it.name ?: "",
                                    parentId = row.parentId,
                                    parentName = library?.name,
                                    includeItemTypes =
                                        library?.collectionType?.let {
                                            getTypeFor(it)?.let {
                                                listOf(it)
                                            }
                                        },
                                    collectionType =
                                        library?.collectionType
                                            ?: CollectionType.UNKNOWN,
                                ),
                            )
                        }

                    Success(
                        title,
                        genres,
                        viewOptions = row.viewOptions,
                        rowType = row,
                        showViewMore = genres.size >= limit,
                    )
                }

                is HomeRowConfig.Studios -> {
                    val request =
                        GetStudiosRequest(
                            parentId = row.parentId,
                            userId = userDto.id,
                            limit = limit,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                        )
                    val items =
                        GetStudiosRequestHandler
                            .execute(api, request)
                            .content.items
                    val library =
                        libraries
                            .firstOrNull { it.itemId == row.parentId }
                    val title =
                        library?.name?.let { ResArgStringProvider(R.string.studios_in, it) }
                            ?: ResStringProvider(R.string.studios)
                    val studios =
                        items.map {
                            val imageUrl =
                                imageUrlService.getItemImageUrl(
                                    itemId = it.id,
                                    imageType = ImageType.THUMB,
                                )
                            BaseItem(
                                it,
                                false,
                                imageUrl,
                                createStudioDestination(
                                    studioId = it.id,
                                    name = it.name ?: "",
                                    parentId = row.parentId,
                                    parentName = library?.name,
                                    includeItemTypes =
                                        library?.collectionType?.let {
                                            getTypeFor(it)?.let {
                                                listOf(it)
                                            }
                                        },
                                ),
                            )
                        }

                    Success(
                        title,
                        studios,
                        viewOptions = row.viewOptions,
                        showViewMore = studios.size >= limit,
                    )
                }

                is HomeRowConfig.RecentlyAdded -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val title = getRecentlyAddedTitle(library?.name)
                    val request =
                        GetLatestMediaRequest(
                            fields = library.itemFields,
                            imageTypeLimit = 1,
                            parentId = row.parentId,
                            groupItems = true,
                            limit = limit,
                            isPlayed = null, // Server will handle user's preference
                        )
                    val latest =
                        api.userLibraryApi
                            .getLatestMedia(request)
                            .content
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                            .let {
                                Success(
                                    title,
                                    it,
                                    row.viewOptions,
                                    rowType = row,
                                    showViewMore = it.size >= limit,
                                )
                            }
                    latest
                }

                is HomeRowConfig.RecentlyAddedCombined -> {
                    // Each library's newest, then merged newest first
                    val latest =
                        coroutineScope {
                            row.parentIds
                                .map { parentId ->
                                    async {
                                        try {
                                            api.userLibraryApi
                                                .getLatestMedia(
                                                    GetLatestMediaRequest(
                                                        fields = HomeItemFields + ItemFields.DATE_CREATED,
                                                        imageTypeLimit = 1,
                                                        parentId = parentId,
                                                        groupItems = true,
                                                        limit = limit,
                                                        isPlayed = null, // Server will handle user's preference
                                                    ),
                                                ).content
                                        } catch (ex: InvalidStatusException) {
                                            // A library the member can no longer see
                                            Timber.w(ex, "Recently added unavailable for %s", parentId)
                                            emptyList()
                                        }
                                    }
                                }.awaitAll()
                                .flatten()
                        }.sortedByDescending { it.dateCreated }
                            .take(limit)
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    Success(
                        ResStringProvider(R.string.home_recently_added_full),
                        latest,
                        row.viewOptions,
                        rowType = row,
                        showViewMore = latest.size >= limit,
                    )
                }

                is HomeRowConfig.JustWatch -> {
                    // Each list has its own length, so the member's row length doesn't apply
                    val items = justWatchRowService.items(userDto.id, row.list)
                    Success(
                        ResStringProvider(row.list.fullTitle),
                        items,
                        row.viewOptions,
                        rowType = row,
                        showViewMore = false,
                    )
                }

                is HomeRowConfig.RecentlyReleased -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val title =
                        library?.name?.let {
                            ResArgStringProvider(R.string.recently_released_in, it)
                        } ?: ResStringProvider(R.string.recently_released)
                    val request =
                        GetItemsRequest(
                            parentId = row.parentId,
                            limit = limit,
                            sortBy =
                                listOf(
                                    ItemSortBy.PREMIERE_DATE,
                                    ItemSortBy.SERIES_SORT_NAME,
                                    ItemSortBy.AIRED_EPISODE_ORDER,
                                ),
                            sortOrder =
                                listOf(
                                    SortOrder.DESCENDING,
                                    SortOrder.ASCENDING,
                                    SortOrder.DESCENDING,
                                ),
                            fields = library.itemFields,
                            recursive = true,
                            maxPremiereDate = LocalDateTime.now(),
                            isUnaired = false,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetItemsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        GetItemsRequestHandler
                            .execute(api, request)
                            .content.items
                            .map { BaseItem.from(it, api, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            title,
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.ByParent -> {
                    val library = libraries.firstOrNull { it.itemId == row.parentId }
                    val request =
                        GetItemsRequest(
                            userId = userDto.id,
                            parentId = row.parentId,
                            recursive = row.recursive,
                            sortBy =
                                row.sort?.let {
                                    buildList {
                                        if (it.sort == ItemSortBy.RANDOM) {
                                            add(ItemSortBy.SORT_NAME)
                                            add(ItemSortBy.RANDOM)
                                        } else {
                                            add(it.sort)
                                            if (it.sort != ItemSortBy.SORT_NAME) {
                                                add(ItemSortBy.SORT_NAME)
                                            }
                                        }
                                    }
                                },
                            sortOrder =
                                row.sort?.let {
                                    buildList {
                                        if (it.sort == ItemSortBy.RANDOM) {
                                            add(SortOrder.ASCENDING)
                                            add(it.direction)
                                        } else {
                                            add(it.direction)
                                            if (it.sort != ItemSortBy.SORT_NAME) {
                                                add(SortOrder.ASCENDING)
                                            }
                                        }
                                    }
                                },
                            limit = limit,
                            fields = library.itemFields,
                        )

                    // Not using getItemName because we want to throw the 404. A kids collection row has its name from the
                    // collection list, so no lookup (and no box set probe) for it
                    val kidCollectionName =
                        kidDiscovery
                            ?.takeIf { isKidOrUnknown(serverRepository.current.value?.user) }
                            ?.collections
                            ?.firstOrNull { it.id == row.parentId }
                            ?.name
                    val title =
                        kidCollectionName?.let { StringStringProvider(it) } ?: api.userLibraryApi
                            .getItem(
                                userId = serverRepository.currentUser?.id,
                                itemId = row.parentId,
                            ).content.name
                            ?.let { StringStringProvider(it) }
                            ?: ResStringProvider(R.string.collection)
                    val items =
                        if (usePaging) {
                            ApiRequestPager(
                                api,
                                request,
                                GetItemsRequestHandler,
                                scope,
                                useSeriesForPrimary = row.viewOptions.useSeries,
                            ).init()
                        } else if (isKidOrUnknown(serverRepository.current.value?.user)) {
                            // A kids collection row (the only parent rows a kids Home has): one more page if the kid
                            // content rules left nothing of the first
                            KidHomeCollections
                                .firstPage(limit) { start ->
                                    GetItemsRequestHandler
                                        .execute(api, request.copy(startIndex = start))
                                        .content.items
                                }.map { BaseItem(it, row.viewOptions.useSeries) }
                        } else {
                            GetItemsRequestHandler
                                .execute(api, request)
                                .content.items
                                .map { BaseItem(it, row.viewOptions.useSeries) }
                        }
                    items.let {
                        Success(
                            title,
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.GetItems -> {
                    val streaming = BuildConfig.FLAVOR == "weaselfin" && StreamingCollections.isStreamingRow(row)
                    val request =
                        row.getItems.let {
                            if (it.limit == null) {
                                it.copy(
                                    userId = userDto.id,
                                    limit = limit,
                                )
                            } else {
                                it.copy(
                                    userId = userDto.id,
                                )
                            }
                        }
                    val items =
                        if (streaming) {
                            streamingOrderService.sort(userDto.id, StreamingCollections.fetch(api, userDto.id)).map {
                                // Collection unwatched counts include episodes; these tiles represent services.
                                BaseItem(
                                    it.copy(
                                        userData = it.userData?.copy(unplayedItemCount = null),
                                        childCount = null,
                                        officialRating = null,
                                        overview = it.overview?.replace(" in WeaselPlex", "", ignoreCase = true),
                                    ),
                                    false,
                                )
                            }
                        } else if (usePaging) {
                            ApiRequestPager(
                                api,
                                request,
                                GetItemsRequestHandler,
                                scope,
                                useSeriesForPrimary = row.viewOptions.useSeries,
                            ).init()
                        } else {
                            GetItemsRequestHandler
                                .execute(api, request)
                                .content.items
                                .map { BaseItem(it, row.viewOptions.useSeries) }
                        }
                    Success(
                        StringStringProvider(
                            if (streaming) {
                                StreamingCollections.NAME
                            } else {
                                row.name
                            },
                        ),
                        items,
                        row.viewOptions,
                        rowType = row,
                        showViewMore = !streaming && items.size >= limit,
                    )
                }

                is HomeRowConfig.Favorite -> {
                    val title =
                        ResProviderStringProvider(
                            R.string.favorite_items_title,
                            ResStringProvider(formatTypeName(row.kind)),
                        )
                    val resultList =
                        when (row.kind) {
                            BaseItemKind.PERSON -> {
                                val request =
                                    GetPersonsRequest(
                                        userId = userDto.id,
                                        limit = limit,
                                        fields = HomeItemFields,
                                        isFavorite = true,
                                        enableImages = true,
                                        enableImageTypes = listOf(ImageType.PRIMARY),
                                    )

                                GetPersonsHandler.execute(api, request).toBaseItems()
                            }

                            BaseItemKind.MUSIC_ARTIST -> {
                                val request =
                                    GetArtistsRequest(
                                        userId = userDto.id,
                                        limit = limit,
                                        fields = HomeItemFields,
                                        isFavorite = true,
                                    )
                                if (usePaging) {
                                    ApiRequestPager(
                                        api,
                                        request,
                                        GetArtistsHandler,
                                        scope,
                                        useSeriesForPrimary = row.viewOptions.useSeries,
                                    ).init()
                                } else {
                                    GetArtistsHandler
                                        .execute(api, request)
                                        .toBaseItems(row.viewOptions.useSeries)
                                }
                            }

                            else -> {
                                val fields =
                                    if (row.kind == BaseItemKind.BOX_SET) {
                                        HomeItemFieldsBoxSets
                                    } else {
                                        HomeItemFields
                                    }
                                val request =
                                    GetItemsRequest(
                                        userId = userDto.id,
                                        recursive = true,
                                        limit = limit,
                                        fields = fields,
                                        includeItemTypes = listOf(row.kind),
                                        isFavorite = true,
                                    )
                                if (usePaging) {
                                    ApiRequestPager(
                                        api,
                                        request,
                                        GetItemsRequestHandler,
                                        scope,
                                        useSeriesForPrimary = row.viewOptions.useSeries,
                                    ).init()
                                } else {
                                    GetItemsRequestHandler
                                        .execute(api, request)
                                        .toBaseItems(row.viewOptions.useSeries)
                                }
                            }
                        }
                    resultList.let {
                        Success(
                            title,
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.Recordings -> {
                    val request =
                        GetRecordingsRequest(
                            userId = userDto.id,
                            isInProgress = true,
                            fields = HomeItemFields,
                            limit = limit,
                            enableImages = true,
                            enableUserData = true,
                            enableTotalRecordCount = false,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetRecordingsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getRecordings(request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            ResStringProvider(R.string.active_recordings),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.TvPrograms -> {
                    val request =
                        GetProgramsDto(
                            userId = userDto.id,
                            fields = ProgramItemFields,
                            limit = limit,
                            enableUserData = true,
                            enableImages = true,
                            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.LOGO),
                            imageTypeLimit = 1,
                            isAiring = true,
                            minEndDate = DateTime.now().plusMinutes(1),
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetProgramsDtoHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getPrograms(request)
                            .content.items
                            .map { BaseItem(it, row.viewOptions.useSeries) }
                    }.let {
                        Success(
                            ResStringProvider(R.string.watch_live),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.TvChannels -> {
                    val request =
                        GetLiveTvChannelsRequest(
                            userId = userDto.id,
                            fields = HomeItemFields,
                            limit = limit,
                            enableImages = true,
                        )
                    if (usePaging) {
                        ApiRequestPager(
                            api,
                            request,
                            GetLiveTvChannelsRequestHandler,
                            scope,
                            useSeriesForPrimary = row.viewOptions.useSeries,
                        ).init()
                    } else {
                        api.liveTvApi
                            .getLiveTvChannels(request)
                            .toBaseItems(api, row.viewOptions.useSeries)
                    }.let {
                        Success(
                            ResStringProvider(R.string.channels),
                            it,
                            row.viewOptions,
                            rowType = row,
                            showViewMore = it.size >= limit,
                        )
                    }
                }

                is HomeRowConfig.Suggestions -> {
                    val library =
                        api.userLibraryApi
                            .getItem(itemId = row.parentId)
                            .content
                    val title = ResArgStringProvider(R.string.suggestions_for, library.name ?: "")
                    val itemKind = SuggestionsWorker.getTypeForCollection(library.collectionType)
                    if (itemKind != null) {
                        val suggestions =
                            suggestionService
                                .getSuggestionsFlow(row.parentId, itemKind)
                                .firstOrNull()
                        when (suggestions) {
                            SuggestionsResource.Empty -> {
                                Success(
                                    title,
                                    listOf(),
                                    row.viewOptions,
                                    rowType = row,
                                )
                            }

                            is SuggestionsResource.Success -> {
                                Success(
                                    title,
                                    suggestions.items,
                                    row.viewOptions,
                                    rowType = row,
                                    showViewMore = suggestions.items.size >= limit,
                                )
                            }

                            SuggestionsResource.Loading,
                            null,
                            -> {
                                HomeRowLoadingState.Loading(title)
                            }
                        }
                    } else {
                        HomeRowLoadingState.Error(
                            title = title,
                            message = "Unsupported type ${library.collectionType}",
                        )
                    }
                }
            }

        companion object {
            const val CUSTOM_PREF_ID = "home_settings"
        }
    }

/**
 * A [HomeRowConfig] with a resolved ID and title so it is usable in the UI
 */
data class HomeRowConfigDisplay(
    val id: Int,
    val title: StringProvider,
    val config: HomeRowConfig,
)

/** How long a kids Home load may show nothing before the loading picture goes anyway (PR #98 review rounds 1 and 2) */
private val KID_LOAD_TIMEOUT = 30.seconds

/** Nothing to show yet after [KID_LOAD_TIMEOUT]: the loading picture goes; rows still loading fill in when they arrive */
class KidHomeSlow : IllegalStateException("Home is taking longer than usual to load.")

/**
 * A kids Home's rows for [owner] as they arrive (owner, 2026-10-23: kids Home speed): saved ones from last time
 * (re-checked) first, then each fetched row in its place. [succeeded]: rows fetched fresh this time; a saved row never
 * replaces one of those, and the others (pending or failed) are tried again on the next visit to Home.
 */
data class KidHomeContent(
    val owner: HomeSettingsOwner,
    val rows: Map<HomeRowConfig, HomeRowLoadingState> = emptyMap(),
    val succeeded: Set<HomeRowConfig> = emptySet(),
    /** A collection list was found (and published) this session */
    val discovered: Boolean = false,
    /** The collection list was looked for, found or not */
    val discoveryDone: Boolean = false,
    /** The saved Home was read (or there was none) */
    val cacheRead: Boolean = false,
    /** The load (or refresh) finished: nothing more will arrive until the next one */
    val loaded: Boolean = false,
    /** Why the load ended early (outside any one row), if it did */
    val failed: Throwable? = null,
) {
    /** Whether any of [settings]'s rows has something to show */
    fun hasContent(settings: HomePageResolvedSettings): Boolean =
        settings.rows.any { (rows[it.config] as? HomeRowLoadingState.Success)?.items?.isNotEmpty() == true }
}

/** The session [HomePageResolvedSettings] were made for: [userId] under session [epoch], as a kids Home or not */
data class HomeSettingsOwner(
    val userId: UUID,
    val epoch: Long,
    val kid: Boolean,
)

/**
 * List of resolved [HomeRowConfig]s as [HomeRowConfigDisplay]s
 *
 * @see HomePageSettings
 */
data class HomePageResolvedSettings(
    val rows: List<HomeRowConfigDisplay>,
    /** Whose session these were made for ([HomeSettingsService.isCurrent]); null for edits in Home settings */
    val owner: HomeSettingsOwner? = null,
) {
    companion object {
        val EMPTY = HomePageResolvedSettings(listOf())
    }
}

// https://github.com/jellyfin/jellyfin/blob/v10.11.6/src/Jellyfin.Database/Jellyfin.Database.Implementations/Enums/HomeSectionType.cs
enum class HomeSectionType(
    val serialName: String,
) {
    NONE("none"),
    SMALL_LIBRARY_TILES("smalllibrarytitles"),
    LIBRARY_BUTTONS("librarybuttons"),
    ACTIVE_RECORDINGS("activerecordings"),
    RESUME("resume"),
    RESUME_AUDIO("resumeaudio"),
    LATEST_MEDIA("latestmedia"),
    NEXT_UP("nextup"),
    LIVE_TV("livetv"),
    RESUME_BOOK("resumebook"),
    ;

    companion object {
        fun fromString(homeKey: String?) = homeKey?.let { entries.firstOrNull { it.serialName == homeKey } }
    }
}

class UnsupportedHomeSettingsVersionException(
    val unsupportedVersion: Int?,
    val maxSupportedVersion: Int = SUPPORTED_HOME_PAGE_SETTINGS_VERSION,
) : Exception("Unsupported version $unsupportedVersion, max supported is $maxSupportedVersion")

// Preserve the pre-v1.2.24 Home order independently of the server-controlled menu order.
private fun homeLibraryOrder(name: String): Int {
    if (BuildConfig.FLAVOR != "weaselfin") return Int.MAX_VALUE
    val order =
        listOf("Movies", "TV Shows", "Stand Up Comedy", "UFC", "Boxing", "4K Movies (LAN)", "4K TV Shows (LAN)")
    val index = order.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }
    return if (index >= 0) index else Int.MAX_VALUE
}

fun getRecentlyAddedTitle(name: String?): StringProvider =
    name?.let { ResArgStringProvider(R.string.recently_added_in, it) }
        ?: ResStringProvider(R.string.recently_added)

private val Library?.itemFields: List<ItemFields>
    get() =
        if (this?.type == BaseItemKind.COLLECTION_FOLDER && this.collectionType == CollectionType.BOXSETS) {
            // Get child count to show in the header
            HomeItemFieldsBoxSets
        } else {
            HomeItemFields
        }

private val HomeItemFieldsBoxSets get() = HomeItemFields + listOf(ItemFields.CHILD_COUNT)

fun viewOptionsForCollectionType(collectionType: CollectionType?): HomeRowViewOptions =
    when (collectionType) {
        CollectionType.MUSIC,
        -> {
            HomeRowViewOptions(
                heightDp = if (BuildConfig.FLAVOR == "weaselfin") BuildConfig.DEFAULT_CARD_HEIGHT_DP else Cards.HEIGHT_EPISODE,
                aspectRatio = if (BuildConfig.FLAVOR == "weaselfin") AspectRatio.TALL else AspectRatio.SQUARE,
            )
        }

        CollectionType.PHOTOS,
        CollectionType.HOMEVIDEOS,
        CollectionType.MUSICVIDEOS,
        CollectionType.TRAILERS,
        -> {
            HomeRowViewOptions(
                heightDp = if (BuildConfig.FLAVOR == "weaselfin") BuildConfig.DEFAULT_CARD_HEIGHT_DP else Cards.HEIGHT_EPISODE,
                aspectRatio = if (BuildConfig.FLAVOR == "weaselfin") AspectRatio.TALL else AspectRatio.WIDE,
            )
        }

        CollectionType.LIVETV,
        -> {
            HomeRowViewOptions.liveTvDefault
        }

        CollectionType.MOVIES,
        CollectionType.TVSHOWS,
        CollectionType.BOXSETS,
        CollectionType.BOOKS,
        CollectionType.PLAYLISTS,
        CollectionType.FOLDERS,
        CollectionType.UNKNOWN,
        null,
        -> {
            HomeRowViewOptions()
        }
    }
