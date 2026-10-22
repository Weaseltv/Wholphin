package com.github.damontecres.wholphin.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.PreviewTvSpec
import com.github.damontecres.wholphin.ui.kids.KeyHint
import com.github.damontecres.wholphin.ui.kids.KidColors
import com.github.damontecres.wholphin.ui.kids.KidDivider
import com.github.damontecres.wholphin.ui.kids.KidErrorText
import com.github.damontecres.wholphin.ui.kids.KidGap
import com.github.damontecres.wholphin.ui.kids.KidPanel
import com.github.damontecres.wholphin.ui.kids.KidPanelDialog
import com.github.damontecres.wholphin.ui.kids.KidSubtitle
import com.github.damontecres.wholphin.ui.kids.KidText
import com.github.damontecres.wholphin.ui.kids.KidTitle
import com.github.damontecres.wholphin.ui.kids.emphasise
import com.github.damontecres.wholphin.ui.kids.kidGlow
import com.github.damontecres.wholphin.ui.playback.isEnterKey
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.delay

/** The PIN character a remote key stands for, or null when it isn't part of a PIN (OK never is). */
fun pinPress(key: Key): Char? =
    when (key) {
        Key.DirectionUp -> 'U'
        Key.DirectionRight -> 'R'
        Key.DirectionDown -> 'D'
        Key.DirectionLeft -> 'L'
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }

/**
 * The PIN prompt (owner mockup `4-pin.png`): [length] boxes, the D-pad as a hint, and the OK / BACK keys.
 *
 * Presses after the [length]th are ignored; the [length]th submits the PIN on its own, and OK submits what is
 * typed so far. Either way [onSubmit] gets the whole PIN and the caller checks it (with attempt backoff where it
 * applies). Only a press that started here counts, so the OK that opened the prompt never submits it. With
 * nothing typed, OK runs [onClickServerAuth] when set ("Login via server"); Back dismisses the dialog around it.
 *
 * A submitted PIN latches the entry: every PIN key and OK (including the empty-OK server sign-in) is ignored
 * until the caller bumps [checks] to say that PIN has been checked, and while [lockedUntilMillis] is in the
 * future. So nothing typed during a check can start a second guess or cancel the pending one. Back still works.
 *
 * The key-hint footer always shows: in a panel too short for everything, the D-pad hint is dropped first and the
 * top part is clipped, never the footer.
 *
 * @param length presses to collect: [PinRules.LENGTH] for a new PIN, [PinRules.entryLength] of the saved PIN
 * when checking one
 * @param allowDigits false while choosing a new PIN, which is arrows only; checking a saved PIN accepts digits
 * @param checks how many submitted PINs the caller has finished checking; a change ends the latch
 * @param prompt the line under the title, eg "Enter **Justin M's** PIN to continue"
 * @param message a line under the boxes (wrong PIN, locked out)
 * @param serverAuthRemovesPin shows the "will remove PIN" note with the server sign-in hint
 */
@Composable
fun PinEntry(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = PinRules.LENGTH,
    allowDigits: Boolean = true,
    checks: Int = 0,
    lockedUntilMillis: Long = 0L,
    title: String? = stringResource(R.string.enter_pin),
    prompt: AnnotatedString? = null,
    message: String? = null,
    onClickServerAuth: (() -> Unit)? = null,
    serverAuthRemovesPin: Boolean = true,
) {
    var input by remember { mutableStateOf("") }
    var pressed by remember { mutableStateOf<Key?>(null) }
    // The [checks] value a PIN was submitted at; entry stays off while it is still current
    var submittedAt by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(checks) {
        if (submittedAt != null && submittedAt != checks) {
            submittedAt = null
            input = ""
        }
    }
    var lockedOut by remember(lockedUntilMillis) { mutableStateOf(lockedUntilMillis > System.currentTimeMillis()) }
    LaunchedEffect(lockedUntilMillis) {
        val wait = lockedUntilMillis - System.currentTimeMillis()
        if (wait > 0) {
            delay(wait)
            lockedOut = false
        }
    }
    val live = submittedAt == null && !lockedOut

    fun submit(pin: String) {
        input = pin
        submittedAt = checks
        onSubmit(pin)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    val press = pinPress(event.key)?.takeIf { allowDigits || it in PinRules.ARROWS }
                    val isPinKey = pinPress(event.key) != null
                    if (!isPinKey && !isEnterKey(event)) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (pressed == null) pressed = event.key
                        }

                        KeyEventType.KeyUp -> {
                            if (pressed == event.key) {
                                pressed = null
                                when {
                                    !live -> {}

                                    isPinKey -> {
                                        // A number key while choosing a new PIN isn't a press at all
                                        if (press != null) {
                                            val next = PinRules.append(input, press, length)
                                            if (PinRules.isComplete(next, length) && next != input) submit(next) else input = next
                                        }
                                    }

                                    input.isNotEmpty() -> {
                                        submit(input)
                                    }

                                    else -> {
                                        onClickServerAuth?.invoke()
                                    }
                                }
                            }
                        }
                    }
                    true
                }.focusable(),
    ) {
        // Everything above the footer; it gives way (D-pad hint first) when the panel is short
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f, fill = false).clipToBounds(),
        ) {
            if (title != null) KidTitle(title)
            if (prompt != null) {
                KidGap(10.dp)
                KidSubtitle(prompt, color = KidColors.Text, size = 19.sp)
            }
            KidGap(22.dp)
            PinBoxes(length = length, filled = input.length)
            if (message != null) {
                KidGap(12.dp)
                KidErrorText(message)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.dropWhenShort(),
            ) {
                KidGap(18.dp)
                DpadHint()
            }
        }
        KidGap(18.dp)
        KidDivider()
        KidGap(16.dp)
        val serverAuthHint = onClickServerAuth != null && input.isEmpty() && live
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyHint(
                stringResource(R.string.kid_key_ok),
                if (serverAuthHint) stringResource(R.string.use_server_credentials) else stringResource(R.string.confirm),
            )
            KeyHint(stringResource(R.string.kid_key_back), stringResource(R.string.cancel))
        }
        if (serverAuthHint && serverAuthRemovesPin) {
            KidGap(6.dp)
            Text(stringResource(R.string.will_remove_pin), style = KidText.body(12.sp), color = KidColors.Faint)
        }
    }
}

