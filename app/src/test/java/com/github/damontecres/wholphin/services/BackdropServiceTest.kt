package com.github.damontecres.wholphin.services

import android.app.Application
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.BackdropStyle
import com.github.damontecres.wholphin.preferences.InterfacePreferences
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.github.damontecres.wholphin.util.configure
import com.github.damontecres.wholphin.util.reset
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackdropServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: BackdropService

    @Before
    fun setUp() {
        WholphinDispatchers.configure(dispatcher)
        val preferences = mockk<DataStore<AppPreferences>>()
        every { preferences.data } returns
            flowOf(
                AppPreferences
                    .newBuilder()
                    .setInterfacePreferences(
                        InterfacePreferences.newBuilder().setBackdropStyle(BackdropStyle.BACKDROP_IMAGE_ONLY),
                    ).build(),
            )
        service = BackdropService(RuntimeEnvironment.getApplication(), mockk(), preferences)
    }

    @After
    fun tearDown() {
        WholphinDispatchers.reset()
    }

    @Test
    fun `moving to an item without a backdrop invalidates the previous pending load`() =
        runTest(dispatcher) {
            launch { service.submit("previous", "previous.jpg") }
            runCurrent()
            service.submit("collection", null)
            advanceUntilIdle()
            assertEquals(BackdropResult.NONE.copy(itemId = "collection"), service.backdropFlow.value)
        }

    @Test
    fun `clearing the page invalidates an in flight backdrop`() =
        runTest(dispatcher) {
            launch { service.submit("previous", "previous.jpg") }
            runCurrent()
            service.clearBackdrop()
            advanceUntilIdle()
            assertEquals(BackdropResult.NONE, service.backdropFlow.value)
        }

    @Test
    fun `older request for the same item cannot overwrite its latest backdrop`() =
        runTest(dispatcher) {
            launch { service.submit("same", "old.jpg") }
            runCurrent()
            advanceTimeBy(100)
            launch { service.submit("same", "new.jpg") }
            runCurrent()
            advanceTimeBy(400)
            runCurrent()
            assertEquals(null, service.backdropFlow.value.imageUrl)
            advanceUntilIdle()
            assertEquals("new.jpg", service.backdropFlow.value.imageUrl)
        }
}
