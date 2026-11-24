package com.github.damontecres.wholphin.ui.coachmark

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Ids of the things the tour points at; each one is registered as a target where it's drawn */
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
    const val UPDATE_PROMPT = "update_prompt"
}

/**
 * The tour's steps in order. [target] is a [CoachMarkIds] id, or with [group] a prefix whose targets are spotlit
 * together; the opening card has none and sits in the middle of the dimmed screen. With [picture] the tour draws the
 * target itself (the update popup). [paddingDp] is the room left around the target. A step that's neither on screen
 * nor in the menu ([CoachMarkRail.has]) when the tour starts is left out.
 */
enum class CoachMarkStep(
    val target: String,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val group: Boolean = false,
    val paddingDp: Float = 8f,
    val picture: Boolean = false,
) {
    WELCOME("", R.string.coach_welcome_title, R.string.coach_welcome_body),
    HOME_ROW(CoachMarkIds.HOME_ROW, R.string.coach_home_row_title, R.string.coach_home_row_body, paddingDp = 4f),

    // Flush with the header, so the outline doesn't cut through the row title below it
    HOME_HEADER(CoachMarkIds.HOME_HEADER, R.string.coach_home_header_title, R.string.coach_home_header_body, paddingDp = 0f),
    MENU(CoachMarkIds.MENU, R.string.coach_menu_title, R.string.coach_menu_body, paddingDp = 0f),
    PROFILE(CoachMarkIds.PROFILE, R.string.coach_profile_title, R.string.coach_profile_body, paddingDp = 2f),
    SEARCH(CoachMarkIds.SEARCH, R.string.search, R.string.coach_search_body, paddingDp = 2f),
    HOME(CoachMarkIds.HOME, R.string.home, R.string.coach_home_body, paddingDp = 2f),
    WATCHLIST(CoachMarkIds.WATCHLIST, R.string.favorites, R.string.coach_watchlist_body, paddingDp = 2f),
    REQUESTS(CoachMarkIds.REQUESTS, R.string.discover, R.string.coach_requests_body, paddingDp = 2f),
    LIBRARIES(CoachMarkIds.LIBRARY_PREFIX, R.string.coach_libraries_title, R.string.coach_libraries_body, group = true, paddingDp = 2f),
    UPDATES(CoachMarkIds.UPDATE_PROMPT, R.string.coach_updates_title, R.string.coach_updates_body, paddingDp = 4f, picture = true),
    SETTINGS(CoachMarkIds.SETTINGS, R.string.coach_settings_title_last, R.string.coach_settings_body, paddingDp = 2f),
    ;

    val hasTarget get() = target.isNotEmpty()
}

/**
 * The menu's part in the tour. Its list only shows some rows at a time, so it says which steps it has and scrolls a
 * step's row into view when that step comes up.
 */
