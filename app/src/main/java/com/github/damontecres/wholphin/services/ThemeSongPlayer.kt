package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.github.damontecres.wholphin.kids.KidContentGuard
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.ThemeSongVolume
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.profile.Codec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple service to play theme song music
 */
@OptIn(UnstableApi::class)
@Singleton
class ThemeSongPlayer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
        private val api: ApiClient,
        @param:IoCoroutineScope private val scope: CoroutineScope,
        private val preferences: DataStore<AppPreferences>,
        private val kidContentGuard: KidContentGuard,
    ) {
        private val mutex = Mutex()
        private val state = MutableStateFlow(ThemeSongState())

        private val player: Player by lazy {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(
                        OkHttpDataSource.Factory(authOkHttpClient),
                    ),
                ).build()
        }

        fun playThemeFor(itemId: UUID) {
            scope.launch {
                mutex.withLock {
                    state.value.job?.join()
                    val volumeLevel =
                        when (
                            preferences.data
                                .first()
                                .interfacePreferences.playThemeSongs
                        ) {
                            ThemeSongVolume.UNRECOGNIZED,
                            ThemeSongVolume.DISABLED,
                            -> return@withLock

                            ThemeSongVolume.LOWEST -> .05f

                            ThemeSongVolume.LOW -> .1f

                            ThemeSongVolume.MEDIUM -> .25f

                            ThemeSongVolume.HIGH -> .5f

                            ThemeSongVolume.HIGHEST -> 75f
                        }
                    val job =
                        scope.launchIO {
                            val themeSongs by api.libraryApi.getThemeSongs(itemId)
                            themeSongs.items.randomOrNull()?.let { theme ->
                                // Kid content rules (contract section 10): a fresh check of the song, and of the item
                                // it is the theme of, right before it plays
                                val ownerPermit = kidContentGuard.playbackPermit(api, itemId)
                                val songPermit = kidContentGuard.playbackPermit(api, theme.id)
                                if (!kidContentGuard.isCurrent(ownerPermit) || !kidContentGuard.isCurrent(songPermit)) {
                                    Timber.i("Kid content rules: theme song refused")
                                    return@launchIO
                                }
                                val url =
                                    api.universalAudioApi.getUniversalAudioStreamUrl(
                                        theme.id,
                                        container =
                                            listOf(
                                                Codec.Audio.OPUS,
                                                Codec.Audio.MP3,
                                                Codec.Audio.AAC,
                                                Codec.Audio.FLAC,
                                            ),
                                    )
                                withContext(WholphinDispatchers.Main) {
                                    // Review round 2: only under the session the song was allowed in
                                    if (!kidContentGuard.isCurrent(ownerPermit) || !kidContentGuard.isCurrent(songPermit)) {
                                        return@withContext
                                    }
                                    player.apply {
                                        stop()
                                        volume = volumeLevel
                                        setMediaItem(MediaItem.fromUri(url))
                                        prepare()
                                        play()
                                    }
                                }
                            }
                        }
                    state.update {
                        it.copy(
                            itemId = itemId,
                            job = job,
                        )
                    }
                }
            }
        }

        fun stop() {
            scope.launch {
                mutex.withLock {
                    state.value.job?.cancelAndJoin()
                    withContext(WholphinDispatchers.Main) {
                        Timber.v("Stopping theme song")
                        player.stop()
                    }
                    state.update {
                        it.copy(
                            itemId = null,
                            job = null,
                        )
                    }
                }
            }
        }
    }

data class ThemeSongState(
    val itemId: UUID? = null,
    val job: Job? = null,
)
