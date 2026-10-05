package com.github.damontecres.wholphin.preferences

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.AppDatabase
import com.github.damontecres.wholphin.data.Migrations
import com.github.damontecres.wholphin.data.PlaybackLanguageChoiceDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.data.model.JellyfinUserPreferences
import com.github.damontecres.wholphin.services.StreamChoiceService
import com.github.damontecres.wholphin.services.subtitle
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserDto
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SubtitleDefaultsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "subtitle-defaults-${UUID.randomUUID()}.db"
    private val expectedDefault =
        if (BuildConfig.FLAVOR == "weaselfin") {
            SubtitleModePreference.ONLY_FORCED
        } else {
            SubtitleModePreference.USE_USER_PROFILE
        }

    @After
    fun cleanup() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `new profile chooses forced translations even when server Smart would enable full captions`() {
        assertEquals(expectedDefault, JellyfinUserPreferences().subtitleMode)
        assertEquals(expectedDefault, UserProfileSettings.SubtitleModePref.defaultValue)
        val selected = chooseSubtitle(includeForced = true)
        assertEquals(if (BuildConfig.FLAVOR == "weaselfin") 1 else 0, selected)
    }

    @Test
    fun `new profile does not fall back to full captions when forced translations are absent`() {
        val selected = chooseSubtitle(includeForced = false)
        assertEquals(if (BuildConfig.FLAVOR == "weaselfin") null else 0, selected)
    }

    @Test
    fun `manually selected full captions still play`() {
        val selected =
            chooseSubtitle(
                includeForced = true,
                itemPlayback =
                    ItemPlayback(
                        userId = 1,
                        itemId = UUID.randomUUID(),
                        subtitleIndex = 0,
                    ),
            )
        assertEquals(0, selected)
    }

    @Test
    fun `version 35 upgrade changes inherited modes preserves other preferences and runs once`() {
        val serverId = UUID.randomUUID()
        val users =
            SubtitleModePreference.entries.mapIndexed { index, mode ->
                JellyfinUser(
                    rowId = index + 1,
                    id = UUID.randomUUID(),
                    serverId = serverId,
                    name = "Profile $index",
                    accessToken = "test-token-$index",
                    pin = "1234",
                    appPreferences =
                        JellyfinUserPreferences(
                            preferredAudioLanguage = "eng",
                            preferredSubtitleLanguage = "fra",
                            subtitleMode = mode,
                        ),
                )
            }
        createVersion35Database(serverId, users)

        withDatabase { db ->
            assertEquals(36, db.openHelper.writableDatabase.version)
            users.forEach { previous ->
                val expected =
                    if (previous.appPreferences.subtitleMode == SubtitleModePreference.USE_USER_PROFILE) {
                        previous.copy(appPreferences = previous.appPreferences.copy(subtitleMode = expectedDefault))
                    } else {
                        previous
                    }
                assertEquals(expected, db.serverDao().getUser(serverId, previous.id))
            }

            val newUser =
                db.serverDao().addOrUpdateUser(
                    JellyfinUser(
                        id = UUID.randomUUID(),
                        serverId = serverId,
                        name = "New profile",
                        accessToken = "test-token-new",
                    ),
                )
            assertEquals(
                expectedDefault,
                db
                    .serverDao()
                    .getUser(serverId, newUser.id)!!
                    .appPreferences.subtitleMode,
            )

            val inheritedUser = db.serverDao().getUser(serverId, users.first().id)!!
            db.serverDao().updateUser(
                inheritedUser.copy(
                    appPreferences = inheritedUser.appPreferences.copy(subtitleMode = SubtitleModePreference.USE_USER_PROFILE),
                ),
            )
        }

        withDatabase { db ->
            assertEquals(
                SubtitleModePreference.USE_USER_PROFILE,
                db
                    .serverDao()
                    .getUser(serverId, users.first().id)!!
                    .appPreferences.subtitleMode,
            )
        }
    }

    private fun chooseSubtitle(
        includeForced: Boolean,
        itemPlayback: ItemPlayback? = null,
    ): Int? {
        val repository = mockk<ServerRepository>()
        every { repository.currentUserDto } returns
            UserDto(
                id = UUID.randomUUID(),
                hasPassword = false,
                hasConfiguredPassword = false,
                hasConfiguredEasyPassword = false,
                configuration =
                    DefaultUserConfiguration.copy(
                        subtitleMode = SubtitlePlaybackMode.SMART,
                        audioLanguagePreference = "eng",
                        subtitleLanguagePreference = "eng",
                    ),
            )
        val service = StreamChoiceService(repository, mockk<PlaybackLanguageChoiceDao>())
        return service
            .chooseSubtitleStream(
                audioStreamLang = "spa",
                candidates =
                    buildList {
                        add(subtitle(0, "eng", default = true))
                        if (includeForced) add(subtitle(1, "eng", forced = true))
                    },
                itemPlayback = itemPlayback,
                playbackLanguageChoice = null,
                prefs = UserPreferences(AppPreferences.getDefaultInstance(), JellyfinUserPreferences()),
            )?.index
    }

    private fun openDatabase(): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(Migrations.Migrate35to36)
            .allowMainThreadQueries()
            .build()

    private inline fun withDatabase(block: (AppDatabase) -> Unit) {
        val db = openDatabase()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun createVersion35Database(
        serverId: UUID,
        users: List<JellyfinUser>,
    ) {
        val schema =
            javaClass
                .getResourceAsStream("/com.github.damontecres.wholphin.data.AppDatabase/35.json")!!
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(35) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val entities = schema.getJSONArray("entities")
                            for (index in 0 until entities.length()) {
                                val entity = entities.getJSONObject(index)
                                val tableName = entity.getString("tableName")
                                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                                val indices = entity.optJSONArray("indices") ?: continue
                                for (indexIndex in 0 until indices.length()) {
                                    db.execSQL(
                                        indices
                                            .getJSONObject(indexIndex)
                                            .getString("createSql")
                                            .replace("\${TABLE_NAME}", tableName),
                                    )
                                }
                            }
                            val setupQueries = schema.getJSONArray("setupQueries")
                            for (index in 0 until setupQueries.length()) {
                                db.execSQL(setupQueries.getString(index))
                            }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = error("Room must perform the upgrade")
                    },
                ).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            val storedServerId = serverId.toString().replace("-", "")
            db.execSQL("INSERT INTO servers (id, name, url) VALUES (?, 'Test server', 'http://localhost')", arrayOf(storedServerId))
            users.forEach { user ->
                db.execSQL(
                    """
                    INSERT INTO users (rowId, id, name, serverId, accessToken, pin,
                        preferredAudioLanguage, preferredSubtitleLanguage, subtitleMode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        user.rowId,
                        user.id.toString().replace("-", ""),
                        user.name,
                        storedServerId,
                        user.accessToken,
                        user.pin,
                        user.appPreferences.preferredAudioLanguage,
                        user.appPreferences.preferredSubtitleLanguage,
                        user.appPreferences.subtitleMode.name,
                    ),
                )
            }
        }
    }
}
