package com.github.damontecres.wholphin.ui.kids

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonFonts

/*
 * The kid-profile screens' shared look (owner mockups, 2026-10-22): a dark panel with a 1dp edge and a glowing
 * lime bar on its left, an Audiowide title, Exo 2 copy, key-hint chips, and square tiles / cards that take a lime
 * border, glow and dark lime fill when focused. Sizes are in dp for the 960×540 dp TV frame (mockup px / 2).
 *
 * These screens always use this look, whatever the app theme, so a PIN prompt looks the same everywhere.
 */

/** Colours of the kid-profile panels. The lime is the WeaselPlex volt accent. */
object KidColors {
    val Lime = NeonBoard.Volt
    val Panel = Color(0xFF0C0F14)
    val PanelEdge = Color(0xFF1F242C)
    val Tile = Color(0xFF12151C)
    val TileEdge = Color(0xFF363C46)
    val LimeFill = Color(0xFF161E08)
    val Text = NeonBoard.Text
    val Muted = Color(0xFFAEB5C0)
    val Faint = Color(0xFF6B7380)
    val Error = Color(0xFFFF6B6B)
    val Track = Color(0xFF22262E)
}

/** Exo 2 for copy, Audiowide for titles; the defaults on flavors that don't ship them. */
@Composable
private fun kidFonts(): Pair<FontFamily, FontFamily> {
    val context = LocalContext.current
    return remember(context) { NeonFonts.families(context) }
}

object KidText {
    @Composable
    fun display(
        size: TextUnit,
        tracking: TextUnit = .02.em,
    ): TextStyle = TextStyle(fontFamily = kidFonts().second, fontSize = size, lineHeight = size * 1.2f, letterSpacing = tracking)

    @Composable
    fun body(
        size: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        tracking: TextUnit = 0.sp,
    ): TextStyle =
        TextStyle(
            fontFamily = kidFonts().first,
            fontSize = size,
            fontWeight = weight,
            lineHeight = size * 1.3f,
            letterSpacing = tracking,
        )
}

/** A soft glow of [color] around the element's bounds; draw it before the element's own background. */
fun Modifier.kidGlow(
    enabled: Boolean = true,
    color: Color = KidColors.Lime,
    radius: Dp = 14.dp,
    alpha: Float = .6f,
): Modifier =
    if (!enabled) {
        this
    } else {
        drawBehind { drawGlowRect(color.copy(alpha = alpha), radius.toPx(), 0f, size.width) }
    }

private fun DrawScope.drawGlowRect(
    color: Color,
    radius: Float,
    left: Float,
    right: Float,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint()
        paint.asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            setShadowLayer(radius, 0f, 0f, color.toArgb())
        }
        canvas.drawRect(left, 0f, right, size.height, paint)
    }
}

/** The dark panel with the glowing lime bar on its left edge. */
@Composable
fun KidPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 44.dp, vertical = 34.dp),
    scrollable: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(KidColors.Panel)
                .border(1.dp, KidColors.PanelEdge)
                .drawWithContent {
                    drawContent()
                    val bar = 5.dp.toPx()
                    drawGlowRect(KidColors.Lime.copy(alpha = .7f), 12.dp.toPx(), 0f, bar)
                    drawRect(KidColors.Lime, size = size.copy(width = bar))
                },
    ) {
        Column(
            horizontalAlignment = horizontalAlignment,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 5.dp)
                    .padding(contentPadding),
            content = content,
        )
    }
}

/** [KidPanel] as a dialog [width] wide; Back dismisses it. */
@Composable
fun KidPanelDialog(
    onDismissRequest: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Room around the panel for the bar's glow
        Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            KidPanel(
                modifier = modifier.width(width).heightIn(max = 516.dp),
                scrollable = scrollable,
                content = content,
            )
        }
    }
}

/** The display-font panel title, eg "Add kid profile". */
@Composable
fun KidTitle(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        // Long titles (eg "Turn off kid protection on this TV") step down so the panel still fits the screen
        style = KidText.display(if (text.length > 22) 24.sp else 32.sp),
        color = KidColors.Text,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Small, spaced, uppercase lime label above a dialog's content. */
@Composable
fun KidEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = KidText.display(12.sp, tracking = .3.em),
        color = KidColors.Lime,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
fun KidSubtitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = KidColors.Muted,
) = KidSubtitle(AnnotatedString(text), modifier, color)

