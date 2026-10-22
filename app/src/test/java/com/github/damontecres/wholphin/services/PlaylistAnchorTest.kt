package com.github.damontecres.wholphin.services

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kid content rules (review round 1): play-from starts at the picked item, not at its filtered position */
class PlaylistAnchorTest {
    /** Windows of [size] from the server's [order], without [hidden], like the kid filter serves them */
    private fun server(
        order: List<String>,
        hidden: Set<String>,
        size: Int,
    ): suspend (Int) -> List<String> = { start -> order.drop(start).take(size).filterNot { it in hidden } }

    @Test
    fun `playing C from a filtered list starts at C`() =
        runTest {
            // Server order [A, hidden, B, C]; the kid sees [A, B, C] and picks C at position 2
            val fetch = server(listOf("A", "h", "B", "C"), setOf("h"), size = 2)
            assertEquals(listOf("C"), anchoredWindow(2, 2, { it: String -> it == "C" }, fetch))
            // B is first in its window already
            assertEquals(listOf("B"), anchoredWindow(1, 2, { it: String -> it == "B" }, fetch))
        }

    @Test
    fun `the picked item is searched in later windows and the queue is filled after it`() =
        runTest {
            val order = (0 until 50).map { "i$it" }
            val hidden = (0 until 30).map { "i$it" }.toSet()
            val fetch = server(order, hidden, size = 10)
            // The kid's position 0 is server position 30
            assertEquals((30 until 40).map { "i$it" }, anchoredWindow(0, 10, { it: String -> it == "i30" }, fetch))
            assertEquals((35 until 45).map { "i$it" }, anchoredWindow(5, 10, { it: String -> it == "i35" }, fetch))
        }

    @Test
    fun `unfiltered lists and no pick are unchanged`() =
        runTest {
            val fetch = server(listOf("A", "B", "C"), emptySet(), size = 10)
            assertEquals(listOf("B", "C"), anchoredWindow(1, 10, { it: String -> it == "B" }, fetch))
            assertEquals(listOf("B", "C"), anchoredWindow(1, 10, null, fetch))
            // A pick that is gone: from the position as before
            assertEquals(listOf("B", "C"), anchoredWindow(1, 10, { it: String -> it == "Z" }, fetch))
        }
}
