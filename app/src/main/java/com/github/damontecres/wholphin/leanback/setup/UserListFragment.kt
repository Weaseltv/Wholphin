package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.kids.profileKind
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.card.AvatarImages
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.profilePicturesEnabled
import com.github.damontecres.wholphin.services.versionedUserImageUrl
import com.github.damontecres.wholphin.ui.kids.AddKidViewModel
import com.github.damontecres.wholphin.ui.kids.KidProfilesViewModel
import com.github.damontecres.wholphin.ui.setup.ChooserPrompt
import com.github.damontecres.wholphin.ui.setup.JellyfinUserAndImage
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.PinPurpose
import com.github.damontecres.wholphin.ui.setup.ServerVersionSupported
import com.github.damontecres.wholphin.ui.setup.SwitchUserResult
import com.github.damontecres.wholphin.ui.setup.SwitchUserState
import com.github.damontecres.wholphin.ui.setup.SwitchUserViewModel
import com.github.damontecres.wholphin.ui.setup.isServerPinned
import com.github.damontecres.wholphin.ui.setup.quickConnectApproveUrl
import com.github.damontecres.wholphin.ui.setup.weaselPlexConnectOnly
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The profile chooser (`SwitchUserContent` + `UserList`): the brand and "SELECT USER" over a centred row of profile tiles
 * (pictures, kid badges), "Add kid profile" and "Add user", "Switch servers" where allowed and the server version
 * warning. OK switches profile through the kid gate (PIN prompts, parent unlocks, "needs a PIN"); a long press opens the
 * profile menu (switch, change picture / PIN, remove PIN, delete or remove from this TV). The sign-in dialog does Quick
 * Connect (with the website's QR code) or username and password; WeaselPlex builds with no profile yet show "Connect your
 * account" instead. Every dialog and view model call is the old one.
 */
@AndroidEntryPoint
class UserListFragment : SetupFragment() {
    private val server: JellyfinServer by lazy {
        Json.decodeFromString(JellyfinServer.serializer(), requireArguments().getString(ARG_SERVER)!!)
    }

    private val viewModel: SwitchUserViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<SwitchUserViewModel.Factory> { it.create(server) }
        },
    )
    private val kidViewModel: KidProfilesViewModel by viewModels()
    private val addKidViewModel: AddKidViewModel by viewModels()
    private val errorViewModel: SetupErrorViewModel by viewModels()

    private val connectOnly = weaselPlexConnectOnly()

    // The old remembered UI state
    private var state = SwitchUserState()
    private var currentUser: JellyfinUser? = null
    private var kidSession = false
    private var pictureVersions: Map<UUID, Int> = emptyMap()

    // Review round 1: a pinless adult's picture needs a parent's PIN on a protected TV (assumed protected until known)
    private var deviceProtected = true
    private var showAddKid = false
    private var changePictureFor: JellyfinUser? = null
    private var changePinFor: JellyfinUser? = null
    private var removePinFor: JellyfinUser? = null
    private var showAddUser = false
    private var addUser: JellyfinUser? = null
    private var username = ""
    private var switchUserWithPin: JellyfinUser? = null
    private var lastSwitchUserState: LoadingState? = null

    // Views
    private lateinit var root: FrameLayout
    private var pageKind: Int = -1
    private var shownError: LoadingState.Error? = null
    private var chooser: ChooserPage? = null
    private var connect: ConnectAccountView? = null

    // Dialogs and flows
    private var pinSetupFlow: SetPinFlow? = null
    private var firstAvatarDialog: FirstAvatarDialog? = null
    private var pinCheckDialog: KidPanelDialog? = null
    private var pinCheckChecking: Boolean? = null
    private var addUserDialog: AddUserDialog? = null
    private var promptDialog: SetupDialog? = null
    private var shownPrompt: ChooserPrompt? = null
    private var addKidFlow: AddKidFlowUi? = null
    private var pictureFlow: ChooserFlowUi? = null
    private var pinFlow: ChooserFlowUi? = null
    private var removePinFlow: ChooserFlowUi? = null
    private var switchPinDialog: PinEntryDialog? = null
    private var profileMenu: MenuDialog? = null

    private val savedUsers: List<JellyfinUser> get() = state.users.filter { it.known }.map { it.user }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(AppTheme.palette.background)
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        pageKind = -1
        viewModel.init()
        viewModel.state.collectWhileStarted { onState(it) }
        viewModel.serverRepository.currentUserFlow.collectWhileStarted {
            currentUser = it
            chooser?.bindUsers()
        }
        combine(kidViewModel.isKidSession, kidViewModel.pictureVersions) { a, b -> a to b }.collectWhileStarted { (kid, versions) ->
            kidSession = kid
            pictureVersions = versions
            refresh()
        }
        kidViewModel.gate.isDeviceProtected.collectWhileStarted { deviceProtected = it }
    }

    override fun onDestroyView() {
        listOfNotNull(addKidFlow, pictureFlow, pinFlow, removePinFlow).forEach { it.dismiss() }
        addKidFlow = null
        pictureFlow = null
        pinFlow = null
        removePinFlow = null
        pinSetupFlow?.dismiss()
        pinSetupFlow = null
        listOfNotNull(firstAvatarDialog, pinCheckDialog, addUserDialog, promptDialog, switchPinDialog, profileMenu).forEach { it.dismiss() }
        firstAvatarDialog = null
        pinCheckDialog = null
        pinCheckChecking = null
        addUserDialog = null
        promptDialog = null
        shownPrompt = null
        switchPinDialog = null
        profileMenu = null
        connect?.leave()
        connect = null
        chooser = null
        super.onDestroyView()
    }

    private fun onState(new: SwitchUserState) {
        state = new
        if (new.switchUserState != lastSwitchUserState) {
            lastSwitchUserState = new.switchUserState
            if (!showAddUser) {
                (new.switchUserState as? LoadingState.Error)?.let { s ->
                    val msg = s.message ?: s.exception?.localizedMessage
                    Toast.makeText(requireContext(), "Error: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
        // Kid profiles: a gated switch that failed with the server (eg expired token)
        new.switchError?.let { error ->
            viewModel.clearSwitchError()
            Toast.makeText(requireContext(), error.errorMessage, Toast.LENGTH_LONG).show()
            if (error.showLogin && error.user != null) showAddUserDialog(error.user)
        }
        refresh()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Actions (the old lambdas)
    // ---------------------------------------------------------------------------------------------------------------

    private fun showAddUserDialog(user: JellyfinUser?) {
        // Kid profiles: a pick still in progress never publishes or navigates behind Add User
        viewModel.leaveForOtherAction()
        if (user != addUser) username = user?.name ?: ""
        addUser = user
        showAddUser = true
        refresh()
    }

    private fun hideAddUserDialog() {
        // The typed username is kept per profile being signed in (`remember(addUser)`)
        if (addUser != null) username = ""
        addUser = null
        showAddUser = false
        refresh()
    }

    private fun trySwitchUser(user: JellyfinUser) {
        val result = viewModel.trySwitchUser(user)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val r = result.await()) {
                is SwitchUserResult.Error -> {
                    Toast.makeText(requireContext(), r.errorMessage, Toast.LENGTH_LONG).show()
                    if (r.showLogin) showAddUserDialog(user)
                }

                SwitchUserResult.Success -> {} // the view model navigates
            }
        }
    }

    private fun onSwitchUser(user: JellyfinUser) {
        when {
            user.accessToken == null || user.requireLogin -> {
                showAddUserDialog(user)
            }

            BuildConfig.KID_PROFILES_ENABLED -> {
                viewModel.onProfileSelected(user)
            }

            user.hasPin -> {
                switchUserWithPin = user
                refresh()
            }

            else -> {
                trySwitchUser(user)
            }
        }
    }

    private val kidProfilesOn get() = BuildConfig.KID_PROFILES_ENABLED

    private fun canAddUser(): Boolean = !(kidSession && kidProfilesOn)

    private fun canSwitchServer(): Boolean = !(connectOnly || isServerPinned || (kidSession && kidProfilesOn))

    private fun canAddKid(): Boolean = kidViewModel.canAddKid(savedUsers, kidSession)

    private fun imageUrl(user: JellyfinUserAndImage): String? = versionedUserImageUrl(user.imageUrl, user.user.id, pictureVersions)

    // ---------------------------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------------------------

    private fun refresh() {
        if (view == null) return
        renderPage()
        syncDialogs()
    }

    private fun renderPage() {
        val context = requireContext()
        val kind =
            when (state.loading) {
                is LoadingState.Error -> 2
                LoadingState.Loading, LoadingState.Pending -> 0
                LoadingState.Success -> if (connectOnly && state.users.isEmpty()) 3 else 1
            }
        val error = state.loading as? LoadingState.Error
        if (kind != pageKind || (kind == 2 && error != shownError)) {
            pageKind = kind
            shownError = error
            connect?.leave()
            connect = null
            chooser = null
            root.removeAllViews()
            when (kind) {
                2 -> {
                    // It also loads again by itself when the network comes back
                    root.addView(
                        context.errorMessageView(
                            error?.message,
                            error?.exception,
                            { errorViewModel.sendLogs() },
                            onRetry = { viewModel.loadUsers() },
                        ),
                        frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }

                0 -> {
                    val loading = SetupLoadingView(context)
                    root.addView(loading, frame())
                    loading.requestFocus()
                }

                3 -> {
                    val c = ConnectAccountView(context)
                    connect = c
                    root.addView(c, frame())
                }

                1 -> {
                    val c = ChooserPage(context)
                    chooser = c
                    root.addView(c.view, frame())
                }
            }
        }
        chooser?.bind()
        connect?.bind()
    }

    private fun syncDialogs() {
        val context = requireContext()
        // Kid profiles: an adult signing in on a protected device sets a PIN before anything is published
        val pinSetup = state.pendingPinSetup
        if (pinSetup != null && pinSetupFlow == null) {
            pinSetupFlow =
                SetPinFlow(
                    context = context,
                    currentPin = null,
                    onAddPin = { viewModel.completePinSetup(it) },
                    onRemovePin = {},
                    onDismissRequest = {
                        viewModel.cancelPinSetup()
                        hideAddUserDialog()
                    },
                ).also { it.show() }
        } else if (pinSetup == null && pinSetupFlow != null) {
            pinSetupFlow?.dismiss()
            pinSetupFlow = null
        }

        // Contract 11.1: an adult signing in without a picture chooses one before anything is published
        val pending = state.pendingAvatar
        if (pending != null) {
            val dialog =
                firstAvatarDialog ?: FirstAvatarDialog(
                    context,
                    avatars = viewModel.firstAvatarChoices,
                    onPick = viewModel::chooseFirstAvatar,
                    onRetry = viewModel::retryFirstAvatar,
                    onContinue = viewModel::continueWithoutAvatar,
                    onDismissRequest = {
                        viewModel.cancelFirstAvatar()
                        hideAddUserDialog()
                    },
                ).also {
                    firstAvatarDialog = it
                    it.show()
                }
            dialog.update(pending)
        } else {
            firstAvatarDialog?.dismiss()
            firstAvatarDialog = null
        }

        // Review round 1: on a protected TV a sign-in waits until its account PIN can be read
        val pinCheck = state.pendingPinCheck
        if (pinCheck != null) {
            val dialog =
                pinCheckDialog ?: KidPanelDialog(
                    context,
                    620f,
                    onDismissRequest = {
                        viewModel.cancelPinCheck()
                        hideAddUserDialog()
                    },
                ).also {
                    pinCheckDialog = it
                    it.show()
                }
            if (pinCheck.checking != pinCheckChecking) {
                pinCheckChecking = pinCheck.checking
                val content = dialog.reset()
                content.addView(
                    context.kidTitle(pinCheck.authenticationResult.user?.name ?: context.getString(R.string.enter_pin)),
                    wrap(Gravity.CENTER_HORIZONTAL),
                )
                content.addView(context.gap(14f))
                content.addView(context.kidErrorText(context.getString(R.string.pin_check_failed)), wrap(Gravity.CENTER_HORIZONTAL))
                content.addView(context.gap(24f))
                if (pinCheck.checking) {
                    content.addView(context.kidSpinner(40f, 4f))
                } else {
                    val retry = context.kidButton(context.getString(R.string.try_again)) { viewModel.retryPinCheck() }
                    content.addView(retry, KidButtonView.params(context).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    dialog.initialFocus = retry
                    retry.requestFocus()
                }
                content.addView(context.gap(24f))
                content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
            }
        } else {
            pinCheckDialog?.dismiss()
            pinCheckDialog = null
            pinCheckChecking = null
        }

        // The sign-in dialog, while no held-back sign-in step shows
        val addUserVisible = showAddUser && pinSetup == null && pending == null && pinCheck == null
        if (addUserVisible) {
            val dialog =
                addUserDialog ?: AddUserDialog(context).also {
                    addUserDialog = it
                    it.show()
                }
            dialog.bind(state)
        } else if (addUserDialog != null) {
            addUserDialog?.dismiss()
            addUserDialog = null
        }

        syncPrompt(state.prompt)

        if (showAddKid && addKidFlow == null) {
            addKidFlow =
                AddKidFlowUi(
                    fragment = this,
                    server = server,
                    users = savedUsers,
                    imageUrls = state.users.associate { it.user.id to imageUrl(it) },
                    viewModel = addKidViewModel,
                    onClose = {
                        showAddKid = false
                        addKidFlow?.dismiss()
                        addKidFlow = null
                        // Contract 12.4: adults may have set a PIN or left the TV in the flow
                        viewModel.reloadUsers()
                        refresh()
                    },
                )
        }

        val pictureUser = changePictureFor
        if (pictureUser != null && pictureFlow == null) {
            pictureFlow =
                ChangePictureFlow(
                    fragment = this,
                    server = server,
                    user = pictureUser,
                    gate = kidViewModel.gate,
                    onDone = {
                        changePictureFor = null
                        pictureFlow?.dismiss()
                        pictureFlow = null
                        refresh()
                    },
                    savedUsers = savedUsers,
                    refresh = kidViewModel::refreshed,
                )
        }
        val pinUser = changePinFor
        if (pinUser != null && pinFlow == null) {
            pinFlow =
                ChangePinFlow(
                    fragment = this,
                    user = pinUser,
                    gate = kidViewModel.gate,
                    refresh = kidViewModel::refreshed,
                    changePin = kidViewModel::changePin,
                    onDone = {
                        changePinFor = null
                        pinFlow?.dismiss()
                        pinFlow = null
                        // The list shows the saved PIN rows again
                        viewModel.reloadUsers()
                        refresh()
                    },
                )
        }
        val removeUser = removePinFor
        if (removeUser != null && removePinFlow == null) {
            removePinFlow =
                RemovePinFlow(
                    fragment = this,
                    user = removeUser,
                    gate = kidViewModel.gate,
                    refresh = kidViewModel::refreshed,
                    kidsOnTv = kidViewModel::kidsOnTv,
                    removePin = kidViewModel::removePin,
                    onDone = {
                        removePinFor = null
                        removePinFlow?.dismiss()
                        removePinFlow = null
                        viewModel.reloadUsers()
                        refresh()
                    },
                )
        }

        val pinUserToSwitch = switchUserWithPin
        if (pinUserToSwitch != null && switchPinDialog == null) {
            switchPinDialog =
                PinEntryDialog(
                    context,
                    onDismissRequest = {
                        switchUserWithPin = null
                        refresh()
                    },
                    config =
                        PinEntryConfig(
                            onClickServerAuth = {
                                showAddUserDialog(pinUserToSwitch)
                                switchUserWithPin = null
                                refresh()
                            },
                            length = PinRules.entryLength(pinUserToSwitch.pin),
                            prompt = context.pinPromptFor(pinUserToSwitch.shownName),
                            onSubmit = {
                                if (it == pinUserToSwitch.pin) {
                                    // The PIN prompt is done: a switch that then fails never leaves it frozen underneath
                                    switchUserWithPin = null
                                    refresh()
                                    trySwitchUser(pinUserToSwitch)
                                    PinOutcome.OTHER
                                } else {
                                    Toast.makeText(context, context.getString(R.string.incorrect), Toast.LENGTH_SHORT).show()
                                    PinOutcome.WRONG
                                }
                            },
                        ),
                ).also { it.show() }
        } else if (pinUserToSwitch == null && switchPinDialog != null) {
            switchPinDialog?.dismiss()
            switchPinDialog = null
        }

        chooser?.updateDim()
    }

    /** The kid gate's prompt: a PIN, "needs a PIN", or which parent unlocks */
    private fun syncPrompt(prompt: ChooserPrompt?) {
        val context = requireContext()
        val old = shownPrompt
        shownPrompt = prompt
        when (prompt) {
            null -> {
                promptDialog?.dismiss()
                promptDialog = null
            }

            is ChooserPrompt.Pin -> {
                val config =
                    PinEntryConfig(
                        onClickServerAuth = {
                            viewModel.dismissPrompt()
                            showAddUserDialog(prompt.target)
                        },
                        // Latched until the check (and what it starts) is done
                        onSubmit = {
                            var wrong = false
                            viewModel.submitPin(it) { wrong = true }?.join()
                            if (wrong) PinOutcome.WRONG else PinOutcome.OTHER
                        },
                        length = PinRules.entryLength(prompt.pinOwner.pin),
                        lockedUntilMillis = prompt.lockedUntilMillis,
                        title =
                            when (prompt.purpose) {
                                PinPurpose.SWITCH -> {
                                    prompt.target.shownName ?: context.getString(R.string.enter_pin)
                                }

                                PinPurpose.DELETE -> {
                                    context.getString(
                                        if (prompt.target.profileKind == ProfileKind.KID) R.string.remove_from_this_tv else R.string.delete,
                                    )
                                }
                            },
                        prompt = context.pinPromptFor(prompt.pinOwner.shownName),
                        message = prompt.message,
                        serverAuthRemovesPin = false,
                    )
                val dialog = promptDialog as? PinEntryDialog
                if (dialog != null && old is ChooserPrompt.Pin && old.id == prompt.id) {
                    // A wrong try (a copy with a message) keeps the same entry, still latched
                    dialog.entry?.message = prompt.message
                    dialog.entry?.lockedUntilMillis = prompt.lockedUntilMillis
                } else if (dialog != null) {
                    // A replacement prompt (eg the parent's PIN after a kid's own) gets a fresh entry
                    dialog.onDismissRequest = viewModel::dismissPrompt
                    dialog.setEntry(config)
                } else {
                    promptDialog?.dismiss()
                    promptDialog = PinEntryDialog(context, viewModel::dismissPrompt, config).also { it.show() }
                }
            }

            is ChooserPrompt.NeedsPin -> {
                val dialog =
                    (promptDialog as? KidPanelDialog)?.takeIf { old is ChooserPrompt.NeedsPin && promptDialog !is PinEntryDialog }
                        ?: KidPanelDialog(context, 620f, onDismissRequest = {}).also {
                            promptDialog?.dismiss()
                            promptDialog = it
                            it.show()
                        }
                if (old !is ChooserPrompt.NeedsPin || old.confirmRemove != prompt.confirmRemove || dialog.content.childCount == 0) {
                    buildNeedsPin(dialog, prompt)
                }
            }

            is ChooserPrompt.PickParent -> {
                if (old != prompt || promptDialog == null) {
                    promptDialog?.dismiss()
                    promptDialog = pickParentDialog(context, prompt).also { it.show() }
                }
            }
        }
    }

    /**
     * Contract 12.4 (v1.7): "<Name> needs a PIN" for a pinless adult on a TV with kids, with "Create a PIN" and "Remove
     * from this TV" (which asks first). Back closes it with no change; Back on the question goes back to the panel.
     */
    private fun buildNeedsPin(
        dialog: KidPanelDialog,
        prompt: ChooserPrompt.NeedsPin,
    ) {
        val context = dialog.context
        val name = prompt.target.shownName.orEmpty()
        dialog.onDismissRequest = if (prompt.confirmRemove) viewModel::cancelRemoveNeedsPin else viewModel::dismissPrompt
        val content = dialog.reset()
        val first: View
        if (prompt.confirmRemove) {
            content.addView(context.kidTitle(context.getString(R.string.remove_from_tv_question, name)), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(28f))
            first = context.kidButton(context.getString(R.string.remove), minWidthDp = 180f) { viewModel.confirmRemoveNeedsPin() }
            content.addView(
                horizontal(context).apply {
                    addView(first)
                    addView(context.hGap(16f))
                    addView(context.kidButton(context.getString(R.string.cancel), minWidthDp = 180f) { viewModel.cancelRemoveNeedsPin() })
                },
                wrap(Gravity.CENTER_HORIZONTAL),
            )
        } else {
            content.addView(context.kidTitle(context.getString(R.string.needs_a_pin, name)), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(10f))
            content.addView(context.kidSubtitle(context.getString(R.string.needs_a_pin_subtitle)), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(24f))
            first =
                context.kidListRow(context.getString(R.string.create_a_pin), context.getString(R.string.fa_user)) {
                    // Contract 12.4 (v1.7): only the profile's owner sets its PIN, so it signs in again first; that
                    // sign-in asks for the new PIN (protected TV, no PIN) and then goes in
                    viewModel.dismissPrompt()
                    showAddUserDialog(prompt.target)
                }
            content.addView(first)
            content.addView(context.gap(12f))
            content.addView(
                context.kidListRow(context.getString(R.string.remove_from_this_tv), context.getString(R.string.fa_xmark)) {
                    viewModel.askRemoveNeedsPin()
                },
            )
        }
        content.addView(context.gap(24f))
        content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
        dialog.initialFocus = first
        first.requestFocus()
    }

    /** Pick which parent unlocks [prompt]'s profile, or sign it in with Quick Connect */
    private fun pickParentDialog(
        context: Context,
        prompt: ChooserPrompt.PickParent,
    ): KidPanelDialog =
        KidPanelDialog(context, 620f, onDismissRequest = viewModel::dismissPrompt).apply {
            val targetName = prompt.target.shownName.orEmpty()
            content.addView(context.kidTitle(targetName), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(10f))
            content.addView(
                context.kidSubtitle(
                    context.getString(if (prompt.locked) R.string.kid_profile_locked else R.string.kid_ask_parent, targetName),
                ),
                wrap(Gravity.CENTER_HORIZONTAL),
            )
            content.addView(context.gap(24f))
            var first: View? = null
            prompt.parents.forEach { parent ->
                if (first != null) content.addView(context.gap(12f))
                val row =
                    context.kidListRow(
                        context.getString(R.string.kid_parent_pin_title, parent.shownName.orEmpty()),
                        context.getString(R.string.fa_user),
                    ) {
                        viewModel.pickParent(parent)
                    }
                content.addView(row)
                if (first == null) first = row
            }
            if (prompt.purpose == PinPurpose.SWITCH) {
                if (first != null) content.addView(context.gap(12f))
                val row =
                    context.kidListRow(
                        context.getString(R.string.kid_sign_in_quick_connect, targetName),
                        context.getString(R.string.fa_arrow_left_arrow_right),
                    ) {
                        viewModel.dismissPrompt()
                        showAddUserDialog(prompt.target)
                    }
                content.addView(row)
                if (first == null) first = row
            }
            content.addView(context.gap(24f))
            content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
            initialFocus = first
        }

    /** The long-press profile menu (`UserList`'s dialog) */
    private fun showProfileMenu(user: JellyfinUserAndImage) {
        val context = requireContext()
        val u = user.user
        val items =
            buildList {
                add(
                    MenuItem(
                        context.getString(R.string.switch_user),
                        glyph = context.getString(R.string.fa_arrow_left_arrow_right),
                    ) { onSwitchUser(u) },
                )
                // Contract 11: every saved profile with its own token (the change is saved with it)
                if (profilePicturesEnabled && user.known && u.accessToken != null &&
                    kidViewModel.canChangePicture(u, savedUsers, deviceProtected)
                ) {
                    add(
                        MenuItem(context.getString(R.string.change_picture), icon = R.drawable.lb_setup_ic_create) {
                            if (kidViewModel.canChangePicture(u, savedUsers, deviceProtected)) {
                                // A pick still in progress never publishes or navigates behind it
                                viewModel.leaveForOtherAction()
                                changePictureFor = u
                                refresh()
                            }
                        },
                    )
                }
                if (kidProfilesOn && user.known && u.hasPin && u.accessToken != null) {
                    add(
                        MenuItem(context.getString(R.string.change_pin), icon = R.drawable.lb_setup_ic_lock) {
                            if (kidViewModel.canChangePin(u)) {
                                viewModel.leaveForOtherAction()
                                changePinFor = u
                                refresh()
                            }
                        },
                    )
                }
                // Owner 2026-10-25: adults with a PIN; on a TV with kids it says why it can't
                if (kidProfilesOn && user.known && kidViewModel.canRemovePin(u)) {
                    add(
                        MenuItem(context.getString(R.string.remove_pin), icon = R.drawable.lb_setup_ic_lock) {
                            if (kidViewModel.canRemovePin(u)) {
                                viewModel.leaveForOtherAction()
                                removePinFor = u
                                refresh()
                            }
                        },
                    )
                }
                if (user.known) {
                    add(
                        MenuItem(
                            // A kid's row is only this TV's sign-in; the kid stays on the account
                            context.getString(if (kidViewModel.isKid(u)) R.string.remove_from_this_tv else R.string.delete),
                            icon = R.drawable.lb_setup_ic_delete,
                            iconTint = withAlpha(0xFFFF0000.toInt(), .8f),
                        ) { viewModel.removeUser(u) },
                    )
                }
            }
        profileMenu =
            track(
                MenuDialog(
                    context,
                    title = u.name ?: u.id.toServerString(),
                    items = items,
                    onDismissRequest = {
                        close(profileMenu)
                        profileMenu = null
                    },
                    dismissOnClick = true,
                    waitToLoad = true,
                ),
            )
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The chooser page
    // ---------------------------------------------------------------------------------------------------------------

    private inner class ChooserPage(
        context: Context,
    ) {
        val view = FrameLayout(context).apply { noClip() }
        private val page = FrameLayout(context).apply { noClip() }
        private val row = TileRow(context, 24f)
        private val adapter = UserAdapter()
        private val switchServers =
            SetupButton(
                context,
                context.getString(R.string.switch_servers),
                context.getString(R.string.fa_arrow_left_arrow_right),
                textButton = false,
            ).apply {
                setOnClickListener { viewModel.setupNavigationManager.navigateTo(SetupDestination.ServerList) }
            }
        private val switchServersBox = FrameLayout(context).apply { noClip() }
        private val versionText =
            context.setupText(
                color = AppTheme.palette.error,
                gravity = Gravity.CENTER,
                maxLines = 2,
            ) { applyTextRole(TextRole.BODY_MEDIUM) }
        private var focusedOnce = false

        init {
            val column =
                vertical(context).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                }
            val header = vertical(context).apply { gravity = Gravity.CENTER_HORIZONTAL }

            fun addHeader(
                v: View,
                params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
            ) {
                if (header.childCount > 0) params.topMargin = context.dp(8)
                header.addView(v, params)
            }
            if (AppTheme.isNeon) {
                // Neon Board (T9): mascot + wordmark, then the title on a volt rule (owner mockup, 2026-10-22)
                addHeader(context.neonBrandRow())
                addHeader(
                    context.setupText(context.getString(R.string.select_user).uppercase(), gravity = Gravity.CENTER) {
                        neonHeader(40f, TextRole.HEADLINE_SMALL)
                    },
                )
                addHeader(NeonRuleView(context), LinearLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                addHeader(
                    context.setupText(
                        context.getString(R.string.select_user),
                        gravity = Gravity.CENTER,
                    ) { applyTextRole(TextRole.DISPLAY_SMALL) },
                )
                addHeader(context.setupText(server.name ?: server.url, gravity = Gravity.CENTER) { applyTextRole(TextRole.TITLE_LARGE) })
            }
            column.addView(header, wrap(Gravity.CENTER_HORIZONTAL))
            // The user list: the tile row, then Switch servers
            row.adapter = adapter
            row.setPadding(context.dp(48), context.dp(32), context.dp(48), context.dp(32))
            val rowBox = FrameLayout(context).apply { noClip() }
            rowBox.addView(row, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            column.addView(rowBox, matchWidth().apply { topMargin = context.dp(24) })
            switchServersBox.setPadding(0, context.dp(8), 0, context.dp(8))
            switchServersBox.addView(switchServers, frame(context.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            column.addView(switchServersBox, matchWidth().apply { topMargin = context.dp(24) })
            page.addView(column, frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            view.addView(page, frame())
            versionText.setPadding(context.dp(32), 0, context.dp(32), context.dp(32))
            view.addView(versionText, frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        fun bind() {
            bindUsers()
            switchServersBox.visibility = if (canSwitchServer()) View.VISIBLE else View.GONE
            val supported = if (state.serverVersionPending) ServerVersionSupported.SUPPORTED else state.serverVersionSupported
            versionText.visibility = if (supported == ServerVersionSupported.SUPPORTED) View.GONE else View.VISIBLE
            versionText.text =
                if (!state.serverVersion.isNullOrBlank()) {
                    getString(R.string.server_version_not_supported) + ": ${state.serverVersion}"
                } else {
                    getString(R.string.server_version_not_supported) + ": " + getString(R.string.unknown)
                }
            if (!focusedOnce) {
                focusedOnce = true
                // The first profile (Add user when there is none) once the row is laid out
                row.resetFocusMemory()
                row.focusWhenLaidOut()
            }
        }

        fun bindUsers() {
            adapter.submit(
                users = state.users,
                addKid = canAddKid(),
                addUser = canAddUser(),
            )
        }

        fun updateDim() = page.dimAndBlur(showAddUser || switchUserWithPin != null || showAddKid)
    }

    private sealed interface Entry {
        data class User(
            val user: JellyfinUserAndImage,
            val imageUrl: String?,
            val isKid: Boolean,
            val isCurrent: Boolean,
        ) : Entry

        data object AddKid : Entry

        data object AddUser : Entry
    }

    private inner class UserAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Entry> = emptyList()

        fun submit(
            users: List<JellyfinUserAndImage>,
            addKid: Boolean,
            addUser: Boolean,
        ) {
            val list =
                buildList {
                    users.forEach {
                        add(Entry.User(it, imageUrl(it), kidViewModel.isKid(it.user), it.user.id == currentUser?.id))
                    }
                    if (addKid) add(Entry.AddKid)
                    // Add User: always rightmost
                    if (addUser) add(Entry.AddUser)
                }
            val old = items
            items = list
            if (old.size != list.size) {
                notifyDataSetChanged()
            } else {
                list.indices.forEach { if (list[it] != old[it]) notifyItemChanged(it) }
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(
                SetupTileView(parent.context, 0, 0).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            ) {}

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            val tile = holder.itemView as SetupTileView
            when (val entry = items[position]) {
                is Entry.User -> bindUser(tile, entry)
                Entry.AddKid -> bindAdd(tile, R.drawable.lb_setup_ic_face, getString(R.string.add_kid_profile)) { openAddKid() }
                Entry.AddUser -> bindAdd(tile, R.drawable.lb_setup_ic_add, getString(R.string.add_user)) { showAddUserDialog(null) }
            }
        }
    }

    private fun openAddKid() {
        // A pick still in progress never publishes or navigates behind Add kid
        viewModel.leaveForOtherAction()
        showAddKid = true
        refresh()
    }

    private fun bindAdd(
        tile: SetupTileView,
        icon: Int,
        label: String,
        onClick: () -> Unit,
    ) {
        val fill = AppTheme.palette.surfaceVariant
        tile.setFills(withAlpha(fill, .4f), withAlpha(fill, .6f))
        tile.setIcon(icon)
        tile.caption.text = label
        tile.surface.contentDescription = label
        tile.surface.setOnClickListener { onClick() }
        tile.surface.setOnLongClickListener(null)
        tile.surface.isLongClickable = false
    }

    private fun bindUser(
        tile: SetupTileView,
        entry: Entry.User,
    ) {
        val context = tile.context
        val user = entry.user
        val color = user.user.id
        if (entry.isCurrent) {
            tile.setFills(idColor(color, .7f), idColor(color, .9f))
        } else {
            tile.setFills(idColor(color, .5f), idColor(color, .7f))
        }
        tile.surface.removeAllViews()
        val letter =
            context.setupText((user.user.name ?: user.user.id.toString()).firstOrNull()?.uppercase() ?: "?", gravity = Gravity.CENTER) {
                if (AppTheme.isNeon) {
                    // NeonType.numeral(40) ExtraBold
                    typeface = AppFonts.body(context, WEIGHT_BOLD)
                    sizeSp(40f)
                    lineHeightSp(40f * 1.2f)
                } else {
                    boldRole(TextRole.DISPLAY_LARGE)
                }
            }
        tile.surface.addView(letter, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val url = entry.imageUrl
        if (!url.isNullOrBlank()) {
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            tile.surface.addView(image, 0, frame())
            letter.visibility = View.GONE
            AvatarImages.load(image, user.user.id, url) {
                image.visibility = View.GONE
                letter.visibility = View.VISIBLE
            }
        }
        if (entry.isKid) {
            tile.surface.addView(
                KidBadgeView(context),
                frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                },
            )
        }
        tile.caption.text = user.user.name ?: user.user.id.toString()
        tile.surface.contentDescription = user.user.name.orEmpty()
        tile.surface.setOnClickListener { onSwitchUser(user.user) }
        tile.surface.setOnLongClickListener {
            showProfileMenu(user)
            true
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sign-in dialog
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Add user / sign in again: Quick Connect (the website's QR code and the code, or the stock hint) or username and
     * password; "Trouble logging in?" with the debug page after three tries. Opening it clears the last error and
     * attempts and starts Quick Connect when that is the way in.
     */
    private inner class AddUserDialog(
        context: Context,
    ) : BasicDialog(
            context,
            onDismissRequest = {},
            widthFraction = .4f,
            maxHeightDp = 440f,
        ) {
        private var useQuickConnect = state.quickConnectEnabled || connectOnly
        private val modeBox = vertical(context)
        private val errorBox = vertical(context)
        private val troubleBox = vertical(context)
        private var qcKey: Any? = null
        private var shownError: LoadingState? = null
        private var usernameField: SetupEditText? = null
        private var passwordField: SetupEditText? = null
        private var loginButton: SetupButton? = null

        init {
            onDismissRequest = {
                viewModel.cancelQuickConnect()
                hideAddUserDialog()
            }
            content.setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
            content.addView(modeBox, matchWidth())
            troubleBox.addView(
                context.setupText("Trouble logging in?", gravity = Gravity.CENTER),
                wrap(Gravity.CENTER_HORIZONTAL),
            )
            troubleBox.addView(
                context
                    .setupButton(R.string.show_debug_info) {
                        viewModel.navigationManager.navigateTo(com.github.damontecres.wholphin.ui.nav.Destination.Debug)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(8) },
            )
            content.addView(troubleBox, matchWidth().apply { topMargin = context.dp(8) })
            viewModel.clearSwitchUserState()
            viewModel.resetAttempts()
            if (useQuickConnect) viewModel.initiateQuickConnect(server, addUser)
            buildMode()
        }

        private fun buildMode() {
            modeBox.removeAllViews()
            qcKey = null
            usernameField = null
            passwordField = null
            loginButton = null
            if (!useQuickConnect) buildPassword()
            bind(state)
        }

        private fun addTo(
            box: LinearLayout,
            v: View,
            params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
        ) {
            if (box.childCount > 0) params.topMargin = context.dp(8)
            box.addView(v, params)
        }

        private fun buildQuickConnect(s: SwitchUserState) {
            modeBox.removeAllViews()
            val status = s.quickConnectStatus
            if (status == null && s.switchUserState !is LoadingState.Error) {
                addTo(
                    modeBox,
                    horizontal(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(32)
                        addView(context.circularProgress(20f))
                        addView(context.hGap(8f))
                        addView(
                            context.setupText(
                                "Waiting for Quick Connect code...",
                                gravity = Gravity.CENTER,
                            ) { applyTextRole(TextRole.TITLE_MEDIUM) },
                        )
                    },
                )
            } else if (status != null) {
                val approveUrl = quickConnectApproveUrl(status.code)
                if (approveUrl != null) {
                    // WeaselFin: the phone scans this, signs in with the website account and approves the code
                    addTo(
                        modeBox,
                        context.setupText(
                            context.getString(R.string.quick_connect_scan_hint, BuildConfig.QUICK_CONNECT_APPROVE_LABEL),
                            gravity = Gravity.CENTER,
                        ) {
                            applyTextRole(TextRole.TITLE_MEDIUM)
                        },
                        matchWidth(),
                    )
                    addTo(
                        modeBox,
                        context.qrCode(approveUrl),
                        LinearLayout.LayoutParams(context.dp(200), context.dp(200)).apply {
                            gravity =
                                Gravity.CENTER_HORIZONTAL
                        },
                    )
                } else {
                    addTo(
                        modeBox,
                        context.setupText(
                            "Use Quick Connect on your device to authenticate to ${server.name ?: server.url}",
                            gravity = Gravity.CENTER,
                        ) {
                            applyTextRole(TextRole.TITLE_MEDIUM)
                        },
                        matchWidth(),
                    )
                }
                addTo(
                    modeBox,
                    context.setupText(
                        status.code ?: "Failed to get code",
                        gravity = Gravity.CENTER,
                    ) { applyTextRole(TextRole.DISPLAY_MEDIUM) },
                )
            }
            addTo(modeBox, errorBox, matchWidth())
            if (!connectOnly) {
                addTo(
                    modeBox,
                    context.setupButton(R.string.username_or_password) {
                        viewModel.cancelQuickConnect()
                        viewModel.clearSwitchUserState()
                        useQuickConnect = false
                        buildMode()
                    },
                )
            }
        }

        private fun buildPassword() {
            val labelStyle: android.widget.TextView.() -> Unit = { applyTextRole(TextRole.BODY_LARGE) }
            addTo(
                modeBox,
                context.setupText("Enter username/password to login to ${server.name ?: server.url}", gravity = Gravity.CENTER) {
                    applyTextRole(TextRole.TITLE_MEDIUM)
                },
                matchWidth(),
            )
            addTo(modeBox, errorBox, matchWidth())
            val user =
                SetupEditText(context).apply {
                    inputType = SetupEditText.PLAIN
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    setText(username)
                    isInputValid = { state.switchUserState !is LoadingState.Error }
                }
            val password =
                SetupEditText(context).apply {
                    inputType = SetupEditText.PASSWORD
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    isInputValid = { state.switchUserState !is LoadingState.Error }
                }
            user.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                    password.requestFocus()
                    true
                } else {
                    false
                }
            }
            user.addTextChangedListener(
                afterChanged {
                    username = it
                    loginButton?.isEnabled = username.isNotBlank()
                },
            )
            password.addTextChangedListener(afterChanged { viewModel.clearSwitchUserState() })
            val submit = { viewModel.login(server, addUser, username, password.text?.toString().orEmpty()) }
            password.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    submit()
                    true
                } else {
                    false
                }
            }
            addTo(
                modeBox,
                horizontal(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        context
                            .setupText(
                                context.getString(R.string.username),
                                style = labelStyle,
                            ).apply { setPadding(0, 0, context.dp(8), 0) },
                    )
                    addView(user)
                },
            )
            addTo(
                modeBox,
                horizontal(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        context
                            .setupText(
                                context.getString(R.string.password),
                                style = labelStyle,
                            ).apply { setPadding(0, 0, context.dp(8), 0) },
                    )
                    addView(password)
                },
            )
            val login = context.setupButton(R.string.login) { submit() }.apply { isEnabled = username.isNotBlank() }
            addTo(modeBox, login)
            usernameField = user
            passwordField = password
            loginButton = login
            val first = if (username.isBlank()) user else password
            initialFocus = first
            if (isShowing) first.requestFocus()
        }

        fun bind(s: SwitchUserState) {
            if (useQuickConnect) {
                val key = Triple(s.quickConnectStatus?.code, s.quickConnectStatus != null, s.switchUserState is LoadingState.Error)
                if (key != qcKey) {
                    qcKey = key
                    (errorBox.parent as? ViewGroup)?.removeView(errorBox)
                    buildQuickConnect(s)
                }
            }
            if (s.switchUserState != shownError) {
                shownError = s.switchUserState
                fillUserStateError(errorBox, s.switchUserState)
                usernameField?.refreshValidity()
                passwordField?.refreshValidity()
            }
            troubleBox.visibility = if (s.loginAttempts > 2) View.VISIBLE else View.GONE
        }
    }

    /** `UserStateError`: the message, the exception's and its cause's, in the error colour */
    private fun fillUserStateError(
        box: LinearLayout,
        userState: LoadingState,
    ) {
        box.removeAllViews()
        val s = userState as? LoadingState.Error ?: return
        val context = box.context
        val color = AppTheme.palette.error

        fun line(text: String) {
            val params = wrap()
            if (box.childCount > 0) params.topMargin = context.dp(4)
            box.addView(context.setupText(text, color), params)
        }
        s.message?.let { line(it) }
        s.exception?.let { ex ->
            ex.localizedMessage?.let { line(it) }
            ex.cause?.localizedMessage?.let { line("Cause: $it") }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Connect your account
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * WeaselPlex first screen (`ConnectYourAccount`): the brand, "To start watching connect your WeaselPlex account to
     * the app." and the QR code a phone scans to approve this TV on theweasel.tv. The code starts as soon as the screen
     * shows; an approval signs the TV in and the view model moves on. A code that expires offers a fresh one. A long
     * error scrolls with the D-pad, and DOWN at its end reaches "Get a new code".
     */
    private inner class ConnectAccountView(
        context: Context,
    ) : FrameLayout(context) {
        private val column =
            vertical(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        private val scroll =
            object : ScrollView(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val parentH = (parent as? View)?.height ?: 0
                    val cap = if (scrollMode && parentH > 0) (parentH - context.dp(32)).coerceAtLeast(0) else Int.MAX_VALUE
                    val spec = if (cap == Int.MAX_VALUE) heightMeasureSpec else MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, spec)
                }

                override fun onKeyDown(
                    keyCode: Int,
                    event: KeyEvent,
                ): Boolean {
                    if (!scrollMode || !isFocused) return super.onKeyDown(keyCode, event)
                    return when {
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && canScrollVertically(1) -> {
                            smoothScrollBy(0, 100)
                            true
                        }

                        keyCode == KeyEvent.KEYCODE_DPAD_UP && canScrollVertically(-1) -> {
                            smoothScrollBy(0, -100)
                            true
                        }

                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                            retry?.requestFocus()
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFocusable = false
                addView(column)
            }
        private var scrollMode = false
        private var retry: View? = null
        private var shownKey: Any? = null

        init {
            val width = (context.resources.displayMetrics.widthPixels * .6f).toInt()
            addView(scroll, frame(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            newCode()
        }

        private fun newCode() {
            viewModel.clearSwitchUserState()
            viewModel.resetAttempts()
            viewModel.initiateQuickConnect(server, null)
        }

        /** The screen goes away: stop polling the code */
        fun leave() = viewModel.cancelQuickConnect()

        fun bind() {
            val s = state
            val status = s.quickConnectStatus
            val approveUrl = quickConnectApproveUrl(status?.code)
            val error = s.switchUserState as? LoadingState.Error
            val key = Triple(error, status?.code, approveUrl)
            if (key == shownKey) return
            shownKey = key
            column.removeAllViews()
            retry = null

            fun add(
                v: View,
                params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
            ) {
                if (column.childCount > 0) params.topMargin = context.dp(12)
                column.addView(v, params)
            }
            if (AppTheme.isNeon) add(context.neonBrandRow())
            add(
                context.setupText(
                    context.getString(R.string.connect_account_intro),
                    gravity = Gravity.CENTER,
                ) { applyTextRole(TextRole.TITLE_LARGE) },
                matchWidth(),
            )
            if (error != null) {
                val box = vertical(context)
                fillUserStateError(box, error)
                add(box, matchWidth())
                val button = context.setupButton(R.string.get_new_code) { newCode() }
                retry = button
                add(button)
            } else if (status == null || approveUrl == null) {
                add(
                    horizontal(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(32)
                        addView(context.circularProgress(20f))
                        addView(context.hGap(8f))
                        addView(context.setupText(context.getString(R.string.getting_code)) { applyTextRole(TextRole.TITLE_MEDIUM) })
                    },
                )
            } else {
                add(
                    context.setupText(
                        context.getString(R.string.quick_connect_scan_hint, BuildConfig.QUICK_CONNECT_APPROVE_LABEL),
                        gravity = Gravity.CENTER,
                    ) {
                        applyTextRole(TextRole.TITLE_MEDIUM)
                    },
                    matchWidth(),
                )
                add(
                    context.qrCode(approveUrl),
                    LinearLayout.LayoutParams(context.dp(200), context.dp(200)).apply {
                        gravity =
                            Gravity.CENTER_HORIZONTAL
                    },
                )
                add(context.setupText(status.code, gravity = Gravity.CENTER) { applyTextRole(TextRole.DISPLAY_MEDIUM) })
            }
            val wasScrolling = scrollMode
            scrollMode = error != null
            scroll.isFocusable = scrollMode
            scroll.isFocusableInTouchMode = scrollMode
            scroll.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            if (scrollMode != wasScrolling) scroll.requestLayout()
            if (scrollMode) {
                // A new error: back to its top, with the scrolling text focused
                scroll.scrollTo(0, 0)
                scroll.requestFocus()
            }
        }
    }

    companion object {
        private const val ARG_SERVER = "wholphin.setup.server"

        /** The chooser for [server] (`SetupDestination.UserList`) */
        fun newInstance(server: JellyfinServer): UserListFragment =
            UserListFragment().apply {
                arguments = bundleOf(ARG_SERVER to Json.encodeToString(JellyfinServer.serializer(), server))
            }
    }
}

/** A TextWatcher that only reports the new text */
internal fun afterChanged(block: (String) -> Unit): android.text.TextWatcher =
    object : android.text.TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) {}

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) {}

        override fun afterTextChanged(s: android.text.Editable?) = block(s?.toString().orEmpty())
    }