/** Lays the element out only if it fits the height left for it; otherwise it takes no space. */
private fun Modifier.dropWhenShort(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        if (constraints.hasBoundedHeight && placeable.height > constraints.maxHeight) {
            layout(0, 0) {}
        } else {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

/** [length] boxes: filled ones show a dot, the next one a lime border, glow and caret. */
@Composable
fun PinBoxes(
    length: Int,
    filled: Int,
    modifier: Modifier = Modifier,
) {
    // Four boxes as drawn; a longer PIN saved before the 4-press rule gets narrower boxes so they still fit.
    val width: Dp = if (length <= 5) 75.dp else (440.dp / length).coerceAtLeast(28.dp)
    val gap: Dp = if (length <= 5) 18.dp else 8.dp
    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = modifier) {
        repeat(length) { index ->
            val current = index == filled
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(width = width, height = 92.dp)
                        .kidGlow(enabled = current)
                        .background(if (current) KidColors.LimeFill else KidColors.Tile)
                        .border(if (current) 2.5.dp else 1.5.dp, if (current) KidColors.Lime else KidColors.TileEdge),
            ) {
                when {
                    index < filled -> {
                        Box(
                            Modifier
                                .size(17.dp)
                                .background(KidColors.Text, CircleShape),
                        )
                    }

                    current -> {
                        Box(
                            Modifier
                                .size(width = 3.dp, height = 40.dp)
                                .background(KidColors.Lime),
                        )
                    }
                }
            }
        }
    }
}

/** The remote's D-pad as a hint: ↑ / ← OK → / ↓. */
@Composable
fun DpadHint(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        DpadKey(stringResource(R.string.fa_arrow_up_long), glyph = true)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DpadKey(stringResource(R.string.fa_arrow_left_long), glyph = true)
            DpadKey(stringResource(R.string.kid_key_ok), glyph = false)
            DpadKey(stringResource(R.string.fa_arrow_right_long), glyph = true)
        }
        DpadKey(stringResource(R.string.fa_arrow_down_long), glyph = true)
    }
}

@Composable
private fun DpadKey(
    text: String,
    glyph: Boolean,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(42.dp)
                .background(KidColors.Tile, shape)
                .border(1.dp, KidColors.TileEdge, shape),
    ) {
        if (glyph) {
            Text(text = text, fontFamily = FontAwesome, fontSize = 15.sp, color = KidColors.Text)
        } else {
            Text(text = text, style = KidText.body(14.sp, FontWeight.Bold), color = KidColors.Text)
        }
    }
}

/** [PinEntry] in its own panel dialog; Back calls [onDismissRequest]. */
@Composable
fun PinEntryDialog(
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    length: Int = PinRules.LENGTH,
    allowDigits: Boolean = true,
    checks: Int = 0,
    lockedUntilMillis: Long = 0L,
    title: String? = stringResource(R.string.enter_pin),
    prompt: AnnotatedString? = null,
    message: String? = null,
    onClickServerAuth: (() -> Unit)? = null,
    serverAuthRemovesPin: Boolean = true,
) {
    // Not scrolling: the entry takes every D-pad key, so its footer must stay on screen instead
    KidPanelDialog(onDismissRequest = onDismissRequest, width = 590.dp, scrollable = false) {
        PinEntry(
            onSubmit = onSubmit,
            length = length,
            allowDigits = allowDigits,
            checks = checks,
            lockedUntilMillis = lockedUntilMillis,
            title = title,
            prompt = prompt,
            message = message,
            onClickServerAuth = onClickServerAuth,
            serverAuthRemovesPin = serverAuthRemovesPin,
        )
    }
}

/** "Enter **[name]'s** PIN to continue". */
@Composable
fun pinPromptFor(name: String?): AnnotatedString? =
    name?.takeIf { it.isNotBlank() }?.let { emphasise(stringResource(R.string.pin_prompt_name, it), "$it's") }

@PreviewTvSpec
@Composable
private fun PinEntryPreview() {
    WholphinTheme {
        KidPanel(Modifier.width(590.dp).padding(16.dp)) {
            PinEntry(onSubmit = {}, prompt = AnnotatedString("Enter Justin M's PIN to continue"))
        }
    }
}
