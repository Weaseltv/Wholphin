package com.github.damontecres.wholphin.leanback.detail

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.LocalTrailer
import com.github.damontecres.wholphin.data.model.RemoteTrailer
import com.github.damontecres.wholphin.data.model.Trailer
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.provideButtonsOverArtwork
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.ButtonVariant
import com.github.damontecres.wholphin.leanback.widget.dialog.ConfirmDeleteDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.leanback.widget.dialog.chooseVersionParams
import com.github.damontecres.wholphin.leanback.widget.text.reportDetailActionHeight
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import kotlin.time.Duration

/*
 * The details pages' action buttons (ui/components/PlayButtons.kt and TrailerDialog.kt as Views): the expandable
 * play / icon buttons, Delete with its confirmation, the trailer button and dialog, and the standard row of them
 * (`ExpandablePlayButtons`).
 */

/** `MinButtonSize`: action buttons are 40dp tall and at least 40dp wide */
const val MIN_BUTTON_DP = 40f

/** `ButtonGlowRoom`: room for a focused button's glow at the ends of a scrolling button row */
const val BUTTON_GLOW_ROOM_DP = 16f

/** The stock content colour at 80 % (Delete's icon at rest) and red at 80 % (focused) */
private const val DELETE_FOCUSED_TINT = 0xCCFF0000.toInt()

/** The red of a favourite's heart (`Color.Red`) */
const val FAVORITE_RED = 0xFFFF0000.toInt()

/**
 * An expandable action button (`ExpandablePlayButton` / `ExpandableFaButton`): [collapsed] shows only its icon until
 * focused, with an uppercase label; [allCaps] (the WeaselTV default) an uppercase label in the button font; otherwise
 * the label (titleSmall) shows while focused on other themes. [setTitle] keeps that case.
 */
class ExpandableButton(
    context: Context,
    val collapsed: Boolean,
    val allCaps: Boolean,
) : AppButton(context) {
    /** Set the label in this button's case */
    fun setTitle(text: CharSequence) {
        if (collapsed || allCaps) setLabel(text, allCaps = true) else setLabel(text)
    }

    fun setTitle(
        @StringRes text: Int,
    ) = setTitle(context.getString(text))
}

/** Factories for the old `ExpandablePlayButton` / `ExpandableFaButton` / `DeleteButton` / `TrailerButton` */
object DetailButtons {
    private fun AppButton.sizeAsActionButton(
        startDp: Float,
        topDp: Float,
        endDp: Float,
        bottomDp: Float,
    ) {
        // `requiredSizeIn(minWidth = 40, minHeight = 40, maxHeight = 40)`: the whole button is 40dp tall
        setContentPaddingDp(startDp, topDp, endDp, bottomDp)
        contentHeightDp = MIN_BUTTON_DP - topDp - bottomDp
        exactContentHeight = true
        minimumWidth = context.dp(MIN_BUTTON_DP)
    }

    private fun ExpandableButton.labelForExpandable(
        text: CharSequence,
        collapsedPadding: Pair<Float, Float>,
        capsPadding: Pair<Float, Float>,
    ) {
        setTitle(text)
        when {
            collapsed -> {
                setLabelPaddingDp(collapsedPadding.first, collapsedPadding.second)
                labelOnlyWhenFocused = true
            }

            allCaps -> {
                setLabelPaddingDp(capsPadding.first, capsPadding.second)
                labelOnlyWhenFocused = false
            }

            else -> {
                // Other themes: the label (titleSmall) shows while focused
                labelView.applyTextRole(TextRole.TITLE_SMALL)
                setLabelPaddingDp(0f, 4f)
                labelOnlyWhenFocused = !AppTheme.isNeon
            }
        }
    }

