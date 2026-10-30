package com.github.damontecres.wholphin.services.tvprovider

import android.content.ContentResolver
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kid content rules (review round 1): clearing the launcher and a worker's writes never interleave */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class LauncherRowsTest {
    @Test
    fun `a run started before a clear writes nothing after it`() =
        runTest {
            val resolver = mockk<ContentResolver>(relaxed = true)
            val context = mockk<Context> { every { contentResolver } returns resolver }
            val started = LauncherRows.generation
            val writes = mutableListOf<String>()

            suspend fun write(what: String): Boolean =
                LauncherRows.locked {
                    if (LauncherRows.generation != started) return false
                    writes.add(what)
                    true
                }
            assertEquals(true, write("before"))
            LauncherRows.clear(context)
            assertNotEquals(started, LauncherRows.generation)
            assertEquals(false, write("after"))
            assertEquals(listOf("before"), writes)
            verify(exactly = 2) { resolver.delete(any(), null, null) }
        }

    @Test
    fun `a clear waits for a write in progress`() =
        runTest {
            val resolver = mockk<ContentResolver>(relaxed = true)
            val context = mockk<Context> { every { contentResolver } returns resolver }
            val order = mutableListOf<String>()
            LauncherRows
                .locked {
                    val clear = async { LauncherRows.clear(context).also { order.add("clear") } }
                    order.add("write")
                    clear
                }.await()
            assertEquals(listOf("write", "clear"), order)
            LauncherRows.invalidate()
        }
}
