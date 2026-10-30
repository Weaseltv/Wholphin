package com.github.damontecres.wholphin.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.github.damontecres.wholphin.data.model.PolicyState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Kid profiles: Room 36 -> 37 adds the server-policy columns to `users` */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class Migration36To37Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-36-37-${UUID.randomUUID()}.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `existing profiles keep their data and start as unknown`() {
        val serverId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        createVersion36Database(serverId, userId)

        val db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(Migrations.Migrate35to36, Migrations.Migrate36to37)
                .allowMainThreadQueries()
                .build()
        try {
            // Opening validates the migrated schema against the v37 entities
            val user = db.serverDao().getUser(serverId, userId)!!
            assertEquals("parent", user.name)
            assertEquals("1234", user.pin)
            assertEquals(PolicyState.UNKNOWN, user.policyState)
            assertNull(user.maxParentalRating)
            assertNull(user.displayName)

            db.serverDao().updateUserPolicy(user.rowId, 10, PolicyState.KID)
            val kid = db.serverDao().getUser(serverId, userId)!!
            assertEquals(PolicyState.KID, kid.policyState)
            assertEquals(10, kid.maxParentalRating)
            assertEquals(1, db.serverDao().countKidUsers())
            assertEquals(1, db.serverDao().countUsers(serverId))
            db.openHelper.readableDatabase.query("SELECT policyState FROM users").use { cursor ->
                cursor.moveToFirst()
                assertEquals("kid", cursor.getString(0))
            }
        } finally {
            db.close()
        }
    }

    private fun createVersion36Database(
        serverId: UUID,
        userId: UUID,
    ) {
        val schema =
            javaClass
                .getResourceAsStream("/com.github.damontecres.wholphin.data.AppDatabase/36.json")!!
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(36) {
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
            db.execSQL(
                "INSERT INTO users (rowId, id, name, serverId, accessToken, pin) VALUES (1, ?, 'parent', ?, 'token', '1234')",
                arrayOf<Any?>(userId.toString().replace("-", ""), storedServerId),
            )
        }
    }
}
