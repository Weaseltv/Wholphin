package com.github.damontecres.wholphin.util

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FuzzySearchTest {
    private fun movie(name: String) = FuzzySearch.Entry(UUID.randomUUID(), name, BaseItemKind.MOVIE, null)

    private fun series(name: String) = FuzzySearch.Entry(UUID.randomUUID(), name, BaseItemKind.SERIES, null)

    private val mashSeries = series("M*A*S*H")
    private val mashMovie = movie("M*A*S*H")

    // Sorted by name, as the server sends the index
    private val library =
        listOf(
            series("AfterMASH"),
            movie("Amélie"),
            series("Breaking Bad"),
            movie("Christmas"),
            series("Game of Thrones"),
            movie("Hit Man"),
            movie("Interstellar"),
            movie("It"),
            movie("Ix"),
            series("Law & Order"),
            mashMovie,
            mashSeries,
            movie("Mask"),
            movie("Mob Land"),
            series("MobLand"),
            movie("Spider-Man"),
            movie("Spider-Man 2"),
            movie("Spider-Man 3"),
            movie("Spider-Man: No Way Home"),
            movie("The Amazing Spider-Man"),
            series("The Flash"),
            movie("The Godfather"),
            movie("The Smashing Machine"),
            movie("Washington"),
        )

    /** Names in the order a section shows them, with no server results. */
    private fun names(query: String) = FuzzySearch.rank(library, query).all.map { it.name }

    @Test
    fun `Keys keep only letters and digits, without accents, case or punctuation`() {
        listOf("M*A*S*H", "M.A.S.H.", "MASH", "mash").forEach { assertEquals("mash", FuzzySearch.searchKey(it)) }
        listOf("MobLand", "mob land", "Mob Land").forEach { assertEquals("mobland", FuzzySearch.searchKey(it)) }
        assertEquals("spidermannowayhome", FuzzySearch.searchKey("Spider-Man: No Way Home"))
        assertEquals("amelie", FuzzySearch.searchKey("Amélie"))
        assertEquals("lawandorder", FuzzySearch.searchKey("Law & Order"))
        // Other scripts still have keys
        assertEquals("кино", FuzzySearch.searchKey("Кино!"))
        assertEquals("2001aspaceodyssey", FuzzySearch.searchKey("2001: A Space Odyssey"))
    }

    @Test
    fun `Entries know where their words start in the key`() {
        val godfather = movie("The Godfather")
        assertEquals("thegodfather", godfather.key)
        assertArrayEquals(intArrayOf(0, 3), godfather.wordStarts)
        assertArrayEquals(intArrayOf(0, 3, 6), movie("Law & Order").wordStarts)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), movie("M*A*S*H").wordStarts)
        assertArrayEquals(intArrayOf(), movie("!!!").wordStarts)
    }

    @Test
    fun `MASH ranks M*A*S*H, movie and series, above AfterMASH and The Smashing Machine`() {
        val ranked = FuzzySearch.rank(library, "MASH")
        assertEquals(setOf(mashMovie, mashSeries), ranked.matches.take(2).toSet())
        assertEquals(listOf("AfterMASH", "The Smashing Machine"), ranked.matches.drop(2).map { it.name })
        assertEquals(ranked, FuzzySearch.rank(library, "M.A.S.H."))
    }

    @Test
    fun `mash never matches by typo, and flash doesn't match Christmas`() {
        val mash = names("mash")
        listOf("Christmas", "The Flash", "Washington", "Mask").forEach { assertTrue(it, it !in mash) }
        assertTrue(FuzzySearch.rank(library, "mash").typos.isEmpty())
        assertEquals(listOf("The Flash"), names("flash"))
    }

    @Test
    fun `mob land and mobland both find MobLand and Mob Land`() {
        listOf("mob land", "mobland", "MobLand").forEach { query ->
            assertEquals(query, setOf("Mob Land", "MobLand"), names(query).take(2).toSet())
        }
    }

    @Test
    fun `spiderman and spider man rank Spider-Man, then the sequels, then The Amazing Spider-Man`() {
        listOf("spiderman", "spider man", "Spider-Man").forEach { query ->
            val ranked = names(query)
            assertEquals(query, "Spider-Man", ranked[0])
            assertEquals(query, setOf("Spider-Man 2", "Spider-Man 3", "Spider-Man: No Way Home"), ranked.subList(1, 4).toSet())
            assertEquals(query, "The Amazing Spider-Man", ranked[4])
        }
        // Shorter keys first among equals
        assertEquals("Spider-Man: No Way Home", names("spiderman")[3])
    }

    @Test
    fun `Typos, accents and ampersands still match`() {
        assertEquals(listOf("Breaking Bad"), FuzzySearch.rank(library, "breking bad").typos.map { it.name })
        assertEquals("Breaking Bad", names("braking bad").first())
        assertEquals("Amélie", names("amelie").first())
        assertEquals("Law & Order", names("law and order").first())
        assertEquals("Law & Order", names("law & order").first())
    }

    @Test
    fun `Typos match from the start of any word`() {
        assertEquals("The Godfather", names("godfater").first())
        assertEquals("Interstellar", names("interstelar").first())
        assertEquals("Game of Thrones", names("game of throns").first())
        assertEquals("Game of Thrones", names("thrnes").first())
    }

    @Test
    fun `Queries under 5 characters get no typo matches`() {
        // "Ix" is one edit from "it"
        assertEquals(listOf("It", "Hit Man"), names("it"))
        assertEquals(listOf("Hit Man"), names("hit"))
        assertTrue(FuzzySearch.rank(library, "maks").typos.isEmpty())
        assertTrue(names("i").isEmpty())
        assertTrue(names("!").isEmpty())
    }

    @Test
    fun `Typos allow one edit up to 8 characters and two from 9`() {
        assertEquals(1, FuzzySearch.prefixDistance("brekingbad", "breakingbad", 0, 2))
        val godfather = movie("The Godfather")
        assertEquals(1, FuzzySearch.typoDistance("godfater", godfather.key, godfather.wordStarts, 1))
        // Not from the middle of a word
        assertNull(FuzzySearch.prefixDistance("godfater", godfather.key, 0, 1))
        assertNull(FuzzySearch.typoDistance("father", godfather.key, godfather.wordStarts, 1))
        // Too short to hold the query, even with the edits
        assertNull(FuzzySearch.prefixDistance("abcdefgh", "abcde", 0, 2))
        // Two typos are too many for 7 characters, but fine from 9
        assertEquals(listOf("Mob Land", "MobLand"), names("moblxnd"))
        assertTrue(names("mxblxnd").isEmpty())
        assertEquals(listOf("Breaking Bad"), names("brxaking bxd"))
    }

    @Test
    fun `At most 50 matches and 10 typos of each type`() {
        val many = (1..60).flatMap { listOf(movie("Interstellar $it"), series("Interstellar $it")) }
        val matches = FuzzySearch.rank(many, "interstellar")
        assertEquals(50, matches.matches.count { it.type == BaseItemKind.MOVIE })
        assertEquals(50, matches.matches.count { it.type == BaseItemKind.SERIES })
        val typos = FuzzySearch.rank(many, "interstelar")
        assertTrue(typos.matches.isEmpty())
        assertEquals(10, typos.typos.count { it.type == BaseItemKind.MOVIE })
        assertEquals(10, typos.typos.count { it.type == BaseItemKind.SERIES })
    }

    @Test
    fun `Merging shows the index's matches, then the server's extras, then typos, without duplicates`() {
        val (a, b, c, d, e) = List(5) { UUID.randomUUID() }
        val f = UUID.randomUUID()
        val index = FuzzySearch.Ranked(matches = listOf(a, b), typos = listOf(e, f, b))
        assertEquals(listOf(a, b, c, d, e, f), FuzzySearch.merge(index, listOf(c, a, d, e)) { it })
        assertEquals(listOf(c, d), FuzzySearch.merge(FuzzySearch.Ranked(emptyList(), emptyList()), listOf(c, d)) { it })
    }
}
