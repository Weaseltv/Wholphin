package com.github.damontecres.wholphin.leanback.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropResult
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

/**
 * The container of the app's pages, with a 1x1 invisible "focus sink" under them: while a new page is still loading
 * and has nothing to focus, focus waits there instead of falling to the side menu (which would open it). The sink is
 * focusable only while it holds focus, so focus search never finds it.
 */
@SuppressLint("ViewConstructor")
internal class PageContainer(
    context: Context,
) : FrameLayout(context) {
    val sink =
        View(context).apply {
            isFocusable = false
            onFocusChangeListener =
                View.OnFocusChangeListener { v, hasFocus ->
                    if (!hasFocus) v.isFocusable = false
                }
        }

    init {
        id = R.id.lb_shell_pages
        // In the middle of the page area, so arrows from it behave like from a page-filling loading view
        addView(sink, LayoutParams(1, 1, android.view.Gravity.CENTER))
        // Card glows, scaled cards and the Neon section rules draw past a page's edges (into the 24dp gutter at the
        // side menu and the 8dp one at the screen end), as the Compose content did
        clipChildren = false
        clipToPadding = false
    }

    /** Park focus on the sink */
    fun park(): Boolean {
        sink.isFocusable = true
        return sink.requestFocus()
    }

    val parked: Boolean get() = sink.isFocused
}

/**
 * Turns the app's back stack ([com.github.damontecres.wholphin.services.NavigationManager.backStack]) into pages: one
 * [DestinationFragment] per entry, created by [ScreenFactory], kept alive (hidden) while later pages are on top and
 * removed (ViewModels cleared) when its entry goes, the lifetime Navigation3 gave each composable.
 *
 * Going forward the new page fades in over 150 ms while the one under it stays whole; going back (the new stack is a
 * strict prefix of the old one, Navigation3's rule) the page on top fades out over 150 ms and the one under it shows at
 * once. Anything else (replaceTop, menu navigation, reloadHome) counts as forward.
 */
