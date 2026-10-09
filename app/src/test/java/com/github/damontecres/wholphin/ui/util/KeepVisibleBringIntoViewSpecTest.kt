package com.github.damontecres.wholphin.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepVisibleBringIntoViewSpecTest {
    private val spec = KeepVisibleBringIntoViewSpec

    @Test
    fun revealingCaptionDoesNotMakeTheInnerImageScrollBack() {
        val movement = spec.calculateScrollDistance(220f, 340f, 540f)
        assertEquals(20f, movement, 0f)
        assertEquals(0f, spec.calculateScrollDistance(220f - movement, 340f, 540f), 0f)
        assertEquals(0f, spec.calculateScrollDistance(220f - movement, 260f, 540f), 0f)
    }

    @Test
    fun horizontalFocusChangesDoNotRepositionAnAlreadyVisibleRow() {
        listOf(280f, 320f, 300f, 340f).forEach { height ->
            assertEquals(0f, spec.calculateScrollDistance(180f, height, 540f), 0f)
        }
    }

    @Test
    fun roundedPixelRemaindersDoNotStartAnotherScroll() {
        assertEquals(0f, spec.calculateScrollDistance(-0.7f, 300f, 540f), 0f)
        assertEquals(0f, spec.calculateScrollDistance(200.7f, 340f, 540f), 0f)
    }

    @Test
    fun oversizedRowsDoNotAlternateBetweenOppositeEdges() {
        assertEquals(0f, spec.calculateScrollDistance(-20f, 600f, 540f), 0f)
        assertEquals(-30f, spec.calculateScrollDistance(-30f, 300f, 540f), 0f)
    }
}
