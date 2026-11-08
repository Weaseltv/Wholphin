@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.github.damontecres.wholphin.services.preload

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal data class PreloadMedia(
    val url: String,
    val sessionId: String,
    val itemId: String,
    val sourceId: String,
    val hls: Boolean,
    val size: Long,
    val durationSec: Double,
    val bitrate: Long,
)

/**
 * All video I/O belongs to this one worker, including player cache misses and manifests.
 * It publishes small committed cache fragments during a response, so playback needn't wait
 * for an entire paced chunk/segment. There is never an independent player HTTP connection.
 */
internal class PreloadSession(
    val media: PreloadMedia,
    private val pacer: PacerClient,
    private val store: PreloadStore,
    private val freeBytes: () -> Long,
    private val totalBytes: () -> Long,
    private val retryDelayMs: Long = 5000,
    private val stallMs: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val watchdogIntervalMs: Long = 1000,
    private val reportIntervalMs: Long = 5000,
) {
    private data class Task(
        val url: String,
        val position: Long,
        val length: Long,
        val playlist: Boolean = false,
        val epoch: Int = 0,
    )

    private data class Segment(
        val task: Task,
        val start: Double,
        val end: Double,
        val dependencies: List<Task>,
    )

    private data class Playlist(
        val url: String,
        val segments: List<Segment>,
        val ended: Boolean,
    )

    private val monitor = Object()
    private val urgent = linkedSetOf<Task>()
    private val failures = mutableMapOf<Task, IOException>()
    private val lengths = mutableMapOf<String, Long>()
    private val manifests = mutableMapOf<String, ByteArray>()
    private val playlists = linkedMapOf<String, Playlist>()

    @Volatile private var closed = false

    @Volatile private var epoch = 0

    @Volatile private var position = 0.0

    @Volatile private var paused = false

    @Volatile private var needsPlay = true

    @Volatile private var backgroundStopped = false

    @Volatile private var active: Task? = null

    @Volatile private var activeCall: Call? = null

    @Volatile private var activeDemand = false

    @Volatile private var lastByteMs = 0L

    @Volatile private var reason = "stop"
    private var directCursor = 0L
    private var anchorByte = 0L
    private var anchorSec = 0.0
    private var stalls = 0
    private val stallTimes = ArrayDeque<Long>()
    private var lastLog = 0L
    private var lastResumeCheckMs = 0L

    // Diagnostics for the five-second status line: disk write time and bytes received.
    private var writeNs = 0L
    private var writeMaxNs = 0L
    private var writes = 0
    private var receivedSinceLog = 0L
    private val reports = Executors.newSingleThreadExecutor { Thread(it, "preload-reports") }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "preload-watchdog") }
    val worker = Thread({ runWorker() }, "preload-video")

    fun start() {
        if (!media.hls) directCursor = bytePosition()
        worker.start()
        report()
        timer.scheduleAtFixedRate({
            if (activeCall != null && clock() - lastByteMs >= stallMs) activeCall?.cancel()
        }, watchdogIntervalMs, watchdogIntervalMs, TimeUnit.MILLISECONDS)
        timer.scheduleAtFixedRate({ report() }, reportIntervalMs, reportIntervalMs, TimeUnit.MILLISECONDS)
    }

    /** The session ended for a reason where playback should carry on over the normal network
     * source (open readers resume there) instead of failing. */
    val networkFallback: Boolean get() = closed && reason in NETWORK_FALLBACK_REASONS

    /** Ends pre-load for this item and lets open readers continue over plain streaming. */
    fun fallbackToNetwork(why: String) {
        synchronized(monitor) {
            if (closed) return
            Timber
                .tag(
                    "Preload",
                ).w(
                    "falling back to normal streaming: %s positionSec=%.1f bufferedAheadSec=%d storedBytes=%d",
                    why,
                    position,
                    bufferedAhead(),
                    store.bytes,
                )
            report(stopped = true)
            reason = why
            closed = true
            activeCall?.cancel()
            monitor.notifyAll()
        }
    }

    fun owns(url: String): Boolean =
        synchronized(monitor) {
            !closed &&
                (
                    url == media.url ||
                        (
                            media.hls &&
                                (
                                    isPlaylist(url) ||
                                        playlists.values.any { p ->
                                            p.segments.any { s ->
                                                s.task.url == url ||
                                                    s.dependencies.any { it.url == url }
                                            }
                                        }
                                )
                        )
                )
        }

    fun update(
        positionSec: Double,
        isPaused: Boolean,
    ) {
        if (closed) return
        position = positionSec.coerceAtLeast(0.0)
        paused = isPaused
        synchronized(monitor) {
            if (closed) return
            val hasData = if (media.hls) hlsCovered(position) else store.available(media.url, bytePosition()) > 0
            if (hasData) needsPlay = false
        }
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun rebuffer() {
        if (closed) return
        val repeated =
            synchronized(monitor) {
                stalls++
                val now = clock()
                stallTimes.addLast(now)
                while (stallTimes.isNotEmpty() && now - stallTimes.first() > STALL_WINDOW_MS) stallTimes.removeFirst()
                val ahead = bufferedAhead()
                Timber.tag("Preload").i(
                    "rebuffer positionSec=%.1f bufferedAheadSec=%d storedBytes=%d background=%s recentStalls=%d",
                    position,
                    ahead,
                    store.bytes,
                    backgroundState(),
                    stallTimes.size,
                )
                stallTimes.size >= STALLS_BEFORE_FALLBACK && ahead < 30
            }
        report()
        // Stored video isn't keeping ahead of playback. Stop pre-loading this item and
        // stream it normally rather than loop through short stalls.
        if (repeated) fallbackToNetwork("repeated-stalls")
    }

    private fun backgroundState() =
        when {
            closed -> "closed"
            backgroundStopped -> "paused-capacity"
            failures.isNotEmpty() -> "paused-failure"
            else -> "on"
        }

    fun seek(positionSec: Double) {
        if (closed) return
        synchronized(monitor) {
            if (closed) return
            val target = positionSec.coerceAtLeast(0.0)
            val covered = if (media.hls) hlsCovered(target) else store.available(media.url, bytePosition(target)) > 0
            position = target
            needsPlay = !covered
            if (!covered && media.hls) {
                // C-Q: a viewer seek is the sole permitted HLS jump. Do not mix ffmpeg runs.
                epoch++
                backgroundStopped = false
                urgent.clear()
                failures.clear()
                manifests.clear()
                activeCall?.cancel()
            } else if (!media.hls) {
                directCursor = bytePosition(target)
                if (!covered) {
                    failures.clear()
                    activeCall?.cancel()
                }
            }
            monitor.notifyAll()
        }
        report()
    }

    fun event() = report()

    fun stop(deleteReason: String) {
        synchronized(monitor) {
            if (closed) return
            report(stopped = true)
            reason = deleteReason
            closed = true
            activeCall?.cancel()
            monitor.notifyAll()
        }
        timer.shutdownNow()
        reports.shutdown()
    }

    fun join() {
        worker.join()
        reports.awaitTermination(4, TimeUnit.SECONDS)
    }

    fun factory(): DataSource.Factory =
        DataSource.Factory {
            // Read-only CacheDataSource: only our worker may lock holes/write. A cache miss reads
            // committed fragments through StoredSource while that SAME worker fills the cache.
            CacheDataSource
                .Factory()
                .setCache(store.cache)
                .setCacheKeyFactory { key(it.uri.toString()) }
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamDataSourceFactory { StoredSource() }
                .createDataSource()
        }

    private fun key(
        url: String,
        generation: Int = epoch,
    ) = if (media.hls) "$generation:$url" else url

    private fun bytePosition(sec: Double = position) =
        (anchorByte + (sec - anchorSec) * media.size / media.durationSec).toLong().coerceIn(
            0,
            max(
                0,
                media.size - 1,
            ),
        )

    /** The extractor knows the actual container byte offset of a seek, including VBR files. */
    fun directSeek(
        byteOffset: Long,
        timeUs: Long,
    ) {
        synchronized(monitor) {
            if (closed || media.hls) return
            anchorByte = byteOffset.coerceIn(0, max(0, media.size - 1))
            anchorSec = (timeUs / 1e6).coerceAtLeast(0.0)
            directCursor = anchorByte
            if (store.available(media.url, bytePosition()) == 0L) needsPlay = true
            val running = active
            if (running != null &&
                (anchorByte < running.position || (running.length > 0 && anchorByte >= running.position + running.length))
            ) {
                activeCall?.cancel()
            }
            monitor.notifyAll()
        }
    }

    private fun resolve(
        base: String,
        url: String,
    ) = URI(base).resolve(url).toString()

    private fun isPlaylist(url: String) = (media.hls && url == media.url) || Uri.parse(url).path?.endsWith(".m3u8", true) == true

    fun bufferedAhead(): Long =
        synchronized(monitor) {
            if (closed) return@synchronized 0L
            if (needsPlay) return@synchronized 0L
            if (!media.hls) {
                PreloadPolicy.directAhead(store.available(media.url, bytePosition()), media.size, media.durationSec, position)
            } else {
                if (playlists.isEmpty()) return@synchronized 0L
                playlists.values.minOf { playlist ->
                    var end = position
                    for (segment in playlist.segments) {
                        if (segment.end <= position) continue
                        if (segment.start > end + 0.05 || !stored(segment.task) || segment.dependencies.any { !stored(it) }) break
                        end = segment.end
                    }
                    (end - position).coerceAtLeast(0.0).toLong()
                }
            }
        }

    private fun hlsCovered(sec: Double): Boolean =
        playlists.isNotEmpty() &&
            playlists.values.all { p ->
                p.segments.firstOrNull { sec >= it.start && sec < it.end }?.let { stored(it.task) && it.dependencies.all(::stored) } == true
            }

    private fun stored(task: Task): Boolean {
        val length = if (task.length >= 0) task.length else lengths[key(task.url)]?.minus(task.position) ?: return false
        return length > 0 && store.available(key(task.url), task.position, length) >= length
    }

    private fun complete(): Boolean =
        if (!media.hls) {
            media.size > 0 && store.available(media.url, 0, media.size) >= media.size
        } else {
            playlists.isNotEmpty() &&
                playlists.values.all { it.ended && it.segments.all { s -> stored(s.task) && s.dependencies.all(::stored) } }
        }

    private fun report(stopped: Boolean = false) {
        if (reports.isShutdown || (closed && !stopped)) return
        val body =
            synchronized(monitor) {
                if (closed && !stopped) return
                val now = clock()
                if (!stopped && now - lastLog >= 5000) {
                    val elapsed = (now - lastLog).coerceAtLeast(1)
                    lastLog = now
                    Timber.tag("Preload").i(
                        "storedBytes=%d bufferedAheadSec=%d complete=%s limitBytes=%d positionSec=%.1f paused=%s background=%s receivedMbps=%.1f writes=%d writeAvgMs=%.1f writeMaxMs=%.1f",
                        store.bytes,
                        bufferedAhead(),
                        complete(),
                        store.limit,
                        position,
                        paused,
                        backgroundState(),
                        receivedSinceLog * 8.0 / 1000.0 / elapsed,
                        writes,
                        if (writes > 0) writeNs / 1e6 / writes else 0.0,
                        writeMaxNs / 1e6,
                    )
                    receivedSinceLog = 0
                    writes = 0
                    writeNs = 0
                    writeMaxNs = 0
                }
                val count = stalls
                stalls = 0
                buildJsonObject {
                    put("playSessionId", media.sessionId)
                    put("itemId", media.itemId)
                    put("mediaSourceId", media.sourceId)
                    put("mode", if (media.hls) "hls" else "direct")
                    put(
                        "state",
                        if (stopped) {
                            "stopped"
                        } else if (paused) {
                            "paused"
                        } else {
                            "playing"
                        },
                    )
                    put("positionSec", position)
                    put("bufferedAheadSec", bufferedAhead())
                    put("bytesStored", store.bytes)
                    put("complete", complete())
                    put("bitrate", media.bitrate)
                    put("stallsSinceLast", count)
                }.toString()
            }
        try {
            reports.execute { pacer.report(body) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun queue(task: Task) {
        synchronized(monitor) {
            val running = active
            val same =
                running != null && running.url == task.url && running.epoch == task.epoch &&
                    (
                        running.playlist ||
                            (task.position >= running.position && (running.length < 0 || task.position < running.position + running.length))
                    )
            if (same) activeDemand = true
            if (!same) {
                urgent.add(task)
                // Don't abort HLS segment N to fetch N+1. Metadata/player direct seeks may preempt.
                if (running != null && (!media.hls || task.playlist)) activeCall?.cancel()
            }
            monitor.notifyAll()
        }
    }

    private fun background(): Task? {
        if (media.hls) {
            for (p in playlists.values) {
                for (s in p.segments) {
                    if (s.end <= position) continue
                    for (dependency in s.dependencies) if (!stored(dependency)) return dependency.copy(epoch = epoch)
                    if (!stored(s.task)) return s.task.copy(epoch = epoch)
                }
            }
            return playlists.values.firstOrNull { !it.ended }?.let { Task(it.url, 0, -1, true, epoch) }
        }
        if (media.size <= 0 || directCursor >= media.size) return null
        var cursor = directCursor
        while (cursor < media.size) {
            val have = store.available(media.url, cursor, media.size - cursor)
            if (have == 0L) break
            cursor += have
        }
        directCursor = cursor
        return if (cursor < media.size) Task(media.url, cursor, minOf(PreloadPolicy.CHUNK, media.size - cursor)) else null
    }

    private fun runWorker() {
        var storedEpoch = 0
        try {
            while (!closed) {
                val task =
                    synchronized(monitor) {
                        if (epoch != storedEpoch) {
                            store.clear("hls-uncached-seek")
                            lengths.clear()
                            storedEpoch = epoch
                        }
                        urgent.removeAll { !it.playlist && stored(it) }
                        if (backgroundStopped && urgent.isEmpty() && clock() - lastResumeCheckMs >= RESUME_CHECK_MS) {
                            lastResumeCheckMs = clock()
                            // Capacity is a pause, not the end of read-ahead for this item: once
                            // watched data can give way, keep downloading ahead of playback.
                            // makeRoom throws only after closing for an actual reserve breach.
                            if (try {
                                    makeRoom(PreloadPolicy.CHUNK, reserve())
                                } catch (_: IOException) {
                                    false
                                }
                            ) {
                                backgroundStopped = false
                                Timber
                                    .tag(
                                        "Preload",
                                    ).i(
                                        "background pre-load resumed storedBytes=%d limitBytes=%d positionSec=%.1f",
                                        store.bytes,
                                        store.limit,
                                        position,
                                    )
                            }
                        }
                        if (closed) return@synchronized null
                        val demand = urgent.firstOrNull()
                        var next = demand ?: if (!backgroundStopped && failures.isEmpty()) background() else null
                        // A player requesting a later HLS segment cannot bypass an earlier one.
                        if (media.hls && demand != null && !demand.playlist) {
                            val ordered = background()
                            if (ordered != null) next = ordered
                        }
                        if (next == null || failures.containsKey(next)) {
                            monitor.wait(250)
                            null
                        } else {
                            urgent.remove(next)
                            active = next
                            activeDemand = demand != null
                            next
                        }
                    } ?: continue
                try {
                    transferWithRetry(task)
                    if (task.playlist) synchronized(monitor) { if (urgent.isEmpty()) monitor.wait(500) }
                } catch (ex: IOException) {
                    if (!closed && task.epoch == epoch) {
                        if (media.hls) {
                            synchronized(monitor) {
                                failures[task] = ex
                                monitor.notifyAll()
                            }
                        } else {
                            // A direct chunk failed twice. Don't leave the item with no read-ahead
                            // until playback reaches the failed range and errors: stream it normally.
                            Timber.tag("Preload").w("chunk failed twice: %s", ex.message)
                            fallbackToNetwork("preload-failed")
                        }
                    }
                } finally {
                    synchronized(monitor) {
                        active = null
                        activeCall = null
                        monitor.notifyAll()
                    }
                }
            }
        } catch (ex: Exception) {
            Timber.tag("Preload").w("worker stopped: %s at %s", ex.javaClass.simpleName, ex.stackTrace.firstOrNull())
            // Open readers resume over the network instead of draining the in-memory buffer
            // and then failing playback.
            try {
                fallbackToNetwork("worker-error")
            } catch (_: Exception) {
                synchronized(monitor) {
                    reason = "worker-error"
                    closed = true
                    monitor.notifyAll()
                }
            }
        } finally {
            timer.shutdownNow()
            if (!reports.isShutdown) reports.shutdown()
            synchronized(monitor) {
                try {
                    store.close(reason)
                } catch (_: Exception) {
                    Timber.tag("Preload").w("delete reason=%s failed; refusing reuse until cleanup succeeds", reason)
                }
                monitor.notifyAll()
            }
        }
    }

    private fun transferWithRetry(task: Task) {
        for (attempt in 0..1) {
            if (closed || task.epoch != epoch) return
            try {
                transfer(task)
                return
            } catch (_: CapacityReached) {
                return
            } catch (ex: IOException) {
                if (closed || task.epoch != epoch) return
                // A canceled background direct read was preempted by a player cache miss.
                if (activeCall?.isCanceled() == true && clock() - lastByteMs < stallMs) return
                if (attempt == 1) throw ex
                Timber.tag("Preload").i(
                    "retry after stall/error; same %s, attempt=1",
                    if (task.playlist) {
                        "playlist"
                    } else if (media.hls) {
                        "segment"
                    } else {
                        "chunk"
                    },
                )
                val deadline = clock() + retryDelayMs
                synchronized(monitor) {
                    while (!closed && task.epoch == epoch && clock() < deadline) {
                        monitor.wait(max(1, deadline - clock()))
                    }
                }
            }
        }
    }

    private fun transfer(task: Task) {
        val original = URI(media.url)
        val destination = URI(task.url)
        if (original.scheme != destination.scheme || original.host != destination.host ||
            original.port != destination.port
        ) {
            throw IOException("Cross-origin pre-load refused")
        }
        val buffered = bufferedAhead()
        val kind = if (activeDemand || needsPlay || buffered < 30) "play" else "preload"
        val builder =
            Request
                .Builder()
                .url(task.url)
                .header("Authorization", pacer.authorization)
                .header("Accept-Encoding", "identity")
                .header("X-WeaselPlex-Session", media.sessionId)
                .header("X-WeaselPlex-Buffered", buffered.toString())
                .header("X-WeaselPlex-Bitrate", media.bitrate.toString())
                .header("X-WeaselPlex-Kind", kind)
        if (!task.playlist && (!media.hls || task.position > 0 || task.length >= 0)) {
            builder.header("Range", "bytes=${task.position}-${if (task.length > 0) task.position + task.length - 1 else ""}")
        }
        val call = pacer.video.newCall(builder.build())
        val startedMs = clock()
        var firstByteMs = -1L
        var receivedBytes = 0L
        var outcome = "error"
        lastByteMs = startedMs
        activeCall = call
        try {
            transferResponse(task, call) { count ->
                if (firstByteMs < 0) firstByteMs = clock() - startedMs
                receivedBytes += count
            }
            outcome = if (closed || task.epoch != epoch) "abandoned" else "ok"
        } catch (ex: CapacityReached) {
            outcome = "capacity"
            throw ex
        } catch (ex: IOException) {
            outcome = if (call.isCanceled()) "canceled" else "error:${ex.message}"
            throw ex
        } finally {
            // One line per request: what the pacer saw from this app (C6 diagnostic log).
            Timber.tag("Preload").i(
                "fetch %s kind=%s range=%d+%d demand=%s bufferedHdr=%d firstByteMs=%d bytes=%d ms=%d result=%s",
                if (task.playlist) {
                    "playlist"
                } else if (media.hls) {
                    "segment"
                } else {
                    "chunk"
                },
                kind,
                task.position,
                task.length,
                activeDemand,
                buffered,
                firstByteMs,
                receivedBytes,
                clock() - startedMs,
                outcome,
            )
        }
    }

    private fun transferResponse(
        task: Task,
        call: Call,
        onBytes: (Int) -> Unit,
    ) {
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Video HTTP ${response.code}")
            if (!task.playlist && !media.hls && response.code != 206) throw IOException("Direct pre-load requires byte ranges")
            if (!task.playlist && task.position > 0 && response.code != 206) throw IOException("Byte range ignored")
            if (response.code == 206) {
                val start =
                    response
                        .header("Content-Range")
                        ?.substringAfter("bytes ")
                        ?.substringBefore('-')
                        ?.toLongOrNull()
                if (start != task.position) throw IOException("Incorrect byte range")
            }
            val expected = response.body.contentLength()
            val total =
                if (!media.hls) {
                    media.size
                } else if (expected >= 0) {
                    task.position + expected
                } else {
                    -1
                }
            if (!task.playlist && total >= 0) {
                synchronized(monitor) {
                    lengths[key(task.url)] = max(lengths[key(task.url)] ?: 0, total)
                    store.length(key(task.url), total)
                    monitor.notifyAll()
                }
            }
            val memory = if (task.playlist) ByteArrayOutputStream() else null
            val input = response.body.byteStream()
            val buffer = ByteArray(PreloadPolicy.FRAGMENT)
            var offset = task.position
            var received = 0L
            var pending = 0
            var firstFragment = true

            fun publish() {
                if (pending == 0) return
                synchronized(monitor) { ensureRoom(pending, task) }
                // Disk I/O happens outside the session lock: only this worker changes the store,
                // and readers see a fragment once it is committed. A slow write must never block
                // the player's reads of data already stored, or the main-thread position tick.
                val began = System.nanoTime()
                store.write(key(task.url), offset, buffer, pending)
                val took = System.nanoTime() - began
                synchronized(monitor) {
                    writeNs += took
                    writes++
                    if (took > writeMaxNs) writeMaxNs = took
                    monitor.notifyAll()
                }
                offset += pending
                pending = 0
                firstFragment = false
            }
            while (!closed && task.epoch == epoch) {
                // Aggregate socket reads into bounded disk fragments, but update the stall
                // clock for EVERY received byte. The first fragment is smaller for startup.
                val count = input.read(buffer, pending, buffer.size - pending)
                if (count < 0) break
                if (count == 0) continue
                lastByteMs = clock()
                received += count
                onBytes(count)
                receivedSinceLog += count
                pending += count
                if (memory != null) {
                    if (received > 2 * 1024 * 1024) throw IOException("Playlist too large")
                    memory.write(buffer, 0, pending)
                    pending = 0
                } else if (pending >= if (firstFragment) 64 * 1024 else buffer.size) {
                    publish()
                }
            }
            if (closed || task.epoch != epoch) return
            if (memory == null) publish()
            if (expected >= 0 && received != expected) throw IOException("Truncated video response")
            if (task.length > 0 && received != task.length) throw IOException("Truncated byte range")
            synchronized(monitor) {
                if (memory != null) {
                    val bytes = memory.toByteArray()
                    parsePlaylist(task.url, bytes)
                    manifests[task.url] = bytes
                } else {
                    if (media.hls) {
                        lengths[key(task.url)] = offset
                        store.length(key(task.url), offset)
                    } else if (task.position <= directCursor && offset > directCursor) {
                        directCursor = offset
                    }
                    needsPlay = false
                }
                urgent.removeAll { it.url == task.url && (it.playlist || stored(it)) }
                monitor.notifyAll()
            }
        }
    }

    private fun reserve() = maxOf(1_500_000_000L, totalBytes() / 10)

    /** Throws (and closes the item) only for an actual reserve breach. */
    private fun fits(
        count: Long,
        reserve: Long,
    ): Boolean {
        val free = freeBytes()
        if (free < reserve) {
            // Actual reserve loss (for example, another app consuming space) still
            // releases the item. Merely lacking room for our next write is capacity.
            reason = "low-storage-reserve"
            closed = true
            throw IOException("Low storage")
        }
        return store.bytes + count <= store.limit &&
            free - reserve >= count + PreloadPolicy.WRITE_OVERHEAD_HEADROOM
    }

    /** Playback time at the end of a stored span, or MAX_VALUE if unknown. */
    private fun spanEndSec(span: androidx.media3.datasource.cache.CacheSpan): Double =
        if (!media.hls) {
            anchorSec + (span.position + span.length - anchorByte) * media.durationSec / media.size
        } else {
            playlists.values
                .flatMap { it.segments }
                .firstOrNull { key(it.task.url) == span.key }
                ?.end ?: Double.MAX_VALUE
        }

    /**
     * Already-watched data gives way to read-ahead for the playing item: first anything more
     * than five minutes behind (kept for quick rewinds); then, only while less than five
     * minutes is stored ahead, anything more than 30 s behind. Data ahead of playback is never
     * evicted here. Rechecks physical headroom after each phase.
     */
    private fun makeRoom(
        count: Long,
        reserve: Long,
    ): Boolean {
        if (fits(count, reserve)) return true
        for ((keepSec, why) in listOf(300.0 to "more-than-five-minutes-behind", 30.0 to "watched-gives-way-to-read-ahead")) {
            if (keepSec < 300.0 && bufferedAhead() >= 300) break
            val cutoff = position - keepSec
            if (cutoff <= 0) continue
            store.removeAll(store.spans().filter { spanEndSec(it) < cutoff }, why)
            if (fits(count, reserve)) return true
        }
        return false
    }

    private fun ensureRoom(
        count: Int,
        task: Task,
    ) {
        val reserve = reserve()
        if (makeRoom(count.toLong(), reserve)) return
        if (!backgroundStopped) {
            Timber.tag("Preload").i(
                "storage limit reached; background pre-load stopped storedBytes=%d limitBytes=%d freeBytes=%d reserveBytes=%d positionSec=%.1f bufferedAheadSec=%d",
                store.bytes,
                store.limit,
                freeBytes(),
                reserve,
                position,
                bufferedAhead(),
            )
        }
        // Paused, not finished: the worker resumes once watched data can make room.
        backgroundStopped = true
        lastResumeCheckMs = clock()
        if (!activeDemand) throw CapacityReached()
        // Player demand may replace retained data. Prefer data farthest from playback,
        // never the range being fetched.
        val protectedKey = key(task.url)
        val playing = bytePosition()
        val order =
            store.spans().sortedByDescending {
                if (media.hls) {
                    (if (it.key == protectedKey) -1.0 else kotlin.math.abs(spanEndSec(it) - position))
                } else {
                    kotlin.math.abs(it.position.toDouble() - playing)
                }
            }
        for (span in order) {
            if (span.key == protectedKey && task.position < span.position + span.length &&
                span.position < task.position + max(1, task.length)
            ) {
                continue
            }
            store.remove(span, "capacity-player-demand")
            if (fits(count.toLong(), reserve)) return
        }
        throw CapacityReached()
    }

    private class CapacityReached : IOException("Pre-load capacity reached")

    private companion object {
        val NETWORK_FALLBACK_REASONS =
            setOf("android-low-storage", "low-storage-reserve", "worker-error", "preload-failed", "repeated-stalls")
        const val STALL_WINDOW_MS = 120_000L
        const val STALLS_BEFORE_FALLBACK = 3
        const val RESUME_CHECK_MS = 1000L
    }

    private fun parsePlaylist(
        url: String,
        bytes: ByteArray,
    ) {
        val playlist = HlsPlaylistParser().parse(Uri.parse(url), bytes.inputStream())
        if (playlist !is HlsMediaPlaylist) return
        val segments =
            playlist.segments.map { segment ->
                val dependencies =
                    buildList {
                        segment.fullSegmentEncryptionKeyUri?.let { add(Task(resolve(url, it), 0, -1, epoch = epoch)) }
                        segment.initializationSegment?.let {
                            add(
                                Task(resolve(url, it.url), it.byteRangeOffset, it.byteRangeLength, epoch = epoch),
                            )
                        }
                    }
                Segment(
                    Task(resolve(url, segment.url), segment.byteRangeOffset, segment.byteRangeLength, epoch = epoch),
                    segment.relativeStartTimeUs / 1e6,
                    (segment.relativeStartTimeUs + segment.durationUs) / 1e6,
                    dependencies,
                )
            }
        playlists[url] = Playlist(url, segments, playlist.hasEndTag)
    }

    private inner class StoredSource : BaseDataSource(false) {
        private var generation = 0
        private lateinit var spec: DataSpec
        private var cursor = 0L
        private var remaining = C.LENGTH_UNSET.toLong()
        private var memory: ByteArray? = null

        @Volatile private var sourceClosed = false

        override fun open(dataSpec: DataSpec): Long {
            generation = epoch
            spec = dataSpec
            cursor = spec.position
            sourceClosed = false
            transferInitializing(spec)
            checkOpen()
            if (isPlaylist(spec.uri.toString())) {
                val task = Task(spec.uri.toString(), 0, -1, true, generation)
                synchronized(monitor) { manifests.remove(task.url) }
                queue(task)
                synchronized(monitor) {
                    while (manifests[task.url] == null) {
                        checkFailure(task)
                        checkOpen()
                        monitor.wait(250)
                    }
                    memory = manifests[task.url]
                }
                remaining = memory!!.size.toLong() - cursor
            } else {
                remaining =
                    if (dataSpec.length >=
                        0
                    ) {
                        dataSpec.length
                    } else if (!media.hls) {
                        (media.size - cursor).coerceAtLeast(0)
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
            }
            transferStarted(spec)
            return remaining
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            checkOpen()
            val bytes = memory
            if (bytes != null) {
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.copyInto(buffer, offset, cursor.toInt(), cursor.toInt() + count)
                cursor += count
                remaining -= count
                bytesTransferred(count)
                return count
            }
            val url = spec.uri.toString()
            val task =
                synchronized(monitor) {
                    if (!media.hls) {
                        Task(url, cursor, minOf(PreloadPolicy.CHUNK, media.size - cursor), epoch = generation)
                    } else {
                        playlists.values
                            .flatMap { it.segments }
                            .flatMap { listOf(it.task) + it.dependencies }
                            .firstOrNull { it.url == url && (it.length < 0 || cursor < it.position + it.length) }
                            ?.copy(epoch = generation)
                            ?: Task(url, spec.position, spec.length, epoch = generation)
                    }
                }
            while (true) {
                synchronized(monitor) {
                    checkOpen()
                    val knownEnd = lengths[key(url, generation)]
                    if (knownEnd != null && cursor >= knownEnd) return C.RESULT_END_OF_INPUT
                    val count =
                        store.read(
                            key(url, generation),
                            cursor,
                            buffer,
                            offset,
                            if (remaining >=
                                0
                            ) {
                                minOf(length.toLong(), remaining).toInt()
                            } else {
                                length
                            },
                        )
                    if (count > 0) {
                        cursor += count
                        if (remaining >= 0) remaining -= count
                        bytesTransferred(count)
                        return count
                    }
                    checkFailure(task)
                    if (media.hls) failures.entries.firstOrNull { it.key.epoch == generation && !it.key.playlist }?.let { throw it.value }
                    queue(task)
                    monitor.wait(250)
                }
            }
        }

        private fun checkOpen() {
            if (closed || sourceClosed || generation != epoch ||
                Thread.currentThread().isInterrupted
            ) {
                throw InterruptedIOException(
                    "Pre-load reader ended (session=$closed, source=$sourceClosed, replaced=${generation != epoch}, interrupted=${Thread.currentThread().isInterrupted})",
                )
            }
        }

        override fun getUri(): Uri? = if (this::spec.isInitialized) spec.uri else null

        override fun close() {
            sourceClosed = true
            memory = null
            synchronized(monitor) { monitor.notifyAll() }
            if (this::spec.isInitialized) transferEnded()
        }
    }

    private fun checkFailure(task: Task) {
        failures.entries
            .firstOrNull { (failed, _) ->
                failed.url == task.url && failed.epoch == task.epoch &&
                    (
                        failed.playlist ||
                            (task.position >= failed.position && (failed.length < 0 || task.position < failed.position + failed.length))
                    )
            }?.let { throw it.value }
    }
}
