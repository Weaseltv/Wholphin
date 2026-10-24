package com.github.damontecres.wholphin.services

import timber.log.Timber
import java.util.UUID

/**
 * Debug timings for Home's first paint (owner, 2026-10-23: kids Home speed), from the moment a profile is picked:
 * `Home timing: <event> after <ms> ms (<user>)`, each event once per pick.
 */
object HomeTiming {
    private data class Pick(
        val userId: UUID,
        val atNanos: Long,
        val logged: MutableSet<String> = mutableSetOf(),
    )

    @Volatile
    private var pick: Pick? = null

    /** Exposed for tests */
    internal var nanoTime: () -> Long = System::nanoTime

    /** A profile was picked on the chooser */
    fun picked(userId: UUID) {
        pick = Pick(userId, nanoTime())
        Timber.d("Home timing: picked %s", userId)
    }

    /** [event] happened for [userId]'s Home; the ms since the pick, or null without one for this profile */
    fun mark(
        userId: UUID,
        event: String,
    ): Long? {
        val current = pick?.takeIf { it.userId == userId } ?: return null
        val elapsed = (nanoTime() - current.atNanos) / 1_000_000
        val first = synchronized(current) { current.logged.add(event) }
        if (first) Timber.d("Home timing: %s after %d ms (%s)", event, elapsed, userId)
        return elapsed
    }
}
