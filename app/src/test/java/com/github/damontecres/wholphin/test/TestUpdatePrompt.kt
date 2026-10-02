package com.github.damontecres.wholphin.test

import com.github.damontecres.wholphin.services.shouldPromptForUpdate
import org.junit.Assert
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class TestUpdatePrompt {
    private val hour = 1.hours.inWholeMilliseconds
    private val now = 1_000 * hour

    @Test
    fun `Never shown before prompts`() {
        Assert.assertTrue(shouldPromptForUpdate(0, now, 12.hours))
    }

    @Test
    fun `Shown within the threshold waits`() {
        Assert.assertFalse(shouldPromptForUpdate(now - 11 * hour, now, 12.hours))
    }

    @Test
    fun `Shown at or past the threshold prompts again`() {
        Assert.assertTrue(shouldPromptForUpdate(now - 12 * hour, now, 12.hours))
        Assert.assertTrue(shouldPromptForUpdate(now - 30 * hour, now, 12.hours))
    }

    @Test
    fun `A clock that moved backwards prompts`() {
        Assert.assertTrue(shouldPromptForUpdate(now + hour, now, 12.hours))
    }
}