    /**
     * `ExpandablePlayButton`: a 28dp icon and the label. Play, Resume and Restart are the primary (accent filled) button
     * on the WeaselTV theme, every other one an outline. [mirror] flips the icon (Restart). [collapsed] shows only the
     * icon until focused.
     */
    fun play(
        context: Context,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        mirror: Boolean = false,
        allCaps: Boolean = AppTheme.isNeon,
        collapsed: Boolean = false,
        onClick: () -> Unit,
    ): ExpandableButton =
        ExpandableButton(context, collapsed, allCaps).apply {
            val primary = !AppTheme.isNeon || title == R.string.play || title == R.string.resume || title == R.string.restart
            variant = if (primary) ButtonVariant.PRIMARY else ButtonVariant.DEFAULT
            if (collapsed) sizeAsActionButton(6f, 0f, 6f, 0f) else sizeAsActionButton(4f, 4f, 4f, 4f)
            setIcon(icon, sizeDp = 28f)
            (iconView.layoutParams as LinearLayout.LayoutParams).marginStart = if (collapsed) 0 else context.dp(2)
            iconView.scaleX = if (mirror) -1f else 1f
            labelForExpandable(context.getString(title), 6f to 6f, 6f to 8f)
            setOnClickListener { onClick() }
        }

    /**
     * `ExpandableFaButton`: a Font Awesome glyph (16sp in a 32dp box) and the label; [glyphColor] colours only the
     * glyph (the red heart). [collapsed] shows only the glyph until focused.
     */
    fun fa(
        context: Context,
        title: CharSequence,
        @StringRes glyph: Int,
        glyphColor: Int? = null,
        collapsed: Boolean = false,
        allCaps: Boolean = AppTheme.isNeon,
        onClick: () -> Unit,
    ): ExpandableButton =
        ExpandableButton(context, collapsed, allCaps).apply {
            when {
                collapsed -> sizeAsActionButton(4f, 0f, 4f, 0f)
                allCaps -> sizeAsActionButton(4f, 0f, 4f, 0f)
                else -> sizeAsActionButton(4f, 4f, 4f, 4f)
            }
            setGlyph(glyph, color = glyphColor)
            labelForExpandable(title, 2f to 6f, 2f to 8f)
            setOnClickListener { onClick() }
        }

    fun fa(
        context: Context,
        @StringRes title: Int,
        @StringRes glyph: Int,
        glyphColor: Int? = null,
        collapsed: Boolean = false,
        allCaps: Boolean = AppTheme.isNeon,
        onClick: () -> Unit,
    ): ExpandableButton = fa(context, context.getString(title), glyph, glyphColor, collapsed, allCaps, onClick)

    /** Re-label and re-colour an [fa] button in place (favourite / watched toggles) */
    fun updateFa(
        button: ExpandableButton,
        title: CharSequence,
        @StringRes glyph: Int,
        glyphColor: Int? = null,
    ) {
        button.setGlyph(glyph, color = glyphColor)
        button.setTitle(title)
    }

    /**
     * `DeleteButton`: the delete icon (24dp, red while focused) and "Delete"; OK asks for confirmation
     * ([ConfirmDeleteDialog]) and calls [onConfirmDelete].
     */
    fun delete(
        context: Context,
        title: () -> String,
        allCaps: Boolean = AppTheme.isNeon,
        onConfirmDelete: () -> Unit,
    ): ExpandableButton =
        ExpandableButton(context, collapsed = false, allCaps = allCaps).apply {
            variant = if (!AppTheme.isNeon) ButtonVariant.PRIMARY else ButtonVariant.DEFAULT
            sizeAsActionButton(4f, 4f, 4f, 4f)
            setIcon(R.drawable.lb_widget_ctl_ic_delete, sizeDp = 24f)
            (iconView.layoutParams as LinearLayout.LayoutParams).marginStart = context.dp(4)
            labelForExpandable(context.getString(R.string.delete), 6f to 6f, 6f to 8f)
            val restTint = withAlpha(AppTheme.palette.onSurface, .8f)
            onContentColor = {
                iconView.imageTintList = ColorStateList.valueOf(if (isFocused) DELETE_FOCUSED_TINT else restTint)
            }
            val button = this
            setOnClickListener {
                lateinit var dialog: ConfirmDeleteDialog
                dialog =
                    ConfirmDeleteDialog(
                        context = context,
                        itemTitle = title(),
                        onCancel = { dialog.dismiss() },
                        onConfirm = {
                            onConfirmDelete()
                            dialog.dismiss()
                        },
                        opener = button,
                    )
                dialog.show()
            }
        }

    /**
     * `TrailerButton`: "No trailers" (disabled), "Play trailer" (plays the only one) or "Trailers" (opens the
     * [showTrailerDialog]).
     */
    fun trailers(
        context: Context,
        onClick: (Trailer) -> Unit,
    ): TrailerButtonHolder = TrailerButtonHolder(context, onClick)

