package com.github.damontecres.wholphin.ui.preferences

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.ProfileChooserLock
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.UnlockPurpose
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.resetSubtitles
import com.github.damontecres.wholphin.preferences.updateSubtitlePreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.ScreensaverService
import com.github.damontecres.wholphin.services.SeerrServerRepository
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PreferencesViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        val preferenceDataStore: DataStore<AppPreferences>,
        val navigationManager: NavigationManager,
        val backdropService: BackdropService,
        val screensaverService: ScreensaverService,
        private val serverRepository: ServerRepository,
        private val seerrServerRepository: SeerrServerRepository,
        private val updateChecker: UpdateChecker,
        private val serverReportService: ServerReportService,
        private val kidSessionGate: KidSessionGateImpl,
        profileChooserLock: ProfileChooserLock,
    ) : ViewModel() {
        val currentUser =
            serverRepository.currentUserFlow.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                null,
            )
        val currentUserDto =
            serverRepository.currentUserDtoFlow.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                null,
            )

        val seerrConnection = seerrServerRepository.connection

        /** Kid profiles: more than one profile on this TV, so the profile screen always shows (plan 4.5) */
        val multiProfile: StateFlow<Boolean> = profileChooserLock.multiProfile

        /** Kid profiles: PIN removal is blocked on a protected device (plan 4.2) */
        val deviceProtected: StateFlow<Boolean> =
            kidSessionGate.isDeviceProtected.stateIn(viewModelScope, SharingStarted.Eagerly, false)

        private val _canTurnOffProtection = MutableStateFlow(false)

        /** Kid profiles: show "Turn off kid protection on this TV" (an adult, protected device, no kid rows) */
        val canTurnOffProtection: StateFlow<Boolean> = _canTurnOffProtection

        fun refreshKidProtection() {
            if (!BuildConfig.KID_PROFILES_ENABLED) return
            viewModelScope.launchIO {
                val adult = serverRepository.currentUser?.profileKind == ProfileKind.ADULT
                _canTurnOffProtection.value = adult && kidSessionGate.canTurnOffProtection()
            }
        }

        /**
         * The "Turn off kid protection" PIN submission in progress (review round 5), or none; guarded by
         * [turnOffLock]. Dismissing the prompt leaves it: it is cancelled, grants no unlock and turns nothing off.
         */
        private var turnOffAttempt: Job? = null
        private val turnOffLock = Any()

        private fun isCurrentTurnOff(attempt: Job): Boolean = synchronized(turnOffLock) { turnOffAttempt === attempt }

        /**
         * "Turn off kid protection on this TV": the adult's PIN gives the [com.github.damontecres.wholphin.kids.ParentUnlock]
         * it needs, granted only while this submission is still the current one, and checked again right where
         * protection is cleared. A later submission replaces an earlier one.
         */
        fun turnOffKidProtection(
            user: JellyfinUser,
            pin: String,
            onResult: (PinCheckResult, Boolean) -> Unit,
        ) {
            synchronized(turnOffLock) {
                turnOffAttempt?.cancel()
                val attempt =
                    viewModelScope.launchIO(ExceptionHandler(autoToast = true), start = CoroutineStart.LAZY) {
                        val self = coroutineContext[Job]!!
                        val result =
                            kidSessionGate.verifyParentPin(user, pin, UnlockPurpose.TurnOffProtection) { grant ->
                                synchronized(turnOffLock) { if (turnOffAttempt === self) grant() }
                            }
                        val turnedOff =
                            result == PinCheckResult.Correct &&
                                isCurrentTurnOff(self) &&
                                kidSessionGate.turnOffProtection { isCurrentTurnOff(self) }
                        kidSessionGate.clearParentUnlock(UnlockPurpose.TurnOffProtection)
                        refreshKidProtection()
                        withContext(WholphinDispatchers.Main) { if (isCurrentTurnOff(self)) onResult(result, turnedOff) }
                    }
                turnOffAttempt = attempt
                attempt
            }.start()
        }

        /** The "Turn off kid protection" prompt was dismissed: a PIN still being checked turns nothing off */
        fun cancelTurnOffKidProtection() {
            synchronized(turnOffLock) {
                turnOffAttempt?.cancel()
                turnOffAttempt = null
                kidSessionGate.clearParentUnlock(UnlockPurpose.TurnOffProtection)
            }
        }

        private val _quickConnectStatus = MutableStateFlow<LoadingState>(LoadingState.Pending)
        val quickConnectStatus: StateFlow<LoadingState> = _quickConnectStatus

        val releaseNotes = MutableStateFlow<DataLoadingState<Release>>(DataLoadingState.Pending)

        val externalPlayers = MutableStateFlow<List<ExternalPlayerApp>>(emptyList())

        init {
            viewModelScope.launchIO {
                val fakeIntent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType("https://damontecres.com/video.mp4".toUri(), "video/*")
                    }
                val externalPlayers = getExternalPlayers(context)
                val systemDefault =
                    ExternalPlayerApp(
                        name = context.getString(R.string.system_default),
                        icon = null,
                        identifier = "",
                    )
                this@PreferencesViewModel.externalPlayers.update { listOf(systemDefault) + externalPlayers }
            }
        }

        fun sendAppLogs() {
            viewModelScope.launchIO { serverReportService.sendAppLogs() }
        }

        fun resetSubtitleSettings() {
            viewModelScope.launchIO {
                resetSubtitleSettings(preferenceDataStore)
            }
        }

        /** The last PIN or login change queued (review round 10); guarded by [authChangeLock] */
        private var lastAuthChange: Job? = null
        private val authChangeLock = Any()

        /**
         * Runs [change] after every PIN or login change made before it (review round 10), so they are saved one at a
         * time and in the order they were made: an earlier one never lands after a later one. A failed change doesn't
         * stop the next.
         */
        private fun queueAuthChange(change: suspend () -> Unit) {
            synchronized(authChangeLock) {
                val previous = lastAuthChange
                lastAuthChange =
                    viewModelScope.launchIO(ExceptionHandler(autoToast = true)) {
                        previous?.join()
                        change()
                    }
            }
        }

        fun setPin(
            user: JellyfinUser,
            pin: String?,
        ) {
            queueAuthChange {
                serverRepository.updateUserAuth(user, pin, false)
                if (!user.hasPin && pin != null) {
                    // A first PIN counts as parental authorization only on an unprotected TV (plan 4.1)
                    kidSessionGate.grantUnlockForFirstPin(user.copy(pin = pin))
                }
            }
        }

        fun setRequireLogin(user: JellyfinUser) {
            queueAuthChange { serverRepository.updateUserAuth(user, null, true) }
        }

        fun removeLoginAndPin(user: JellyfinUser) {
            queueAuthChange { serverRepository.updateUserAuth(user, null, false) }
        }

        fun resetQuickConnectStatus() {
            _quickConnectStatus.value = LoadingState.Pending
        }

        fun authorizeQuickConnect(code: String) {
            viewModelScope.launchIO {
                _quickConnectStatus.value = LoadingState.Loading
                try {
                    val success = serverRepository.authorizeQuickConnect(code)
                    _quickConnectStatus.value =
                        if (success) {
                            LoadingState.Success
                        } else {
                            LoadingState.Error("Authorization failed")
                        }
                } catch (e: Exception) {
                    _quickConnectStatus.value = LoadingState.Error(e)
                }
            }
        }

        fun fetchReleaseNotes() {
            viewModelScope.launchIO {
                releaseNotes.update { DataLoadingState.Loading }
                try {
                    val release = updateChecker.getRelease(updateChecker.getInstalledVersion())
                    if (release != null) {
                        releaseNotes.update { DataLoadingState.Success(release) }
                    } else {
                        releaseNotes.update { DataLoadingState.Error("Release not found") }
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Error fetching release")
                    releaseNotes.update { DataLoadingState.Error(ex) }
                }
            }
        }

        companion object {
            suspend fun resetSubtitleSettings(appPreferences: DataStore<AppPreferences>) {
                appPreferences.updateData {
                    it.updateSubtitlePreferences {
                        resetSubtitles()
                    }
                }
            }
        }
    }

data class ExternalPlayerApp(
    val name: String,
    val icon: ImageBitmap?,
    val identifier: String,
)

fun getExternalPlayers(context: Context): List<ExternalPlayerApp> {
    val fakeIntent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType("https://damontecres.com/video.mp4".toUri(), "video/*")
        }
    val externalPlayers =
        context.packageManager
            .queryIntentActivities(fakeIntent, PackageManager.MATCH_ALL)
            .filter { it.priority >= 0 }
            .map {
                val component =
                    ComponentName(
                        it.activityInfo.packageName,
                        it.activityInfo.name,
                    )
                ExternalPlayerApp(
                    name = it.loadLabel(context.packageManager).toString(),
                    icon =
                        it
                            .loadIcon(context.packageManager)
                            .toBitmap()
                            .asImageBitmap(),
                    identifier = component.flattenToString(),
                )
            }
    return externalPlayers
}
