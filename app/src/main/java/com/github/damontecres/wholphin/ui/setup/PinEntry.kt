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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.github.damontecres.wholphin.kids.PinCheckResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

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

/** What a submitted PIN turned out to be; [WRONG] entries count towards the forgot-PIN hint (contract 11.2) */
enum class PinOutcome { WRONG, OTHER }

/** A gate check's [PinOutcome]: a lockout came from a wrong entry too */
fun PinCheckResult.outcome(): PinOutcome = if (this == PinCheckResult.Correct) PinOutcome.OTHER else PinOutcome.WRONG

/**
 * The PIN prompt (owner mockup `4-pin.png`): [length] boxes, the D-pad as a hint, and the OK / BACK keys.
 *
 * Presses after the [length]th are ignored; the [length]th submits the PIN on its own, and OK submits what is
 * typed so far. Only a press that started here counts, so the OK that opened the prompt never submits it. With
 * nothing typed, OK runs [onClickServerAuth] when set ("Login via server"); Back dismisses the dialog around it.
 *
 * The entry owns its latch: while [onSubmit] runs (the caller checks the PIN, with attempt backoff where it
 * applies, and acts on it) every PIN key and OK is ignored, so nothing typed meanwhile can start a second guess
 * or cancel the pending one. The latch is released however [onSubmit] ends (done, wrong, failed or cancelled)
 * and the boxes clear; if it throws, a retryable error shows. Keys are ignored until [lockedUntilMillis] too.
 * The latch and the lockout are read when a key arrives, never from a value captured at composition.
 *
 * The boxes and the key-hint footer always keep their room: the title and prompt are limited to two lines each,
 * and in a short panel the message is shortened and the D-pad hint dropped instead.
 *
 * Forgot PIN (contract 11.2): once [onSubmit] has reported more than [PinRules.FORGOT_PIN_AFTER_WRONG] wrong entries on
 * this entry, the D-pad hint gives way to "Forgot your PIN?" with the website's reset page and a QR code for it. Every
 * PIN screen gets it; the gate's backoff still applies.
 *
 * @param onSubmit checks [PinOutcome.WRONG] for a wrong PIN, [PinOutcome.OTHER] for anything else (right, a new PIN)
 * @param length presses to collect: [PinRules.LENGTH] for a new PIN, [PinRules.entryLength] of the saved PIN
 * when checking one
 * @param allowDigits false while choosing a new PIN, which is arrows only; checking a saved PIN accepts digits
 * @param prompt the line under the title, eg "Enter **Justin M's** PIN to continue"
 * @param message a line under the boxes (wrong PIN, locked out)
 * @param serverAuthRemovesPin shows the "will remove PIN" note with the server sign-in hint
 */
@Composable
fun PinEntry(
    onSubmit: suspend (String) -> PinOutcome,
    modifier: Modifier = Modifier,
    length: Int = PinRules.LENGTH,
    allowDigits: Boolean = true,
    lockedUntilMillis: Long = 0L,
    title: String? = stringResource(R.string.enter_pin),
    prompt: AnnotatedString? = null,
    message: String? = null,
    onClickServerAuth: (() -> Unit)? = null,
    serverAuthRemovesPin: Boolean = true,
) {
    var input by remember { mutableStateOf("") }
    var pressed by remember { mutableStateOf<Key?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var wrongEntries by remember { mutableIntStateOf(0) }
    var lockedOut by remember { mutableStateOf(lockedUntilMillis > System.currentTimeMillis()) }
    val lockedUntil by rememberUpdatedState(lockedUntilMillis)
    val submitNow by rememberUpdatedState(onSubmit)
    val serverAuth by rememberUpdatedState(onClickServerAuth)
    LaunchedEffect(lockedUntilMillis) {
        val wait = lockedUntilMillis - System.currentTimeMillis()
        if (wait > 0) {
            lockedOut = true
            delay(wait)
        }
        lockedOut = false
    }
    val scope = rememberCoroutineScope()

    // Read at key time: a 4th press followed straight away by OK finds the latch already set
    fun accepting(): Boolean = !busy && !lockedOut && System.currentTimeMillis() >= lockedUntil

    fun submit(pin: String) {
        if (!accepting()) return
        busy = true
        failed = false
        input = pin
        scope.launch {
            try {
                if (submitNow(pin) == PinOutcome.WRONG) wrongEntries++
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "PIN check failed")
                failed = true
            } finally {
                input = ""
                busy = false
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    val isPinKey = pinPress(event.key) != null
                    if (!isPinKey && !isEnterKey(event)) return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (pressed == null) pressed = event.key
                        }

                        KeyEventType.KeyUp -> {
                            if (pressed == event.key) {
                                pressed = null
                                // A number key while choosing a new PIN isn't a press at all
                                val press = pinPress(event.key)?.takeIf { allowDigits || it in PinRules.ARROWS }
                                when {
                                    !accepting() -> {}

                                    isPinKey -> {
                                        if (press != null) {
                                            val next = PinRules.append(input, press, length)
                                            if (PinRules.isComplete(next, length) && next != input) submit(next) else input = next
                                        }
                                    }

                                    input.isNotEmpty() -> {
                                        submit(input)
                                    }

                                    else -> {
                                        serverAuth?.invoke()
                                    }
                                }
                            }
                        }
                    }
                    true
                }.focusable(),
    ) {
        // The header can't grow without limit, so the boxes and footer below always keep their room
        if (title != null) KidTitle(title, maxLines = 2)
        if (prompt != null) {
            KidGap(10.dp)
            KidSubtitle(prompt, color = KidColors.Text, size = 19.sp, maxLines = 2)
        }
        KidGap(22.dp)
        PinBoxes(length = length, filled = input.length)
        // Only what is left after the header, boxes and footer: the message shortens, the D-pad hint drops out
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f, fill = false).clipToBounds(),
        ) {
            val shown = if (failed) stringResource(R.string.kids_error_generic) else message
            if (shown != null) {
                KidGap(12.dp)
                KidErrorText(shown, maxLines = 2)
            }
            val forgotUrl = forgotPinUrl()?.takeIf { PinRules.offersForgotPin(wrongEntries) }
            if (forgotUrl != null) {
                KidGap(16.dp)
                ForgotPinHint(forgotUrl)
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.dropWhenShort(),
                ) {
                    KidGap(18.dp)
                    DpadHint()
                }
            }
        }
        KidGap(18.dp)
        KidDivider()
        KidGap(16.dp)
        val serverAuthHint = onClickServerAuth != null && input.isEmpty() && !busy
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

/** "Forgot your PIN? You can reset it on the website at theweasel.tv/weaselplex/profiles", beside a QR code for [url]. */
@Composable
fun ForgotPinHint(
    url: String,
    modifier: Modifier = Modifier,
) {
    val shown = url.removePrefix("https://").removePrefix("http://")
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        QrCode(content = url, size = 112.dp)
        Text(
            text = emphasise(stringResource(R.string.forgot_pin_hint, shown), shown, KidColors.Lime),
            style = KidText.body(16.sp),
            color = KidColors.Text,
            modifier = Modifier.width(300.dp),
        )
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
    onSubmit: suspend (String) -> PinOutcome,
    length: Int = PinRules.LENGTH,
    allowDigits: Boolean = true,
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
            PinEntry(onSubmit = { PinOutcome.OTHER }, prompt = AnnotatedString("Enter Justin M's PIN to continue"))
        }
    }
}