    /** Version chooser (`chooseVersionParams` in a menu, as `ExpandablePlayButtons` showed it) */
    fun showVersionChooser(
        anchor: View,
        mediaSources: List<MediaSourceInfo>,
        chosenStreams: ChosenStreams?,
        onChooseVersion: (MediaSourceInfo) -> Unit,
    ) {
        val context = anchor.context
        val params =
            chooseVersionParams(
                context.resources,
                mediaSources,
                chosenStreams?.source?.id?.toUUIDOrNull(),
            ) { index -> onChooseVersion(mediaSources[index]) }
        MenuDialog(context, params, dismissOnClick = true, opener = anchor).show()
    }
}

/** The trailer button and its state (the list of trailers it plays) */
class TrailerButtonHolder(
    context: Context,
    private val onClick: (Trailer) -> Unit,
) {
    private var trailers: List<Trailer> = emptyList()

    val button: ExpandableButton =
        DetailButtons.fa(context, R.string.no_trailers, R.string.fa_film) {
            val list = trailers
            if (list.size == 1) {
                onClick(list.first())
            } else if (list.isNotEmpty()) {
                showTrailerDialog(this.buttonView(), list, onClick)
            }
        }

    private fun buttonView(): View = button

    fun bind(trailers: List<Trailer>) {
        this.trailers = trailers
        val title =
            when {
                trailers.isEmpty() -> R.string.no_trailers
                trailers.size == 1 -> R.string.play_trailer
                else -> R.string.trailers_title
            }
        DetailButtons.updateFa(button, button.context.getString(title), R.string.fa_film)
        button.isActionEnabled = trailers.isNotEmpty()
    }
}

/** `TrailerDialog`: "Play trailer" with each trailer's name and Local / its site (or External) below */
fun showTrailerDialog(
    anchor: View,
    trailers: List<Trailer>,
    onClick: (Trailer) -> Unit,
) {
    val context = anchor.context
    val local = context.getString(R.string.local)
    val external = context.getString(R.string.external_track)
    MenuDialog.show(
        context = context,
        title = context.getString(R.string.play_trailer),
        entries =
            trailers.map { trailer ->
                MenuItem(
                    headline = trailer.name,
                    supporting =
                        when (trailer) {
                            is LocalTrailer -> local
                            is RemoteTrailer -> trailer.subtitle ?: external
                        },
                    onClick = { onClick(trailer) },
                )
            },
        dismissOnClick = true,
        waitToLoad = false,
        opener = anchor,
    )
}

/** What the standard play button row shows (`ExpandablePlayButtons`' parameters) */
data class PlayButtonsState(
    val title: String,
    val resumePosition: Duration,
    val watched: Boolean,
    val favorite: Boolean,
    val canDelete: Boolean,
    /** Null: no trailer button (episodes) */
    val trailers: List<Trailer>?,
    val mediaSources: List<MediaSourceInfo>,
    val chosenStreams: ChosenStreams?,
)

/** The actions of the standard play button row */
class PlayButtonsActions(
    val playOnClick: (position: Duration) -> Unit,
    val watchOnClick: () -> Unit,
    val favoriteOnClick: () -> Unit,
    val moreOnClick: () -> Unit,
    val trailerOnClick: (Trailer) -> Unit,
    val onConfirmDelete: () -> Unit,
    val onChooseVersion: (MediaSourceInfo) -> Unit,
    /** A button took focus (the page brings its header into view) */
    val onButtonFocused: () -> Unit = {},
)

/**
 * `ExpandablePlayButtons`: Play (Resume and Restart when started), Version (several versions), Watchlist, Watched,
 * Trailers (when given), Delete (when allowed) and More, 16dp apart, over the artwork (white outlines and a dark
 * backing on the WeaselTV theme). [collapseMore] shows More as its icon until focused.
 *
 * The row is 8dp + 40dp + 8dp tall plus [bottomPaddingDp], reported as the detail action bar. The first button sits
 * 8dp in from the page's content edge; the row's clipping edge is 16dp further out so the first button's glow shows.
 * Focus starts on Play and returns to the last focused button; when Restart disappears while focused, Play takes the
 * focus.
 */