@Composable
fun KidSubtitle(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = KidColors.Muted,
    size: TextUnit = 17.sp,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = KidText.body(size),
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun KidErrorText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = KidText.body(15.sp, FontWeight.Medium),
        color = KidColors.Error,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** [text] with the first [part] in it emphasised: bold, and in [color] when set. */
fun emphasise(
    text: String,
    part: String,
    color: Color? = null,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val start = if (part.isEmpty()) -1 else text.indexOf(part)
        if (start >= 0) {
            addStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = color ?: Color.Unspecified),
                start,
                start + part.length,
            )
        }
    }

/** One remote key and what it does, eg [OK] Select. */
@Composable
fun KeyHint(
    key: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = key,
            style = KidText.body(12.sp, FontWeight.Bold, tracking = .06.em),
            color = KidColors.Text,
            modifier =
                Modifier
                    .border(1.dp, KidColors.TileEdge, RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(
            text = label,
            style = KidText.body(14.sp),
            color = KidColors.Muted,
        )
    }
}

/** The footer key hints: [OK] [okLabel] and [BACK] Cancel. */
@Composable
fun OkBackHints(
    modifier: Modifier = Modifier,
    okLabel: String = stringResource(R.string.kid_hint_select),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        KeyHint(stringResource(R.string.kid_key_ok), okLabel)
        KeyHint(stringResource(R.string.kid_key_back), stringResource(R.string.cancel))
    }
}

/** A 1dp rule across the panel. */
@Composable
fun KidDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(KidColors.PanelEdge),
    )
}

/** The lime ring spinner. */
@Composable
fun KidSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    strokeWidth: Dp = 7.dp,
) {
    CircularProgressIndicator(
        color = KidColors.Lime,
        trackColor = KidColors.Track,
        strokeWidth = strokeWidth,
        modifier = modifier.size(size),
    )
}

/**
 * A large square tile with [label] under it (a kid, an avatar, "Add a new kid"). Focused: lime border, glow and
 * dark lime fill, and the label turns lime. [dashed] draws the dashed edge of an "add" tile. [modifier] goes on the
 * focusable tile.
 */
@Composable
fun KidTile(
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    dashed: Boolean = false,
    caption: String? = null,
    description: String? = label,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier.size(size).semantics { description?.let { contentDescription = it } },
            shape = ClickableSurfaceDefaults.shape(RectangleShape),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) KidColors.LimeFill else KidColors.Tile,
                    focusedContainerColor = KidColors.LimeFill,
                    pressedContainerColor = KidColors.LimeFill,
                    disabledContainerColor = KidColors.Tile.copy(alpha = .5f),
                ),
            border =
                ClickableSurfaceDefaults.border(
                    border =
                        when {
                            selected -> Border(BorderStroke(2.dp, KidColors.Lime.copy(alpha = .75f)), shape = RectangleShape)
                            dashed -> Border.None
                            else -> Border(BorderStroke(1.5.dp, KidColors.TileEdge), shape = RectangleShape)
                        },
                    focusedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                    pressedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                    disabledBorder = Border(BorderStroke(1.dp, KidColors.TileEdge.copy(alpha = .5f)), shape = RectangleShape),
                ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(KidColors.Lime.copy(alpha = .55f), 14.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(if (dashed && !focused) Modifier.dashedEdge(KidColors.TileEdge) else Modifier),
            ) {
                content(focused)
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = KidText.body(18.sp, FontWeight.Bold),
                color =
                    if (focused) {
                        KidColors.Lime
                    } else if (enabled) {
                        KidColors.Text
                    } else {
                        KidColors.Faint
                    },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(size + 24.dp),
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = KidText.body(13.sp),
                color = KidColors.Faint,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size + 24.dp).padding(top = 0.dp),
            )
        }
    }
}

private fun Modifier.dashedEdge(color: Color): Modifier =
    drawBehind {
        val stroke = 1.5.dp.toPx()
        drawRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = size.copy(width = size.width - stroke, height = size.height - stroke),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
        )
    }

/** The thin "+" of an "add" tile. */
@Composable
fun KidPlus(
    modifier: Modifier = Modifier,
    color: Color = KidColors.Muted,
) {
    Canvas(modifier.size(36.dp)) {
        val w = 2.5.dp.toPx()
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = w)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = w)
    }
}

/** A profile's picture, or the first letter of [name] when it has none (or it fails to load). */
@Composable
fun KidAvatar(
    name: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    letterSize: TextUnit = 68.sp,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!imageUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().clip(RectangleShape),
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                style = KidText.body(letterSize, FontWeight.Bold),
                color = KidColors.Text,
            )
        }
    }
}

