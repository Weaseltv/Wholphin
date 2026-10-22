package com.github.damontecres.wholphin.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Kid content rules (review rounds 1 and 2): play-from starts with the picked item, never at another position */
class PlaylistAnchorTest {
    /** Windows of [size] from the server's [order], without [hidden], like the kid filter serves them */
    private fun server(
        order: List<String>,
        hidden: Set<String>,
        size: Int,
        fetched: MutableList<Int> = mutableListOf(),
    ): suspend (Int) -> Window<String> =
        { start ->
            fetched.add(start)
            Window(order.drop(start).take(size).filterNot { it in hidden }, order.size)
        }

    private fun pick(id: String): suspend () -> String? = { id }

    @Test
    fun `playing C from a filtered list starts at C`() =
        runTest {
            // Server order [A, hidden, B, C]; the kid sees [A, B, C] and picks C at position 2
            val fetch = server(listOf("A", "h", "B", "C"), setOf("h"), size = 2)
            assertEquals(listOf("C"), queueFrom(2, 2, { it: String -> it == "C" }, pick("C"), fetch))
            // B is first in its window already: that window as it is
            assertEquals(listOf("B"), queueFrom(1, 2, { it: String -> it == "B" }, pick("B"), fetch))
        }

    @Test
    fun `the picked item is found however far on, then its successors follow`() =
        runTest {
            val order = (0 until 5000).map { "i$it" }
            val hidden = (0 until 4000).map { "i$it" }.toSet()
            val fetch = server(order, hidden, size = 100)
            // The kid's position 0 is server position 4000: 40 windows on, beyond the old cap of 20
            assertEquals((4000 until 4100).map { "i$it" }, queueFrom(0, 100, { it: String -> it == "i4000" }, pick("i4000"), fetch))
            assertEquals((4050 until 4150).map { "i$it" }, queueFrom(50, 100, { it: String -> it == "i4050" }, pick("i4050"), fetch))
        }

    @Test
    fun `the last window is examined too`() =
        runTest {
            val order = (0 until 25).map { "i$it" }
            val fetch = server(order, (0 until 20).map { "i$it" }.toSet(), size = 10)
            assertEquals(listOf("i24"), queueFrom(4, 10, { it: String -> it == "i24" }, pick("i24"), fetch))
        }

    @Test
    fun `a pick that can't be loaded fails instead of playing another position`() =
        runTest {
            val fetch = server(listOf("A", "B", "C"), setOf("A"), size = 10)
            try {
                queueFrom(0, 10, { it: String -> it == "C" }, { null }, fetch)
                fail("Expected the pick to be unavailable")
            } catch (_: StartItemUnavailableException) {
            }
            // Loaded but no longer in the list: just the pick
            assertEquals(listOf("Z"), queueFrom(0, 10, { it: String -> it == "Z" }, pick("Z"), fetch))
        }

    @Test
    fun `unfiltered lists and no pick are unchanged`() =
        runTest {
            val fetched = mutableListOf<Int>()
            val fetch = server(listOf("A", "B", "C"), emptySet(), size = 10, fetched)
            assertEquals(listOf("B", "C"), queueFrom(1, 10, { it: String -> it == "B" }, { error("not loaded") }, fetch))
            assertEquals(listOf("B", "C"), queueFrom(1, 10, null, { error("not loaded") }, fetch))
            assertEquals(listOf(1, 1), fetched)
        }
}
