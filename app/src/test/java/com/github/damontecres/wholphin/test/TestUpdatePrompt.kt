package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.services.shouldPromptForUpdate
import com.github.damontecres.wholphin.util.Version
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class TestUpdatePrompt {
    private val hour = 1.hours.inWholeMilliseconds
    private val now = 1_000 * hour
    private val v127 = Version.fromString("v1.2.7")
    private val v126 = Version.fromString("v1.2.6")

    @Test
    fun `Never shown before prompts`() {
        Assert.assertTrue(shouldPromptForUpdate(v127, null, 0, now, 12.hours))
    }

    @Test
    fun `Same release shown within the threshold waits`() {
        Assert.assertFalse(shouldPromptForUpdate(v127, v127, now - 11 * hour, now, 12.hours))
    }

    @Test
    fun `Same release shown at or past the threshold prompts again`() {
        Assert.assertTrue(shouldPromptForUpdate(v127, v127, now - 12 * hour, now, 12.hours))
        Assert.assertTrue(shouldPromptForUpdate(v127, v127, now - 30 * hour, now, 12.hours))
    }

    @Test
    fun `A newer release prompts straight away`() {
        Assert.assertTrue(shouldPromptForUpdate(v127, v126, now - 1 * hour, now, 12.hours))
    }

    @Test
    fun `A clock that moved backwards prompts`() {
        Assert.assertTrue(shouldPromptForUpdate(v127, v127, now + hour, now, 12.hours))
    }
}
