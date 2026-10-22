package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.kids.KidContentRules.Decision
import com.github.damontecres.wholphin.kids.KidContentRules.HideReason
import com.github.damontecres.wholphin.kids.KidContentRules.ItemFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Contract section 10 (v1.3): the owner's content rules for every kids profile */
class KidContentRulesTest {
    private val pg13Max = 2
    private val pgMax = 1

    private fun movie(
        rating: String?,
        premiere: String? = null,
        year: Int? = null,
        genres: List<String> = listOf("Comedy"),
        locked: List<String> = emptyList(),
    ) = ItemFacts(
        id = "m",
        type = "Movie",
        officialRating = rating,
        genres = genres,
        premiereDate = premiere,
        productionYear = year,
        lockedFields = locked,
    )

    private fun series(
        rating: String?,
        genres: List<String> = listOf("Drama"),
        locked: List<String> = emptyList(),
    ) = ItemFacts(id = "s", type = "Series", officialRating = rating, genres = genres, lockedFields = locked)

    private fun episode(
        rating: String? = null,
        type: String = "Episode",
    ) = ItemFacts(id = "e", type = type, officialRating = rating, genres = emptyList(), lockedFields = emptyList(), seriesId = "s")

    private fun hidden(reason: HideReason) = Decision.Hide(reason)

    // The owner's must-pass cases (profile maximum PG-13 = level 2)

