package com.github.damontecres.wholphin.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailViewportStateTest {
    @Test
    fun lateArrivingCastReplacesEarlierChapterMeasurement() {
        val viewport = DetailViewportState(540.dp)
        val chapters = Any()
        val cast = Any()
        viewport.reportRow(chapters, 180.dp, priority = 3)
        viewport.reportRow(cast, 240.dp, priority = 1)
        viewport.reportRow(chapters, 190.dp, priority = 3)
        assertEquals(240.dp, viewport.firstRowHeight)
    }

    @Test
    fun captionSizeChangesUpdateTheFirstRowWithoutUsingLaterRows() {
        val viewport = DetailViewportState(540.dp)
        val seasons = Any()
        viewport.reportRow(seasons, 250.dp, priority = 1)
        viewport.reportRow(Any(), 330.dp, priority = 2)
        viewport.reportRow(seasons, 280.dp, priority = 1)
        assertEquals(280.dp, viewport.firstRowHeight)
    }
}
