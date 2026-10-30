package com.github.damontecres.wholphin.kids

import com.github.damontecres.wholphin.kids.KidContentRules.ItemFacts
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON side of the kid content rules: which items a response keeps (contract section 10) */
class KidContentFilterTest {
    private class FakeLoader(
        val facts: Map<String, ItemFacts> = emptyMap(),
        val boxSets: Map<String, Boolean> = emptyMap(),
        val fail: Boolean = false,
    ) : KidFactsLoader {
        val asked = mutableListOf<Set<String>>()

        override suspend fun items(
            ids: Collection<String>,
            fresh: Boolean,
        ): Map<String, ItemFacts> {
            asked.add(ids.toSet())
            if (fail) throw IllegalStateException("offline")
            return ids.mapNotNull { id -> facts[id]?.let { id to it } }.toMap()
        }

        override suspend fun boxSetHasVisibleChild(
            id: String,
            maxLevel: Int?,
            fresh: Boolean,
        ): Boolean {
            if (fail) throw IllegalStateException("offline")
            return boxSets[id] == true
        }
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun movie(
        id: String,
        rating: String?,
        premiere: String?,
        genres: List<String>,
        locked: List<String> = emptyList(),
    ): String {
        val ratingJson = rating?.let { "\"OfficialRating\":\"$it\"," } ?: ""
        val premiereJson = premiere?.let { "\"PremiereDate\":\"$it\"," } ?: ""
        val genreJson = genres.joinToString(",") { "\"$it\"" }
        val lockedJson = locked.joinToString(",") { "\"$it\"" }
        return """{"Id":"$id","Name":"$id","ServerId":"s","Type":"Movie",$ratingJson$premiereJson""" +
            """"Genres":[$genreJson],"LockedFields":[$lockedJson],"IsFolder":false}"""
    }

    private fun ids(root: JsonElement?): List<String> {
        val array = if (root is JsonArray) root else root!!.jsonObject["Items"]!!.jsonArray
        return array.map { it.jsonObject["Id"]!!.jsonPrimitive.content }
    }

    private val poltergeist = movie("poltergeist", "PG", "1982-06-04T00:00:00.0000000Z", listOf("Horror", "Thriller"))
    private val gremlins = movie("gremlins", "PG", "1984-06-08T00:00:00.0000000Z", listOf("Fantasy", "Horror", "Comedy"))
    private val pinkPanther = movie("pinkpanther", "PG", "1963-12-19T00:00:00.0000000Z", listOf("Comedy"))
    private val templeOfDoom = movie("templeofdoom", "PG", "1984-05-23T00:00:00.0000000Z", listOf("Adventure"))
    private val moonstruck = movie("moonstruck", "PG", "1987-12-16T00:00:00.0000000Z", listOf("Comedy", "Romance"))
    private val angryMen = movie("angrymen", "NR", "1957-04-10T00:00:00.0000000Z", listOf("Drama"))
    private val lockedR = movie("lockedr", "R", "2001-01-01T00:00:00.0000000Z", listOf("Action"), listOf("OfficialRating"))
    private val ufc = movie("ufc300", null, "2024-04-13T00:00:00.0000000Z", listOf("Sport"))

    private val library =
        listOf(poltergeist, gremlins, pinkPanther, templeOfDoom, moonstruck, angryMen, lockedR, ufc)

    private fun queryResult(items: List<String>) =
        json("""{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size + 100},"StartIndex":0}""")

    @Test
    fun `owner cases in a library page at PG-13`() =
        runTest {
            val result = KidResponseFilter(2, FakeLoader()).filter(queryResult(library))
            assertEquals(listOf("pinkpanther", "templeofdoom", "moonstruck"), ids(result.root))
            assertEquals(5, result.removedFromList)
            // TotalRecordCount goes down by as many as were removed
            assertEquals(
                103,
                result.root!!
                    .jsonObject["TotalRecordCount"]!!
                    .jsonPrimitive.int,
            )
            assertTrue(result.changed)
        }

    @Test
    fun `owner cases in a library page at PG`() =
        runTest {
            val result = KidResponseFilter(1, FakeLoader()).filter(queryResult(library))
            assertEquals(listOf("moonstruck"), ids(result.root))
        }

    @Test
    fun `a top-level array is filtered too`() =
        runTest {
            val result = KidResponseFilter(2, FakeLoader()).filter(json("[$poltergeist,$moonstruck]"))
            assertEquals(listOf("moonstruck"), ids(result.root))
            assertEquals(1, result.removedFromList)
        }

    @Test
    fun `a hidden item as the whole response is hidden`() =
        runTest {
            assertNull(KidResponseFilter(2, FakeLoader()).filter(json(poltergeist)).root)
            val shown = KidResponseFilter(2, FakeLoader()).filter(json(moonstruck))
            assertFalse(shown.changed)
        }

    private val ginnyFacts =
        ItemFacts(
            id = "ginny",
            type = "Series",
            name = "Ginny & Georgia",
            officialRating = "TV-MA",
            genres = listOf("Drama", "Comedy"),
            lockedFields = listOf("OfficialRating"),
        )
    private val blueyFacts =
        ItemFacts(id = "bluey", type = "Series", officialRating = "TV-Y", genres = listOf("Animation"), lockedFields = emptyList())

    private fun episode(
        id: String,
        seriesId: String,
        rating: String? = "TV-14",
        type: String = "Episode",
    ): String {
        val ratingJson = rating?.let { "\"OfficialRating\":\"$it\"," } ?: ""
        return """{"Id":"$id","Name":"$id","ServerId":"s","Type":"$type",$ratingJson"SeriesId":"$seriesId",""" +
            """"SeriesName":"x","Genres":[],"LockedFields":[]}"""
    }

    @Test
    fun `Next Up hides every episode of Ginny and Georgia, loading the series once`() =
        runTest {
            val loader = FakeLoader(mapOf("ginny" to ginnyFacts, "bluey" to blueyFacts))
            val nextUp = queryResult(listOf(episode("g1", "ginny"), episode("b1", "bluey", "TV-Y"), episode("g2", "ginny", null)))
            val result = KidResponseFilter(2, loader).filter(nextUp)
            assertEquals(listOf("b1"), ids(result.root))
            assertEquals(listOf(setOf("ginny", "bluey")), loader.asked)
        }

    @Test
    fun `seasons of a hidden series are hidden`() =
        runTest {
            val loader = FakeLoader(mapOf("ginny" to ginnyFacts))
            val result = KidResponseFilter(4, loader).filter(json("[${episode("s1", "ginny", null, "Season")}]"))
            // TV-MA is level 3: visible at 4 (no profile has 4 but the general rule)
            assertEquals(listOf("s1"), ids(result.root))
            val atTeens = KidResponseFilter(2, loader).filter(json("[${episode("s1", "ginny", null, "Season")}]"))
            assertEquals(emptyList<String>(), ids(atTeens.root))
        }

    @Test
    fun `a series in the same response is used for its episodes`() =
        runTest {
            val loader = FakeLoader()
            val series =
                """{"Id":"bluey","Name":"Bluey","ServerId":"s","Type":"Series","OfficialRating":"TV-Y","Genres":["Animation"],"LockedFields":[]}"""
            val result = KidResponseFilter(1, loader).filter(queryResult(listOf(series, episode("b1", "bluey", null))))
            assertEquals(listOf("bluey", "b1"), ids(result.root))
            assertTrue(loader.asked.isEmpty())
        }

    @Test
    fun `search hints have their metadata loaded`() =
        runTest {
            val hints =
                json(
                    """{"SearchHints":[
                    {"ItemId":"poltergeist","Id":"poltergeist","Name":"Poltergeist","Type":"Movie","MediaType":"Video","IsFolder":false},
                    {"ItemId":"moonstruck","Id":"moonstruck","Name":"Moonstruck","Type":"Movie","MediaType":"Video","IsFolder":false},
                    {"ItemId":"g1","Id":"g1","Name":"Pilot","Type":"Episode","Series":"Ginny & Georgia","IsFolder":false},
                    {"ItemId":"gone","Id":"gone","Name":"Gone","Type":"Movie","IsFolder":false},
                    {"ItemId":"p1","Id":"p1","Name":"Some Actor","Type":"Person","IsFolder":false}
                    ],"TotalRecordCount":5}""",
                )
            val loader =
                FakeLoader(
                    mapOf(
                        "poltergeist" to KidJson.factsOf(json(poltergeist).jsonObject),
                        "moonstruck" to KidJson.factsOf(json(moonstruck).jsonObject),
                        "g1" to KidJson.factsOf(json(episode("g1", "ginny")).jsonObject),
                        "ginny" to ginnyFacts,
                    ),
                )
            val result = KidResponseFilter(2, loader).filter(hints)
            val kept =
                result.root!!
                    .jsonObject["SearchHints"]!!
                    .jsonArray
                    .map { it.jsonObject["Id"]!!.jsonPrimitive.content }
            assertEquals(listOf("moonstruck", "p1"), kept)
            assertEquals(
                2,
                result.root!!
                    .jsonObject["TotalRecordCount"]!!
                    .jsonPrimitive.int,
            )
            // The person needed nothing; the hints were loaded in one batch, then the episode's series
            assertEquals(listOf(setOf("poltergeist", "moonstruck", "g1", "gone"), setOf("ginny")), loader.asked)
        }

    @Test
    fun `a failed lookup hides what it was for`() =
        runTest {
            val withoutGenres = """{"Id":"m1","Name":"m1","ServerId":"s","Type":"Movie","OfficialRating":"G"}"""
            val result = KidResponseFilter(4, FakeLoader(fail = true)).filter(queryResult(listOf(withoutGenres, moonstruck)))
            assertEquals(listOf("moonstruck"), ids(result.root))
            val episodes = KidResponseFilter(4, FakeLoader(fail = true)).filter(json("[${episode("b1", "bluey", "TV-Y")}]"))
            assertEquals(emptyList<String>(), ids(episodes.root))
        }

    @Test
    fun `a PG movie the era rule could raise is loaded when its locked fields are missing`() =
        runTest {
            val noSettings =
                """{"Id":"pp","Name":"pp","ServerId":"s","Type":"Movie","OfficialRating":"PG","ProductionYear":1963,"Genres":["Comedy"]}"""
            val loader =
                FakeLoader(
                    mapOf(
                        "pp" to
                            ItemFacts(
                                "pp",
                                "Movie",
                                officialRating = "PG",
                                genres = listOf("Comedy"),
                                productionYear = 1963,
                                lockedFields = listOf("OfficialRating"),
                            ),
                    ),
                )
            val result = KidResponseFilter(1, loader).filter(queryResult(listOf(noSettings)))
            // Hand-set PG: shown at PG
            assertEquals(listOf("pp"), ids(result.root))
            assertEquals(listOf(setOf("pp")), loader.asked)
        }

    @Test
    fun `box sets are shown only with a visible child`() =
        runTest {
            fun boxSet(id: String) = """{"Id":"$id","Name":"$id","ServerId":"s","Type":"BoxSet","IsFolder":true}"""
            val loader = FakeLoader(boxSets = mapOf("kept" to true, "empty" to false))
            val result = KidResponseFilter(2, loader).filter(queryResult(listOf(boxSet("kept"), boxSet("empty"))))
            assertEquals(listOf("kept"), ids(result.root))
            val failing = KidResponseFilter(2, FakeLoader(fail = true)).filter(queryResult(listOf(boxSet("kept"))))
            assertEquals(emptyList<String>(), ids(failing.root))
        }

    @Test
    fun `containers stay, the horror genre goes`() =
        runTest {
            val items =
                listOf(
                    """{"Id":"lib","Name":"Movies","ServerId":"s","Type":"CollectionFolder","IsFolder":true}""",
                    """{"Id":"view","Name":"Movies","ServerId":"s","Type":"UserView","IsFolder":true}""",
                    """{"Id":"person","Name":"Someone","ServerId":"s","Type":"Person"}""",
                    """{"Id":"genre1","Name":"Comedy","ServerId":"s","Type":"Genre"}""",
                    """{"Id":"genre2","Name":"Horror","ServerId":"s","Type":"Genre"}""",
                    """{"Id":"studio","Name":"Studio","ServerId":"s","Type":"Studio"}""",
                    """{"Id":"playlist","Name":"Mine","ServerId":"s","Type":"Playlist","IsFolder":true}""",
                )
            val result = KidResponseFilter(null, FakeLoader()).filter(queryResult(items))
            assertEquals(listOf("lib", "view", "person", "genre1", "studio", "playlist"), ids(result.root))
        }

    @Test
    fun `an unknown ceiling shows no title`() =
        runTest {
            val result = KidResponseFilter(null, FakeLoader()).filter(queryResult(library))
            assertEquals(emptyList<String>(), ids(result.root))
        }

    @Test
    fun `unknown item types are titles, other objects are left alone`() =
        runTest {
            val unknownType = """{"Id":"new","Name":"New","ServerId":"s","Type":"HologramShow","Genres":[],"LockedFields":[]}"""
            val people =
                """{"Id":"m","Name":"m","ServerId":"s","Type":"Movie","OfficialRating":"G","Genres":[],"LockedFields":[],""" +
                    """"People":[{"Id":"a1","Name":"Actor","Type":"Actor"}],"MediaSources":[{"Id":"ms","Type":"Default"}]}"""
            val result = KidResponseFilter(4, FakeLoader()).filter(queryResult(listOf(unknownType, people)))
            assertEquals(listOf("m"), ids(result.root))
            val movie =
                result.root!!
                    .jsonObject["Items"]!!
                    .jsonArray[0]
                    .jsonObject
            assertEquals(1, movie["People"]!!.jsonArray.size)
            assertEquals(1, movie["MediaSources"]!!.jsonArray.size)
        }

    @Test
    fun `hidden nested items are removed from their field`() =
        runTest {
            val sessions = json("""[{"Id":"session","DeviceName":"TV","NowPlayingItem":$poltergeist}]""")
            val result = KidResponseFilter(2, FakeLoader()).filter(sessions)
            val session = (result.root as JsonArray)[0].jsonObject
            assertFalse(session.containsKey("NowPlayingItem"))
            assertEquals(0, result.removedFromList)
        }

    @Test
    fun `responses without items are unchanged`() =
        runTest {
            val user = json("""{"Id":"u","Name":"Kid","Policy":{"MaxParentalRating":10}}""")
            val result = KidResponseFilter(1, FakeLoader()).filter(user)
            assertFalse(result.changed)
            assertEquals(user, result.root)
            val theme = json("""{"ThemeSongsResult":{"Items":[],"TotalRecordCount":0}}""")
            assertFalse(KidResponseFilter(1, FakeLoader()).filter(theme).changed)
        }

    @Test
    fun `nested query results keep their own counts`() =
        runTest {
            val ufcNoGenres = """{"Id":"song","Name":"Theme","ServerId":"s","Type":"Audio","Genres":[],"LockedFields":[]}"""
            val theme = json("""{"ThemeSongsResult":{"OwnerId":"x","Items":[$ufcNoGenres],"TotalRecordCount":1}}""")
            val result = KidResponseFilter(4, FakeLoader()).filter(theme)
            val songs = result.root!!.jsonObject["ThemeSongsResult"]!!.jsonObject
            assertEquals(0, songs["Items"]!!.jsonArray.size)
            assertEquals(0, songs["TotalRecordCount"]!!.jsonPrimitive.int)
            assertTrue(result.root is JsonObject)
        }
}
