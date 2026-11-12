package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.HomeRowLoadingState
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each library's Recommended tab (Movies, TV Shows, ...) showed last time, kept in memory until the app closes
 * (speed plan, owner feedback 2026-11-11).
 *
 * Opening a library from the side menu builds a new page every time, and its rows take the server seconds (TV Shows'
 * Just Released was 12 s on the owner's Shield). With this the page shows the last rows at once and each row is
 * replaced in place when its fresh answer arrives.
 *
 * Nothing is written to disk, so the first visit after the app starts still waits for the server. The caller leaves
 * kid sessions out: a kid's rows are filtered by rules that can change between visits.
 */
@Singleton
class RecommendedRowsMemory
    @Inject
    constructor() {
        /**
         * @param rows the tab's rows, in order
         * @param mainLibraryRows whether the library's own rows were cut down to Movies' and TV Shows' short list
         */
        data class Page(
            val rows: List<HomeRowLoadingState>,
            val mainLibraryRows: Boolean,
        )

        private val pages = ConcurrentHashMap<String, Page>()

        /** [key] names the server, the profile and the library */
        fun get(key: String): Page? = pages[key]

        fun put(
            key: String,
            page: Page,
        ) {
            pages[key] = page
        }
    }