    @Test
    fun `Poltergeist is hidden as horror`() {
        val poltergeist = movie("PG", "1982-06-04T00:00:00.0000000Z", 1982, listOf("Horror", "Thriller"))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(poltergeist, pg13Max))
    }

    @Test
    fun `Gremlins is hidden as horror`() {
        val gremlins = movie("PG", "1984-06-08T00:00:00.0000000Z", 1984, listOf("Fantasy", "Horror", "Comedy"))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(gremlins, pg13Max))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(gremlins, 4))
    }

    @Test
    fun `The Pink Panther counts as PG-13`() {
        val pinkPanther = movie("PG", "1963-12-19T00:00:00.0000000Z", 1963)
        assertEquals(Decision.Show, KidContentRules.decide(pinkPanther, pg13Max))
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(pinkPanther, pgMax))
    }

    @Test
    fun `Temple of Doom counts as PG-13`() {
        val templeOfDoom = movie("PG", "1984-05-23T00:00:00.0000000Z", 1984, listOf("Adventure", "Action"))
        assertEquals(Decision.Show, KidContentRules.decide(templeOfDoom, pg13Max))
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(templeOfDoom, pgMax))
    }

    @Test
    fun `Moonstruck stays PG`() {
        val moonstruck = movie("PG", "1987-12-16T00:00:00.0000000Z", 1987, listOf("Comedy", "Drama", "Romance"))
        assertEquals(Decision.Show, KidContentRules.decide(moonstruck, pgMax))
    }

    @Test
    fun `12 Angry Men is hidden as not rated`() {
        val angryMen = movie("NR", "1957-04-10T00:00:00.0000000Z", 1957, listOf("Drama"))
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(angryMen, pg13Max))
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(angryMen, 4))
    }

    @Test
    fun `Ginny and Georgia, a locked TV-MA series, is hidden with all its episodes and seasons`() {
        val ginny = series("TV-MA", listOf("Drama", "Comedy"), locked = listOf("OfficialRating"))
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(ginny, pg13Max))
        assertEquals(hidden(HideReason.SERIES_HIDDEN), KidContentRules.decide(episode("TV-14"), pg13Max, ginny))
        assertEquals(hidden(HideReason.SERIES_HIDDEN), KidContentRules.decide(episode(null), pg13Max, ginny))
        assertEquals(hidden(HideReason.SERIES_HIDDEN), KidContentRules.decide(episode("TV-G", "Season"), pg13Max, ginny))
    }

    @Test
    fun `a movie locked at R is hidden at PG-13`() {
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(movie("R", locked = listOf("OfficialRating")), pg13Max))
    }

    @Test
    fun `a UFC event without a rating is hidden`() {
        val ufc = ItemFacts(id = "u", type = "Movie", name = "UFC 300", genres = listOf("Sport"), lockedFields = emptyList())
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(ufc, pg13Max))
        val ufcVideo = ufc.copy(type = "Video", officialRating = "")
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(ufcVideo, pg13Max))
    }

    // Rule 1

    @Test
    fun `horror matches any genre containing horror, case-insensitively, and beats a locked rating`() {
        val locked = listOf("OfficialRating")
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(movie("G", genres = listOf("Comedy Horror"), locked = locked), 4))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(movie("G", genres = listOf("HORROR")), 4))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(movie("G", genres = listOf("horror-comedy")), 4))
        assertEquals(Decision.Show, KidContentRules.decide(movie("G", genres = listOf("Horse riding")), 0))
    }

    @Test
    fun `episodes and seasons use their series' genres`() {
        val horrorSeries = series("TV-PG", listOf("Horror"))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(episode("TV-G"), 4, horrorSeries))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(episode(null, "Season"), 4, horrorSeries))
        // An episode's own horror genre also hides it
        val ownHorror = episode("TV-G").copy(genres = listOf("Horror"))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(ownHorror, 4, series("TV-G")))
    }

    // Rule 2

    @Test
    fun `ratings are trimmed, uppercased and lose a leading US-`() {
        assertEquals("PG-13", KidContentRules.normalizeRating("  pg-13 "))
        assertEquals("TV-Y7-FV", KidContentRules.normalizeRating("us-tv-y7-fv"))
        assertEquals("PG", KidContentRules.normalizeRating("US-PG"))
        assertNull(KidContentRules.normalizeRating("   "))
        assertNull(KidContentRules.normalizeRating(null))
        assertNull(KidContentRules.normalizeRating("US-"))
        assertEquals(Decision.Show, KidContentRules.decide(movie(" us-pg-13 "), pg13Max))
    }

    @Test
    fun `only US movie and TV ratings are usable`() {
        val unusable =
            listOf(
                "",
                "NR",
                "Not Rated",
                "UNRATED",
                "Approved",
                "PASSED",
                "X",
                "GB-15",
                "16",
                "12",
                "NC16",
                "L",
                "TP",
                "0",
                "10",
                "U",
                "B",
                "6",
                "E",
                "MA15+",
                "Från 15 år",
                "A",
                "14",
                "ALL",
                "DE-12",
                "TV-Y7FV",
                "PG13",
            )
        unusable.forEach {
            assertEquals(it, hidden(HideReason.NO_US_RATING), KidContentRules.decide(movie(it, year = 2000), 4))
        }
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(movie(null, year = 2000), 4))
        val usable = listOf("G", "PG", "PG-13", "R", "NC-17", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14", "TV-MA")
        usable.forEach {
            assertEquals(it, Decision.Show, KidContentRules.decide(movie(it, year = 2000), 4))
            assertEquals(it, Decision.Show, KidContentRules.decide(series(it), 4))
        }
    }

    @Test
    fun `episodes and seasons without their own rating use the series', and are hidden if it has none`() {
        assertEquals(Decision.Show, KidContentRules.decide(episode(null), pgMax, series("TV-PG")))
        assertEquals(Decision.Show, KidContentRules.decide(episode("", "Season"), pgMax, series("TV-Y7")))
        assertEquals(hidden(HideReason.SERIES_HIDDEN), KidContentRules.decide(episode(null), pgMax, series("TV-14")))
        // A series with no rating is hidden, and so are its episodes
        assertEquals(hidden(HideReason.SERIES_HIDDEN), KidContentRules.decide(episode("TV-G"), 4, series(null)))
        // An episode's own higher rating counts
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(episode("TV-14"), pgMax, series("TV-PG")))
        // An episode's own unusable rating hides it
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(episode("16"), 4, series("TV-PG")))
    }

    @Test
    fun `episodes and seasons without their series are hidden`() {
        assertEquals(hidden(HideReason.MISSING_FACTS), KidContentRules.decide(episode("TV-G"), 4, null))
        assertEquals(hidden(HideReason.MISSING_FACTS), KidContentRules.decide(episode("TV-G", "Season"), 4, null))
    }

    // Rules 3 and 4

    @Test
    fun `a hand-set PG skips the era rule`() {
        val locked = movie("PG", "1963-12-19T00:00:00.0000000Z", 1963, locked = listOf("Name", "OfficialRating"))
        assertEquals(Decision.Show, KidContentRules.decide(locked, pgMax))
        // A locked unusable rating is still hidden (rule 2 comes first)
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(movie("NR", locked = listOf("OfficialRating")), 4))
    }

    @Test
    fun `the era rule uses the UTC premiere date`() {
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(movie("PG", "1984-06-30T23:59:59.0000000Z"), pgMax))
        assertEquals(Decision.Show, KidContentRules.decide(movie("PG", "1984-07-01T00:00:00.0000000Z", 1984), pgMax))
        // 1984-06-30 22:00 in New York is already 1984-07-01 in UTC
        assertEquals(Decision.Show, KidContentRules.decide(movie("PG", "1984-06-30T22:00:00-04:00"), pgMax))
        assertEquals(LocalDate.of(1984, 7, 1), KidContentRules.premiereDateOf("1984-06-30T22:00:00-04:00"))
        assertEquals(LocalDate.of(1963, 12, 19), KidContentRules.premiereDateOf("1963-12-19T00:00:00"))
        assertNull(KidContentRules.premiereDateOf("not a date"))
    }

    @Test
    fun `without a premiere date the era rule uses the production year`() {
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(movie("PG", year = 1984), pgMax))
        assertEquals(Decision.Show, KidContentRules.decide(movie("PG", year = 1985), pgMax))
        // Neither: counts as PG-13 (fail closed)
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(movie("PG"), pgMax))
        assertEquals(Decision.Show, KidContentRules.decide(movie("PG"), pg13Max))
        // An unreadable premiere date counts as missing
        assertEquals(Decision.Show, KidContentRules.decide(movie("PG", "garbage", 1990), pgMax))
    }

    @Test
    fun `the era rule is only for PG movies`() {
        assertEquals(Decision.Show, KidContentRules.decide(movie("G", year = 1960), 0))
        val oldPgSeries = series("TV-PG").copy(premiereDate = "1970-01-01T00:00:00.0000000Z", productionYear = 1970)
        assertEquals(Decision.Show, KidContentRules.decide(oldPgSeries, pgMax))
        val oldPgVideo = movie("PG", year = 1970).copy(type = "Video")
        assertEquals(Decision.Show, KidContentRules.decide(oldPgVideo, pgMax))
    }

    @Test
    fun `a PG movie the era rule would raise needs its locked fields`() {
        val withoutLocked = movie("PG", year = 1970).copy(lockedFields = null)
        assertFalse(KidContentRules.hasFacts(withoutLocked))
        assertEquals(hidden(HideReason.MISSING_FACTS), KidContentRules.decide(withoutLocked, 4))
        // Not needed when the era rule can't apply
        assertTrue(KidContentRules.hasFacts(movie("PG", year = 1990).copy(lockedFields = null)))
        assertTrue(KidContentRules.hasFacts(movie("R").copy(lockedFields = null)))
        // Genres are always needed for a title, but episodes take their series'
        assertFalse(KidContentRules.hasFacts(movie("G").copy(genres = null)))
        assertTrue(KidContentRules.hasFacts(episode("TV-G").copy(genres = null)))
        assertEquals(hidden(HideReason.MISSING_FACTS), KidContentRules.decide(movie("G").copy(genres = null), 4))
    }

    // Rule 5 and the profile maximum

    @Test
    fun `levels compare on one scale`() {
        val expected =
            mapOf(
                "G" to 0,
                "TV-Y" to 0,
                "TV-Y7" to 0,
                "TV-Y7-FV" to 0,
                "TV-G" to 0,
                "PG" to 1,
                "TV-PG" to 1,
                "PG-13" to 2,
                "TV-14" to 2,
                "R" to 3,
                "TV-MA" to 3,
                "NC-17" to 4,
            )
        expected.forEach { (rating, level) ->
            assertEquals(rating, level, KidContentRules.levelOf(rating))
            for (max in 0..4) {
                val decision = KidContentRules.decide(movie(rating, year = 2000), max)
                assertEquals("$rating at $max", level <= max, decision == Decision.Show)
            }
        }
    }

    @Test
    fun `profile maximum from the session ceiling`() {
        assertEquals(1, KidContentRules.maxLevelFor(ProfileCeiling.Rating(10)))
        assertEquals(2, KidContentRules.maxLevelFor(ProfileCeiling.Rating(14)))
        assertEquals(3, KidContentRules.maxLevelFor(ProfileCeiling.Rating(17)))
        assertEquals(4, KidContentRules.maxLevelFor(ProfileCeiling.Rating(18)))
        assertEquals(4, KidContentRules.maxLevelFor(ProfileCeiling.Rating(21)))
        assertEquals(2, KidContentRules.maxLevelFor(ProfileCeiling.Rating(13)))
        assertEquals(1, KidContentRules.maxLevelFor(ProfileCeiling.Rating(12)))
        assertEquals(0, KidContentRules.maxLevelFor(ProfileCeiling.Rating(9)))
        assertEquals(0, KidContentRules.maxLevelFor(ProfileCeiling.Rating(0)))
        assertNull(KidContentRules.maxLevelFor(ProfileCeiling.Unknown))
    }

    @Test
    fun `Teens no longer see NC-17`() {
        val teens = KidContentRules.maxLevelFor(ProfileCeiling.Rating(17))
        assertEquals(hidden(HideReason.ABOVE_MAX), KidContentRules.decide(movie("NC-17"), teens))
        assertEquals(Decision.Show, KidContentRules.decide(movie("R"), teens))
    }

    @Test
    fun `an unknown ceiling shows no title but still shows containers`() {
        assertEquals(hidden(HideReason.UNKNOWN_CEILING), KidContentRules.decide(movie("G"), null))
        assertEquals(hidden(HideReason.UNKNOWN_CEILING), KidContentRules.decide(episode("TV-G"), null, series("TV-G")))
        assertEquals(Decision.Show, KidContentRules.decide(ItemFacts("l", "CollectionFolder"), null))
    }

    // Containers

    @Test
    fun `containers are exempt, but the horror genre is hidden`() {
        listOf("CollectionFolder", "UserView", "Folder", "AggregateFolder", "UserRootFolder", "Person", "Studio", "Year", "Playlist")
            .forEach { assertEquals(it, Decision.Show, KidContentRules.decide(ItemFacts("c", it, name = "Horror stuff"), 0)) }
        assertEquals(Decision.Show, KidContentRules.decide(ItemFacts("g", "Genre", name = "Comedy"), 0))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(ItemFacts("g", "Genre", name = "Horror"), 4))
        assertEquals(hidden(HideReason.HORROR), KidContentRules.decide(ItemFacts("g", "MusicGenre", name = "horrorcore"), 4))
    }

    @Test
    fun `unknown types and trailers are treated as titles`() {
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(ItemFacts("x", "SomethingNew", genres = emptyList()), 4))
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(ItemFacts("x", null, genres = emptyList()), 4))
        assertEquals(hidden(HideReason.NO_US_RATING), KidContentRules.decide(ItemFacts("t", "Trailer", genres = emptyList()), 4))
        val ratedTrailer = ItemFacts("t", "Trailer", officialRating = "PG", genres = emptyList(), productionYear = 2000)
        assertEquals(Decision.Show, KidContentRules.decide(ratedTrailer, pgMax))
    }

    @Test
    fun `box sets need a visible child`() {
        assertEquals(Decision.Show, KidContentRules.decideBoxSet(true))
        assertEquals(hidden(HideReason.EMPTY_BOX_SET), KidContentRules.decideBoxSet(false))
    }
}