interface CoachMarkRail {
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
 * It only ever starts when someone asks for it, from Settings → Help ([start]). Until 2026-11-13 it also started by
 * itself, once for every adult account on the TV and again for each account added; the owner had that taken out.
 *
 * Everything runs on the main thread. The remote's keys come straight from the activity ([onKey]) while the tour runs,
 * so focus never moves underneath it and is still on the same poster when the tour ends.
 * OK takes the chosen button (Next, or Skip with ▶), BACK goes to the previous step and leaves from the first one.
 *
 * No-Compose UI (2026-11-22): state is in StateFlows; targets are registered with a window-bounds provider (Views use
 * `leanback.shell.CoachMarkTargets`).
 */
@Singleton
class CoachMarkTour
    @Inject
    constructor() {
        private class Target(
            val token: Any,
            val bounds: () -> Rect?,
        )

        private val targets = mutableMapOf<String, Target>()

        private val _pending = MutableStateFlow(false)
        private val _state = MutableStateFlow<CoachMarkState?>(null)
        private val _active = MutableStateFlow(false)
        private val _homeRowReady = MutableStateFlow(false)
        private val _homeToTop = MutableStateFlow(false)

        /** Asked for, waiting for Home to be on screen before working out the steps */
        val pendingFlow: StateFlow<Boolean> = _pending.asStateFlow()

        /** The running tour, or null */
        val stateFlow: StateFlow<CoachMarkState?> = _state.asStateFlow()

        /** [active] as a flow */
        val activeFlow: StateFlow<Boolean> = _active.asStateFlow()

        /** [homeRowReady] as a flow */
        val homeRowReadyFlow: StateFlow<Boolean> = _homeRowReady.asStateFlow()

        /** [homeToTop] as a flow */
        val homeToTopFlow: StateFlow<Boolean> = _homeToTop.asStateFlow()

        /** Asked for, waiting for Home to be on screen before working out the steps */
        var pending: Boolean
            get() = _pending.value
            private set(value) {
                _pending.value = value
                _active.value = value || _state.value != null
            }

        var state: CoachMarkState?
            get() = _state.value
            private set(value) {
                _state.value = value
                _active.value = _pending.value || value != null
            }

        val active get() = pending || state != null

        /** Set by Home once the row the tour starts on (Trending Movies) has focus, or it has no such row */
        var homeRowReady: Boolean
            get() = _homeRowReady.value
            set(value) {
                _homeRowReady.value = value
            }

        /** When [start] was asked for; the overlay gives up if Home doesn't come up soon after */
        var pendingSince = 0L
            private set

        /** Set by the menu while it's on screen */
        var rail: CoachMarkRail? = null

        /** Keys pressed down while the tour ran; a key only acts when it comes back up, so a stray release never does */
        private val downKeys = mutableSetOf<Int>()

        /** Asks for the tour (Settings → Help); it appears once Home is on screen */
        fun start() {
            Timber.i("Coach-mark tour requested")
            state = null
            downKeys.clear()
            homeRowReady = false
            homeToTop = false
            pendingSince = SystemClock.uptimeMillis()
            pending = true
        }

        /** Works out the steps from what's on screen; nothing to show ends it */
        fun begin() {
            if (!pending) return
            pending = false
            val steps = CoachMarkStep.entries.filter { it.picture || bounds(it) != null || rail?.has(it) == true }
            Timber.i("Coach-mark tour started: %s", steps)
            state = if (steps.all { it.picture }) null else CoachMarkState(listOf(CoachMarkStep.WELCOME) + steps)
        }

        /** Set when the tour is closed on Home (Done, Skip tour, or BACK from the first card), for Home to go back to its top row */
        var homeToTop: Boolean
            get() = _homeToTop.value
            set(value) {
                _homeToTop.value = value
            }

        fun end() {
            if (active) Timber.i("Coach-mark tour ended at %s", state?.step)
            pending = false
            state = null
            downKeys.clear()
        }

        private fun finish() {
            end()
            homeToTop = true
        }

        private fun next() {
            val s = state ?: return
            if (s.isLast) finish() else state = s.copy(index = s.index + 1, skipSelected = false)
        }

        private fun previous() {
            val s = state ?: return
            if (s.index == 0) finish() else state = s.copy(index = s.index - 1, skipSelected = false)
        }

        /** True when the tour took the key */
        fun onKey(event: KeyEvent): Boolean {
            // Waiting for Home to come up: the remote works as usual
            if (state == null) return false
            val code = event.keyCode
            if (code !in handledKeys) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) downKeys.add(code)
                val s = state ?: return true
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> state = s.copy(skipSelected = false)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (!s.isLast) state = s.copy(skipSelected = true)
                }
                return true
            }
            if (event.action != KeyEvent.ACTION_UP || !downKeys.remove(code)) return true
            val s = state ?: return true
            when (code) {
                in okKeys -> if (s.skipSelected) finish() else next()
                in backKeys -> previous()
            }
            return true
        }

        /**
         * Marks [token]'s thing as the target for [id]; [bounds] gives its place in the window (null while it isn't laid
         * out). A later registration for the same id replaces an earlier one.
         */
        fun register(
            id: String,
            token: Any,
            bounds: () -> Rect?,
        ) {
            if (targets[id]?.token !== token) targets[id] = Target(token, bounds)
        }

        /** Removes [token]'s registration for [id]; another token's registration stays */
        fun unregister(
            id: String,
            token: Any,
        ) {
            if (targets[id]?.token === token) targets.remove(id)
        }

        /** Where [step]'s target is in the window, or null when it isn't laid out */
        fun bounds(step: CoachMarkStep): Rect? {
            if (!step.hasTarget) return null
            val rects =
                targets
                    .filter { (id, _) -> if (step.group) id.startsWith(step.target) else id == step.target }
                    .values
                    .mapNotNull { it.bounds() }
                    .filter { it.width() > 0 && it.height() > 0 }
            if (rects.isEmpty()) return null
            return Rect(rects.first()).apply { rects.drop(1).forEach { union(it) } }
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
