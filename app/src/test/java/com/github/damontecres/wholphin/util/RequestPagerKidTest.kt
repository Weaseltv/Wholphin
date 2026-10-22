package com.github.damontecres.wholphin.util

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kid content rules: pages the filter shortened leave no gaps (contract section 10) */
class RequestPagerKidTest {
    /**
     * A server of [total] items "0".."total-1" in pages of [pageSize]; [hidden] are removed the way the kid filter does:
     * dropped from the page, the total lowered by as many, and the page marked as filtered
     */
    private class FakePager(
        scope: TestScope,
        val total: Int,
        val hidden: Set<Int>,
        val filtered: Boolean = true,
        pageSize: Int = 10,
    ) : RequestPager<String>(scope, pageSize) {
        val fetched = mutableListOf<Int>()

        override suspend fun fetchPage(
            pageNumber: Int,
            includeTotalCount: Boolean,
        ): QueryResult<String> {
            fetched.add(pageNumber)
            val range = (pageNumber * pageSize until minOf(total, (pageNumber + 1) * pageSize))
            val kept = range.filterNot { filtered && it in hidden }.map { it.toString() }
            val removed = range.count() - kept.size
            return QueryResult(kept, total - removed, if (filtered) removed else null)
        }
    }

    private suspend fun FakePager.all(): List<String?> {
        val out = mutableListOf<String?>()
        var i = 0
        while (i < size) {
            val item = getBlocking(i)
            // The list can shrink to below i while the page loads
            if (i < size) out.add(item)
            i++
        }
        return out
    }

    @Test
    fun `short pages are stitched together without gaps`() =
        runTest {
            val hidden = setOf(1, 2, 3, 12, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 31)
            val pager = FakePager(this, total = 35, hidden = hidden).also { it.init(0) }
            // Until the other pages load they count as full
            assertEquals(32, pager.size)
            val expected = (0 until 35).filterNot { it in hidden }.map { it.toString() }
            assertEquals(expected, pager.all())
            assertEquals(expected.size, pager.size)
            assertEquals(listOf(0, 1, 2, 3), pager.fetched)
        }

    @Test
    fun `an empty page in the middle is skipped`() =
        runTest {
            val pager = FakePager(this, total = 30, hidden = (10 until 20).toSet()).also { it.init(0) }
            val expected = ((0 until 10) + (20 until 30)).map { it.toString() }
            assertEquals(expected, pager.all())
        }

    @Test
    fun `everything hidden ends empty`() =
        runTest {
            val pager = FakePager(this, total = 25, hidden = (0 until 25).toSet()).also { it.init(0) }
            assertEquals(emptyList<String>(), pager.all())
            assertEquals(0, pager.size)
        }

    @Test
    fun `jumping ahead loads that page and corrects positions as earlier pages arrive`() =
        runTest {
            val hidden = setOf(0, 1, 2, 3, 4)
            val pager = FakePager(this, total = 40, hidden = hidden).also { it.init(0) }
            assertEquals(35, pager.size)
            // Position 25 is estimated in page 3 (pages 1 and 2 not loaded yet, counted as full)
            assertEquals("30", pager.getBlocking(25))
            assertEquals((5 until 40).map { it.toString() }, pager.all())
        }

    @Test
    fun `unfiltered pagers keep fixed pages`() =
        runTest {
            val pager = FakePager(this, total = 25, hidden = setOf(1), filtered = false).also { it.init(0) }
            assertEquals(25, pager.size)
            assertEquals((0 until 25).map { it.toString() }, pager.all())
        }

    @Test
    fun `indexOf finds items in filtered pages`() =
        runTest {
            val pager = FakePager(this, total = 30, hidden = setOf(0, 5, 15)).also { it.init(0) }
            assertEquals(14, pager.indexOfBlocking { it == "17" })
            assertEquals(-1, pager.indexOfBlocking { it == "15" })
        }

    @Test
    fun `positions behind many empty filtered pages still resolve`() =
        runTest {
            // Review round 1: 60 pages in a row are empty after filtering
            val pager = FakePager(this, total = 700, hidden = (0 until 600).toSet()).also { it.init(0) }
            assertEquals("600", pager.getBlocking(0))
            assertEquals(50, pager.indexOfBlocking { it == "650" })
            assertEquals((600 until 700).map { it.toString() }, pager.all())
        }
}
