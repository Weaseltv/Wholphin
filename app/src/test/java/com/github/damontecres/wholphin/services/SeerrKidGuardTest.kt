package com.github.damontecres.wholphin.services

import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert
import org.junit.Test
import java.util.UUID

/**
 * Kid profiles must never reach Seerr, so anything short of "this exact user's
 * policy, with no parental rating ceiling" is treated as a kid.
 */
class SeerrKidGuardTest {
    private val userId = UUID.randomUUID()

    private fun userDto(
        id: UUID = userId,
        policy: UserPolicy?,
    ) = UserDto(
        id = id,
        name = "test-user",
        serverName = "test server",
        hasPassword = true,
        hasConfiguredPassword = true,
        hasConfiguredEasyPassword = false,
        policy = policy,
    )

    private fun policy(maxParentalRating: Int?) =
        mockk<UserPolicy> {
            every { this@mockk.maxParentalRating } returns maxParentalRating
        }

    @Test
    fun `an adult has no parental rating ceiling`() {
        Assert.assertTrue(SeerrKidGuard.isConfirmedAdult(userId, userDto(policy = policy(null))))
    }

    @Test
    fun `any parental rating ceiling is a kid`() {
        listOf(0, 10, 14, 17).forEach {
            Assert.assertFalse(SeerrKidGuard.isConfirmedAdult(userId, userDto(policy = policy(it))))
        }
    }

    @Test
    fun `an unknown policy is a kid`() {
        Assert.assertFalse(SeerrKidGuard.isConfirmedAdult(userId, userDto(policy = null)))
        Assert.assertFalse(SeerrKidGuard.isConfirmedAdult(userId, null))
        Assert.assertFalse(SeerrKidGuard.isConfirmedAdult(null, userDto(policy = policy(null))))
    }

    @Test
    fun `another user's policy does not count`() {
        Assert.assertFalse(
            SeerrKidGuard.isConfirmedAdult(userId, userDto(id = UUID.randomUUID(), policy = policy(null))),
        )
    }
}
