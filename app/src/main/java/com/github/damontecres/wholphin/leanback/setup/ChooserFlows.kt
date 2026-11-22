package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidSessionGate
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRemoval
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.kids.ProfileKind
import com.github.damontecres.wholphin.services.ProfilePictureTarget
import com.github.damontecres.wholphin.ui.kids.ChooserPictureAccess
import com.github.damontecres.wholphin.ui.kids.PictureAccessState
import com.github.damontecres.wholphin.ui.kids.PictureLock
import com.github.damontecres.wholphin.ui.setup.outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A chooser flow shown over the profile chooser; [dismiss] closes whatever it shows */
internal interface ChooserFlowUi {
    fun dismiss()
}

/**
 * "Change picture" from the chooser's profile menu (contract 11.1; `ChooserChangePicture`): kids pick from the kid list
 * without a PIN; adults pick from the adult list, and an adult with a PIN proves they are its owner first. On a
 * kid-protected TV a pinless adult's picture needs a parent's PIN, also when protection turns on while it is open
 * ([ChooserPictureAccess]). These checks grant no unlock.
 */
internal class ChangePictureFlow(
    private val fragment: Fragment,
    private val server: JellyfinServer,
    user: JellyfinUser,
    private val gate: KidSessionGate,
    private val onDone: () -> Unit,
    savedUsers: List<JellyfinUser>,
    refresh: suspend (JellyfinUser) -> JellyfinUser,
) : ChooserFlowUi {
    private val context: Context = fragment.requireContext()
    private val token = user.accessToken
    private val kind = gate.profileKind(user)
    private val access = ChooserPictureAccess(user, kind, gate, savedUsers, refresh)
    private val jobs = mutableListOf<Job>()
    private var pictureDialog: ProfilePictureDialog? = null
    private var pictureRow: Int? = null
    private var parentDialog: KidPanelDialog? = null
    private var pinDialog: PinEntryDialog? = null
    private var pinOwner: Int? = null
    private var done = false

    init {
        if (token == null) {
            finish()
        } else {
            val scope = fragment.viewLifecycleOwner.lifecycleScope
            jobs += scope.launch { access.follow() }
            jobs += scope.launch { access.state.collect { render(it) } }
        }
    }

    private fun finish() {
        if (done) return
        done = true
        onDone()
    }

    private fun render(state: PictureAccessState) {
        when (state) {
            PictureAccessState.Deciding -> {}

            PictureAccessState.Closed -> {
                finish()
            }

            is PictureAccessState.Unlocked -> {
                closeLocked()
                val row = state.row
                if (pictureDialog == null || pictureRow != row.rowId) {
                    pictureDialog?.dismiss()
                    pictureRow = row.rowId
                    pictureDialog =
                        ProfilePictureDialog(
                            owner = fragment,
                            target = ProfilePictureTarget(server.url, row.id, token!!, row.shownName, isKid = kind != ProfileKind.ADULT),
                            onDismissRequest = { finish() },
                            mayChange = access::mayChange,
                        ).also { it.show() }
                }
            }

            is PictureAccessState.Locked -> {
                pictureDialog?.dismiss()
                pictureDialog = null
                pictureRow = null
                val parents = (state.lock as? PictureLock.ParentPin)?.parents
                val owner = if (parents != null) state.parent else state.row
                if (owner == null && parents != null) {
                    pinDialog?.dismiss()
                    pinDialog = null
                    pinOwner = null
                    if (parentDialog == null) parentDialog = pickParent(state.row, parents).also { it.show() }
                } else if (owner != null) {
                    parentDialog?.dismiss()
                    parentDialog = null
                    // Keyed on the owner: a new owner gets a fresh entry
                    if (pinDialog == null || pinOwner != owner.rowId) {
                        pinDialog?.dismiss()
                        pinOwner = owner.rowId
                        pinDialog = picturePin(owner).also { it.show() }
                    }
                }
            }
        }
    }

    private fun closeLocked() {
        parentDialog?.dismiss()
        parentDialog = null
        pinDialog?.dismiss()
        pinDialog = null
        pinOwner = null
    }

    /** Which parent unlocks a pinless adult's picture on a protected TV */
    private fun pickParent(
        row: JellyfinUser,
        parents: List<JellyfinUser>,
    ): KidPanelDialog =
        KidPanelDialog(context, 620f, onDismissRequest = { finish() }).apply {
            content.addView(context.kidTitle(context.getString(R.string.change_picture)), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(10f))
            content.addView(context.kidSubtitle(context.getString(R.string.kid_ask_parent, row.shownName.orEmpty())), wrap(Gravity.CENTER_HORIZONTAL))
            content.addView(context.gap(24f))
            parents.forEachIndexed { index, candidate ->
                val item =
                    context.kidListRow(
                        context.getString(R.string.kid_parent_pin_title, candidate.shownName.orEmpty()),
                        context.getString(R.string.fa_user),
                    ) { access.pickParent(candidate) }
                if (index > 0) content.addView(context.gap(12f))
                content.addView(item)
                if (index == 0) initialFocus = item
            }
            content.addView(context.gap(24f))
            content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
        }

    /** [owner]'s PIN before a picture changes; the entry stays latched while the check runs */
    private fun picturePin(owner: JellyfinUser): PinEntryDialog {
        val dialog = PinEntryDialog(context, onDismissRequest = { finish() })
        dialog.setEntry(
            PinEntryConfig(
                title = context.getString(R.string.change_picture),
                prompt = context.pinPromptFor(owner.shownName),
                length = PinRules.entryLength(owner.pin),
                onSubmit = { pin ->
                    val result = access.submitPin(pin)
                    if (result != PinCheckResult.Correct) {
                        dialog.entry?.apply {
                            lockedUntilMillis = (result as? PinCheckResult.LockedOut)?.retryAtMillis ?: 0L
                            message =
                                when (result) {
                                    is PinCheckResult.Wrong -> context.getString(R.string.pin_wrong)
                                    is PinCheckResult.LockedOut -> context.getString(R.string.kids_error_rate_limited_unknown)
                                    else -> null
                                }
                        }
                    }
                    result.outcome()
                },
            ),
        )
        return dialog
    }

    override fun dismiss() {
        done = true
        jobs.forEach { it.cancel() }
        pictureDialog?.dismiss()
        closeLocked()
    }
}