class ExpandablePlayButtonsView(
    context: Context,
    private val actions: PlayButtonsActions,
    collapseMore: Boolean = false,
    bottomPaddingDp: Float = 16f,
) : ActionButtonRow(context, spacingDp = 16f, glowRoomDp = BUTTON_GLOW_ROOM_DP) {
    private var state: PlayButtonsState? = null

    val playButton: ExpandableButton =
        DetailButtons.play(context, R.string.play, R.drawable.lb_widget_ctl_ic_play_arrow) {
            actions.playOnClick(state?.resumePosition ?: Duration.ZERO)
        }
    private val restartButton: ExpandableButton =
        DetailButtons.play(context, R.string.restart, R.drawable.lb_widget_ctl_ic_refresh, mirror = true) {
            actions.playOnClick(Duration.ZERO)
        }
    private val versionButton: ExpandableButton =
        DetailButtons.fa(context, R.string.version, R.string.fa_file_video) {
            val s = state ?: return@fa
            DetailButtons.showVersionChooser(this.versionAnchor(), s.mediaSources, s.chosenStreams, actions.onChooseVersion)
        }
    private val favoriteButton: ExpandableButton =
        DetailButtons.fa(context, R.string.add_favorite, R.string.fa_heart) { actions.favoriteOnClick() }
    private val watchedButton: ExpandableButton =
        DetailButtons.fa(context, R.string.mark_watched, R.string.fa_eye_slash) { actions.watchOnClick() }
    private val trailerHolder = DetailButtons.trailers(context, actions.trailerOnClick)
    private val deleteButton: ExpandableButton =
        DetailButtons.delete(context, title = { state?.title ?: "" }) { actions.onConfirmDelete() }
    private val moreButton: ExpandableButton =
        DetailButtons.play(
            context,
            R.string.more,
            R.drawable.lb_detail_ic_more_vert,
            collapsed = collapseMore,
        ) { actions.moreOnClick() }

    init {
        provideButtonsOverArtwork()
        val gutter = context.dp(BUTTON_GLOW_ROOM_DP)
        // `contentPadding(start = 8 + 16, end = 16, top = 8, bottom = 8)` inside a row bled 16dp to the start
        strip.setPaddingRelative(context.dp(8f) + gutter, context.dp(8f), gutter, context.dp(8f))
        setPaddingRelative(0, 0, 0, context.dp(bottomPaddingDp))
        listOf(
            playButton,
            restartButton,
            versionButton,
            favoriteButton,
            watchedButton,
            trailerHolder.button,
            deleteButton,
            moreButton,
        ).forEach { button ->
            addButton(button)
            button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) actions.onButtonFocused() }
        }
        firstFocus = playButton
        reportDetailActionHeight()
    }

    private fun versionAnchor(): View = versionButton

    /** Lay the row out bled [BUTTON_GLOW_ROOM_DP] past the start edge of the space it is given */
    fun bledLayoutParams(): ViewGroup.MarginLayoutParams =
        ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -context.dp(BUTTON_GLOW_ROOM_DP)
        }

    fun bind(new: PlayButtonsState) {
        state = new
        val resumed = new.resumePosition > Duration.ZERO
        playButton.setTitle(if (resumed) R.string.resume else R.string.play)
        if (!resumed && restartButton.hasFocus()) {
            // Reset focus if the restart button was focused and it is going to disappear
            playButton.requestFocus()
        }
        restartButton.visibility = if (resumed) View.VISIBLE else View.GONE
        versionButton.visibility = if (new.mediaSources.size > 1) View.VISIBLE else View.GONE
        DetailButtons.updateFa(
            favoriteButton,
            context.getString(if (new.favorite) R.string.remove_favorite else R.string.add_favorite),
            R.string.fa_heart,
            if (new.favorite) FAVORITE_RED else null,
        )
        DetailButtons.updateFa(
            watchedButton,
            context.getString(if (new.watched) R.string.mark_unwatched else R.string.mark_watched),
            if (new.watched) R.string.fa_eye else R.string.fa_eye_slash,
        )
        val trailers = new.trailers
        if (trailers != null) {
            trailerHolder.bind(trailers)
            trailerHolder.button.visibility = View.VISIBLE
        } else {
            trailerHolder.button.visibility = View.GONE
        }
        if (!new.canDelete && deleteButton.hasFocus()) playButton.requestFocus()
        deleteButton.visibility = if (new.canDelete) View.VISIBLE else View.GONE
    }
}
