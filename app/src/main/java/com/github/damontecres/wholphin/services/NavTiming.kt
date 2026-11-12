package com.github.damontecres.wholphin.services

import coil3.decode.DataSource
import com.github.damontecres.wholphin.ui.nav.Destination
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Speed plan step 0 (2026-11): log lines only, nothing here changes what the app does. For each page change it logs
 * how long the page took to show its content, to be revealed and to show its backdrop, and, when the page is left,
 * where the images it loaded came from. The lines start with "Nav timing:" and are logged at INFO, so they are in a
 * release build's log too (Settings, Advanced, Send app logs).
 *
 * An image read from disk and checked with the server first counts as "network": that check is the wait.
 */
object NavTiming {
    /** One page change, up to the next */
    class Trip internal constructor(
        internal val label: String,
        internal val startNanos: Long,
    ) {
        internal val once: MutableSet<String> = ConcurrentHashMap.newKeySet()
        internal val memory = AtomicInteger()
        internal val disk = AtomicInteger()
        internal val diskMillis = AtomicLong()
        internal val network = AtomicInteger()
        internal val networkMillis = AtomicLong()
        internal val networkMaxMillis = AtomicLong()
        internal val failed = AtomicInteger()
        internal val slowLogged = AtomicInteger()
    }

    /** An image slower than this gets a line of its own, up to [SLOW_IMAGE_LINES] per page */
    private const val SLOW_IMAGE_MS = 150L
    private const val SLOW_IMAGE_LINES = 20

    @Volatile
    private var trip: Trip? = null

    /** Exposed for tests */
    internal var nanoTime: () -> Long = System::nanoTime

    /** The page change in progress, to pass back to [mark] later */
    fun current(): Trip? = trip

    /** The app went to [destination]: the page before it is summed up and timing starts again */
    fun navigated(destination: Destination?) {
        val now = nanoTime()
        trip?.let { summarize(it, now) }
        val label = label(destination)
        trip = Trip(label, now)
        Timber.i("Nav timing: open %s", label)
    }

    /**
     * [event] happened on the current page; the log line says how long after the page change. With [of], only if
     * that page change is still the current one (eg a loading page that goes away because the page was left is not
     * the next page's content). With [once], only the first time on this page.
     */
    fun mark(
        event: String,
        of: Trip? = trip,
        once: Boolean = false,
    ) {
        val current = trip ?: return
        if (of !== current) return
        if (once && !current.once.add(event)) return
        Timber.i("Nav timing: %s %s +%d ms", current.label, event, (nanoTime() - current.startNanos) / 1_000_000)
    }

    /**
     * An image finished loading from [source] after [millis]. [describe] says what kind of image it was (never which
     * title) and is only asked for when the image was slow enough to get its own line: a card that shows empty before
     * its picture appears is one of these, and the line says whether it was fetched, read from disk, or could not be
     * kept in memory.
     */
    fun image(
        source: DataSource,
        millis: Long,
        describe: () -> String = { "" },
    ) {
        val current = trip ?: return
        if (millis >= SLOW_IMAGE_MS && source != DataSource.MEMORY_CACHE && current.slowLogged.incrementAndGet() <= SLOW_IMAGE_LINES) {
            Timber.i(
                "Nav timing: %s slow image %d ms from %s +%d ms %s",
                current.label,
                millis,
                source,
                (nanoTime() - current.startNanos) / 1_000_000,
                describe(),
            )
        }
        when (source) {
            DataSource.MEMORY_CACHE, DataSource.MEMORY -> {
                current.memory.incrementAndGet()
            }

            DataSource.DISK -> {
                current.disk.incrementAndGet()
                current.diskMillis.addAndGet(millis)
            }

            DataSource.NETWORK -> {
                current.network.incrementAndGet()
                current.networkMillis.addAndGet(millis)
                current.networkMaxMillis.accumulateAndGet(millis, ::maxOf)
            }
        }
    }

    fun imageFailed() {
        trip?.failed?.incrementAndGet()
    }

    private fun summarize(
        trip: Trip,
        now: Long,
    ) {
        val memory = trip.memory.get()
        val disk = trip.disk.get()
        val network = trip.network.get()
        val failed = trip.failed.get()
        // A page passed through on the way to another (eg Home, from the menu) loaded nothing worth a line
        if (memory + disk + network + failed == 0) return
        Timber.i(
            "Nav timing: %s images over %d ms: memory=%d disk=%d (avg %d ms) network=%d (avg %d ms, max %d ms) failed=%d",
            trip.label,
            (now - trip.startNanos) / 1_000_000,
            memory,
            disk,
            if (disk > 0) trip.diskMillis.get() / disk else 0,
            network,
            if (network > 0) trip.networkMillis.get() / network else 0,
            trip.networkMaxMillis.get(),
            failed,
        )
    }

    /** A short name for the page: its kind, never its contents */
    private fun label(destination: Destination?): String =
        when (destination) {
            null -> "none"
            is Destination.MediaItem -> "MediaItem:${destination.type}" + (destination.collectionType?.let { "/$it" } ?: "")
            else -> destination::class.simpleName ?: "page"
        }
}