/**
 * "Change PIN" from the chooser's profile menu (contract 11.2; `ChooserChangePin`): the current PIN (checked by the gate,
 * with its backoff), then the new PIN, then the new PIN again. The account's PIN is pulled first; with no PIN any more
 * there is nothing to change.
 */
internal class ChangePinFlow(
    fragment: Fragment,
    user: JellyfinUser,
    gate: KidSessionGate,
    refresh: suspend (JellyfinUser) -> JellyfinUser,
    changePin: suspend (JellyfinUser, String) -> Boolean,
    onDone: () -> Unit,
) : ChooserFlowUi {
    private var flow: SetPinFlow? = null
    private val job: Job
    private val context = fragment.requireContext()

    init {
        job =
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val row = refresh(user)
                if (!row.hasPin) {
                    onDone()
                    return@launch
                }
                flow =
                    SetPinFlow(
                        context = context,
                        title = context.getString(R.string.change_pin),
                        currentPin = row.pin,
                        checkCurrentPin = { gate.checkOwnPin(row, it) },
                        allowRemove = false,
                        onAddPin = { pin ->
                            changePin(row, pin).also { saved ->
                                if (saved) {
                                    Toast.makeText(context, context.getString(R.string.pin_changed), Toast.LENGTH_SHORT).show()
                                    onDone()
                                }
                            }
                        },
                        onRemovePin = {},
                        onDismissRequest = onDone,
                    ).also { it.show() }
            }
    }

    override fun dismiss() {
        job.cancel()
        flow?.dismiss()
    }
}

