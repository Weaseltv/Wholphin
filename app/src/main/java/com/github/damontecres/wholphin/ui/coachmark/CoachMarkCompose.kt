package com.github.damontecres.wholphin.ui.coachmark

import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/*
 * LEGACY (no-Compose rewrite, 2026-11-22): the Compose side of the tour, kept only so the old Compose HomePage and
 * PreferencesContent still compile until integration deletes them. Views use `leanback.shell.CoachMarkTargets`.
 * Safe to delete together with those files.
 */

/** The tour while signed in; null elsewhere and in flavors without it, which makes [coachMarkTarget] do nothing */
val LocalCoachMarkTour = staticCompositionLocalOf<CoachMarkTour?> { null }

/** Marks this as the thing the tour spotlights for [id] (a [CoachMarkIds] id); null marks nothing */
@Composable
fun Modifier.coachMarkTarget(id: String?): Modifier {
    val tour = LocalCoachMarkTour.current
    if (tour == null || id == null) return this
    val token = remember { Any() }
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(tour, id) { onDispose { tour.unregister(id, token) } }
    return this.onGloballyPositioned { coordinates ->
        holder[0] = coordinates
        tour.register(id, token) {
            holder[0]?.takeIf { it.isAttached }?.boundsInWindow()?.let {
                Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
            }
        }
    }
}
