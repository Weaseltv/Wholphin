package com.github.damontecres.wholphin.leanback.setup

import androidx.fragment.app.Fragment
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.setup.isServerPinned

/**
 * Setup-layer screens for the shell (no-Compose UI): [SetupDestination.ServerList] (the server list, or the pinned-server
 * sign-in on builds locked to one server, as `MainContent` chose) and [SetupDestination.UserList] (the profile chooser).
 * The shell itself shows Loading and AppContent. Returns null for anything else.
 */
object SetupScreens {
    fun create(destination: SetupDestination): Fragment? =
        when (destination) {
            SetupDestination.ServerList -> if (isServerPinned) PinnedServerFragment() else ServerListFragment()
            is SetupDestination.UserList -> UserListFragment.newInstance(destination.server)
            else -> null
        }
}
