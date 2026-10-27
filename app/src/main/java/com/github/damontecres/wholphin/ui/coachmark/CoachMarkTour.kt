package com.github.damontecres.wholphin.ui.coachmark

import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Ids of the things the tour points at; each one is tagged with [coachMarkTarget] where it's drawn */
object CoachMarkIds {
    const val HOME_ROW = "home_row"
    const val HOME_HEADER = "home_header"
    const val MENU = "menu"
    const val PROFILE = "profile"
    const val SEARCH = "search"
    const val HOME = "home"
    const val WATCHLIST = "watchlist"
    const val REQUESTS = "requests"
    const val LIBRARY_PREFIX = "library:"
    const val SETTINGS = "settings"
}

/**
 * The tour's steps in order. [target] is a [CoachMarkIds] id, or with [group] a prefix whose targets are spotlit
 * together. [body] is a paragraph, or with [bullets] one line per entry. [padding] is the room left around the target.
 * A step that's neither on screen nor in the menu ([CoachMarkRail.has]) when the tour starts is left out.
 */
enum class CoachMarkStep(
    val target: String,
    @param:StringRes val title: Int,
    // A string, or a string array with [bullets]
    val body: Int,
    val group: Boolean = false,
    val bullets: Boolean = false,
    val padding: Dp = 8.dp,
) {
    HOME_ROW(CoachMarkIds.HOME_ROW, R.string.coach_home_row_title, R.array.coach_home_row_bullets, bullets = true),

    // Flush with the header, so the outline doesn't cut through the row title below it
    HOME_HEADER(CoachMarkIds.HOME_HEADER, R.string.coach_home_header_title, R.string.coach_home_header_body, padding = 0.dp),
    MENU(CoachMarkIds.MENU, R.string.coach_menu_title, R.string.coach_menu_body, padding = 0.dp),
    PROFILE(CoachMarkIds.PROFILE, R.string.coach_profile_title, R.string.coach_profile_body, padding = 2.dp),
    SEARCH(CoachMarkIds.SEARCH, R.string.search, R.string.coach_search_body, padding = 2.dp),
    HOME(CoachMarkIds.HOME, R.string.home, R.string.coach_home_body, padding = 2.dp),
    WATCHLIST(CoachMarkIds.WATCHLIST, R.string.favorites, R.string.coach_watchlist_body, padding = 2.dp),
    REQUESTS(CoachMarkIds.REQUESTS, R.string.discover, R.string.coach_requests_body, padding = 2.dp),

    // The body names the libraries (%1$s), from [CoachMarkRail.libraryNames]
    LIBRARIES(CoachMarkIds.LIBRARY_PREFIX, R.string.coach_libraries_title, R.string.coach_libraries_body, group = true, padding = 2.dp),
    SETTINGS(CoachMarkIds.SETTINGS, R.string.settings, R.string.coach_settings_body, padding = 2.dp),
}

/**
 * The menu's part in the tour. Its list only lays out the rows on screen, so it says which steps it has and scrolls a
 * step's row into view when that step comes up.
 */
interface CoachMarkRail {
    val libraryNames: List<String>

    fun has(step: CoachMarkStep): Boolean

    suspend fun bringIntoView(step: CoachMarkStep)
}

/** A running tour: the steps found on screen, the current one, and whether Skip (instead of Next) is chosen */
data class CoachMarkState(
    val steps: List<CoachMarkStep>,
    val index: Int = 0,
    val skipSelected: Boolean = false,
) {
    val step get() = steps[index]
    val isLast get() = index == steps.lastIndex
}

/**
 * The coach-mark tour: dims the screen, spotlights one thing at a time and explains it.
 *
 * Everything runs on the main thread. The remote's keys come straight from the activity ([onKey]) while the tour runs,
 * so focus never moves underneath it and is still on the same poster when the tour ends.
 * OK takes the chosen button (Next, or Skip with ▶), BACK goes to the previous step and leaves from the first one.
 */
