package com.github.damontecres.wholphin.leanback.shell

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * What a screen can ask of the activity (implemented by MainActivity). Contract: `runbooks/no-compose/CONTRACT.md`.
 */
interface ScreenHost {
    /** The signed-in user's preferences; null only before the first value is read */
    val userPreferences: StateFlow<UserPreferences?>

    /** Clear the shared backdrop (the old `onClearBackdrop`) */
    fun clearBackdrop()

    /** Open or focus the side menu (nav drawer) from a screen that has it, eg LEFT at a row's first card */
    fun focusNavDrawer(): Boolean
}

/**
 * Base class of every destination screen in the no-Compose UI. The shell (`leanback.shell.NavHost`) creates one per
 * back-stack entry through `ScreenFactory`, keeps it alive (hidden) while a later page is on top and removes it (its
 * ViewModels are cleared) when the entry is popped, the same lifetime Navigation3 gave the composables.
 *
 * Subclasses are `@AndroidEntryPoint` Hilt fragments; get ViewModels with `by viewModels()` (assisted ones with
 * `by viewModels(extrasProducer = { defaultViewModelCreationExtras.withCreationCallback<Factory> { it.create(...) } })`).
 */
abstract class DestinationFragment : Fragment() {
    /** Set by the shell right after construction (also kept in the arguments by key, see [NavHostState]) */
    var destinationOrNull: Destination? = null
        internal set

    val destination: Destination
        get() = destinationOrNull ?: error("${javaClass.simpleName} has no destination")

    @Suppress("UNCHECKED_CAST")
    fun <T : Destination> requireDestination(): T = destination as T

    val screenHost: ScreenHost
        get() = requireActivity() as ScreenHost

    /** The latest preferences; screens that showed `preferences: UserPreferences` read this */
    val preferences: UserPreferences
        get() = screenHost.userPreferences.value ?: error("No preferences yet")

    val preferencesFlow: Flow<UserPreferences>
        get() = screenHost.userPreferences.filterNotNull()

    /**
     * The page's section accent and card appearance (what the Compose tree provided through CompositionLocals:
     * `LocalNeonSectionAccent`, `LocalHomeCardAppearance`, `LocalHomeMediaSettings`, `LocalPosterCountAppearance`).
     * Set by the shell before the screen is shown and updated when the side menu or Home settings change;
     * [onPageStyleChanged] is called after an update.
     */
    var pageStyle: PageStyle = PageStyle()
        set(value) {
            val changed = field != value
            field = value
            if (changed && view != null) onPageStyleChanged(value)
        }

    /** Re-bind visible cards when [pageStyle] changed (default: nothing) */
    open fun onPageStyleChanged(style: PageStyle) {}

    /** The view that had focus when this screen was hidden, restored by [onScreenFocus] */
    internal var lastFocused: View? = null

    /**
     * True while this screen is the visible top page (set by the shell). A screen whose content arrives after
     * [onScreenFocus] (data loading) checks it before taking focus for that content.
     */
    var isTopScreen: Boolean = false
        internal set

    /**
     * Content that arrived after [onScreenFocus] (eg the first row once data loaded) takes focus on [view] when this is
     * the top page and focus is not already inside it; this also takes focus from the side menu after a menu click,
     * as the Compose pages' load-time requestFocus did. Returns whether [view] has focus.
     */
    fun requestInitialFocus(view: View): Boolean {
        if (!isTopScreen || this.view?.hasFocus() == true) return view.isFocused
        return view.requestFocus()
    }

    /**
     * The shell calls this when the screen becomes the visible top page and should take focus: after it is first
     * shown ([restoring] false) and when the page above it is popped ([restoring] true).
     *
     * The default restores the last focused view, otherwise asks the root for its default focus. Override to put
     * focus on a specific view first time (eg the Play button), call super for the restore case.
     */
    open fun onScreenFocus(restoring: Boolean) {
        val last = lastFocused
        if (restoring && last != null && last.isAttachedToWindow && last.isShown && last.requestFocus()) return
        view?.let { root ->
            // restoreDefaultFocus is API 26+; before that the first focusable descendant takes focus
            val restored = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && root.restoreDefaultFocus()
            if (!restored) root.requestFocus()
        }
    }

    /**
     * BACK while this screen is on top. Return true when the screen consumed it (closed an inner step such as a
     * dialog-like panel or an expanded section); false lets the shell go back a page.
     */
    open fun onBackPressed(): Boolean = false

    /**
     * Every key event while this screen is on top, before normal View dispatch (the old `onPreviewKeyEvent` at page
     * level, used by playback and the slideshow). Return true to consume.
     */
    open fun onPreviewKeyEvent(event: KeyEvent): Boolean = false

    /** Whether the side menu (nav drawer) shows next to this screen; from [Destination.fullScreen] */
    open val showsNavDrawer: Boolean
        get() = destinationOrNull?.fullScreen == false

    /** Collect while the view is at least STARTED, cancelled with the view */
    fun <T> Flow<T>.collectWhileStarted(block: suspend (T) -> Unit): Job =
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { block(it) }
            }
        }

    /** Launch in the view lifecycle while STARTED */
    fun launchWhileStarted(block: suspend CoroutineScope.() -> Unit): Job =
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED, block)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (destinationOrNull == null) {
            destinationOrNull = NavHostState.destinationFor(arguments)
        }
    }

    companion object {
        const val ARG_DESTINATION_KEY = "wholphin.destinationKey"
    }
}

/**
 * Process-wide map from a fragment argument key to its [Destination], so a fragment re-created by the
 * FragmentManager (activity recreation) finds its destination. After process death the map is empty and the shell
 * rebuilds the stack from the saved back stack instead.
 */
object NavHostState {
    private val destinations = HashMap<String, Destination>()

    @Synchronized
    fun register(
        key: String,
        destination: Destination,
    ) {
        destinations[key] = destination
    }

    @Synchronized
    fun unregister(key: String) {
        destinations.remove(key)
    }

    /** Whether [key] is registered (its page can be restored with its destination) */
    @Synchronized
    fun has(key: String): Boolean = destinations.containsKey(key)

    @Synchronized
    fun destinationFor(arguments: Bundle?): Destination? =
        arguments?.getString(DestinationFragment.ARG_DESTINATION_KEY)?.let { destinations[it] }
}
