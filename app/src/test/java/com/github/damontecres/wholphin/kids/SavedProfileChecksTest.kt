package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.PolicyState
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.ui.setup.chooserImageUrl
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.operations.ImageApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Contract 12.5: deleted kids leave the TV; pictures stay current */
class SavedProfileChecksTest {
    private val current = MutableStateFlow<CurrentUser?>(null)
    private val serverRepository = mockk<ServerRepository>(relaxed = true) { every { current } returns this@SavedProfileChecksTest.current }
    private val cache = FakeProfileImageCache()
    private val statuses = mutableMapOf<UUID, ProfileStatus>()
    private val asked = mutableListOf<UUID>()
    private val checks =
        SavedProfileChecks(
            remote =
                object : ProfileStatusRemote {
                    override suspend fun status(
                        serverUrl: String,
                        userId: UUID,
                        accessToken: String,
                    ): ProfileStatus {
                        asked += userId
                        return statuses[userId] ?: ProfileStatus.Unknown
                    }
                },
            serverRepository = serverRepository,
            pictures = ProfilePictureService(FakeAvatarCatalog(), FakeUserImageUploader(), cache),
        )

    private fun kid(name: String) = testUser(name).copy(policyState = PolicyState.KID, maxParentalRating = 10)

    private fun adult(name: String) = testUser(name).copy(policyState = PolicyState.ADULT)

    private fun removed(user: JellyfinUser): Boolean = runCatching { coVerify { serverRepository.removeUser(user, any()) } }.isSuccess

    @Test
    fun `a kid signed out on the server leaves the tv with its picture state`() =
        runTest {
            val emma = kid("Emma")
            cache.chosen[emma.id] = "bee-blue"
            statuses[emma.id] = ProfileStatus.SignedOut
            val result = checks.check(testServer.url, listOf(emma))
            assertEquals(setOf(emma.id), result.removed)
            assertTrue(removed(emma))
            assertNull(cache.chosen[emma.id])
            assertEquals(1, cache.versions.value[emma.id])
        }

    @Test
    fun `only a 401 removes a kid, and adults are never removed`() =
        runTest {
            val unknown = kid("Unknown")
            val active = kid("Active")
            val dad = adult("Dad")
            statuses[active.id] = ProfileStatus.Active("tag")
            statuses[dad.id] = ProfileStatus.SignedOut
            val result = checks.check(testServer.url, listOf(unknown, active, dad))
            assertTrue(result.removed.isEmpty())
            assertFalse(removed(unknown))
            assertFalse(removed(active))
            assertFalse(removed(dad))
        }

    @Test
    fun `failures map to no change except a 401`() {
        assertEquals(ProfileStatus.SignedOut, profileStatusOf(InvalidStatusException(401)))
        assertEquals(ProfileStatus.Unknown, profileStatusOf(InvalidStatusException(403)))
        assertEquals(ProfileStatus.Unknown, profileStatusOf(InvalidStatusException(500)))
        assertEquals(ProfileStatus.Unknown, profileStatusOf(IOException("offline")))
    }

    @Test
    fun `profiles without a token aren't asked`() =
        runTest {
            val gone = kid("Gone").copy(accessToken = null)
            statuses[gone.id] = ProfileStatus.SignedOut
            val result = checks.check(testServer.url, listOf(gone))
            assertTrue(asked.isEmpty())
            assertTrue(result.removed.isEmpty())
            assertTrue(result.imageTags.isEmpty())
        }

    @Test
    fun `the session in use is never ended this way`() =
        runTest {
            val emma = kid("Emma")
            statuses[emma.id] = ProfileStatus.SignedOut
            checks.check(testServer.url, listOf(emma))
            val authorize = slot<() -> Unit>()
            coVerify { serverRepository.removeUser(emma, capture(authorize)) }
            authorize.captured()
            current.value = CurrentUser(testServer, emma)
            assertThrows(IllegalStateException::class.java) { authorize.captured() }
        }

    @Test
    fun `every answer's picture tag is returned, adults' too`() =
        runTest {
            val emma = kid("Emma")
            val dad = adult("Dad")
            val mom = adult("Mom")
            statuses[emma.id] = ProfileStatus.Active("kid-tag")
            statuses[dad.id] = ProfileStatus.Active(null)
            val result = checks.check(testServer.url, listOf(emma, dad, mom))
            assertEquals(mapOf(emma.id to "kid-tag", dad.id to null), result.imageTags)
        }

    @Test
    fun `the chooser's image url carries the tag for profiles with a token`() {
        val api =
            mockk<ApiClient> {
                every { createUrl(any(), any(), any(), any()) } answers {
                    val query = thirdArg<Map<String, Any?>>().filterValues { it != null }
                    firstArg<String>() + "?" + query.entries.joinToString("&") { "${it.key}=${it.value}" }
                }
            }
        val images = ImageApi(api)
        val dad = adult("Dad")
        val noToken = adult("Gran").copy(accessToken = null)
        val tags = mapOf(dad.id to "abc", noToken.id to "def")
        assertTrue(chooserImageUrl(images, dad, tags).contains("tag=abc"))
        assertFalse(chooserImageUrl(images, noToken, tags).contains("tag="))
        assertFalse(chooserImageUrl(images, adult("Mom"), tags).contains("tag="))
    }
}
