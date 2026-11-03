package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem

@Composable
fun DiscoverPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    initialTab: Int? = null,
    focusRequestId: Int? = null,
) {
    // WeaselPlex: the Requests page is one ACTIVITY tab (owner design 2026-11-03). The search
    // box sits at the top; below it, the request queue, or search results while a search runs.
    // Upstream's Discover, Movies and TV Shows browse tabs stay hidden (their composables remain
    // for the detail pages that still use them).
    val tabs = remember { listOf(TabDetails(R.string.activity)) }
    var showHeader by rememberSaveable { mutableStateOf(true) }

    TabbedPage(
        itemId = NavDrawerItem.Discover.id,
        // Older callers still ask for the former My Requests tab (index 1); there is one tab now.
        initialTab = initialTab?.let { 0 },
        tabs = tabs,
        modifier = modifier,
        showTabs = showHeader,
    ) { _, tabDetails ->
        DiscoverSearchPage(
            preferences = preferences,
            positionCallback = { columns, index -> showHeader = index < columns },
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(tabDetails.contentFocusRequester),
            // With the tab row shown, the search bar and the queue run the full content width.
            fullWidth = showHeader,
            idleContent = { queueModifier ->
                // Scrolling search results can hide the tab; the queue always shows it.
                LaunchedEffect(Unit) { showHeader = true }
                SeerrRequestsPage(
                    focusRequestId = focusRequestId,
                    modifier = queueModifier,
                )
            },
        )
    }
}
