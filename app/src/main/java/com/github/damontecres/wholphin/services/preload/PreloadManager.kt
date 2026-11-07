@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.ui.playback.CurrentPlayback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreloadManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:StandardOkHttpClient private val client: OkHttpClient,
    private val api: ApiClient,
    private val clientInfo: ClientInfo,
    private val deviceInfo: DeviceInfo,
) {
    private val directory = File(context.filesDir, "preload")
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: PreloadSession? = null
    @Volatile private var playbackOwner: Any? = null
    private var previous: PreloadSession? = null
    private var attached: Player? = null
    @Volatile private var lowStorage = false
    private var initialized = false
    private var checkedPlayback: String? = null
    private var supported = false
    @Volatile private var generation = 0L
    private var seenReady = false
    private var seeking = false

    fun initialize() {
        // Runs before any Activity/player is created, including after a force-stop/crash.
        if (initialized) return
        initialized = true
        try { PreloadStore.deleteDirectory(directory, "app-start", androidx.media3.database.StandaloneDatabaseProvider(context)) } catch (_: Exception) {
            lowStorage = true
            Timber.tag("Preload").w("Cannot remove crash leftovers; pre-load disabled")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_DEVICE_STORAGE_LOW -> { lowStorage = true; stop("android-low-storage") }
                    Intent.ACTION_DEVICE_STORAGE_OK -> lowStorage = false
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_DEVICE_STORAGE_LOW); addAction(Intent.ACTION_DEVICE_STORAGE_OK) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun enabledFor(owner: Any): Boolean = session != null && playbackOwner === owner

    val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (session != null) androidx.media3.common.C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

        override fun getMinimumLoadableRetryCount(dataType: Int): Int =
            if (session != null) 0 else super.getMinimumLoadableRetryCount(dataType)
    }

    fun dataSources(): DataSource.Factory {
        val original = DefaultDataSource.Factory(context)
        return DataSource.Factory { SessionSource(original) }
    }

    fun extractors(original: androidx.media3.extractor.ExtractorsFactory): androidx.media3.extractor.ExtractorsFactory =
        object : androidx.media3.extractor.ExtractorsFactory by original {
            private fun wrap(extractors: Array<androidx.media3.extractor.Extractor>): Array<androidx.media3.extractor.Extractor> {
                val owner = session?.takeIf { !it.media.hls } ?: return extractors
                return extractors.map { extractor ->
                    object : androidx.media3.extractor.Extractor by extractor {
                        override fun seek(position: Long, timeUs: Long) {
                            owner.directSeek(position, timeUs)
                            extractor.seek(position, timeUs)
                        }
                    }
                }.toTypedArray()
            }
            override fun createExtractors() = wrap(original.createExtractors())
            override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>) = wrap(original.createExtractors(uri, responseHeaders))
            override fun setSubtitleParserFactory(factory: androidx.media3.extractor.text.SubtitleParser.Factory): androidx.media3.extractor.ExtractorsFactory {
                original.setSubtitleParserFactory(factory); return this
            }
            override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): androidx.media3.extractor.ExtractorsFactory {
                original.experimentalSetTextTrackTranscodingEnabled(enabled); return this
            }
            override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): androidx.media3.extractor.ExtractorsFactory {
                original.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs); return this
            }
            override fun setParseHagcMetadata(enabled: Boolean): androidx.media3.extractor.ExtractorsFactory {
                original.setParseHagcMetadata(enabled); return this
            }
        }

    /** Non-video resources keep the original data source. On low storage, an already-open
     * reader resumes through the original source after the writer has closed/deleted disk data. */
    private inner class SessionSource(private val original: DataSource.Factory) : DataSource {
        private var source: DataSource? = null
        private var owner: PreloadSession? = null
        private lateinit var spec: androidx.media3.datasource.DataSpec
        private var consumed = 0L
        private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
        override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
            listeners.add(listener)
            source?.addTransferListener(listener)
        }
        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            spec = dataSpec
            consumed = 0
            owner = session?.takeIf { !it.storageFallback && it.owns(spec.uri.toString()) }
            source = owner?.factory()?.createDataSource() ?: original.createDataSource()
            listeners.forEach { source!!.addTransferListener(it) }
            return try { source!!.open(spec) } catch (ex: Exception) {
                if (owner?.storageFallback != true) throw ex
                resumeNetwork()
            }
        }
        private fun resumeNetwork(): Long {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Reader canceled")
            source?.close()
            owner?.join()
            synchronized(this@PreloadManager) { if (session === owner) stop("low-storage-network-fallback") }
            owner = null
            source = original.createDataSource()
            listeners.forEach { source!!.addTransferListener(it) }
            return source!!.open(spec.subrange(consumed))
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (owner?.storageFallback == true) resumeNetwork()
            val count = try { source!!.read(buffer, offset, length) } catch (ex: Exception) {
                if (owner?.storageFallback != true) throw ex
                resumeNetwork()
                source!!.read(buffer, offset, length)
            }
            if (count > 0) consumed += count
            return count
        }
        override fun getUri() = source?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders ?: emptyMap()
        override fun close() { source?.close(); source = null; owner = null }
    }

    /** Called with the old player stopped, before installing the new MediaItem. */
    suspend fun prepare(playback: CurrentPlayback, url: String, prefs: AppPreferences, startMs: Long, owner: Any? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (owner != null && playbackOwner !== owner) return@withLock
            stop("new-playback")
            val requestGeneration = generation
            previous?.join()
            previous = null
            if (directory.exists()) {
                try { PreloadStore.deleteDirectory(directory, "before-new-playback", androidx.media3.database.StandaloneDatabaseProvider(context)) } catch (_: Exception) {
                    lowStorage = true
                    return@withLock
                }
            }
            if (!initialized) initialize()
            val source = playback.mediaSourceInfo
            val base = api.baseUrl?.toHttpUrlOrNull()
            val stream = url.toHttpUrlOrNull()
            val sessionId = playback.playSessionId
            val duration = (source.runTimeTicks ?: playback.item.data.runTimeTicks ?: 0) / 10_000_000.0
            val size = source.size ?: 0
            val hls = stream?.encodedPath?.endsWith(".m3u8", true) == true || source.container.equals("hls", true)
            val enabled = AppPreference.PreloadVideo.getter(prefs)
            if (!enabled || lowStorage || playback.backend != PlayerBackend.EXO_PLAYER || base == null || stream == null || sessionId == null || source.id == null ||
                base.scheme != stream.scheme || base.host != stream.host || base.port != stream.port || duration <= 0 || (!hls && size <= 0)) return@withLock
            // DASH/remote files retain the existing path. v1's contract covers direct files and HLS.
            if (!hls && playback.playMethod != org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY) return@withLock
            val auth = AuthorizationHeaderBuilder.buildHeader(clientName = clientInfo.name, clientVersion = clientInfo.version,
                deviceId = deviceInfo.id, deviceName = deviceInfo.name, accessToken = api.accessToken)
            val pacer = PacerClient(client, base, auth)
            val identity = "${playback.item.id}:$sessionId"
            if (checkedPlayback != identity) {
                checkedPlayback = identity
                supported = pacer.hello()
                Timber.tag("Preload").i("capability=%s; backend=ExoPlayer", if (supported) "v1" else "unavailable; normal playback")
            }
            if (!supported || generation != requestGeneration) return@withLock
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val disk = StatFs(context.filesDir.absolutePath)
            val budget = PreloadPolicy.budget(disk.availableBytes, disk.totalBytes)
            if (budget < PreloadPolicy.MINIMUM) {
                Timber.tag("Preload").i("normal playback: storage budget below 300 MB")
                return@withLock
            }
            try {
                val store = PreloadStore(directory, budget, androidx.media3.database.StandaloneDatabaseProvider(context))
                val bitrate = if (hls) (source.bitrate ?: 0).toLong() else PreloadPolicy.averageBitrate(size, duration)
                if (bitrate <= 0) { store.close("unknown-bitrate"); return@withLock }
                val created = PreloadSession(PreloadMedia(url, sessionId, playback.item.id.toString(), source.id!!, hls, size, duration, bitrate),
                    pacer, store, { StatFs(context.filesDir.absolutePath).availableBytes }, { StatFs(context.filesDir.absolutePath).totalBytes })
                created.update(startMs.coerceAtLeast(0) / 1000.0, false)
                synchronized(this@PreloadManager) {
                    if (generation == requestGeneration && !lowStorage) {
                        session = created
                        created.start()
                    } else store.close("playback-canceled-before-start")
                }
            } catch (ex: Exception) {
                Timber.tag("Preload").w("normal playback: cannot open storage (%s)", ex.javaClass.simpleName)
                PreloadStore.deleteDirectory(directory, "storage-open-failed", androidx.media3.database.StandaloneDatabaseProvider(context))
            }
        }
    }

    fun attach(player: Player, owner: Any? = null) {
        if (owner != null && playbackOwner !== owner) return
        detach()
        if (session == null) return
        attached = player
        seenReady = false
        seeking = false
        player.addListener(listener)
        tick.run()
    }

    private val tick = object : Runnable {
        override fun run() {
            val player = attached ?: return
            if (session?.storageFallback == true) { stop("low-storage-network-fallback"); return }
            session?.update(player.currentPosition / 1000.0, !player.playWhenReady)
            main.postDelayed(this, 250)
        }
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (com.github.damontecres.wholphin.BuildConfig.DEBUG && session != null) {
                Timber.tag("PreloadPlayer").i("state=%d positionMs=%d", playbackState, attached?.currentPosition ?: -1)
            }
            if (playbackState == Player.STATE_READY) { seenReady = true; seeking = false }
            if (playbackState == Player.STATE_BUFFERING && seenReady && !seeking && attached?.playWhenReady == true) session?.rebuffer()
            if (playbackState == Player.STATE_ENDED) stop("playback-ended")
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            attached?.let { session?.update(it.currentPosition / 1000.0, !playWhenReady) }
            session?.event()
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                if (com.github.damontecres.wholphin.BuildConfig.DEBUG && session != null) {
                    Timber.tag("PreloadPlayer").i("seek positionMs=%d", newPosition.positionMs)
                }
                seeking = true
                session?.seek(newPosition.positionMs / 1000.0)
            }
        }
    }

    private fun detach() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attached?.removeListener(listener)
            attached = null
            main.removeCallbacks(tick)
        } else main.post { detach() }
    }

    @Synchronized
    fun begin(owner: Any) {
        stop("another-item-started")
        playbackOwner = owner
    }

    @Synchronized
    fun stop(reason: String, owner: Any? = null) {
        // An old PlaybackPage can release again when its ViewModel is cleared after
        // the next page has already started. That must not stop the newer playback.
        if (owner != null && playbackOwner !== owner) return
        generation++
        val old = session ?: return
        session = null
        old.stop(reason)
        previous = old
        detach()
    }
}