/**
 * "Remove PIN" from the chooser's profile menu (owner 2026-10-25; `ChooserRemovePin`). With a kid profile saved on this
 * TV it only says why not: every grown-up on a TV with kids needs a PIN (contract 12.4). Otherwise the current PIN, then
 * the PIN is removed, the account first; nothing changes if that fails.
 */
internal class RemovePinFlow(
    fragment: Fragment,
    private val user: JellyfinUser,
    private val gate: KidSessionGate,
    private val refresh: suspend (JellyfinUser) -> JellyfinUser,
    kidsOnTv: suspend () -> Boolean,
    private val removePin: suspend (JellyfinUser) -> PinRemoval,
    private val onDone: () -> Unit,
) : ChooserFlowUi {
    private val context = fragment.requireContext()
    private val scope = fragment.viewLifecycleOwner.lifecycleScope
    private val jobs = mutableListOf<Job>()
    private var kidsPanel: KidPanelDialog? = null
    private var pinDialog: PinEntryDialog? = null

    init {
        jobs +=
            scope.launch {
                if (kidsOnTv()) showKidsNeedPin() else askPin()
            }
    }

    private suspend fun askPin() {
        val row = refresh(user)
        if (!row.hasPin) {
            onDone()
            return
        }
        val dialog = PinEntryDialog(context, onDismissRequest = onDone)
        dialog.setEntry(
            PinEntryConfig(
                title = context.getString(R.string.remove_pin),
                prompt = context.getString(R.string.pin_prompt_current),
                length = PinRules.entryLength(row.pin),
                onSubmit = { typed ->
                    // Suspends for the check and the removal, so the entry stays latched until they are done
                    val result = gate.checkOwnPin(row, typed)
                    when (result) {
                        PinCheckResult.Correct -> {
                            when (removePin(row)) {
                                PinRemoval.REMOVED -> {
                                    Toast.makeText(context, context.getString(R.string.pin_removed), Toast.LENGTH_SHORT).show()
                                    onDone()
                                }

                                PinRemoval.KIDS_ON_TV -> {
                                    showKidsNeedPin()
                                }

                                PinRemoval.FAILED -> {
                                    dialog.entry?.message = context.getString(R.string.pin_remove_failed)
                                }
                            }
                        }

                        is PinCheckResult.Wrong -> {
                            dialog.entry?.message = context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                        }

                        is PinCheckResult.LockedOut -> {
                            val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                            dialog.entry?.message = context.getString(R.string.kid_pin_locked_out, seconds)
                            dialog.entry?.lockedUntilMillis = result.retryAtMillis
                        }
                    }
                    result.outcome()
                },
            ),
        )
        pinDialog = dialog
        dialog.show()
    }

    /** "<Name>'s PIN can't be removed": this TV has kid profiles, so every grown-up needs a PIN. OK or Back closes it */
    private fun showKidsNeedPin() {
        pinDialog?.dismiss()
        pinDialog = null
        if (kidsPanel != null) return
        kidsPanel =
            KidPanelDialog(context, 620f, onDismissRequest = onDone).apply {
                content.addView(context.kidTitle(context.getString(R.string.pin_cant_be_removed, user.shownName.orEmpty())), wrap(Gravity.CENTER_HORIZONTAL))
                content.addView(context.gap(10f))
                content.addView(context.kidSubtitle(context.getString(R.string.pin_cant_be_removed_subtitle)), wrap(Gravity.CENTER_HORIZONTAL))
                content.addView(context.gap(28f))
                val ok = context.kidButton(context.getString(R.string.pin_ok), minWidthDp = 180f) { onDone() }
                content.addView(ok, KidButtonView.params(context).apply { gravity = Gravity.CENTER_HORIZONTAL })
                initialFocus = ok
                show()
            }
    }

    override fun dismiss() {
        jobs.forEach { it.cancel() }
        pinDialog?.dismiss()
        kidsPanel?.dismiss()
    }
}

/** Gives the view tagged [tag] in [root] focus; true when one was found */
internal fun focusTagged(
    root: View,
    tag: String?,
): Boolean {
    if (tag == null) return false
    val view = root.findViewWithTag<View>(tag) ?: return false
    return view.requestFocus()
}
