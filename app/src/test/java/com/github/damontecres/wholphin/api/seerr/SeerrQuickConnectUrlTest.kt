package com.github.damontecres.wholphin.api.seerr

import org.junit.Assert
import org.junit.Test

/**
 * Regression test for a bug that actually shipped.
 *
 * The Quick Connect calls prefixed `/api/v1` onto a base URL that already ended in
 * `/api/v1`, producing `/api/v1/api/v1/...`. That 404s; the silent-connect path caught
 * the failure and logged it, so Seerr simply never signed in and the app looked fine.
 *
 * Verified against the live server at the time: doubled path -> 404, correct path -> 200.
 */
class SeerrQuickConnectUrlTest {
    @Test
    fun `does not duplicate the api version segment`() {
        val url = quickConnectUrl("https://requests.theweasel.tv/api/v1", "initiate")
        Assert.assertEquals(
            "https://requests.theweasel.tv/api/v1/auth/jellyfin/quickconnect/initiate",
            url,
        )
        Assert.assertFalse(
            "base URL already ends in /api/v1; it must not be added again",
            url.contains("/api/v1/api/v1"),
        )
    }

    @Test
    fun `tolerates a trailing slash on the base url`() {
        Assert.assertEquals(
            "https://x.tv/api/v1/auth/jellyfin/quickconnect/authenticate",
            quickConnectUrl("https://x.tv/api/v1/", "authenticate"),
        )
    }
}
