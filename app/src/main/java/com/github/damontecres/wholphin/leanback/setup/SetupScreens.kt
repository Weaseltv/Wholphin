package com.github.damontecres.wholphin.leanback.setup

import androidx.fragment.app.Fragment
import com.github.damontecres.wholphin.services.SetupDestination

/**
 * Setup-layer screens for the shell (no-Compose UI): [SetupDestination.ServerList] (server list or the pinned-server
 * sign-in) and [SetupDestination.UserList] (the profile chooser). The shell itself shows Loading and AppContent.
 * Return null for anything else.
 */
object SetupScreens {
    fun create(destination: SetupDestination): Fragment? = null
}
