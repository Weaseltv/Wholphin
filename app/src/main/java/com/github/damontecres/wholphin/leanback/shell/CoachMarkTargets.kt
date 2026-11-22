package com.github.damontecres.wholphin.leanback.shell

import android.graphics.Rect
import android.view.View
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkIds
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour

/**
 * Where Views tell the WeaselPlex tour what to spotlight (the old Compose `Modifier.coachMarkTarget`).
 *
 * ```kotlin
 * CoachMarkTargets.register(CoachMarkIds.HOME_HEADER, headerView)   // once the view exists
 * CoachMarkTargets.register(CoachMarkIds.HOME_ROW, focusedRowView)  // again whenever another row takes focus
 * CoachMarkTargets.unregister(CoachMarkIds.HOME_ROW, oldRowView)    // or unregisterAll(view) when it goes away
 * ```
 *
 * A later registration for the same id replaces the earlier one; unregistering only removes the view's own
 * registration. The tour reads each target's window bounds while it runs; a view that is detached or not shown counts
 * as not on screen. Does nothing while the tour is off (flavors without it, or before the shell starts).
 */
object CoachMarkTargets {
    /** Set by the shell: the tour, or null when this flavor has none ([com.github.damontecres.wholphin.BuildConfig.COACH_MARK_TOUR_ENABLED]) */
    internal var tour: CoachMarkTour? = null

    /** Whether registrations go anywhere */
    val enabled: Boolean get() = tour != null

    /** Marks [view] as the thing the tour spotlights for [id] (a [CoachMarkIds] id, or the library prefix + item id) */
    fun register(
        id: String,
        view: View,
    ) {
        val t = tour ?: return
        noteLibraryId(id)
        t.register(id, view) { boundsOf(view) }
    }

    /** Removes [view]'s registration for [id] */
    fun unregister(
        id: String,
        view: View,
    ) {
        tour?.unregister(id, view)
    }

    /** Removes [view]'s registrations for every id the shell and Home use */
    fun unregisterAll(view: View) {
        val t = tour ?: return
        listOf(
            CoachMarkIds.HOME_ROW,
            CoachMarkIds.HOME_HEADER,
            CoachMarkIds.MENU,
            CoachMarkIds.PROFILE,
            CoachMarkIds.SEARCH,
            CoachMarkIds.HOME,
            CoachMarkIds.WATCHLIST,
            CoachMarkIds.REQUESTS,
            CoachMarkIds.SETTINGS,
            CoachMarkIds.UPDATE_PROMPT,
        ).forEach { t.unregister(it, view) }
        libraryIds.toList().forEach { t.unregister(it, view) }
    }

    private val libraryIds = mutableSetOf<String>()

    private fun boundsOf(view: View): Rect? {
        if (!view.isAttachedToWindow || !view.isShown || view.width == 0 || view.height == 0) return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    internal fun noteLibraryId(id: String) {
        if (id.startsWith(CoachMarkIds.LIBRARY_PREFIX)) libraryIds += id
    }
}
