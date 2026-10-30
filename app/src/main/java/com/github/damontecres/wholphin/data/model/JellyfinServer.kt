@file:UseSerializers(UUIDSerializer::class, ZonedDateTimeSerializer::class)

package com.github.damontecres.wholphin.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.github.damontecres.wholphin.data.ZonedDateTimeSerializer
import com.github.damontecres.wholphin.preferences.SubtitleModePreference
import com.github.damontecres.wholphin.preferences.UserProfileSettings
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Represents a Jellyfin server
 */
@Entity(tableName = "servers")
@Serializable
data class JellyfinServer(
    @PrimaryKey val id: UUID,
    val name: String?,
    val url: String,
    val version: String?,
    val lastUsed: ZonedDateTime? = null,
) {
    @get:Ignore
    val serverVersion: ServerVersion? by lazy { version?.let(ServerVersion::fromString) }
}

/**
 * Represents a Jellyfin user for a particular server
 */
@Entity(
    tableName = "users",
    foreignKeys = [
        ForeignKey(
            entity = JellyfinServer::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("serverId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("id", "serverId", unique = true)],
)
@Serializable
data class JellyfinUser(
    @PrimaryKey(autoGenerate = true)
    val rowId: Int = 0,
    @ColumnInfo(index = true)
    val id: UUID,
    val name: String?,
    @ColumnInfo(index = true)
    val serverId: UUID,
    val accessToken: String?,
    val pin: String? = null,
    @ColumnInfo(defaultValue = "false")
    val requireLogin: Boolean = false,
    val lastUsed: ZonedDateTime? = null,
    val uiLanguage: String? = null,
    @Embedded val appPreferences: JellyfinUserPreferences = JellyfinUserPreferences(),
    // Kid profiles: the server policy's MaxParentalRating (null for adults), as last fetched
    val maxParentalRating: Int? = null,
    // Kid profiles: whether the server policy said adult or kid; unknown until first fetched
    @ColumnInfo(defaultValue = "unknown")
    val policyState: PolicyState = PolicyState.UNKNOWN,
    // Kid profiles: the name the website gave this kid, shown instead of the server name
    val displayName: String? = null,
) {
    val hasPin: Boolean get() = pin.isNotNullOrBlank()

    /** The name to show for this profile */
    val shownName: String? get() = displayName?.takeIf { it.isNotBlank() } ?: name

    val isProtected: Boolean get() = hasPin || requireLogin

    override fun toString(): String =
        "JellyfinUser(rowId=$rowId, id=$id, name=$name, serverId=$serverId, lastUsed=$lastUsed, " +
            "accessToken?=${accessToken.isNotNullOrBlank()}, pin?=${pin.isNotNullOrBlank()}), " +
            "requireLogin=$requireLogin, lastUsed=$lastUsed, uiLanguage=$uiLanguage, " +
            "appPreferences=$appPreferences, policyState=$policyState, maxParentalRating=$maxParentalRating"
}

/**
 * What the server policy said about a saved profile (kid profiles, contract section 1). Stored as
 * `unknown`, `adult` or `kid`.
 */
enum class PolicyState(
    val dbValue: String,
) {
    UNKNOWN("unknown"),
    ADULT("adult"),
    KID("kid"),
    ;

    companion object {
        fun fromDbValue(value: String?): PolicyState = entries.firstOrNull { it.dbValue == value } ?: UNKNOWN
    }
}

/**
 * Represents the relationship between [JellyfinServer] and its [JellyfinUser]
 */
data class JellyfinServerUsers(
    @Embedded val server: JellyfinServer,
    @Relation(
        parentColumn = "id",
        entityColumn = "serverId",
    )
    val users: List<JellyfinUser>,
)

@Serializable
data class JellyfinUserPreferences(
    @ColumnInfo(defaultValue = "")
    val preferredAudioLanguage: String = UserProfileSettings.USE_USER_PROFILE,
    @ColumnInfo(defaultValue = "")
    val preferredSubtitleLanguage: String = UserProfileSettings.USE_USER_PROFILE,
    @ColumnInfo(defaultValue = "USE_USER_PROFILE")
    val subtitleMode: SubtitleModePreference = UserProfileSettings.defaultSubtitleMode,
)
