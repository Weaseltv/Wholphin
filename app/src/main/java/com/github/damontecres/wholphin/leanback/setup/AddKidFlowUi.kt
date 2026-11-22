package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.kids.KidLevel
import com.github.damontecres.wholphin.kids.KidProfile
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.kids.AddKidError
import com.github.damontecres.wholphin.ui.kids.AddKidStep
import com.github.damontecres.wholphin.ui.kids.AddKidViewModel
import com.github.damontecres.wholphin.ui.kids.AdultPinOutcome
import com.github.damontecres.wholphin.ui.kids.NameError
import com.github.damontecres.wholphin.ui.kids.PinError
import com.github.damontecres.wholphin.ui.kids.Retry
import com.github.damontecres.wholphin.ui.kids.Work
import com.github.damontecres.wholphin.ui.kids.description
import com.github.damontecres.wholphin.ui.kids.label
import com.github.damontecres.wholphin.ui.setup.PinOutcome
import com.github.damontecres.wholphin.ui.setup.forgotPinUrl
import com.github.damontecres.wholphin.ui.setup.pairKidUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The chooser's "Add kid profile" flow in one panel dialog (plan 4.3; owner mockups 2026-10-22; `AddKidFlow`), driven by
 * [AddKidViewModel.step]: pick the parent, the parent's PIN (or a first PIN), PINs for the other adults, then a new kid
 * (name, level, picture) or an existing one (pairing code), with progress and failures. Back or Cancel leaves it.
 */
