package com.github.damontecres.wholphin.util

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

val supportItemKinds =
    setOf(
        BaseItemKind.MOVIE,
        BaseItemKind.EPISODE,
        BaseItemKind.SERIES,
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO,
        BaseItemKind.SEASON,
        BaseItemKind.COLLECTION_FOLDER,
        BaseItemKind.FOLDER,
        BaseItemKind.USER_VIEW,
        BaseItemKind.TRAILER,
    )

val supportedCollectionTypes =
    setOf(
        CollectionType.MOVIES,
        CollectionType.TVSHOWS,
        CollectionType.HOMEVIDEOS,
        CollectionType.PLAYLISTS,
        CollectionType.BOXSETS,
        CollectionType.MUSICVIDEOS,
        CollectionType.FOLDERS,
        CollectionType.MUSIC,
        CollectionType.PHOTOS,
        null, // Mixed
    )

val supportedPlayableTypes =
    setOf(
        BaseItemKind.MOVIE,
        BaseItemKind.EPISODE,
        BaseItemKind.VIDEO,
        BaseItemKind.MUSIC_VIDEO,
        BaseItemKind.SEASON,
        BaseItemKind.SERIES,
        BaseItemKind.BOX_SET,
        BaseItemKind.PLAYLIST,
    )

val supportedShufflableTypes =
    setOf(
        BaseItemKind.SEASON,
        BaseItemKind.SERIES,
        BaseItemKind.BOX_SET,
        BaseItemKind.PLAYLIST,
    )
