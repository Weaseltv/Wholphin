package com.github.damontecres.wholphin.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import coil3.SingletonImageLoader
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.FileInfo
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/*
 * WeaselPlex profile pictures (kid-profiles plan 4.6). A picture is the Jellyfin user's own
 * Primary image, picked from the avatar set bundled in the weaselfin flavor
 * (`assets/avatars/<group>/<id>.webp`; the file name is the stable avatar id). Kid profiles pick from
 * the kids set, adults from the adults set. Every profile saves its own picture with its own access
 * token, never the shared ApiClient's.
 */

/** Whether this build offers profile pictures (weaselfin, with kid profiles on). */
val profilePicturesEnabled: Boolean get() = BuildConfig.KID_PROFILES_ENABLED

/** The two avatar sets; [dir] is the folder under `assets/avatars/`. */
enum class AvatarGroup(
    val dir: String,
) {
    KIDS("kids"),
    ADULTS("adults"),
    ;

    companion object {
        /** Kid profiles (and any whose kind isn't known yet) pick from the kids set. */
        fun forKid(isKid: Boolean): AvatarGroup = if (isKid) KIDS else ADULTS
    }
}

/** One bundled avatar; [id] is its file name without `.webp`, lowercase-hyphen, never renamed. */
data class Avatar(
    val id: String,
    val group: AvatarGroup,
) {
    /** Its path under the app's assets. */
    val assetPath: String get() = "$AVATAR_DIR/${group.dir}/$id.webp"

    /** What Coil loads for the picker. */
    val model: String get() = "file:///android_asset/$assetPath"

    companion object {
        const val AVATAR_DIR = "avatars"
    }
}

/** Whose picture to change: a saved profile and its own session. */
data class ProfilePictureTarget(
    val serverUrl: String,
    val userId: UUID,
    val accessToken: String,
    val name: String?,
    /** A kid profile picks from the kids set; adults from the adults set. */
    val isKid: Boolean = false,
)

interface AvatarCatalog {
    fun avatars(group: AvatarGroup): List<Avatar>

    /** The avatar as PNG bytes, ready to upload. */
    suspend fun pngBytes(avatar: Avatar): ByteArray
}

interface UserImageUploader {
    suspend fun upload(
        target: ProfilePictureTarget,
        png: ByteArray,
    )

    suspend fun delete(target: ProfilePictureTarget)
}

/**
 * Remembers which avatar each profile picked on this TV and a per-profile version that is added
 * to the profile image URL, so a changed picture is fetched again instead of served from Coil's
 * cache.
 */
interface ProfileImageCache {
    val versions: StateFlow<Map<UUID, Int>>

    fun chosenAvatar(userId: UUID): String?

    fun onPictureChanged(
        userId: UUID,
        avatarId: String?,
    )
}

/** [url] with this profile's cache version, so URLs change after a save or remove. */
fun versionedUserImageUrl(
    url: String?,
    userId: UUID,
    versions: Map<UUID, Int>,
): String? {
    val version = versions[userId] ?: return url
    if (url.isNullOrBlank() || version == 0) return url
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}wpPicture=$version"
}

sealed interface ProfilePictureResult {
    data object Saved : ProfilePictureResult

    data class Failed(
        val cause: Throwable,
    ) : ProfilePictureResult
}

@Singleton
class ProfilePictureService
    @Inject
    constructor(
        private val catalog: AvatarCatalog,
        private val uploader: UserImageUploader,
        private val cache: ProfileImageCache,
    ) {
        val versions: StateFlow<Map<UUID, Int>> get() = cache.versions

        /** The set [isKid]'s profile picks from. */
        fun avatars(isKid: Boolean): List<Avatar> = catalog.avatars(AvatarGroup.forKid(isKid))

        fun chosenAvatar(userId: UUID): String? = cache.chosenAvatar(userId)

        suspend fun save(
            target: ProfilePictureTarget,
            avatar: Avatar,
        ): ProfilePictureResult =
            attempt("save") {
                uploader.upload(target, catalog.pngBytes(avatar))
                cache.onPictureChanged(target.userId, avatar.id)
            }

        suspend fun remove(target: ProfilePictureTarget): ProfilePictureResult =
            attempt("remove") {
                uploader.delete(target)
                cache.onPictureChanged(target.userId, null)
            }

        private suspend fun attempt(
            what: String,
            block: suspend () -> Unit,
        ): ProfilePictureResult =
            try {
                block()
                ProfilePictureResult.Saved
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Profile picture %s failed", what)
                ProfilePictureResult.Failed(ex)
            }
    }

