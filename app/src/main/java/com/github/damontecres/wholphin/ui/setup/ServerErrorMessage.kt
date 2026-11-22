package com.github.damontecres.wholphin.ui.setup

import android.content.res.Resources
import com.github.damontecres.wholphin.R

/** The line under the server list for the focused server, or null when it is fine */
fun serverErrorMessage(
    server: ServerState?,
    resources: Resources,
): String? =
    when {
        server?.status is ServerConnectionStatus.Error -> {
            server.status.message
        }

        server?.status is ServerConnectionStatus.Success &&
            server.versionSupported == ServerVersionSupported.NOT_SUPPORTED -> {
            resources.getString(R.string.server_version_not_supported) + ": ${server.status.systemInfo.version}"
        }

        server?.versionSupported == ServerVersionSupported.NOT_SUPPORTED -> {
            resources.getString(R.string.server_version_not_supported)
        }

        else -> {
            null
        }
    }
