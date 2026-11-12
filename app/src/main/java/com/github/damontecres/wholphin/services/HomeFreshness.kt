package com.github.damontecres.wholphin.services

import java.util.concurrent.atomic.AtomicLong

/**
 * Speed plan step 16 (2026-11): a count of the things done since app start that Home might have to show.
 *
 * Home used to reload every row each time it was returned to, so going Back and straight into another title put that
 * title's requests behind the row reloads. An adult's Home now skips the reload when its last one finished moments ago
 * and this count hasn't moved since that load began ([com.github.damontecres.wholphin.ui.main.HomeViewModel]).
 *
 * The count moves on every request that changes something on the media server (anything but a read: marking
 * watched or favourite, playback reports, deleting), at both its start and its end; when playback opens; and when a
 * request is made, changed or withdrawn in Requests. Moving it more often than needed only costs a reload, as before.
 */
object HomeFreshness {
    private val changes = AtomicLong()

    /** Something happened that Home may need to show */
    fun changed() {
        changes.incrementAndGet()
    }

    /** The count now; a later [mark] that differs means something changed in between */
    fun mark(): Long = changes.get()
}
