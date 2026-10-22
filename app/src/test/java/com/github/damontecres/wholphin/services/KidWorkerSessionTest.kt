package com.github.damontecres.wholphin.services

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.tvprovider.TvProviderWorker
import com.github.damontecres.wholphin.test.currentUser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Kid profiles (plan 4.1): LatestNextUpWorker and TvProviderWorker never touch the foreground session */
class KidWorkerSessionTest {
    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val latestNextUpService = mockk<LatestNextUpService>(relaxed = true)
    private val current = MutableStateFlow<CurrentUser?>(null)

    private fun params(): WorkerParameters =
        mockk<WorkerParameters>(relaxed = true).also {
            every { it.inputData } returns
                Data
                    .Builder()
                    .putString(LatestNextUpWorker.PARAM_SERVER_ID, serverId.toString())
                    .putString(LatestNextUpWorker.PARAM_USER_ID, userId.toString())
                    .build()
        }

    private fun latestNextUpWorker() =
        LatestNextUpWorker(mockk(relaxed = true), params(), serverRepository, mockk(relaxed = true), latestNextUpService)

    private fun tvProviderWorker() =
        TvProviderWorker(
            mockk(relaxed = true),
            params(),
            serverRepository,
            mockk(relaxed = true),
            mockk(relaxed = true),
            latestNextUpService,
            mockk(relaxed = true),
            mockk { coEvery { isProtected() } returns false },
        )

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        every { serverRepository.current } returns current
        every { serverRepository.sessionEpoch } returns MutableStateFlow(0L)
    }

    @Test
    fun `no current user - skip without work or restore`() =
        runTest {
            assertEquals(ListenableWorker.Result.success(), latestNextUpWorker().doWork())
            assertEquals(ListenableWorker.Result.success(), tvProviderWorker().doWork())
            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            coVerify(exactly = 0) { latestNextUpService.updateRemovedFromNextUp(any(), any()) }
            coVerify(exactly = 0) { latestNextUpService.getResume(any(), any(), any()) }
        }

    @Test
    fun `another user is current - retry`() =
        runTest {
            current.value = currentUser(serverId, UUID.randomUUID())
            assertEquals(ListenableWorker.Result.retry(), latestNextUpWorker().doWork())
            assertEquals(ListenableWorker.Result.retry(), tvProviderWorker().doWork())
            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            coVerify(exactly = 0) { latestNextUpService.updateRemovedFromNextUp(any(), any()) }
        }

    @Test
    fun `scheduled user is current - runs, and drops the result if the user changed`() =
        runTest {
            current.value = currentUser(serverId, userId)
            coEvery { latestNextUpService.updateRemovedFromNextUp(userId, any()) } coAnswers {
                // The kid switched in mid-run: the write must be dropped
                current.value = currentUser(serverId, UUID.randomUUID())
                assertEquals(false, secondArg<() -> Boolean>().invoke())
            }
            assertEquals(ListenableWorker.Result.retry(), latestNextUpWorker().doWork())
            coVerify(exactly = 1) { latestNextUpService.updateRemovedFromNextUp(userId, any()) }
        }
}
