package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.api.seerr.SeerrQuickConnectException
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException

/**
 * WeaselFin: when the app may run the Seerr sign-in again after it failed.
 *
 * The request server's fail2ban jail counts 401/403 answers on /api/v1/auth/ and
 * bans the whole household IP (media server included) for an hour after five in
 * ten minutes. So a refusal is retried only after [REFUSAL_COOLDOWN_MS], longer
 * than that window, which keeps one TV to at most one strike per window however
 * often it is brought to the foreground. Anything else (server down, timeout)
 * is retried after [FAILURE_COOLDOWN_MS], enough to stop a resume/pause loop
 * from hammering a server that is already struggling.
 *
 * Pure, so it is unit-tested without Android.
 */
object SeerrReconnectPolicy {
    const val REFUSAL_COOLDOWN_MS: Long = 15L * 60L * 1000L
    const val FAILURE_COOLDOWN_MS: Long = 60L * 1000L

    /** 401/403 from Seerr: this account is not allowed in (yet). */
    fun isRefusal(ex: Throwable): Boolean {
        val code =
            when (ex) {
                is SeerrQuickConnectException -> ex.statusCode
                is ClientException -> ex.statusCode
                else -> null
            }
        return code == 401 || code == 403
    }

    /**
     * Whether a new attempt is allowed [now], given when the last attempt for
     * this user happened and how it ended. No previous attempt: always allowed.
     */
    fun mayRetry(
        lastAttemptAt: Long?,
        lastWasRefusal: Boolean,
        now: Long,
    ): Boolean {
        if (lastAttemptAt == null) return true
        val gap = if (lastWasRefusal) REFUSAL_COOLDOWN_MS else FAILURE_COOLDOWN_MS
        return now - lastAttemptAt >= gap
    }
}
