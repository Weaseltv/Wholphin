package com.github.damontecres.wholphin.ui.setup

import org.jellyfin.sdk.model.api.PublicSystemInfo

/** Whether a saved server answered (moved out of the Compose ServerList.kt for the no-Compose UI) */
sealed interface ServerConnectionStatus {
    data class Success(
        val systemInfo: PublicSystemInfo,
    ) : ServerConnectionStatus

    object Pending : ServerConnectionStatus

    data class Error(
        val message: String?,
    ) : ServerConnectionStatus
}
