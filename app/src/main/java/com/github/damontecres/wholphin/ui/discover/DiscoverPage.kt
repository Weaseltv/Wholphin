package com.github.damontecres.wholphin.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.TabDetails
import com.github.damontecres.wholphin.ui.components.TabbedPage
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.util.DiscoverRequestType

@Composable
fun DiscoverPage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    // WeaselPlex: the Requests page is a search box first and a request history second.
    // Upstream's Discover, Movies and TV Shows browse tabs are hidden (the composables stay
    // in the tree for the detail pages that still use them); Search is the first tab so the
    // page opens ready to type, and the old "Request" tab is labelled History.
    val tabs =
        remember {
            listOf(
                TabDetails(R.string.search),
                TabDetails(R.string.requests_history),
            )
        }
    var showHeader by rememberSaveable { mutableStateOf(true) }

    TabbedPage(
        itemId = NavDrawerItem.Discover.id,
        tabs = tabs,
        modifier = modifier,
        showTabs = showHeader,
    ) { tabIndex, tabDetails ->
        when (tabIndex) {
            // Search
            0 -> {
                DiscoverSearchPage(
                    preferences = preferences,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            // History (upstream's Requests tab: current and prior requests)
            1 -> {
                SeerrRequestsPage(
                    focusRequesterOnEmpty = tabDetails.tabFocusRequester,
                    positionCallback = { columns, index -> showHeader = index < columns },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(tabDetails.contentFocusRequester),
                )
            }

            else -> {
                ErrorMessage("Invalid tab index $tabIndex", null)
            }
        }
    }
}
