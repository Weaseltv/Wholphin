package com.github.damontecres.wholphin.ui.search

import org.jellyfin.sdk.model.api.BaseItemKind

/** Primary search filters stay visible; optional Collections/Requests remain in View options. */
val PrimarySearchTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.PERSON)
