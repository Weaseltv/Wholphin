package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.core.content.edit
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/*
 * Account avatars (contract 11.1). A profile's avatar is its Jellyfin user's Primary image, so it follows the person to
 * every TV. An adult signing in fresh without one chooses one first; an adult already saved on a TV without one gets a
 * random adult avatar, once per user per TV. An existing image is never replaced automatically.
 */

/** Whether a profile has a Primary image, with its own token */
fun interface PrimaryImageLookup {
    /** True or false, or null when the server couldn't say (then it is asked again another time) */
    suspend fun hasPrimaryImage(
        serverUrl: String,
        userId: UUID,
        accessToken: String,
    ): Boolean?
}

/** Which profiles had their one automatic avatar check on this TV */
interface AvatarAssignments {
    fun isDone(
        serverId: UUID,
        userId: UUID,
    ): Boolean

    fun markDone(
        serverId: UUID,
        userId: UUID,
    )
}

@Singleton
class AccountAvatars
    @Inject
    constructor(
        private val pictures: ProfilePictureService,
        private val lookup: PrimaryImageLookup,
        private val assignments: AvatarAssignments,
    ) {
        /** Exposed for tests */
        internal var random: Random = Random.Default

        /** The adult avatar set, which adults choose from */
        val adultAvatars: List<Avatar> get() = pictures.avatars(isKid = false)

        /**
         * A fresh sign-in of [user] (username/password or Quick Connect, not a profile switch) asks for an avatar first:
         * an adult (the server policy says so) with no Primary image, on a build with avatars
         */
        fun needsFirstAvatar(user: UserDto?): Boolean {
            val policy = user?.policy ?: return false
            return profilePicturesEnabled &&
                policy.maxParentalRating == null &&
                user.primaryImageTag.isNullOrBlank() &&
                adultAvatars.isNotEmpty()
        }

        /**
         * Gives each saved adult in [users] without a Primary image a random adult avatar, once per user per TV. The check
         * is done once the server has answered, even if the upload then fails, so it never retries forever; a server that
         * can't be reached is asked again next time.
         */
        suspend fun assignMissing(
            serverUrl: String,
            users: List<JellyfinUser>,
        ) {
            if (!profilePicturesEnabled) return
            val avatars = adultAvatars.takeIf { it.isNotEmpty() } ?: return
            users
                .filter { it.profileKind == ProfileKind.ADULT && it.accessToken != null && !assignments.isDone(it.serverId, it.id) }
                .forEach { user ->
                    val token = user.accessToken ?: return@forEach
                    val hasImage = lookup.hasPrimaryImage(serverUrl, user.id, token) ?: return@forEach
                    if (!hasImage) {
                        Timber.i("Giving profile %s a random avatar", user.rowId)
                        pictures.save(ProfilePictureTarget(serverUrl, user.id, token, user.name, isKid = false), avatars.random(random))
                    }
                    assignments.markDone(user.serverId, user.id)
                }
        }
    }

@Singleton
class JellyfinPrimaryImageLookup
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
    ) : PrimaryImageLookup {
        override suspend fun hasPrimaryImage(
            serverUrl: String,
            userId: UUID,
            accessToken: String,
        ): Boolean? =
            try {
                withContext(WholphinDispatchers.IO) {
                    val api =
                        jellyfin.createApi(
                            baseUrl = serverUrl,
                            accessToken = accessToken,
                            httpClientOptions =
                                HttpClientOptions(requestTimeout = TIMEOUT, connectTimeout = TIMEOUT, socketTimeout = TIMEOUT),
                        )
                    val me by api.userApi.getCurrentUser()
                    // Only this profile's own answer counts
                    if (me.id == userId) !me.primaryImageTag.isNullOrBlank() else null
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Couldn't check the profile picture")
                null
            }

        private companion object {
            val TIMEOUT = 6.seconds
        }
    }

@Singleton
class SharedPrefsAvatarAssignments
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AvatarAssignments {
        private val prefs by lazy {
            context.getSharedPreferences("${context.packageName}_avatar_assignments", Context.MODE_PRIVATE)
        }

        override fun isDone(
            serverId: UUID,
            userId: UUID,
        ): Boolean = prefs.getBoolean(key(serverId, userId), false)

        override fun markDone(
            serverId: UUID,
            userId: UUID,
        ) = prefs.edit { putBoolean(key(serverId, userId), true) }

        private fun key(
            serverId: UUID,
            userId: UUID,
        ) = "$serverId/$userId"
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountAvatarModule {
    @Binds
    abstract fun primaryImageLookup(impl: JellyfinPrimaryImageLookup): PrimaryImageLookup

    @Binds
    abstract fun avatarAssignments(impl: SharedPrefsAvatarAssignments): AvatarAssignments
}