@Singleton
class CoachMarkTour
    @Inject
    constructor() {
        private class Target(
            val token: Any,
            val coordinates: LayoutCoordinates,
        )

        private val targets = mutableMapOf<String, Target>()

        /** Asked for, waiting for Home to be on screen before working out the steps */
        var pending by mutableStateOf(false)
            private set

        var state by mutableStateOf<CoachMarkState?>(null)
            private set

        val active get() = pending || state != null

        /** Set by the menu while it's on screen */
        var rail: CoachMarkRail? = null

        /** Keys pressed down while the tour ran; a key only acts when it comes back up, so a stray release never does */
        private val downKeys = mutableSetOf<Int>()

        private var pendingSince = 0L

        fun start() {
            Timber.i("Coach-mark tour requested")
            state = null
            downKeys.clear()
            pendingSince = SystemClock.uptimeMillis()
            pending = true
        }

        /** Works out the steps from what's on screen; nothing to show ends it */
        fun begin() {
            if (!pending) return
            pending = false
            val steps = CoachMarkStep.entries.filter { bounds(it) != null || rail?.has(it) == true }
            Timber.i("Coach-mark tour started: %s", steps)
            state = if (steps.isEmpty()) null else CoachMarkState(steps)
        }

        fun end() {
            if (active) Timber.i("Coach-mark tour ended at %s", state?.step)
            pending = false
            state = null
            downKeys.clear()
        }

        private fun next() {
            val s = state ?: return
            if (s.isLast) end() else state = s.copy(index = s.index + 1, skipSelected = false)
        }

        private fun previous() {
            val s = state ?: return
            if (s.index == 0) end() else state = s.copy(index = s.index - 1, skipSelected = false)
        }

        /** True when the tour took the key */
        fun onKey(event: KeyEvent): Boolean {
            if (!active) return false
            // Never keep the remote if Home didn't come up to start it (the overlay gives up after about 6 s)
            if (state == null && SystemClock.uptimeMillis() - pendingSince > 8_000) {
                end()
                return false
            }
            val code = event.keyCode
            if (code !in handledKeys) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) downKeys.add(code)
                val s = state
                if (s != null) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> state = s.copy(skipSelected = false)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (!s.isLast) state = s.copy(skipSelected = true)
                    }
                }
                return true
            }
            if (event.action != KeyEvent.ACTION_UP || !downKeys.remove(code)) return true
            val s = state ?: return true
            when (code) {
                in okKeys -> if (s.skipSelected) end() else next()
                in backKeys -> previous()
            }
            return true
        }

        internal fun register(
            id: String,
            token: Any,
            coordinates: LayoutCoordinates,
        ) {
            val old = targets[id]
            if (old?.token !== token || old.coordinates !== coordinates) targets[id] = Target(token, coordinates)
        }

        internal fun unregister(
            id: String,
            token: Any,
        ) {
            if (targets[id]?.token === token) targets.remove(id)
        }

        /** Where [step]'s target is in the window, or null when it isn't laid out */
        fun bounds(step: CoachMarkStep): Rect? {
            val rects =
                targets
                    .filter { (id, _) -> if (step.group) id.startsWith(step.target) else id == step.target }
                    .values
                    .mapNotNull { t -> t.coordinates.takeIf { it.isAttached }?.boundsInWindow() }
                    .filter { it.width > 0f && it.height > 0f }
            if (rects.isEmpty()) return null
            return rects.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
        }

        private companion object {
            val okKeys =
                setOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A,
                )
            val backKeys = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)

            /** Everything that could move focus or start something underneath; volume and power still work */
            val handledKeys =
                okKeys + backKeys +
                    setOf(
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP_LEFT,
                        KeyEvent.KEYCODE_DPAD_UP_RIGHT,
                        KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
                        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_TAB,
                        KeyEvent.KEYCODE_SPACE,
                        KeyEvent.KEYCODE_SEARCH,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_MEDIA_NEXT,
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                        KeyEvent.KEYCODE_PAGE_UP,
                        KeyEvent.KEYCODE_PAGE_DOWN,
                        KeyEvent.KEYCODE_MOVE_HOME,
                        KeyEvent.KEYCODE_MOVE_END,
                    )
        }
    }

/** The tour while signed in; null elsewhere and in flavors without it, which makes [coachMarkTarget] do nothing */
val LocalCoachMarkTour = staticCompositionLocalOf<CoachMarkTour?> { null }

/** Marks this as the thing the tour spotlights for [id] (a [CoachMarkIds] id); null marks nothing */
@Composable
fun Modifier.coachMarkTarget(id: String?): Modifier {
    val tour = LocalCoachMarkTour.current
    if (tour == null || id == null) return this
    val token = remember { Any() }
    DisposableEffect(tour, id) { onDispose { tour.unregister(id, token) } }
    return this.onGloballyPositioned { tour.register(id, token, it) }
}
