package com.github.damontecres.wholphin.services.tvprovider

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.hilt.work.HiltWorker
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.Channels
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.MainActivity
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.kids.KidProtectionStore
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.IntentService
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.services.WorkerSession
import com.github.damontecres.wholphin.ui.SlimItemFields
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Updates the Android OS resume watching row for a given Jellyfin user
 *
 * @see TvProviderSchedulerService
 */
@HiltWorker
class TvProviderWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted workerParams: WorkerParameters,
        private val serverRepository: ServerRepository,
        private val preferences: DataStore<AppPreferences>,
        private val api: ApiClient,
        private val latestNextUpService: LatestNextUpService,
        private val imageUrlService: ImageUrlService,
        private val kidProtectionStore: KidProtectionStore,
    ) : CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            Timber.d("Start")
            val serverId =
                inputData.getString(PARAM_SERVER_ID)?.toUUIDOrNull() ?: return Result.failure()
            val userId =
                inputData.getString(PARAM_USER_ID)?.toUUIDOrNull() ?: return Result.failure()

            // Kid profiles: whether the scheduled user stopped being the session (then the result to return). Kid
            // content rules (review round 1): also once the launcher rows were cleared or the session changed since
            // this run started; checked under [LauncherRows.locked] together with each write
            // Review round 2: the run is bound to the session epoch it starts in (read first, before the session check).
            // Every request on the shared client fails if the epoch changes while it is under way, and every write
            // checks the epoch under the launcher lock, so nothing fetched for another session is ever written
            val epoch = serverRepository.sessionEpoch.value
            val generation = LauncherRows.generation
            // Review round 3: a protected TV (kid protection on) shows no launcher rows for any profile, so nothing is
            // written while it is protected; checked under the lock right before each write too
            val stale: suspend () -> Result? = {
                when {
                    !BuildConfig.KID_PROFILES_ENABLED -> null
                    kidProtectionStore.isProtected() -> Result.success()
                    serverRepository.sessionEpoch.value != epoch -> Result.success()
                    LauncherRows.generation != generation -> Result.success()
                    else -> WorkerSession.check(serverRepository, serverId, userId)
                }
            }
            if (BuildConfig.KID_PROFILES_ENABLED) {
                stale()?.let { return it }
            } else if (api.baseUrl.isNullOrBlank() || api.accessToken.isNullOrBlank()) {
                // Not active
                var currentUser = serverRepository.current.value
                if (currentUser == null) {
                    serverRepository.restoreSession(serverId, userId)
                    currentUser = serverRepository.current.value
                }
                if (currentUser == null) {
                    Timber.w("No user found during run")
                    return Result.failure()
                }
            }
            try {
                val prefs = preferences.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                val potentialItemsToAdd =
                    getPotentialItems(
                        userId,
                        prefs.homePagePreferences.enableRewatchingNextUp,
                        prefs.homePagePreferences.maxDaysNextUp,
                    )
                // Don't put another profile's items on the launcher
                stale()?.let { return it }
                val potentialItemsToAddIds = potentialItemsToAdd.map { it.id.toString() }

                Timber.v("potentialItemsToAddIds=%s", potentialItemsToAddIds)
                val currentItems = getCurrentTvChannelNextUp()
                val currentItemIds = currentItems.map { it.internalProviderId }

                // TODO Remove after v0.6.5 release
                // This cleans up movies that are using the poster image, see https://github.com/damontecres/Wholphin/issues/1425
                currentItems
                    .filter {
                        it.type == WatchNextPrograms.TYPE_MOVIE &&
                            it.posterArtUri
                                .toString()
                                .lowercase()
                                .contains("primary")
                    }.map { TvContractCompat.buildWatchNextProgramUri(it.id) }
                    .let { uris ->
                        LauncherRows.locked {
                            stale()?.let { return it }
                            uris.forEach { context.contentResolver.delete(it, null, null) }
                        }
                    }
                // End temporary clean up

                val toRemove =
                    currentItems.filterNot { it.internalProviderId in potentialItemsToAddIds }

                val userRemoved = currentItems.filterNot { it.isBrowsable }
                val userRemovedIds = userRemoved.map { it.internalProviderId }
                Timber.v("toRemove (%s)=%s", toRemove.size, toRemove.map { it.internalProviderId })
                val toAdd =
                    potentialItemsToAdd.filter { it.id.toString() !in currentItemIds && it.id.toString() !in userRemovedIds }
                Timber.v("toAdd (%s)=%s", toAdd.size, toAdd.map { it.id })

                // Remove existing items if they are no longer in the next up from server
                LauncherRows.locked {
                    stale()?.let { return it }
                    (toRemove + userRemoved)
                        .map { TvContractCompat.buildWatchNextProgramUri(it.id) }
                        .forEach {
                            context.contentResolver.delete(it, null, null)
                        }
                }

                // Add new ones
                val toInsert = toAdd.map { convert(it).toContentValues() }.toTypedArray()
                val addedCount =
                    LauncherRows.locked {
                        stale()?.let { return it }
                        context.contentResolver.bulkInsert(WatchNextPrograms.CONTENT_URI, toInsert)
                    }
                Timber.v("Added %s", addedCount)

                stale()?.let { return it }
                addOtherChannels(stale)?.let { return it }

                Timber.d("Completed successfully")
            } catch (_: ApiClientException) {
                return Result.retry()
            } catch (_: Exception) {
                return Result.failure()
            }
            return Result.success()
        }

        private suspend fun getPotentialItems(
            userId: UUID,
            enableRewatching: Boolean,
            maxDaysNextUp: Int,
        ): List<BaseItem> {
            val resumeItems = latestNextUpService.getResume(userId, 10, true)
            val seriesIds = resumeItems.mapNotNull { it.data.seriesId }
            val nextUpItems =
                latestNextUpService
                    .getNextUp(userId, 10, enableRewatching, false, maxDaysNextUp)
                    .filter { it.data.seriesId != null && it.data.seriesId !in seriesIds }
            return latestNextUpService.buildCombined(resumeItems, nextUpItems)
        }

        private suspend fun getCurrentTvChannelNextUp(): List<WatchNextProgram> =
            context.contentResolver
                .query(
                    WatchNextPrograms.CONTENT_URI,
                    WatchNextProgram.PROJECTION,
                    null,
                    null,
                    null,
                )?.map(WatchNextProgram::fromCursor)
                .orEmpty()

        private fun convert(item: BaseItem): WatchNextProgram =
            WatchNextProgram
                .Builder()
                .apply {
                    val dto = item.data
                    setInternalProviderId(item.id.toString())

                    val type =
                        when (item.type) {
                            BaseItemKind.EPISODE -> WatchNextPrograms.TYPE_TV_EPISODE
                            BaseItemKind.MOVIE -> WatchNextPrograms.TYPE_MOVIE
                            else -> WatchNextPrograms.TYPE_CLIP
                        }
                    setType(type)

                    val resumePosition = dto.userData?.playbackPositionTicks?.ticks
                    if (resumePosition != null && resumePosition >= 2.minutes) {
                        // https://developer.android.com/training/tv/discovery/guidelines-app-developers#types-of-content
                        setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                        setLastPlaybackPositionMillis(resumePosition.inWholeMilliseconds.toInt())
                    } else {
                        setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                    }
                    dto.runTimeTicks
                        ?.ticks
                        ?.inWholeMilliseconds
                        ?.toInt()
                        ?.let(::setDurationMillis)

                    setLastEngagementTimeUtcMillis(
                        dto.userData
                            ?.lastPlayedDate
                            ?.atZone(ZoneId.systemDefault())
                            ?.toEpochSecond()
                            ?: Date().time, // TODO
                    )

                    setTitle(item.title)
                    setDescription(dto.overview)
                    if (item.type == BaseItemKind.EPISODE) {
                        setEpisodeTitle(item.name)
                        dto.indexNumber?.let(::setEpisodeNumber)
                        dto.parentIndexNumber?.let(::setSeasonNumber)
                    }

                    setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                    val imageType =
                        when (item.type) {
                            BaseItemKind.EPISODE -> ImageType.THUMB
                            else -> if (ImageType.THUMB in item.data.imageTags.orEmpty()) ImageType.THUMB else ImageType.PRIMARY
                        }
                    setPosterArtUri(imageUrlService.getItemImageUrl(item, imageType)!!.toUri())

                    setIntent(
                        Intent(context, MainActivity::class.java)
                            .putExtra(IntentService.INTENT_ITEM_ID, item.id.toString())
                            .putExtra(IntentService.INTENT_ITEM_TYPE, item.type.serialName)
                            .apply {
                                if (item.type == BaseItemKind.EPISODE) {
                                    putExtra(
                                        IntentService.INTENT_SERIES_ID,
                                        dto.seriesId?.toString(),
                                    )
                                    putExtra(
                                        IntentService.INTENT_SEASON_ID,
                                        dto.seasonId?.toString(),
                                    )
                                    dto.parentIndexNumber?.let {
                                        putExtra(
                                            IntentService.INTENT_SEASON_NUMBER,
                                            it,
                                        )
                                    }
                                    dto.indexNumber?.let {
                                        putExtra(
                                            IntentService.INTENT_EPISODE_NUMBER,
                                            it,
                                        )
                                    }
                                }
                            },
                    )
                }.build()

        /**
         * Replaces the Recently Added channel. [stale] is checked after the request and before every provider
         * write; a non-null result means the scheduled user is no longer current, so nothing more is written and
         * that result is returned.
         */
        private suspend fun addOtherChannels(stale: suspend () -> Result?): Result? {
            val preferences = preferences.data.firstOrNull()
            val channelsPrefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

            val latest = fetchLatest(preferences)
            stale()?.let { return it }

            var channelId = channelsPrefs.getString("latest", null)?.toUri()
            if (channelId == null) {
                Timber.d("channelId for latest is null")
                val channel =
                    Channel
                        .Builder()
                        .apply {
                            setDisplayName(context.getString(R.string.recently_added))
                            setType(Channels.TYPE_PREVIEW)
                            setAppLinkIntent(Intent(context, MainActivity::class.java))
                        }.build()
                channelId =
                    LauncherRows.locked {
                        stale()?.let { return it }
                        context.contentResolver.insert(
                            Channels.CONTENT_URI,
                            channel.toContentValues(),
                        )
                    }
                if (channelId != null) {
                    channelsPrefs.edit(true) {
                        putString("latest", channelId.toString())
                    }
                    TvContractCompat.requestChannelBrowsable(
                        context,
                        ContentUris.parseId(channelId),
                    )
                } else {
                    Timber.w("channelId was null")
                    throw IllegalStateException("channelId was null")
                }
            }
            val programs = latest.map { convert(channelId, it).toContentValues() }.toTypedArray()

            // Delete & replace
            val count =
                LauncherRows.locked {
                    stale()?.let { return it }
                    context.contentResolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)
                    context.contentResolver.bulkInsert(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        programs,
                    )
                }
            Timber.v("Inserted $count records")
            return null
        }

        /** The scheduled user's Recently Added items */
        internal suspend fun fetchLatest(preferences: AppPreferences?): List<BaseItem> =
            api.userLibraryApi
                .getLatestMedia(
                    GetLatestMediaRequest(
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                        imageTypeLimit = 1,
                        parentId = null,
                        groupItems = true,
                        limit =
                            preferences?.homePagePreferences?.maxItemsPerRow
                                ?: AppPreference.HomePageItems.defaultValue.toInt(),
                        isPlayed = null, // Server will handle user's preference
                    ),
                ).content
                .map { BaseItem(it, true) }

        private fun convert(
            channelId: Uri,
            item: BaseItem,
        ): PreviewProgram =
            PreviewProgram
                .Builder()
                .apply {
                    setChannelId(ContentUris.parseId(channelId))

                    val dto = item.data
                    setInternalProviderId(item.id.toString())

                    val type =
                        when (item.type) {
                            BaseItemKind.SERIES -> WatchNextPrograms.TYPE_TV_SERIES
                            BaseItemKind.SEASON -> WatchNextPrograms.TYPE_TV_SEASON
                            BaseItemKind.EPISODE -> WatchNextPrograms.TYPE_TV_EPISODE
                            BaseItemKind.MOVIE -> WatchNextPrograms.TYPE_MOVIE
                            else -> WatchNextPrograms.TYPE_CLIP
                        }
                    setType(type)

                    val resumePosition = dto.userData?.playbackPositionTicks?.ticks
                    if (resumePosition != null && resumePosition >= 2.minutes) {
                        // https://developer.android.com/training/tv/discovery/guidelines-app-developers#types-of-content
                        setLastPlaybackPositionMillis(resumePosition.inWholeMilliseconds.toInt())
                    }
                    dto.runTimeTicks
                        ?.ticks
                        ?.inWholeMilliseconds
                        ?.toInt()
                        ?.let(::setDurationMillis)

                    setTitle(item.title)
                    setDescription(dto.overview)
                    if (item.type == BaseItemKind.EPISODE) {
                        setEpisodeTitle(item.name)
                        dto.indexNumber?.let(::setEpisodeNumber)
                        dto.parentIndexNumber?.let(::setSeasonNumber)
                    }

                    setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                    setPosterArtUri(imageUrlService.getItemImageUrl(item, ImageType.THUMB)!!.toUri())

                    setIntent(
                        Intent(context, MainActivity::class.java)
                            .putExtra(IntentService.INTENT_ITEM_ID, item.id.toString())
                            .putExtra(IntentService.INTENT_ITEM_TYPE, item.type.serialName)
                            .apply {
                                if (item.type == BaseItemKind.EPISODE) {
                                    putExtra(
                                        IntentService.INTENT_SERIES_ID,
                                        dto.seriesId?.toString(),
                                    )
                                    putExtra(
                                        IntentService.INTENT_SEASON_ID,
                                        dto.seasonId?.toString(),
                                    )
                                    dto.parentIndexNumber?.let {
                                        putExtra(
                                            IntentService.INTENT_SEASON_NUMBER,
                                            it,
                                        )
                                    }
                                    dto.indexNumber?.let {
                                        putExtra(
                                            IntentService.INTENT_EPISODE_NUMBER,
                                            it,
                                        )
                                    }
                                }
                            },
                    )
                }.build()

        companion object {
            const val WORK_NAME = "com.github.damontecres.wholphin.services.tvprovider.TvProviderWorker"
            const val PARAM_USER_ID = "userId"
            const val PARAM_SERVER_ID = "serverId"
        }
    }