@Singleton
class AssetAvatarCatalog
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AvatarCatalog {
        private val sets: Map<AvatarGroup, List<Avatar>> by lazy {
            AvatarGroup.entries.associateWith { group ->
                context.assets
                    .list("${Avatar.AVATAR_DIR}/${group.dir}")
                    .orEmpty()
                    .filter { it.endsWith(".webp") }
                    .map { Avatar(it.removeSuffix(".webp"), group) }
                    .sortedBy { it.id }
            }
        }

        override fun avatars(group: AvatarGroup): List<Avatar> = if (profilePicturesEnabled) sets[group].orEmpty() else emptyList()

        override suspend fun pngBytes(avatar: Avatar): ByteArray =
            withContext(WholphinDispatchers.IO) {
                val bitmap =
                    context.assets.open(avatar.assetPath).use {
                        BitmapFactory.decodeStream(it)
                    } ?: throw IllegalStateException("Could not read avatar ${avatar.id}")
                // PNG reads everywhere (phone apps, Web Player); the bundled WebP keeps the APK small.
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bitmap.recycle()
                    out.toByteArray()
                }
            }
    }

@Singleton
class JellyfinUserImageUploader
    @Inject
    constructor(
        private val jellyfin: Jellyfin,
    ) : UserImageUploader {
        private fun api(target: ProfilePictureTarget) = jellyfin.createApi(baseUrl = target.serverUrl, accessToken = target.accessToken)

        override suspend fun upload(
            target: ProfilePictureTarget,
            png: ByteArray,
        ) {
            withContext(WholphinDispatchers.IO) {
                // Jellyfin reads the user image body as base64 text, as jellyfin-web sends it.
                val body = Base64.getEncoder().encode(png)
                api(target).imageApi.postUserImage(target.userId, FileInfo(body, "image/png"))
            }
        }

        override suspend fun delete(target: ProfilePictureTarget) {
            withContext(WholphinDispatchers.IO) {
                api(target).imageApi.deleteUserImage(target.userId)
            }
        }
    }

@Singleton
class SharedPrefsProfileImageCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ProfileImageCache {
        private val prefs by lazy {
            context.getSharedPreferences("${context.packageName}_profile_pictures", Context.MODE_PRIVATE)
        }

        private val _versions =
            MutableStateFlow(
                prefs.all
                    .mapNotNull { (key, value) ->
                        val id = key.removePrefix(VERSION_PREFIX).takeIf { key.startsWith(VERSION_PREFIX) }
                        val uuid = id?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        if (uuid != null && value is Int) uuid to value else null
                    }.toMap(),
            )
        override val versions: StateFlow<Map<UUID, Int>> = _versions

        override fun chosenAvatar(userId: UUID): String? = prefs.getString(AVATAR_PREFIX + userId, null)

        override fun onPictureChanged(
            userId: UUID,
            avatarId: String?,
        ) {
            val next = (_versions.value[userId] ?: 0) + 1
            prefs.edit {
                putInt(VERSION_PREFIX + userId, next)
                if (avatarId != null) putString(AVATAR_PREFIX + userId, avatarId) else remove(AVATAR_PREFIX + userId)
            }
            _versions.update { it + (userId to next) }
            // Drop the old picture from memory; the new URL version skips the disk cache entry.
            val loader = SingletonImageLoader.get(context)
            val dashless = userId.toString().replace("-", "")
            loader.memoryCache?.let { memory ->
                memory.keys
                    .filter { key -> key.key.contains("/Users/$userId/", true) || key.key.contains("/Users/$dashless/", true) }
                    .forEach { memory.remove(it) }
            }
        }

        private companion object {
            const val VERSION_PREFIX = "version."
            const val AVATAR_PREFIX = "avatar."
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfilePictureModule {
    @Binds
    abstract fun avatarCatalog(impl: AssetAvatarCatalog): AvatarCatalog

    @Binds
    abstract fun userImageUploader(impl: JellyfinUserImageUploader): UserImageUploader

    @Binds
    abstract fun profileImageCache(impl: SharedPrefsProfileImageCache): ProfileImageCache
}
