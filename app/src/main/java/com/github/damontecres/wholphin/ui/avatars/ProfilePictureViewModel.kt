package com.github.damontecres.wholphin.ui.avatars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.CurrentAvatarFinder
import com.github.damontecres.wholphin.services.CurrentPicture
import com.github.damontecres.wholphin.services.ProfilePictureResult
import com.github.damontecres.wholphin.services.ProfilePictureService
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks and saves (or removes) one profile's picture with that profile's own session. */
@HiltViewModel
class ProfilePictureViewModel
    @Inject
    constructor(
        private val service: ProfilePictureService,
        private val serverRepository: ServerRepository,
        private val finder: CurrentAvatarFinder,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ProfilePictureState())
        val state: StateFlow<ProfilePictureState> = _state

        private var job: Job? = null

        /** Finding the profile's current avatar on the server, while the picker is open */
        private var matchJob: Job? = null

        /** Checked right before every save or remove (review round 2); null: always allowed */
        private var mayChange: (suspend () -> Boolean)? = null

        fun start(
            target: ProfilePictureTarget,
            mayChange: (suspend () -> Boolean)? = null,
        ) {
            this.mayChange = mayChange
            val avatars = service.avatars(target.isKid)
            // The TV's remembered avatar is only a hint (this TV's own saves); the last result found for this profile
            // shows straight away, and the server's picture decides once it is compared
            val hint = service.chosenAvatar(target.userId)
            _state.value =
                ProfilePictureState(
                    target = target,
                    avatars = avatars,
                    current = finder.lastKnown(target.userId).tickOr(hint),
                )
            matchJob?.cancel()
            if (avatars.isEmpty()) return
            matchJob =
                viewModelScope.launch {
                    val found = finder.find(target, avatars)
                    _state.update { state ->
                        when {
                            // Only for the picker it was asked for, and never over a pick made meanwhile
                            state.target != target || state.status != PictureStatus.Idle -> state

                            found == CurrentPicture.Unknown -> state

                            else -> state.copy(current = found.tickOr(hint))
                        }
                    }
                }
        }

        /** The picker closed: stop looking for its current avatar */
        fun stop() {
            matchJob?.cancel()
            matchJob = null
        }

        private fun CurrentPicture?.tickOr(hint: String?): String? =
            when (this) {
                is CurrentPicture.Bundled -> id
                CurrentPicture.NotBundled -> null
                CurrentPicture.Unknown, null -> hint
            }

        /** Settings: the signed-in profile changes its own picture. */
        fun startForCurrentUser() {
            val current = serverRepository.current.value ?: return
            val token = current.user.accessToken ?: return
            start(
                ProfilePictureTarget(
                    current.server.url,
                    current.user.id,
                    token,
                    current.user.name,
                    isKid = current.user.profileKind != ProfileKind.ADULT,
                ),
            )
        }

        fun pick(avatar: Avatar) = run { service.save(it, avatar) to avatar.id }

        fun remove() = run { service.remove(it) to null }

        fun clearResult() = _state.update { it.copy(status = PictureStatus.Idle) }

        private fun run(action: suspend (ProfilePictureTarget) -> Pair<ProfilePictureResult, String?>) {
            val target = _state.value.target ?: return
            if (job?.isActive == true) return
            _state.update { it.copy(status = PictureStatus.Saving) }
            job =
                viewModelScope.launch {
                    if (mayChange?.invoke() == false) {
                        // What opened the picker no longer allows it: nothing is saved
                        _state.update { it.copy(status = PictureStatus.Refused) }
                        return@launch
                    }
                    val (result, avatarId) = action(target)
                    _state.update {
                        when (result) {
                            ProfilePictureResult.Saved -> it.copy(current = avatarId, status = PictureStatus.Saved)
                            is ProfilePictureResult.Failed -> it.copy(status = PictureStatus.Failed)
                        }
                    }
                }
        }
    }

/** [Refused]: the save-time check said no; nothing changed */
enum class PictureStatus { Idle, Saving, Saved, Failed, Refused }

data class ProfilePictureState(
    val target: ProfilePictureTarget? = null,
    val avatars: List<Avatar> = emptyList(),
    val current: String? = null,
    val status: PictureStatus = PictureStatus.Idle,
)
