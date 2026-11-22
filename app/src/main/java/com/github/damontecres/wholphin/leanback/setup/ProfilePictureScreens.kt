package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.Avatar
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.avatars.PictureStatus
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureState
import com.github.damontecres.wholphin.ui.avatars.ProfilePictureViewModel
import com.github.damontecres.wholphin.ui.setup.FirstAvatarStatus
import com.github.damontecres.wholphin.ui.setup.PendingAvatar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The picker for one profile's picture, driven by its [ProfilePictureViewModel] (`ProfilePictureContent`): "Change
 * picture", the profile's avatar set, "Remove picture"; progress and errors in the footer. A save or remove closes it
 * with "Picture saved"; a refused one (the save-time check said no) closes it with no change.
 */
internal class ProfilePictureBinder(
    private val context: Context,
    private val viewModel: ProfilePictureViewModel,
    private val onDone: () -> Unit,
) {
    val picker: AvatarPickerView =
        AvatarPickerView(
            context = context,
            title = context.getString(R.string.change_picture),
            subtitle = viewModel.state.value.target?.name?.let { context.getString(R.string.pick_a_picture_for, it) },
            onPick = { viewModel.pick(it) },
            onRemove = { viewModel.remove() },
        )
    private var shownStatus: PictureStatus? = null

    /** Follows the view model in [scope] until it is cancelled */
    fun collectIn(scope: CoroutineScope): Job = scope.launch { viewModel.state.collect { render(it) } }

    private fun render(state: ProfilePictureState) {
        picker.bind(state.avatars, state.current, enabled = state.status != PictureStatus.Saving)
        if (state.status != shownStatus) {
            shownStatus = state.status
            picker.footer.removeAllViews()
            when (state.status) {
                PictureStatus.Saving -> {
                    picker.footer.addView(context.kidSpinner(28f, 3f).apply { layoutParams = frame(context.dp(28), context.dp(28), Gravity.CENTER) })
                }

                PictureStatus.Failed -> {
                    picker.footer.addView(
                        context.kidErrorText(context.getString(R.string.picture_save_failed), maxLines = 2),
                        frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                    )
                }

                else -> {}
            }
        }
        when (state.status) {
            PictureStatus.Saved -> {
                Toast.makeText(context, context.getString(R.string.picture_saved), Toast.LENGTH_SHORT).show()
                viewModel.clearResult()
                onDone()
            }

            PictureStatus.Refused -> {
                viewModel.clearResult()
                onDone()
            }

            else -> {}
        }
    }
}

/**
 * Change or remove [target]'s picture right away, in a dialog (chooser menu; `ProfilePictureDialog`). [mayChange] runs
 * right before every save or remove; false refuses it and closes the dialog with no change. The view model is one per
 * profile in [owner] (the chooser), as `hiltViewModel(key = "picture-<id>")` was.
 */
internal class ProfilePictureDialog(
    owner: Fragment,
    target: ProfilePictureTarget,
    onDismissRequest: () -> Unit,
    mayChange: (suspend () -> Boolean)? = null,
) : KidPanelDialog(owner.requireContext(), 750f, onDismissRequest = onDismissRequest) {
    private val viewModel =
        ViewModelProvider(owner.viewModelStore, owner.defaultViewModelProviderFactory, owner.defaultViewModelCreationExtras)[
            "picture-${target.userId}",
            ProfilePictureViewModel::class.java,
        ]

    init {
        viewModel.start(target, mayChange)
        val binder = ProfilePictureBinder(context, viewModel) { this.onDismissRequest() }
        content.addView(binder.picker, matchWidth())
        binder.collectIn(scope)
        setOnDismissListener { viewModel.stop() }
    }
}

/**
 * An adult's first sign-in on a TV without a picture (contract 11.1; `FirstAvatarDialog`): they choose one from the
 * adult list before going on. There is no skip; Back leaves the sign-in. If the upload fails it says so and offers "Try
 * again" and "Continue without a picture".
 */
internal class FirstAvatarDialog(
    context: Context,
    private val avatars: List<Avatar>,
    private val onPick: (Avatar) -> Unit,
    private val onRetry: () -> Unit,
    private val onContinue: () -> Unit,
    onDismissRequest: () -> Unit,
) : KidPanelDialog(context, 750f, onDismissRequest = onDismissRequest) {
    private var failedShown: Boolean? = null
    private var picker: AvatarPickerView? = null

    fun update(pending: PendingAvatar) {
        val name = pending.target.name.orEmpty()
        val title = if (name.isBlank()) context.getString(R.string.pick_a_picture) else context.getString(R.string.first_avatar_title, name)
        val failed = pending.status == FirstAvatarStatus.FAILED
        if (failed != failedShown) {
            failedShown = failed
            val content = reset()
            if (failed) {
                picker = null
                content.addView(context.kidTitle(title), wrap(Gravity.CENTER_HORIZONTAL))
                content.addView(context.gap(14f))
                content.addView(context.kidErrorText(context.getString(R.string.first_avatar_failed)), wrap(Gravity.CENTER_HORIZONTAL))
                content.addView(context.gap(28f))
                val retry = context.kidButton(context.getString(R.string.try_again)) { onRetry() }
                content.addView(
                    horizontal(context).apply {
                        addView(retry)
                        addView(context.hGap(16f))
                        addView(context.kidButton(context.getString(R.string.continue_without_picture), arrow = true) { onContinue() })
                    },
                    wrap(Gravity.CENTER_HORIZONTAL),
                )
                content.addView(context.gap(24f))
                content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
                initialFocus = retry
                if (isShowing) retry.requestFocus()
            } else {
                val view =
                    AvatarPickerView(
                        context = context,
                        title = title,
                        subtitle = context.getString(R.string.first_avatar_subtitle),
                        onPick = onPick,
                    )
                picker = view
                content.addView(view, matchWidth())
            }
        }
        picker?.let { view ->
            view.bind(avatars, pending.chosen?.id, enabled = pending.status != FirstAvatarStatus.SAVING)
            view.footer.removeAllViews()
            if (pending.status == FirstAvatarStatus.SAVING) {
                view.footer.addView(context.kidSpinner(28f, 3f).apply { layoutParams = frame(context.dp(28), context.dp(28), Gravity.CENTER) })
            }
        }
    }
}

/** Settings: the signed-in profile's picture, as a full page (`ProfilePicturePage`); done goes back */
@AndroidEntryPoint
class ProfilePictureFragment : DestinationFragment() {
    @Inject
    lateinit var navigationManager: NavigationManager

    private val viewModel: ProfilePictureViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        viewModel.startForCurrentUser()
        val root =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(AppTheme.palette.background)
            }
        val panel = KidPanelView(context, scrollable = true)
        val binder = ProfilePictureBinder(context, viewModel) { navigationManager.goBack() }
        panel.content.addView(binder.picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(panel, frame(context.dp(750), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.tag = binder
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        (view.tag as ProfilePictureBinder).collectIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onScreenFocus(restoring: Boolean) {
        // First time the picker focuses the current avatar itself, once it is laid out
        if (restoring) super.onScreenFocus(true)
    }

    override fun onDestroyView() {
        viewModel.stop()
        super.onDestroyView()
    }
}