internal class NavHost(
    private val fm: FragmentManager,
    private val container: PageContainer,
    private val callbacks: Callbacks,
) {
    /** What the host needs from the shell */
    interface Callbacks {
        val preferences: UserPreferences?

        /** The page style of [destination] under [stack] (ApplicationContent's CompositionLocals) */
        fun pageStyle(
            stack: List<Destination>,
            destination: Destination,
        ): PageStyle

        /** The kid guard: whether [destination] may open for the session now */
        fun mayOpen(destination: Destination): Boolean

        /** A kid session was kept out of [destination] */
        fun onBlocked(destination: Destination)

        fun clearBackdrop()

        /** The backdrop showing now, saved on a page that another page covers */
        fun currentBackdrop(): BackdropResult

        /** Show [backdrop] again (a covered page came back) */
        fun restoreBackdrop(backdrop: BackdropResult)

        fun menuHasFocus(): Boolean

        /** A new page is on top; [pop] when it was revealed by going back */
        fun onTopChanged(
            top: DestinationFragment,
            pop: Boolean,
        )

        /** Place a page's view (margins for the side menu, slide) */
        fun layoutPage(
            fragment: DestinationFragment,
            view: View,
        )
    }

    private class Entry(
        val destination: Destination,
        val key: String,
        val fragment: DestinationFragment,
    )

    private val entries = ArrayList<Entry>()

    /** Pages are only built while the signed-in app content shows */
    var active = false
        private set

    private var pendingFocus: DestinationFragment? = null
    private var pendingRestoring = false
    private var pendingPark = false

    /** The visible page */
    val top: DestinationFragment? get() = entries.lastOrNull()?.fragment

    /** Every page, bottom first */
    val pages: List<DestinationFragment> get() = entries.map { it.fragment }

    /** The destinations the pages show */
    val destinations: List<Destination> get() = entries.map { it.destination }

    private val lifecycleCallbacks =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?,
            ) {
                if (f is DestinationFragment && entries.any { it.fragment === f }) callbacks.layoutPage(f, v)
            }

            override fun onFragmentResumed(
                fm: FragmentManager,
                f: Fragment,
            ) {
                if (f === pendingFocus) {
                    pendingFocus = null
                    focusTop(f as DestinationFragment, pendingRestoring, pendingPark)
                }
            }
        }

    init {
        fm.registerFragmentLifecycleCallbacks(lifecycleCallbacks, false)
    }

    /** Start building pages for [stack]; restored pages (activity recreation) that still match it are kept */
    fun activate(stack: List<Destination>) {
        if (active) return
        active = true
        adoptRestored(stack)
        if (entries.isNotEmpty() && entries.map { it.destination } == stack) {
            // Every restored page still matches: show the top one as if it had just been reached
            entries.forEachIndexed { i, e -> e.fragment.isTopScreen = i == entries.lastIndex }
            entries.forEach { e -> e.fragment.view?.let { callbacks.layoutPage(e.fragment, it) } }
            refreshStyles()
            val top = entries.last()
            callbacks.onTopChanged(top.fragment, pop = false)
            focusTop(top.fragment, restoring = true, park = true)
        } else {
            entries.forEach { e -> e.fragment.view?.let { callbacks.layoutPage(e.fragment, it) } }
            apply(stack)
        }
    }

    /** Remove every page (the signed-in content went away) */
    fun deactivate() {
        active = false
        clearAll()
    }

    /** Remove every page now, without animation */
    fun clearAll() {
        pendingFocus = null
        if (fm.isDestroyed) {
            entries.clear()
            return
        }
        val pageFragments = fm.fragments.filter { it.tag?.startsWith(TAG_PREFIX) == true }
        if (pageFragments.isNotEmpty()) {
            val tx = fm.beginTransaction().setReorderingAllowed(true)
            pageFragments.forEach { tx.remove(it) }
            tx.commitNowAllowingStateLoss()
        }
        entries.forEach { NavHostState.unregister(it.key) }
        entries.clear()
    }

    /**
     * After activity recreation the FragmentManager has restored the old pages: keep those whose destination still
     * matches the restored back stack from the bottom, drop the rest (orphans)
     */
    private fun adoptRestored(stack: List<Destination>) {
        if (fm.isDestroyed) return
        val restored = fm.fragments.filter { it.tag?.startsWith(TAG_PREFIX) == true }
        if (restored.isEmpty()) return
        val keep = mutableListOf<Entry>()
        for (f in restored) {
            val i = keep.size
            val d = (f as? DestinationFragment)?.destinationOrNull
            if (f is DestinationFragment && d != null && i < stack.size && stack[i] == d) {
                keep += Entry(d, f.tag!!, f)
            } else {
                break
            }
        }
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        restored.filter { f -> keep.none { it.fragment === f } }.forEach {
            Timber.d("Dropping restored page %s", it.tag)
            it.tag?.let(NavHostState::unregister)
            tx.remove(it)
        }
        keep.forEachIndexed { i, e -> if (i < keep.lastIndex) tx.hide(e.fragment) else tx.show(e.fragment) }
        tx.commitNowAllowingStateLoss()
        entries.clear()
        entries.addAll(keep)
        Timber.d("Kept %s restored pages", keep.size)
    }

    /** Follow [stack]; empty stacks (a caller's clear() before addAll()) are ignored */
    fun apply(stack: List<Destination>) {
        if (!active || stack.isEmpty() || fm.isDestroyed) return
        val old = entries.map { it.destination }
        if (old == stack) {
            refreshStyles()
            return
        }
        var common = 0
        while (common < old.size && common < stack.size && old[common] == stack[common]) common++
        val pop = old.isNotEmpty() && stack.size < old.size && common == stack.size
        val focusInMenu = callbacks.menuHasFocus()
        val oldTop = entries.lastOrNull()
        val removed = entries.subList(common, entries.size).toList()
        val focusInRemoved = removed.any { it.fragment.view?.hasFocus() == true }
        val focusInOldTop = oldTop?.fragment?.view?.hasFocus() == true
        val added = mutableListOf<Entry>()
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        if (pop) {
            tx.setCustomAnimations(0, R.animator.lb_shell_page_exit)
            removed.forEach { tx.remove(it.fragment) }
            tx.show(entries[common - 1].fragment)
        } else {
            if (oldTop != null) tx.setCustomAnimations(R.animator.lb_shell_page_enter, R.animator.lb_shell_page_hold)
            removed.forEach { tx.remove(it.fragment) }
            if (removed.isEmpty() && oldTop != null) {
                // What to restore when this page is shown again: taken now, because when the page above is removed
                // later, Android moves focus into this page by itself and that would overwrite lastFocused
                oldTop.fragment.focusToRestore = oldTop.fragment.lastFocused
                oldTop.fragment.backdropToRestore = callbacks.currentBackdrop()
                tx.hide(oldTop.fragment)
            }
            for (i in common until stack.size) {
                val entry = create(stack, i)
                tx.add(container.id, entry.fragment, entry.key)
                if (i < stack.lastIndex) tx.hide(entry.fragment)
                added += entry
            }
        }
        entries.subList(common, entries.size).clear()
        entries.addAll(added)
        entries.forEachIndexed { i, e -> e.fragment.isTopScreen = i == entries.lastIndex }
        tx.commitNowAllowingStateLoss()
        removed.forEach { NavHostState.unregister(it.key) }
        refreshStyles()

        val newTop = entries.last()
        callbacks.onTopChanged(newTop.fragment, pop)
        val savedBackdrop = newTop.fragment.backdropToRestore.takeIf { pop }
        newTop.fragment.backdropToRestore = null
        if (savedBackdrop != null) {
            // Back to a covered page: the backdrop it showed, as its Compose page re-submitted on returning
            callbacks.restoreBackdrop(savedBackdrop)
        } else if (callbacks.mayOpen(newTop.destination) && clearsBackdrop(newTop.destination)) {
            callbacks.clearBackdrop()
        }
        // Focus: a page reached from the menu takes focus itself once it can (the menu keeps it meanwhile); going back
        // with focus in the menu leaves it there; otherwise the new page takes it, or it waits on the sink
        val parkIfNone = !focusInMenu && (focusInRemoved || focusInOldTop || container.parked || !container.hasFocus())
        if (pop) {
            if (!focusInMenu) focusTop(newTop.fragment, restoring = true, park = true)
        } else {
            focusTop(newTop.fragment, restoring = false, park = parkIfNone)
        }
    }

    private fun create(
        stack: List<Destination>,
        index: Int,
    ): Entry {
        val destination = stack[index]
        val key = TAG_PREFIX + UUID.randomUUID()
        val preferences = callbacks.preferences
        val allowed = callbacks.mayOpen(destination)
        val fragment =
            when {
                !allowed -> BlankScreenFragment()
                preferences == null -> UnsupportedScreenFragment()
                else -> ScreenFactory.create(destination, preferences) ?: UnsupportedScreenFragment()
            }
        fragment.destinationOrNull = destination
        fragment.arguments = (fragment.arguments ?: Bundle()).apply { putString(DestinationFragment.ARG_DESTINATION_KEY, key) }
        NavHostState.register(key, destination)
        fragment.pageStyle = callbacks.pageStyle(stack, destination)
        if (!allowed) container.post { callbacks.onBlocked(destination) }
        return Entry(destination, key, fragment)
    }

    /** Recompute every page's style (side menu items or Home settings changed) */
    fun refreshStyles() {
        val stack = entries.map { it.destination }
        entries.forEach { it.fragment.pageStyle = callbacks.pageStyle(stack, it.destination) }
    }

    /** Give the top page focus: [DestinationFragment.onScreenFocus], or the sink when it has nothing yet */
    fun focusTop(
        fragment: DestinationFragment,
        restoring: Boolean,
        park: Boolean,
    ) {
        val view = fragment.view
        if (view == null || !view.isAttachedToWindow || !fragment.isResumed) {
            pendingFocus = fragment
            pendingRestoring = restoring
            pendingPark = park
            return
        }
        fragment.onScreenFocus(restoring)
        if (park && !view.hasFocus()) container.park()
    }

    /** Remember the focused view of the page holding [focused] (restored when the page above it is popped) */
    fun onFocusChanged(focused: View?) {
        if (focused == null) return
        for (e in entries) {
            val v = e.fragment.view ?: continue
            if (isDescendant(focused, v)) {
                e.fragment.lastFocused = focused
                return
            }
        }
    }

    /** Whether [view] is inside the top page */
    fun inTopPage(view: View?): Boolean {
        val v = top?.view ?: return false
        return view != null && isDescendant(view, v)
    }

    /**
     * The app layer was asked for focus (eg the focused view went away): the top page first (its last focused view),
     * then the sink
     */
    fun requestFocusForLayer(
        direction: Int,
        rect: Rect?,
    ): Boolean {
        val f = top
        val v = f?.view
        if (f != null && v != null && v.isShown) {
            f.onScreenFocus(restoring = true)
            if (v.hasFocus()) return true
        }
        return container.park()
    }

    companion object {
        const val TAG_PREFIX = "lb_page:"

        fun isDescendant(
            view: View,
            ancestor: View,
        ): Boolean {
            var v: View? = view
            while (v != null) {
                if (v === ancestor) return true
                v = v.parent as? View
            }
            return false
        }

        /**
         * Pages that clear the shared backdrop when they show (the old DestinationContent's `onClearBackdrop` calls):
         * full-screen pages, collections, playlists, libraries, people, photo albums, grids, Watchlist, Search and
         * Requests
         */
        fun clearsBackdrop(destination: Destination): Boolean =
            destination.fullScreen ||
                when (destination) {
                    is Destination.MediaItem -> {
                        destination.type in
                            setOf(
                                BaseItemKind.BOX_SET,
                                BaseItemKind.PLAYLIST,
                                BaseItemKind.COLLECTION_FOLDER,
                                BaseItemKind.FOLDER,
                                BaseItemKind.USER_VIEW,
                                BaseItemKind.PERSON,
                                BaseItemKind.PHOTO_ALBUM,
                            )
                    }

                    is Destination.FilteredCollection,
                    is Destination.MoreHomeRow,
                    is Destination.ItemGrid<*>,
                    Destination.Favorites,
                    is Destination.Search,
                    is Destination.Discover,
                    is Destination.DiscoverMoreResult,
                    -> {
                        true
                    }

                    is Destination.DiscoveredItem -> {
                        destination.item.type == SeerrItemType.PERSON
                    }

                    else -> {
                        false
                    }
                }
    }
}