internal class AddKidFlowUi(
    private val fragment: Fragment,
    private val server: JellyfinServer,
    users: List<JellyfinUser>,
    private val imageUrls: Map<UUID, String?>,
    private val viewModel: AddKidViewModel,
    private val onClose: () -> Unit,
) : ChooserFlowUi {
    private val context: Context = fragment.requireContext()
    private val title = context.getString(R.string.add_kid_profile)
    private val dialog = KidPanelDialog(context, 540f, onDismissRequest = { close() })
    private val jobs = mutableListOf<Job>()
    private var shown: AddKidStep? = null

    // Sub-flows that take the panel's place while they show
    private var firstPinFlow: SetPinFlow? = null
    private var firstPinFor: AddKidStep.CreateFirstPin? = null
    private var adultPinFlow: SetPinFlow? = null
    private var adultPinStep: AddKidStep? = null

    // In-place parts of the current step
    private var pinEntry: PinEntryView? = null
    private var lockTicker: Job? = null
    private var nameField: KidTextFieldView? = null
    private var nameError: View? = null
    private var formError: View? = null
    private var formErrorHolder: LinearLayout? = null
    private var levelCards: Map<KidLevel, KidOptionCardView> = emptyMap()
    private var picker: AvatarPickerView? = null
    // Who was first in the list last time: one moving into first place takes focus (`initialFocus(index == 0)`)
    private var firstKid: String? = null
    private var firstAdult: Int? = null

    init {
        viewModel.start(server, users)
        jobs += fragment.viewLifecycleOwner.lifecycleScope.launch { viewModel.step.collect { render(it) } }
    }

    private fun close() {
        viewModel.cancel()
        onClose()
    }

    override fun dismiss() {
        jobs.forEach { it.cancel() }
        lockTicker?.cancel()
        firstPinFlow?.dismiss()
        adultPinFlow?.dismiss()
        dialog.dismiss()
    }

    private fun render(step: AddKidStep) {
        if (step == AddKidStep.Done) {
            dialog.hide()
            return
        }
        // A first PIN or another adult's PIN takes the panel's place until the step moves on or it is dismissed
        var fresh = false
        if (firstPinFor != null && firstPinFor != step) {
            dropFirstPin()
            fresh = true
        }
        if (adultPinStep != null && adultPinStep != step) {
            dropAdultPin()
            fresh = true
        }
        if (firstPinFlow != null || adultPinFlow != null) return
        // Back from a PIN flow the panel is composed again: built fresh
        if (fresh) shown = null
        val previous = shown
        shown = step
        if (!dialog.isShowing) dialog.show()
        when {
            step is AddKidStep.EnterPin && previous is AddKidStep.EnterPin -> {
                updatePin(step)
            }

            step is AddKidStep.Form && previous is AddKidStep.Form -> {
                updateForm(step)
            }

            step is AddKidStep.PickPicture && previous is AddKidStep.PickPicture -> {
                picker?.bind(viewModel.avatars, step.selected, enabled = true)
            }

            else -> {
                build(step, sameKind = previous != null && previous::class == step::class)
            }
        }
    }

    private fun panelWidth(step: AddKidStep): Float =
        when (step) {
            is AddKidStep.Form, is AddKidStep.PickPicture, is AddKidStep.AdultPins, is AddKidStep.Pairing -> 750f
            is AddKidStep.ChooseHow, is AddKidStep.PickParent -> 680f
            is AddKidStep.EnterPin -> 590f
            else -> 540f
        }

    /** Builds [step]'s panel; for a refreshed step of the same kind the focus stays on the same item */
    private fun build(
        step: AddKidStep,
        sameKind: Boolean,
    ) {
        val focusedTag = if (sameKind) dialog.root.findFocus()?.tag as? String else null
        lockTicker?.cancel()
        pinEntry = null
        nameField = null
        picker = null
        // The PIN entry takes every D-pad key, so it keeps its footer on screen instead of scrolling
        val content = dialog.reset(panelWidth(step), scrollable = step !is AddKidStep.EnterPin)
        var initial: View? = null

        fun add(
            view: View,
            params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
        ) = content.addView(view, params)
        when (step) {
            AddKidStep.Loading, AddKidStep.Done -> {
                add(context.kidTitle(title))
                add(context.gap(28f))
                add(context.kidSpinner(56f, 5f))
                add(context.gap(12f))
            }

            is AddKidStep.PickParent -> {
                add(context.kidTitle(title))
                add(context.gap(10f))
                add(context.kidSubtitle(context.getString(R.string.who_is_adding_kid)))
                add(context.gap(28f))
                // Three tiles fit at the mockup's size; more get smaller tiles (and the row scrolls past four)
                val size = if (step.parents.size <= 3) 150f else 112f
                val row =
                    horizontal(context).apply {
                        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                    }
                step.parents.forEachIndexed { index, parent ->
                    val name = parent.shownName ?: parent.id.toString()
                    val tile = KidTileView(context, name, size)
                    tile.surface.addView(KidAvatarView(context).apply { bind(name, imageUrls[parent.id]) }, frame())
                    tile.surface.setOnClickListener { viewModel.selectParent(parent) }
                    tile.surface.tag = "parent:${parent.rowId}"
                    if (index > 0) row.addView(context.hGap(36f))
                    row.addView(tile)
                    if (index == 0) initial = tile.surface
                }
                val scroll =
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        clipChildren = false
                        clipToPadding = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        addView(row)
                    }
                add(scroll)
                add(context.gap(26f))
                add(context.okBackHints())
            }

            is AddKidStep.EnterPin -> {
                buildPin(step, content)
                initial = pinEntry
            }

            is AddKidStep.CreateFirstPin -> {
                add(context.kidTitle(title))
                add(context.gap(14f))
                add(context.kidSubtitle(context.getString(R.string.set_pin_first, step.parent.shownName.orEmpty()), KidPalette.TEXT))
                if (step.saveFailed) {
                    add(context.gap(12f))
                    add(context.kidErrorText(context.getString(R.string.pin_save_failed)))
                }
                add(context.gap(28f))
                val button = context.kidButton(context.getString(R.string.set_pin_button), arrow = true) { openFirstPin(step) }
                add(button, KidButtonView.params(context).apply { gravity = Gravity.CENTER_HORIZONTAL })
                initial = button
                add(context.gap(24f))
                add(context.okBackHints())
            }

            is AddKidStep.ParentLocked -> {
                add(context.kidTitle(title))
                add(context.gap(14f))
                add(context.kidSubtitle(context.getString(R.string.parent_locked, step.parent.shownName.orEmpty()), KidPalette.TEXT))
                add(context.gap(28f))
                val cancel = context.kidButton(context.getString(R.string.cancel)) { close() }
                add(cancel, KidButtonView.params(context).apply { gravity = Gravity.CENTER_HORIZONTAL })
                initial = cancel
            }

            is AddKidStep.AdultPins -> {
                add(context.kidTitle(title))
                add(context.gap(10f))
                add(context.kidSubtitle(context.getString(R.string.adult_pins_subtitle)))
                if (step.removeFailed) {
                    add(context.gap(10f))
                    add(context.kidErrorText(context.getString(R.string.adult_remove_failed)))
                }
                add(context.gap(24f))
                val column = vertical(context)
                step.adults.forEachIndexed { index, adult ->
                    if (index > 0) column.addView(context.gap(14f))
                    val (row, create) = adultPinRow(adult, step)
                    column.addView(row, matchWidth())
                    // The first adult's Create PIN takes focus when that adult becomes first
                    if (index == 0 && (!sameKind || adult.rowId != firstAdult)) initial = create
                }
                firstAdult = step.adults.firstOrNull()?.rowId
                add(column, matchWidth())
                add(context.gap(24f))
                add(context.okBackHints())
            }

            AddKidStep.ChooseHow -> {
                add(context.kidTitle(title))
                add(context.gap(10f))
                add(context.kidSubtitle(context.getString(R.string.kid_how_subtitle)))
                add(context.gap(24f))
                val create =
                    context.kidListRow(context.getString(R.string.create_new_kid_profile), context.getString(R.string.fa_user)) {
                        viewModel.createNew()
                    }
                add(create, matchWidth())
                add(context.gap(12f))
                add(
                    context.kidListRow(context.getString(R.string.add_existing_kid_profile), context.getString(R.string.fa_tv)) {
                        viewModel.addExisting()
                    },
                    matchWidth(),
                )
                initial = create
                add(context.gap(24f))
                add(context.okBackHints())
            }

            is AddKidStep.Pairing -> {
                initial = buildPairing(step, content, sameKind)
            }

            AddKidStep.PairingRefused -> {
                add(context.kidEyebrow(title))
                add(context.gap(22f))
                add(
                    context.setupText(
                        context.getString(R.string.pair_refused),
                        KidPalette.TEXT,
                        Gravity.CENTER,
                    ) { kidBody(22f, WEIGHT_BOLD) },
                )
                add(context.gap(28f))
                val newCode = context.kidButton(context.getString(R.string.pair_new_code), minWidthDp = 200f) { viewModel.addExisting() }
                add(
                    horizontal(context).apply {
                        addView(newCode)
                        addView(context.hGap(16f))
                        addView(context.kidButton(context.getString(R.string.cancel), minWidthDp = 180f) { close() })
                    },
                )
                initial = newCode
                add(context.gap(24f))
                add(context.okBackHints())
            }

            is AddKidStep.Form -> {
                add(context.kidTitle(title))
                add(context.gap(20f))
                initial = buildForm(step, content)
            }

            is AddKidStep.PickPicture -> {
                val view =
                    AvatarPickerView(
                        context = context,
                        title = title,
                        subtitle = context.getString(R.string.pick_a_picture_for, step.name),
                        onPick = { viewModel.pickPicture(it) },
                        onSkip = { viewModel.pickPicture(null) },
                    )
                picker = view
                add(view, matchWidth())
                view.bind(viewModel.avatars, step.selected, enabled = true)
            }

            is AddKidStep.Working -> {
                add(context.gap(12f))
                add(context.kidEyebrow(title))
                add(context.gap(30f))
                add(context.kidSpinner())
                add(context.gap(36f))
                val line =
                    when (step.work) {
                        Work.Creating -> context.getString(R.string.creating_kid, step.kidName)
                        Work.Connecting -> context.getString(R.string.adding_kid_to_tv, step.kidName)
                    }
                add(
                    context.setupText(context.emphasise(line, step.kidName, KidPalette.LIME), KidPalette.TEXT, Gravity.CENTER) {
                        kidBody(26f, WEIGHT_BOLD)
                    },
                )
                add(context.gap(10f))
                add(context.kidSubtitle(context.getString(R.string.kid_takes_a_moment)))
                add(context.gap(12f))
            }

            is AddKidStep.Failed -> {
                add(context.kidEyebrow(title))
                add(context.gap(22f))
                add(context.setupText(errorText(step.error), KidPalette.TEXT, Gravity.CENTER) { kidBody(22f, WEIGHT_BOLD) })
                add(context.gap(28f))
                val row = horizontal(context)
                if (step.retry != Retry.None) {
                    val retry = context.kidButton(context.getString(R.string.retry), minWidthDp = 180f) { viewModel.retry() }
                    row.addView(retry)
                    row.addView(context.hGap(16f))
                    initial = retry
                }
                val cancel = context.kidButton(context.getString(R.string.cancel), minWidthDp = 180f) { close() }
                row.addView(cancel)
                if (initial == null) initial = cancel
                add(row)
                add(context.gap(24f))
                add(context.okBackHints())
            }
        }
        // A new step's first item takes focus once, as does a kid or adult that became first; a refreshed step otherwise
        // keeps the focus on the same item
        val target = initial
        val newFirst = sameKind && (step is AddKidStep.Pairing || step is AddKidStep.AdultPins) && target != null
        if (target != null && (newFirst || (!focusTagged(dialog.root, focusedTag) && (!sameKind || dialog.root.findFocus() == null)))) {
            dialog.initialFocus = target
            target.requestFocus()
        } else if (target == null) {
            focusTagged(dialog.root, focusedTag)
        }
    }

    private fun buildPin(
        step: AddKidStep.EnterPin,
        content: LinearLayout,
    ) {
        val name = step.parent.shownName.orEmpty()
        val entry =
            PinEntryView(
                context,
                PinEntryConfig(
                    title = title,
                    prompt = context.pinPromptFor(name),
                    length = PinRules.entryLength(step.parent.pin),
                    lockedUntilMillis = (step.error as? PinError.LockedOut)?.retryAtMillis ?: 0L,
                    message = pinMessage(step.error),
                    // Latched until the check is done; a wrong PIN then clears the boxes
                    onSubmit = { pin ->
                        if (pin.isEmpty()) {
                            PinOutcome.OTHER
                        } else {
                            viewModel.submitPin(pin).join()
                            if (viewModel.lastPinWasWrong()) PinOutcome.WRONG else PinOutcome.OTHER
                        }
                    },
                ),
            )
        pinEntry = entry
        content.addView(entry, matchWidth())
        startLockTicker(step.error)
    }

    /** A wrong PIN, a lockout (counting down) or a failed check, under the boxes */
    private fun updatePin(step: AddKidStep.EnterPin) {
        val entry = pinEntry ?: return
        entry.lockedUntilMillis = (step.error as? PinError.LockedOut)?.retryAtMillis ?: 0L
        entry.message = pinMessage(step.error)
        startLockTicker(step.error)
    }

    private fun startLockTicker(error: PinError?) {
        lockTicker?.cancel()
        if (error !is PinError.LockedOut) return
        lockTicker =
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                while (secondsUntil(error.retryAtMillis) > 0) {
                    delay(1_000)
                    pinEntry?.message = pinMessage(error)
                }
            }
    }

    private fun pinMessage(error: PinError?): String? =
        when (error) {
            is PinError.Wrong -> {
                if (error.attemptsBeforeLockout in 1..9) {
                    context.getString(R.string.pin_wrong_attempts, error.attemptsBeforeLockout)
                } else {
                    context.getString(R.string.pin_wrong)
                }
            }

            is PinError.LockedOut -> {
                context.getString(R.string.pin_locked_out, secondsUntil(error.retryAtMillis))
            }

            PinError.CheckFailed -> {
                context.getString(R.string.kids_error_generic)
            }

            null -> {
                null
            }
        }

    private fun secondsUntil(atMillis: Long) = ((atMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

    /** Contract 12.4: one adult without a PIN, with "Create PIN" and "Remove from this TV" */
    private fun adultPinRow(
        adult: JellyfinUser,
        step: AddKidStep,
    ): Pair<View, View> {
        val name = adult.shownName ?: adult.id.toString()
        val row = horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            KidAvatarView(context, 26f).apply {
                bind(name, imageUrls[adult.id])
            },
            LinearLayout.LayoutParams(context.dp(56), context.dp(56)),
        )
        row.addView(context.hGap(16f))
        row.addView(
            context.setupText(name, KidPalette.TEXT, maxLines = 1) { kidBody(19f, 600) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(context.hGap(16f))
        val create = context.kidButton(context.getString(R.string.create_pin), minWidthDp = 150f) { openAdultPin(adult, step) }
        create.tag = "adult:${adult.rowId}:create"
        row.addView(create)
        row.addView(context.hGap(16f))
        val remove = context.kidButton(context.getString(R.string.remove_from_this_tv), minWidthDp = 150f) { viewModel.removeAdult(adult) }
        remove.tag = "adult:${adult.rowId}:remove"
        row.addView(remove)
        return row to create
    }

    /**
     * Contract 12.3 "Add an existing kid profile": the pairing code, big, with where to enter it and a QR code for the
     * website's Kids profiles page, then this account's own kids that aren't on this TV yet. Returns the item that takes
     * focus (the first kid, when it becomes first).
     */
    private fun buildPairing(
        step: AddKidStep.Pairing,
        content: LinearLayout,
        sameKind: Boolean,
    ): View? {
        content.addView(context.kidEyebrow(title), wrap(Gravity.CENTER_HORIZONTAL))
        content.addView(context.gap(12f))
        content.addView(context.kidTitle(context.getString(R.string.add_existing_kid_profile)), wrap(Gravity.CENTER_HORIZONTAL))
        content.addView(context.gap(22f))
        val row = horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        pairKidUrl()?.let {
            row.addView(context.qrCode(it, 150f))
            row.addView(context.hGap(28f))
        }
        val column = vertical(context)
        val code = step.code
        if (code != null) {
            column.addView(context.setupText(AddKidViewModel.formatCode(code), KidPalette.LIME) { kidDisplay(52f, .08f) })
        } else {
            column.addView(context.kidSpinner(44f, 4f).apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.START })
        }
        column.addView(context.gap(12f))
        val site = forgotPinUrl()?.removePrefix("https://")?.removePrefix("http://").orEmpty()
        column.addView(
            context.setupText(
                context.emphasise(context.getString(R.string.pair_code_instructions, site), site, KidPalette.LIME),
                KidPalette.TEXT,
            ) {
                kidBody(17f)
            },
        )
        if (step.trouble) {
            column.addView(context.gap(10f))
            column.addView(context.kidErrorText(context.getString(R.string.pair_trouble)).apply { gravity = Gravity.START })
        }
        row.addView(column, LinearLayout.LayoutParams(context.dp(420), ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(row, wrap(Gravity.CENTER_HORIZONTAL))
        var focus: View? = null
        if (step.kids.isNotEmpty()) {
            content.addView(context.gap(22f))
            content.addView(context.kidDivider())
            content.addView(context.gap(16f))
            content.addView(context.kidSectionTitle(context.getString(R.string.pair_your_kids)), matchWidth())
            content.addView(context.gap(12f))
            step.kids.forEachIndexed { index, kid: KidProfile ->
                if (index > 0) content.addView(context.gap(10f))
                val item =
                    context.kidListRow(
                        context.getString(R.string.sign_in_kid_on_tv, kid.displayName),
                        context.getString(R.string.fa_user),
                    ) {
                        viewModel.signInExisting(kid)
                    }
                item.tag = "kid:${kid.id}"
                content.addView(item)
                if (index == 0 && (!sameKind || kid.id != firstKid)) focus = item
            }
        }
        firstKid = step.kids.firstOrNull()?.id
        content.addView(context.gap(22f))
        content.addView(context.okBackHints(), wrap(Gravity.CENTER_HORIZONTAL))
        return focus
    }

    /** The name and level form; the name field stays while it is typed into, the errors and selection update in place */
    private fun buildForm(
        form: AddKidStep.Form,
        content: LinearLayout,
    ): View {
        content.addView(context.kidFieldLabel(context.getString(R.string.kid_name)), matchWidth())
        content.addView(context.gap(10f))
        val field =
            KidTextFieldView(context, context.getString(R.string.kid_name_placeholder)).apply {
                inputType = KidTextFieldView.NAME_INPUT
                imeOptions = KidTextFieldView.IME_DONE
                filters = arrayOf(InputFilter.LengthFilter(40))
                setText(form.name)
                setSelection(text.length)
                tag = "kid:name"
                addTextChangedListener(
                    object : TextWatcher {
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

                        override fun afterTextChanged(s: Editable?) {
                            val value = s?.toString().orEmpty().take(40)
                            if (value != (shown as? AddKidStep.Form)?.name) viewModel.updateForm(name = value)
                        }
                    },
                )
            }
        nameField = field
        content.addView(field)
        val nameErrorHolder = vertical(context)
        content.addView(nameErrorHolder, matchWidth())
        nameError = nameErrorHolder
        content.addView(context.gap(28f))
        content.addView(context.kidSectionTitle(context.getString(R.string.kid_level)), matchWidth())
        content.addView(context.gap(12f))
        val levels = horizontal(context)
        val cards = LinkedHashMap<KidLevel, KidOptionCardView>()
        KidLevel.entries.forEachIndexed { index, level ->
            val card =
                KidOptionCardView(context, context.getString(level.label), context.getString(level.description)).apply {
                    tag = "level:${level.name}"
                    setOnClickListener { viewModel.updateForm(level = level) }
                }
            cards[level] = card
            if (index > 0) levels.addView(context.hGap(18f))
            levels.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        levelCards = cards
        content.addView(levels, matchWidth())
        val errorHolder = vertical(context)
        formErrorHolder = errorHolder
        content.addView(errorHolder, matchWidth())
        content.addView(context.gap(26f))
        val bottom = horizontal(context).apply { gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(context.okBackHints())
        bottom.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val next = context.kidButton(context.getString(R.string.next), arrow = true, minWidthDp = 220f) { viewModel.submitForm() }
        next.tag = "kid:next"
        bottom.addView(next)
        content.addView(bottom, matchWidth())
        updateForm(form)
        return field
    }

    private fun updateForm(form: AddKidStep.Form) {
        val field = nameField ?: return
        if (field.text.toString() != form.name) {
            field.setText(form.name)
            field.setSelection(field.text.length)
        }
        field.isError = form.nameError != null
        (nameError as? LinearLayout)?.apply {
            removeAllViews()
            val text =
                when (form.nameError) {
                    NameError.Invalid -> context.getString(R.string.kid_name_invalid)
                    NameError.Taken -> context.getString(R.string.kid_name_taken)
                    null -> null
                }
            if (text != null) {
                addView(context.gap(8f))
                addView(context.kidErrorText(text), wrap(Gravity.CENTER_HORIZONTAL))
            }
        }
        levelCards.forEach { (level, card) -> card.setSelectedOption(form.level == level) }
        formErrorHolder?.apply {
            removeAllViews()
            form.error?.let {
                addView(context.gap(10f))
                addView(context.kidErrorText(errorText(it)), matchWidth())
            }
        }
        formError = formErrorHolder
    }

    private fun openFirstPin(step: AddKidStep.CreateFirstPin) {
        firstPinFor = step
        dialog.hide()
        firstPinFlow =
            SetPinFlow(
                context = context,
                currentPin = null,
                onAddPin = {
                    viewModel.createFirstPin(it)
                    true
                },
                onRemovePin = {},
                onDismissRequest = { closeFirstPin() },
            ).also { it.show() }
    }

    private fun dropFirstPin() {
        firstPinFlow?.dismiss()
        firstPinFlow = null
        firstPinFor = null
    }

    private fun closeFirstPin() {
        dropFirstPin()
        returnToPanel()
    }

    /** Contract 12.4: "Create PIN" for another adult, until the step moves on (saved) or it is dismissed */
    private fun openAdultPin(
        adult: JellyfinUser,
        step: AddKidStep,
    ) {
        adultPinStep = step
        dialog.hide()
        adultPinFlow =
            SetPinFlow(
                context = context,
                title = context.getString(R.string.adult_pin_title, adult.shownName.orEmpty()),
                currentPin = null,
                onAddPin = {
                    when (viewModel.createAdultPin(adult, it)) {
                        AdultPinOutcome.SAVED -> {
                            true
                        }

                        AdultPinOutcome.ALREADY_HAD_PIN -> {
                            val message = context.getString(R.string.adult_already_has_pin, adult.shownName.orEmpty())
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            true
                        }

                        AdultPinOutcome.NOT_SAVED -> {
                            false
                        }
                    }
                },
                onRemovePin = {},
                onDismissRequest = { closeAdultPin() },
            ).also { it.show() }
    }

    private fun dropAdultPin() {
        adultPinFlow?.dismiss()
        adultPinFlow = null
        adultPinStep = null
    }

    private fun closeAdultPin() {
        dropAdultPin()
        returnToPanel()
    }

    /** The panel comes back fresh, as it was composed again, with its first item focused */
    private fun returnToPanel() {
        if (firstPinFlow != null || adultPinFlow != null) return
        shown = null
        render(viewModel.step.value)
    }

    private fun errorText(error: AddKidError): String =
        when (error) {
            AddKidError.NoParent -> {
                context.getString(R.string.kids_error_no_parent)
            }

            AddKidError.NotAvailable -> {
                context.getString(R.string.kids_error_unavailable)
            }

            AddKidError.LimitReached -> {
                context.getString(R.string.kids_error_limit)
            }

            AddKidError.NameTaken -> {
                context.getString(R.string.kid_name_taken)
            }

            AddKidError.PickLevel -> {
                context.getString(R.string.kid_pick_level)
            }

            AddKidError.AccessInactive -> {
                context.getString(R.string.kids_error_access)
            }

            AddKidError.SignInAgain -> {
                context.getString(R.string.kids_error_sign_in_again)
            }

            AddKidError.KidNotActive -> {
                context.getString(R.string.kids_error_kid_not_active)
            }

            is AddKidError.RateLimited -> {
                error.minutes?.let { context.getString(R.string.kids_error_rate_limited, it) }
                    ?: context.getString(R.string.kids_error_rate_limited_unknown)
            }

            AddKidError.Network -> {
                context.getString(R.string.kids_error_network)
            }

            AddKidError.Generic -> {
                context.getString(R.string.kids_error_generic)
            }
        }
}
