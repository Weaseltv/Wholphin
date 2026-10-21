package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.api.seerr.SeerrApiClient
import com.github.damontecres.wholphin.api.seerr.SettingsApi
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.data.model.SeerrServer
import com.github.damontecres.wholphin.data.model.SeerrUser
import com.github.damontecres.wholphin.kids.FakeKidSessionGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID
import javax.inject.Provider

/** Kid profiles (plan 4.1): a Seerr sign-in is published only for the profile session that started it */
class SeerrSessionTagTest {
    private val serverId = UUID.randomUUID()
    private val adult = JellyfinUser(rowId = 1, id = UUID.randomUUID(), name = "adult", serverId = serverId, accessToken = "a")
    private val kid = JellyfinUser(rowId = 2, id = UUID.randomUUID(), name = "kid", serverId = serverId, accessToken = "k")
    private var currentUser: JellyfinUser? = adult
    private var currentDto: UserDto? = dto(adult.id, null)

    private val serverRepository = mockk<ServerRepository>()
    private val gate = FakeKidSessionGate()
    private val settingsApi = mockk<SettingsApi>()
    private val seerrApi = mockk<SeerrApi>(relaxed = true)
    private lateinit var repository: SeerrServerRepository

    private val seerrServer = SeerrServer(id = 1, url = "https://requests.example")
    private val seerrUser = SeerrUser(adult.rowId, 1, SeerrAuthMethod.QUICK_CONNECT, null, null, null)

    private fun dto(
        id: UUID,
        maxParentalRating: Int?,
    ) = UserDto(
        id = id,
        name = "user",
        hasPassword = false,
        hasConfiguredPassword = false,
        hasConfiguredEasyPassword = false,
        policy = mockk<UserPolicy> { every { this@mockk.maxParentalRating } returns maxParentalRating },
    )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        every { serverRepository.currentUser } answers { currentUser }
        every { serverRepository.currentUserDto } answers { currentDto }
        val client = mockk<SeerrApiClient>()
        every { seerrApi.api } returns client
        every { client.settingsApi } returns settingsApi
        repository = SeerrServerRepository(seerrApi, mockk(relaxed = true), serverRepository, mockk(relaxed = true), Provider { gate })
    }

    private fun switchToKid() {
        currentUser = kid
        currentDto = dto(kid.id, 10)
        gate.isKidSession.value = true
    }

    @Test
    fun `an adult's sign-in finishing after a switch to a kid publishes nothing`() =
        runTest {
            coEvery { settingsApi.settingsPublicGet() } coAnswers {
                switchToKid()
                mockk(relaxed = true)
            }

            repository.set(seerrServer, seerrUser, mockk(relaxed = true), adult)

            assertEquals(SeerrConnectionStatus.NotConfigured, repository.connection.value)
        }

    @Test
    fun `a sign-in started for another profile publishes nothing`() =
        runTest {
            val otherAdult = adult.copy(rowId = 3, id = UUID.randomUUID())
            coEvery { settingsApi.settingsPublicGet() } returns mockk(relaxed = true)

            repository.set(seerrServer, seerrUser, mockk(relaxed = true), otherAdult)
            assertEquals(SeerrConnectionStatus.NotConfigured, repository.connection.value)

            // The profile that started it is still the session: published
            repository.set(seerrServer, seerrUser, mockk(relaxed = true), adult)
            assertTrue(repository.connection.value is SeerrConnectionStatus.Success)
        }
}