/** A level card: title, description and a radio circle. Selected: lime border, dark lime fill, filled radio. */
@Composable
fun KidOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = 100.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) KidColors.LimeFill else KidColors.Tile,
                focusedContainerColor = KidColors.LimeFill,
                pressedContainerColor = KidColors.LimeFill,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border =
                    if (selected) {
                        Border(BorderStroke(2.dp, KidColors.Lime.copy(alpha = .75f)), shape = RectangleShape)
                    } else {
                        Border(BorderStroke(1.5.dp, KidColors.TileEdge), shape = RectangleShape)
                    },
                focusedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(KidColors.Lime.copy(alpha = .5f), 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = title,
                    style = KidText.body(21.sp, FontWeight.Bold),
                    color = KidColors.Text,
                    modifier = Modifier.weight(1f).padding(top = 4.dp),
                )
                KidRadio(selected)
            }
            Text(
                text = description,
                style = KidText.body(15.sp),
                color = KidColors.Muted,
            )
        }
    }
}

@Composable
private fun KidRadio(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(23.dp)
                .border(2.dp, if (selected) KidColors.Lime else KidColors.Muted, CircleShape),
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(13.dp)
                    .background(KidColors.Lime, CircleShape),
            )
        }
    }
}

/** An outlined button; focused: lime border, glow and fill. [arrow] adds a lime → after the label. */
@Composable
fun KidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrow: Boolean = false,
    minWidth: Dp = 200.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.widthIn(min = minWidth).height(50.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = KidColors.Tile,
                focusedContainerColor = KidColors.LimeFill,
                pressedContainerColor = KidColors.LimeFill,
                disabledContainerColor = KidColors.Tile,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(2.dp, KidColors.Muted.copy(alpha = .7f)), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                disabledBorder = Border(BorderStroke(1.dp, KidColors.TileEdge), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(KidColors.Lime.copy(alpha = .5f), 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .widthIn(min = minWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp),
        ) {
            Text(
                text = text,
                style = KidText.body(19.sp, FontWeight.Bold, tracking = .02.em),
                color =
                    when {
                        !enabled -> KidColors.Faint
                        focused -> KidColors.Lime
                        else -> KidColors.Text
                    },
                maxLines = 1,
            )
            if (arrow) {
                Text(
                    text = stringResource(R.string.fa_arrow_right_long),
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = KidColors.Lime,
                )
            }
        }
    }
}

/** A full-width choice row with an optional FontAwesome [glyph], for short lists (eg which parent unlocks). */
@Composable
fun KidListRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = KidColors.Tile,
                focusedContainerColor = KidColors.LimeFill,
                pressedContainerColor = KidColors.LimeFill,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.5.dp, KidColors.TileEdge), shape = RectangleShape),
                focusedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
                pressedBorder = Border(BorderStroke(2.5.dp, KidColors.Lime), shape = RectangleShape),
            ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(KidColors.Lime.copy(alpha = .5f), 12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    fontFamily = FontAwesome,
                    fontSize = 16.sp,
                    color = if (focused) KidColors.Lime else KidColors.Muted,
                    modifier = Modifier.width(22.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = text,
                style = KidText.body(18.sp, FontWeight.SemiBold),
                color = if (focused) KidColors.Lime else KidColors.Text,
            )
        }
    }
}

/** The name field: lime border, glow and caret when focused; [placeholder] while empty. */
@Composable
fun KidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val textStyle = KidText.body(22.sp).copy(color = KidColors.Text)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(KidColors.Lime),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .kidGlow(enabled = focused, alpha = .5f)
                        .background(if (focused) KidColors.LimeFill else KidColors.Tile)
                        .border(
                            if (focused) 2.5.dp else 1.5.dp,
                            when {
                                focused -> KidColors.Lime
                                isError -> KidColors.Error
                                else -> KidColors.TileEdge
                            },
                        ).padding(horizontal = 18.dp),
            ) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = textStyle, color = KidColors.Faint)
                }
                inner()
            }
        },
    )
}

/** A label above a field, eg NAME. */
@Composable
fun KidFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = KidText.body(14.sp, FontWeight.Bold, tracking = .06.em),
        color = KidColors.Muted,
        modifier = modifier,
    )
}

/** A section heading inside a panel, eg "What can they watch?". */
@Composable
fun KidSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = KidText.body(19.sp, FontWeight.Bold),
        color = KidColors.Text,
        modifier = modifier,
    )
}

@Composable
fun KidGap(height: Dp) = Spacer(Modifier.height(height))
