package com.github.damontecres.wholphin.ui.playback

import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

// Compose-free playback constants (moved out of PlaybackConstants.kt for the no-Compose UI, 2026-11-22)

val playbackSpeedOptions = listOf(".25", ".5", ".75", "1.0", "1.25", "1.5", "1.75", "2.0")

/**
 * Whether the type can be played as-is
 *
 * For example, a video file is playable as-is, but a playlist requires fetching the items first
 */
val BaseItemKind.playable: Boolean
    get() =
        when (this) {
            BaseItemKind.EPISODE,
            BaseItemKind.MOVIE,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.TRAILER,
            BaseItemKind.VIDEO,
            -> true

            // TODO add support for these eventually
            BaseItemKind.AUDIO_BOOK,
            BaseItemKind.CHANNEL,
            -> false

            BaseItemKind.AGGREGATE_FOLDER,
            BaseItemKind.AUDIO,
            BaseItemKind.BASE_PLUGIN_FOLDER,
            BaseItemKind.BOOK,
            BaseItemKind.BOX_SET,
            BaseItemKind.CHANNEL_FOLDER_ITEM,
            BaseItemKind.COLLECTION_FOLDER,
            BaseItemKind.FOLDER,
            BaseItemKind.GENRE,
            BaseItemKind.LIVE_TV_CHANNEL,
            BaseItemKind.LIVE_TV_PROGRAM,
            BaseItemKind.MANUAL_PLAYLISTS_FOLDER,
            BaseItemKind.MUSIC_ALBUM,
            BaseItemKind.MUSIC_ARTIST,
            BaseItemKind.MUSIC_GENRE,
            BaseItemKind.PERSON,
            BaseItemKind.PHOTO,
            BaseItemKind.PHOTO_ALBUM,
            BaseItemKind.PLAYLIST,
            BaseItemKind.PLAYLISTS_FOLDER,
            BaseItemKind.PROGRAM,
            BaseItemKind.RECORDING,
            BaseItemKind.SEASON,
            BaseItemKind.SERIES,
            BaseItemKind.STUDIO,
            BaseItemKind.TV_CHANNEL,
            BaseItemKind.TV_PROGRAM,
            BaseItemKind.USER_ROOT_FOLDER,
            BaseItemKind.USER_VIEW,
            BaseItemKind.YEAR,
            -> false
        }

fun getTypeFor(collectionType: CollectionType): BaseItemKind? =
    when (collectionType) {
        CollectionType.UNKNOWN -> null
        CollectionType.MOVIES -> BaseItemKind.MOVIE
        CollectionType.TVSHOWS -> BaseItemKind.SERIES
        CollectionType.MUSIC -> null
        CollectionType.MUSICVIDEOS -> BaseItemKind.MUSIC_VIDEO
        CollectionType.TRAILERS -> BaseItemKind.TRAILER
        CollectionType.HOMEVIDEOS -> BaseItemKind.VIDEO
        CollectionType.BOXSETS -> BaseItemKind.BOX_SET
        CollectionType.BOOKS -> BaseItemKind.BOOK
        CollectionType.PHOTOS -> BaseItemKind.PHOTO_ALBUM
        CollectionType.LIVETV -> null
        CollectionType.PLAYLISTS -> BaseItemKind.PLAYLIST
        CollectionType.FOLDERS -> BaseItemKind.FOLDER
    }
