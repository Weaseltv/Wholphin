package com.github.damontecres.wholphin.services

import android.content.ContentResolver
import android.content.Context
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.CurrentUser
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.tvprovider.TvProviderWorker
import com.github.damontecres.wholphin.test.currentUser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Kid profiles (plan 4.1): the Recently Added launcher channel is never written for a profile that is no longer current */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TvProviderWorkerSessionTest {
    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val current = MutableStateFlow<CurrentUser?>(null)
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)

    private fun params(): WorkerParameters =
        mockk<WorkerParameters>(relaxed = true).also {
            every { it.inputData } returns
                Data
                    .Builder()
                    .putString(TvProviderWorker.PARAM_SERVER_ID, serverId.toString())
                    .putString(TvProviderWorker.PARAM_USER_ID, userId.toString())
                    .build()
        }

    @Before
    fun setUp() {
        Assume.assumeTrue(BuildConfig.KID_PROFILES_ENABLED)
        every { serverRepository.current } returns current
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } returns null
    }

    @Test
    fun `an adult's recently added row is dropped after a switch to a kid`() =
        runTest {
            current.value = currentUser(serverId, userId)
            val worker =
                spyk(
                    TvProviderWorker(
                        context,
                        params(),
                        serverRepository,
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                        mockk(relaxed = true),
                    ),
                )
            coEvery { worker.fetchLatest(any()) } coAnswers {
                // The kid switched in while the request was out
                current.value = currentUser(serverId, UUID.randomUUID())
                emptyList()
            }

            assertEquals(ListenableWorker.Result.retry(), worker.doWork())

            verify(exactly = 0) { resolver.insert(TvContractCompat.Channels.CONTENT_URI, any()) }
            verify(exactly = 0) { resolver.delete(TvContractCompat.PreviewPrograms.CONTENT_URI, any(), any()) }
            verify(exactly = 0) { resolver.bulkInsert(TvContractCompat.PreviewPrograms.CONTENT_URI, any()) }
        }
}
