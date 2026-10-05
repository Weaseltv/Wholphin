package com.github.damontecres.wholphin.ui.theme

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LoadingArtworkRotationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("weaselplex_loading_artwork", Context.MODE_PRIVATE)
    private val artworks = (101..112).toList()

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `every artwork appears before the cycle repeats including across the wrap`() {
        val selected = List(artworks.size * 2 + 1) { LoadingArtworkRotation.next(context, artworks) }
        assertEquals(artworks, selected.take(artworks.size))
        assertEquals(artworks, selected.drop(artworks.size).take(artworks.size))
        selected.zipWithNext().forEach { (previous, next) -> assertNotEquals(previous, next) }
    }

    @Test
    fun `saved position is resumed and the next appearance is persisted`() {
        preferences.edit().putInt("next_index", 10).commit()
        assertEquals(111, LoadingArtworkRotation.next(context, artworks))
        assertEquals(11, preferences.getInt("next_index", -1))
        assertEquals(112, LoadingArtworkRotation.next(context, artworks))
        assertEquals(0, preferences.getInt("next_index", -1))
    }

    @Test
    fun `changing the artwork count accepts a previous saved position`() {
        preferences.edit().putInt("next_index", 11).commit()
        assertEquals(102, LoadingArtworkRotation.next(context, artworks.take(2)))
        assertEquals(101, LoadingArtworkRotation.next(context, artworks.take(2)))
    }

    @Test
    fun `a flavor without artwork does not consume an appearance`() {
        assertNull(LoadingArtworkRotation.next(context, emptyList()))
        assertEquals(101, LoadingArtworkRotation.next(context, artworks))
    }
}
