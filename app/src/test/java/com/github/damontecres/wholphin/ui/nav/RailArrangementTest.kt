package com.github.damontecres.wholphin.ui.nav

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The side menu's spacing (owner 2026-10-23): a kid's menu has no Settings footer, so nothing is pinned to the bottom */
class RailArrangementTest {
    private val density = Density(1f)

    private fun positions(
        pinFooter: Boolean,
        sizes: IntArray,
        total: Int,
    ): IntArray {
        val out = IntArray(sizes.size)
        with(railArrangement(4.dp, pinFooter)) { density.arrange(total, sizes, out) }
        return out
    }

    @Test
    fun `an adult's rail keeps Settings at the bottom`() {
        // Search, Home, Watchlist, Movies, TV Shows, Settings in a tall rail
        val sizes = IntArray(6) { 40 }
        assertArrayEquals(intArrayOf(0, 44, 88, 132, 176, 460), positions(pinFooter = true, sizes, total = 500))
    }

    @Test
    fun `a kid's rail puts TV Shows right under Movies`() {
        // Search, Home, Watchlist, Collections, Movies, TV Shows: no gap before the last one
        val sizes = IntArray(6) { 40 }
        assertArrayEquals(intArrayOf(0, 44, 88, 132, 176, 220), positions(pinFooter = false, sizes, total = 500))
    }
}