fun <T> Cursor.map(transform: (Cursor) -> T): List<T> =
    this.use {
        buildList {
            if (moveToFirst()) {
                do {
                    add(transform.invoke(this@map))
                } while (moveToNext())
            }
        }
    }

/**
 * Kid content rules (contract section 10, review round 1): launcher writes and clearing never interleave. Clearing and
 * every session change start a new [generation]; a worker run checks, together with each write and under the same
 * lock, that its generation is still the current one, so a run that passed its session check before a clear can't
 * put rows back after it.
 */
internal object LauncherRows {
    @PublishedApi
    internal val mutex = Mutex()

    @Volatile
    var generation = 0L
        private set

    suspend inline fun <T> locked(action: () -> T): T = mutex.withLock { action() }

    /** A session change: runs started before it write nothing more */
    suspend fun invalidate() {
        mutex.withLock { generation++ }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [clear] off the caller's thread, eg right before a session that turns kid protection on is published */
    fun requestClear(context: Context) {
        scope.launch { runCatching { clear(context) }.onFailure { Timber.w(it, "Couldn't clear the launcher rows") } }
    }

    /** Removes this app's Watch Next and preview programs from the launcher, and stops runs started before */
    suspend fun clear(context: Context) {
        mutex.withLock {
            generation++
            context.contentResolver.delete(WatchNextPrograms.CONTENT_URI, null, null)
            context.contentResolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, null, null)
        }
    }
}
