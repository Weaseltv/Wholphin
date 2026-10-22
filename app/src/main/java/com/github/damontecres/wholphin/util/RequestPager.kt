package com.github.damontecres.wholphin.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.damontecres.wholphin.ui.DEFAULT_PAGE_SIZE
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.function.Predicate

/**
 * Handles paging for an API request. You must call [init] prior to use.
 *
 * Initially, items returned will be null, but requesting the items triggers API calls in the given [CoroutineScope].
 * Since the items are stored in [androidx.compose.runtime.MutableState], it will automatically trigger recompositions when the items are ultimately fetched.
 *
 * Finally, items are cached allow for backward and forward scrolling.
 */
abstract class RequestPager<T>(
    protected val scope: CoroutineScope,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    cacheSize: Long = 8,
) : AbstractList<T?>(),
    BlockingList<T?> {
    protected var totalCount by mutableIntStateOf(-1)
    protected var items by mutableStateOf(ItemList<T>(0, pageSize, mapOf()))
    protected val mutex = Mutex()

    /**
     * Kid content rules (contract section 10): set once a page came back filtered (shorter than the server's page).
     * Pages are still fetched by server page number, but each has its own length; positions are cumulative.
     */
    private var layout: PageLayout? = null
    protected val cachedPages =
        CacheBuilder
            .newBuilder()
            .maximumSize(cacheSize)
            .build<Int, MutableList<T>>()

    open suspend fun init(initialPosition: Int = 0): RequestPager<T> {
        if (totalCount < 0) {
            fetchPageBlocking(initialPosition, true)
        }
        return this
    }

    override operator fun get(index: Int): T? {
        if (index in 0..<totalCount) {
            val item = items[index]
            if (item == null) {
                fetchPage(index)
            }
            return item
        } else {
            throw IndexOutOfBoundsException("$index of $totalCount")
        }
    }

    override suspend fun getBlocking(index: Int): T? {
        if (index in 0..<totalCount) {
            var item = items[index]
            // Kid content rules: a filtered page can come back shorter, moving [index] into a later page, so this
            // fetches until the page holding it is loaded (one fetch when pages are full)
            var fetches = 0
            while (item == null && fetches <= MAX_REFETCHES) {
                if (fetches > 0 && cachedPages.getIfPresent(items.pageOf(index)) != null) break
                fetchPageBlocking(index, false)
                fetches++
                if (index >= totalCount) return null
                item = items[index]
            }
            return item
        } else {
            throw IndexOutOfBoundsException("$index of $totalCount")
        }
    }

    override suspend fun indexOfBlocking(predicate: Predicate<T?>): Int {
        init(0)
        // totalCount can shrink while filtered pages load, so it is read again on every step
        var i = 0
        while (i < totalCount) {
            val currentItem = getBlocking(i)
            if (currentItem != null && predicate.test(currentItem)) {
                return i
            }
            i++
        }
        return -1
    }

    override val size: Int
        get() = totalCount

    private fun fetchPage(position: Int): Job =
        scope.launch(ExceptionHandler() + WholphinDispatchers.IO) {
            fetchPageBlocking(position, false)
        }

    protected suspend fun fetchPageBlocking(
        position: Int,
        setTotalCount: Boolean,
    ) {
        mutex.withLock {
            fetchPageNumberLocked(items.pageOf(position), setTotalCount)
        }
    }

    /** Fetches the server page [pageNumber] (unless cached); the caller holds [mutex] */
    protected suspend fun fetchPageNumberLocked(
        pageNumber: Int,
        setTotalCount: Boolean,
    ) {
        if (cachedPages.getIfPresent(pageNumber) == null || setTotalCount) {
            if (DEBUG) Timber.v("fetchPage: $pageNumber")
            val result = fetchPage(pageNumber, setTotalCount)
            if (result.removed != null) {
                // Kid content rules: this page came back filtered, so pages have their own lengths from now on
                val pages = layout ?: PageLayout(pageSize).also { layout = it }
                if (result.totalCount != null && (setTotalCount || pages.serverTotal < 0)) {
                    pages.serverTotal = (result.totalCount + result.removed).coerceAtLeast(0)
                }
                pages.lengths[pageNumber] = result.items.size
                totalCount = pages.total()
            } else if (setTotalCount && result.totalCount != null) {
                totalCount = result.totalCount.coerceAtLeast(0)
            }
            cachedPages.put(pageNumber, result.items.toMutableList())
            items = ItemList(totalCount, pageSize, cachedPages.asMap(), layout?.offsets())
        }
    }

    suspend fun refresh() {
        cachedPages.asMap().keys.toList().forEachIndexed { index, pageNum ->
            if (DEBUG) Timber.v("refresh: pageNum=%s", pageNum)
            mutex.withLock { fetchPageNumberLocked(pageNum, index == 0) }
        }
    }

    protected abstract suspend fun fetchPage(
        pageNumber: Int,
        includeTotalCount: Boolean,
    ): QueryResult<T>

    companion object {
        internal const val DEBUG = false

        /** Kid content rules: how many further pages [getBlocking] fetches for one position (empty filtered pages) */
        private const val MAX_REFETCHES = 50
    }

    /**
     * The server's page size and total, and the length of each page fetched so far once filtered; a page not fetched yet
     * counts as full until it is
     */
    private class PageLayout(
        val pageSize: Int,
    ) {
        var serverTotal: Int = -1
        val lengths = HashMap<Int, Int>()

        private val pageCount: Int
            get() = if (serverTotal <= 0) (lengths.keys.maxOrNull()?.plus(1) ?: 0) else (serverTotal + pageSize - 1) / pageSize

        private fun lengthOf(page: Int): Int =
            lengths[page] ?: if (serverTotal < 0) pageSize else (serverTotal - page * pageSize).coerceIn(0, pageSize)

        fun total(): Int = (0 until pageCount).sumOf(::lengthOf)

        /** Where each page starts, plus the end of the last one */
        fun offsets(): IntArray {
            val count = pageCount
            val offsets = IntArray(count + 1)
            for (page in 0 until count) offsets[page + 1] = offsets[page] + lengthOf(page)
            return offsets
        }
    }

    class ItemList<T>(
        val size: Int,
        val pageSize: Int,
        val pages: Map<Int, List<T>>,
        /** Kid content rules: page start positions when pages have their own lengths, else null (all full) */
        private val offsets: IntArray? = null,
    ) {
        /** The server page holding [position] */
        fun pageOf(position: Int): Int {
            val offsets = offsets ?: return position / pageSize
            if (offsets.size < 2) return 0
            // The last page starting at or before position, skipping empty pages
            var low = 0
            var high = offsets.size - 2
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (offsets[mid] <= position) low = mid else high = mid - 1
            }
            return low
        }

        fun withPages(pages: Map<Int, List<T>>): ItemList<T> = ItemList(size, pageSize, pages, offsets)

        /** The position of the first item of [page] */
        fun startOf(page: Int): Int = offsets?.let { if (page < it.size) it[page] else it.last() } ?: (page * pageSize)

        operator fun get(position: Int): T? {
            val page = pageOf(position)
            val data = pages[page]
            if (data != null) {
                val index = position - startOf(page)
                if (index in data.indices) {
                    return data[index]
                } else {
                    // This can happen when items are removed while scrolling
                    Timber.w(
                        "Index $index not in data: position=$position, data.size=${data.size}",
                    )
                    return null
                }
            } else {
                return null
            }
        }
    }
}

data class QueryResult<T>(
    val items: List<T>,
    val totalCount: Int?,
    /**
     * Kid content rules: how many entries the filter removed from this page (its [totalCount] is already lowered by as
     * many), or null if the page wasn't filtered
     */
    val removed: Int? = null,
)
