package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.core.content.edit
import com.github.damontecres.wholphin.util.Version
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** One release's What's New pop-up: [version] as major.minor.patch, and its bullets. */
data class WhatsNewEntry(
    val version: String,
    val notes: List<String>,
)

/**
 * What's New bullets for each release, rewritten from its GitHub release notes for everyday
 * viewers: 3 to 5 short lines in US English. Add the new version's entry before tagging a
 * release. A version without an entry shows no pop-up.
 */
private val notes1244 =
    listOf(
        "New Home rows: Trending Movies, Trending TV Shows, 90 Days - Recently Released and Movie Roulette.",
        "Recently Added is now two rows, one for movies and one for TV shows.",
        "Requests has one Activity page: see download progress live, what's waiting and what's ready to watch.",
        "A pop-up lets you know when something you requested is ready to watch.",
        "Shows that are still airing tell you when the next episode comes out.",
    )

internal val WhatsNewNotes: Map<String, List<String>> =
    mapOf(
        // Owner 2026-11-04: v1.2.45 keeps v1.2.44's notes, since most viewers had not updated to v1.2.44 yet.
        "1.2.45" to notes1244,
        "1.2.44" to notes1244,
        "1.2.43" to
            listOf(
                "New: a one-minute tour that shows you around Home, the side menu, your Watchlist, Requests and Settings.",
                "Press OK to move on, BACK to go back, or Skip tour to leave.",
                "Every account on your TV sees the tour once, including accounts you add later.",
                "Watch it again anytime from Settings → Help → WeaselPlex tour.",
            ),
    )

/**
 * Shows What's New once after the app is updated. An install counts once: it is remembered by
 * the package's update time, so the next update (or Alpha install) shows it again. A fresh
 * install is not an update and shows nothing; new viewers get the tour instead.
 */
@Singleton
class WhatsNewService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val prefs by lazy { context.getSharedPreferences("whats_new", Context.MODE_PRIVATE) }
        private val _entry = MutableStateFlow<WhatsNewEntry?>(null)

        /** The pop-up waiting to be shown, if any */
        val entry: StateFlow<WhatsNewEntry?> = _entry.asStateFlow()

        private var installedAt = NEVER

        /** Works out at start-up whether this install still owes a What's New pop-up. */
        fun check() {
            val info =
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (ex: Exception) {
                    Timber.w(ex, "What's New: package info unavailable")
                    return
                }
            installedAt = info.lastUpdateTime
            val seen = prefs.getLong(SEEN_KEY, NEVER)
            if (seen == installedAt) return
            if (seen == NEVER && info.firstInstallTime == info.lastUpdateTime) {
                markSeen()
                return
            }
            val version = Version.fromStringLenient(info.versionName)?.let { "${it.major}.${it.minor}.${it.patch}" }
            val notes = version?.let { WhatsNewNotes[it] }
            if (version == null || notes.isNullOrEmpty()) {
                Timber.i("What's New: nothing written for %s", info.versionName)
                markSeen()
                return
            }
            Timber.i("What's New: showing %s after an update", version)
            _entry.value = WhatsNewEntry(version, notes)
        }

        /** OK (or BACK): not again until the next update. */
        fun dismiss() {
            markSeen()
            _entry.value = null
        }

        private fun markSeen() = prefs.edit { putLong(SEEN_KEY, installedAt) }

        private companion object {
            const val SEEN_KEY = "seen_install_time"
            const val NEVER = -1L
        }
    }
