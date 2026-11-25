package com.github.damontecres.wholphin.leanback.search

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.model.MovieDetails
import com.github.damontecres.wholphin.data.model.RequestStatus
import com.github.damontecres.wholphin.data.model.SeerrAvailability
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlColors
import com.github.damontecres.wholphin.leanback.theme.ControlScales
import com.github.damontecres.wholphin.leanback.theme.ControlStyles
import com.github.damontecres.wholphin.leanback.theme.ControlSurfaceStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.AvailabilityIndicatorView
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.control.SurfaceController
import com.github.damontecres.wholphin.leanback.widget.control.TvSwitchView
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuItem
import com.github.damontecres.wholphin.leanback.widget.dialog.MenuListView
import com.github.damontecres.wholphin.ui.detail.discover.MovieRequest
import com.github.damontecres.wholphin.ui.detail.discover.RequestSeason
import com.github.damontecres.wholphin.ui.detail.discover.SeerrProfile
import com.github.damontecres.wholphin.ui.detail.discover.SeerrRequestData
import com.github.damontecres.wholphin.ui.detail.discover.SeerrRootFolder
import com.github.damontecres.wholphin.ui.detail.discover.TvRequest
import com.github.damontecres.wholphin.ui.formatSeasonNumber
import com.github.damontecres.wholphin.util.LoadingState

/**
 * `ClickSwitch` (RequestSeasons.kt): a focusable surface with a switch and its label, 8dp padding, at least 54dp tall.
 * WeaselTV: the outline recipe (1dp `line2`, accent border and `chipOn` fill when focused, accent glow); other themes
 * transparent, inverseSurface when focused, 12dp corners, 1.1 when focused. The label wraps to every line it needs.
 */
internal class ClickSwitchView(
    context: Context,
) : LinearLayout(context) {
    private val switch = TvSwitchView(context)
    val label = TextView(context).apply { applyTextRole(TextRole.BODY_LARGE) }
    private val surface =
        SurfaceController(this) { scope ->
            if (AppTheme.isNeon) {
                val accent = scope.accentOrDefault
                val (container, content) = ControlStyles.outlineColors(accent)
                ControlSurfaceStyle(
                    container = container,
                    content = content,
                    border = ControlStyles.surfaceBorder(accent, 1f),
                    glow = ControlStyles.surfaceGlow(accent),
                    scale = ControlScales.neonSurface(),
                )
            } else {
                val p = AppTheme.palette
                ControlSurfaceStyle(
                    container = ControlColors(rest = 0, focused = p.inverseSurface, pressed = p.inverseSurface),
                    content = ControlColors(rest = p.onSurface, focused = p.inverseOnSurface, pressed = p.inverseOnSurface),
                    scale = ControlScales.BUTTON,
                    cornerDp = 12f,
                )
            }
        }

    var checked: Boolean
        get() = switch.checked
        set(value) {
            switch.checked = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        minimumHeight = context.dp(54)
        setPadding(context.dp(8), 0, context.dp(8), 0)
        addView(switch, LayoutParams(context.dp(40), context.dp(24)).apply { marginEnd = context.dp(8) })
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        surface.onContentColor = { label.setTextColor(it) }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        // Wrap the label's width (the old Row was as wide as its content), but never wider than the space given
        val lp = label.layoutParams as LayoutParams
        lp.width = LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && measuredWidth >= MeasureSpec.getSize(widthMeasureSpec)) {
            lp.width = 0
            lp.weight = 1f
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        @Suppress("SENSELESS_COMPARISON")
        if (surface != null) surface.refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surface.onAttached()
    }
}

/** `ChooseProfile`: "Quality profile" with the chosen one ("name (Default)"); OK opens the list of profiles */
internal fun profileChooserRow(
    context: Context,
    selected: SeerrProfile,
    profiles: List<SeerrProfile>,
    onClickProfile: (SeerrProfile) -> Unit,
): AppListItem {
    val defaultStr = context.getString(R.string.is_default)
    val title = context.getString(R.string.quality_profile)
    return AppListItem(context).apply {
        setHeadline(title)
        setSupporting(if (selected.default) "${selected.name} ($defaultStr)" else selected.name)
        setOnClickListener { opener ->
            MenuDialog(
                context,
                title,
                profiles.map {
                    MenuItem(
                        headline = it.name,
                        supporting = if (it.default) defaultStr else null,
                        reserveSupportingLine = true,
                        leading = ListLeading.SelectedDot(it == selected),
                        onClick = { onClickProfile(it) },
                    )
                },
                dismissOnClick = true,
                waitToLoad = false,
                elevationDp = 3f,
                opener = opener,
            ).show()
        }
    }
}

/** `ChooseFolder`: "Root folder" with the chosen path ("path (Default)") and its free space; OK opens the list */
internal fun folderChooserRow(
    context: Context,
    selected: SeerrRootFolder,
    folders: List<SeerrRootFolder>,
    onClickFolder: (SeerrRootFolder) -> Unit,
): AppListItem {
    val defaultStr = context.getString(R.string.is_default)
    val title = context.getString(R.string.root_folder)
    return AppListItem(context).apply {
        setHeadline(title)
        setSupporting(if (selected.default) "${selected.path} ($defaultStr)" else selected.path)
        setTrailing(selected.freeSpace)
        setOnClickListener { opener ->
            MenuDialog(
                context,
                title,
                folders.map {
                    MenuItem(
                        headline = it.path,
                        supporting = if (it.default) defaultStr else null,
                        reserveSupportingLine = true,
                        trailing = it.freeSpace,
                        leading = ListLeading.SelectedDot(it == selected),
                        onClick = { onClickFolder(it) },
                    )
                },
                dismissOnClick = true,
                waitToLoad = false,
                elevationDp = 3f,
                opener = opener,
            ).show()
        }
    }
}

/** A 1dp outline-variant divider (`HorizontalDivider`) */
private class DividerView(
    context: Context,
) : View(context) {
    private val paint = Paint().apply { color = AppTheme.palette.borderVariant }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(1))

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

private fun titleText(
    context: Context,
    text: String,
) = TextView(context).apply {
    this.text = text
    applyTextRole(TextRole.TITLE_LARGE)
    setTextColor(AppTheme.palette.onSurface)
}

private fun fullWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** The default choice of a list: the one marked default, else the first */
private fun <T> List<T>.defaultOf(isDefault: (T) -> Boolean): T? = firstOrNull(isDefault) ?: firstOrNull()

/**
 * The request dialogs' shared frame (`BasicDialog` with 16dp around the content): the loading view, the error, or the
 * form the subclass builds; [update] rebuilds when the state changes.
 */
internal abstract class RequestDialogBase(
    context: Context,
    opener: View?,
    private val onSendLogs: () -> Unit,
) : AppDialog(context, DialogOptions(), opener = opener) {
    protected val frame =
        FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            val pad = context.dp(16)
            setPadding(pad, pad, pad, pad)
        }
    private var shown: LoadingState? = null

    init {
        setPanelContent(frame)
    }

    /** Show [loading]; [form] builds the content for [LoadingState.Success] (called again on every update) */
    protected fun showState(
        loading: LoadingState,
        form: () -> View?,
    ) {
        when (loading) {
            is LoadingState.Error -> {
                if (shown != loading) {
                    frame.removeAllViews()
                    frame.addView(ErrorView(context, loading, onSendLogs))
                }
            }

            LoadingState.Loading, LoadingState.Pending -> {
                if (shown !is LoadingState.Loading && shown !is LoadingState.Pending) {
                    frame.removeAllViews()
                    frame.addView(
                        LoadingPageView(context),
                        FrameLayout.LayoutParams(context.dp(320), context.dp(200)),
                    )
                }
            }

            LoadingState.Success -> {
                val focused = frame.findFocus()?.id
                val view = form() ?: return
                if (view.parent !== frame) {
                    frame.removeAllViews()
                    frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                if (shown != LoadingState.Success) {
                    initialFocus()?.requestFocus()
                } else if (focused != null && focused != View.NO_ID) {
                    frame.findViewById<View>(focused)?.requestFocus()
                }
            }
        }
        shown = loading
    }

    /** The view to focus when the form first shows */
    protected abstract fun initialFocus(): View?
}

/**
 * `RequestMovieDialog`: while the quality profiles and folders load, the loading view; with neither the request is
 * sent at once; otherwise the title ("Title (date)") over Submit and the profile and folder choosers. There is no 4K
 * choice (owner, 2026-11-23).
 */
internal class RequestMovieDialog(
    context: Context,
    opener: View?,
    private val movie: MovieDetails,
    private val onSubmit: (MovieRequest) -> Unit,
    onSendLogs: () -> Unit,
) : RequestDialogBase(context, opener, onSendLogs) {
    private var data = SeerrRequestData()
    private var profile: SeerrProfile? = null
    private var folder: SeerrRootFolder? = null
    private var submitted = false
    private var form: View? = null
    private var firstFocus: View? = null

    private val title: String get() = movie.title + " (${movie.releaseDate ?: ""})"

    fun update(
        loading: LoadingState,
        data: SeerrRequestData,
    ) {
        val changed = data != this.data
        this.data = data
        if (changed) form = null
        showState(loading) { form ?: buildForm().also { form = it } }
    }

    override fun initialFocus(): View? = firstFocus

    private fun buildForm(): View? {
        val noOptions = data.profiles.isEmpty() && data.rootFolders.isEmpty()
        if (noOptions) {
            if (!submitted) {
                submitted = true
                onSubmit(MovieRequest(data, movie.id, null, null))
            }
            return null
        }
        return optionsForm()
    }

    /** `RequestMovieWithOptions` */
    private fun optionsForm(): View {
        val profiles = data.profiles
        val folders = data.rootFolders
        if (profile == null || profile !in profiles) profile = profiles.defaultOf { it.default }
        if (folder == null || folder !in folders) folder = folders.defaultOf { it.default }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        column.addView(titleText(context, title), fullWidth())
        val items =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, 0, context.dp(8))
            }
        val submit =
            AppButton(context).apply {
                id = R.id.lb_search_request_submit
                setLabel(R.string.submit)
                setOnClickListener { onSubmit(MovieRequest(data, movie.id, profile?.id, folder?.path)) }
            }
        actions.addView(submit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        items.addView(actions, fullWidth())
        profile?.takeIf { profiles.isNotEmpty() }?.let { selected ->
            items.addView(
                profileChooserRow(context, selected, profiles) {
                    profile = it
                    rebuild()
                }.apply { id = R.id.lb_search_request_profile },
                fullWidth(),
            )
        }
        folder?.takeIf { folders.isNotEmpty() }?.let { selected ->
            items.addView(
                folderChooserRow(context, selected, folders) {
                    folder = it
                    rebuild()
                }.apply { id = R.id.lb_search_request_folder },
                fullWidth(),
            )
        }
        column.addView(
            MenuListView(context).apply {
                val edge = context.dp(MenuDialog.DIALOG_LIST_EDGE_DP)
                setPadding(0, edge, 0, edge)
                addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            fullWidth().apply { topMargin = context.dp(8) },
        )
        firstFocus = submit
        return column
    }

    private fun rebuild() {
        form = null
        showState(LoadingState.Success) { buildForm().also { form = it } }
    }
}

/**
 * `RequestSeasonsDialog`: the title, the last submission error, then a list: Submit (disabled with nothing chosen, "Submitting…" while sending), the profile and folder choosers, a divider to the panel's edges,
 * "Seasons" with Select all, one row per season (status dot, name, episode count, switch; seasons already requested
 * or available are on and cannot be changed), and a second Submit after more than three seasons.
 */
internal class RequestSeasonsDialog(
    context: Context,
    opener: View?,
    private val id: Int,
    private val title: String,
    private val onSubmit: (TvRequest) -> Unit,
    onSendLogs: () -> Unit,
) : RequestDialogBase(context, opener, onSendLogs) {
    private var data = SeerrRequestData()
    private var seasons: List<RequestSeason> = emptyList()
    private var submitting = false
    private var errorMessage: String? = null
    private var profile: SeerrProfile? = null
    private var folder: SeerrRootFolder? = null
    private var selectionFor: List<RequestSeason>? = null
    private val selectedSeasons = linkedSetOf<Int>()
    private var availableSeasons: Set<Int> = emptySet()
    private var allSeasonNumbers: Set<Int> = emptySet()
    private var firstFocus: View? = null

    fun update(
        loading: LoadingState,
        data: SeerrRequestData,
        seasons: List<RequestSeason>,
        submitting: Boolean,
        errorMessage: String?,
    ) {
        this.data = data
        this.seasons = seasons
        this.submitting = submitting
        this.errorMessage = errorMessage
        showState(loading) { buildForm() }
    }

    override fun initialFocus(): View? = firstFocus

    /** The choices start over whenever the season list changes (the old `remember(seasons)`) */
    private fun resetSelection(list: List<RequestSeason>) {
        if (selectionFor == list) return
        selectionFor = list
        allSeasonNumbers = list.filter { it.editable }.mapNotNull { it.season.seasonNumber }.toSet()
        availableSeasons =
            list
                .filter { season ->
                    !season.editable && (
                        season.status == RequestStatus.PENDING ||
                            season.status == RequestStatus.APPROVED ||
                            season.status == RequestStatus.COMPLETED ||
                            season.availability == SeerrAvailability.PARTIALLY_AVAILABLE ||
                            season.availability == SeerrAvailability.AVAILABLE
                    )
                }.mapNotNull { it.season.seasonNumber }
                .toSet()
        selectedSeasons.clear()
        selectedSeasons.addAll(list.filter { it.status == RequestStatus.PENDING }.mapNotNull { it.season.seasonNumber })
    }

    private fun submit() {
        if (submitting) return
        onSubmit(
            TvRequest(
                data = data,
                tvId = id,
                seasons = selectedSeasons.toList(),
                profileId = profile?.id,
                folder = folder?.path,
            ),
        )
    }

    private fun submitButton(viewId: Int): AppButton =
        AppButton(context).apply {
            this.id = viewId
            setLabel(if (submitting) "Submitting…" else context.getString(R.string.submit))
            isActionEnabled = selectedSeasons.isNotEmpty()
            setOnClickListener { submit() }
        }

    private fun rebuild() = showState(LoadingState.Success) { buildForm() }

    private fun buildForm(): View {
        val list = seasons
        resetSelection(list)
        val profiles = data.profiles
        val folders = data.rootFolders
        if (profile == null || profile !in profiles) profile = profiles.defaultOf { it.default }
        if (folder == null || folder !in folders) folder = folders.defaultOf { it.default }

        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        column.addView(titleText(context, title), fullWidth())
        errorMessage?.let {
            column.addView(
                TextView(context).apply {
                    text = it
                    applyTextRole(TextRole.BODY_LARGE)
                    setTextColor(AppTheme.palette.error)
                },
                fullWidth().apply { topMargin = context.dp(8) },
            )
        }
        val items =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, context.dp(8), 0, context.dp(8))
            }
        val submit = submitButton(R.id.lb_search_request_submit)
        actions.addView(submit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        items.addView(actions, fullWidth())
        profile?.takeIf { profiles.isNotEmpty() }?.let { selected ->
            items.addView(
                profileChooserRow(context, selected, profiles) {
                    profile = it
                    rebuild()
                }.apply { id = R.id.lb_search_request_profile },
                fullWidth(),
            )
        }
        folder?.takeIf { folders.isNotEmpty() }?.let { selected ->
            items.addView(
                folderChooserRow(context, selected, folders) {
                    folder = it
                    rebuild()
                }.apply { id = R.id.lb_search_request_folder },
                fullWidth(),
            )
        }
        // Through the dialog's 16dp padding to the panel edges
        items.addView(
            DividerView(context),
            fullWidth().apply {
                marginStart = -context.dp(16)
                marginEnd = -context.dp(16)
            },
        )
        items.addView(titleText(context, context.getString(R.string.tv_seasons)), fullWidth())
        val allSelected = selectedSeasons.containsAll(allSeasonNumbers)
        items.addView(
            ClickSwitchView(context).apply {
                id = R.id.lb_search_request_select_all
                label.setText(R.string.select_all)
                checked = allSelected
                setOnClickListener {
                    if (allSelected) selectedSeasons.removeAll(allSeasonNumbers) else selectedSeasons.addAll(allSeasonNumbers)
                    rebuild()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        list.forEachIndexed { index, season ->
            val number = season.season.seasonNumber
            val checked = number in selectedSeasons || number in availableSeasons
            items.addView(
                seasonRow(season, checked) {
                    if (checked) {
                        selectedSeasons.remove(number)
                    } else {
                        number?.let { selectedSeasons.add(it) }
                    }
                    rebuild()
                }.apply { id = SEASON_ID_BASE + index },
                fullWidth(),
            )
        }
        if (list.size > 3) {
            val bottom =
                FrameLayout(context).apply {
                    clipChildren = false
                    clipToPadding = false
                    setPadding(0, context.dp(8), 0, context.dp(8))
                    addView(
                        submitButton(R.id.lb_search_request_submit_bottom),
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                    )
                }
            items.addView(bottom, fullWidth())
        }
        column.addView(
            MenuListView(context).apply {
                // Room for the focus glow of the Submit button and the last season
                setPadding(0, context.dp(12), 0, context.dp(MenuDialog.DIALOG_LIST_EDGE_DP))
                addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
            fullWidth().apply { topMargin = context.dp(8) },
        )
        firstFocus = submit
        return column
    }

    /** `SeasonListItem` */
    private fun seasonRow(
        season: RequestSeason,
        checked: Boolean,
        onClick: () -> Unit,
    ): AppListItem =
        AppListItem(context).apply {
            setHeadline(season.season.name ?: season.season.seasonNumber?.let { formatSeasonNumber(it) } ?: "")
            setSupporting(season.season.episodeCount?.let { "$it " + context.getString(R.string.episodes) })
            val indicator =
                when (season.availability) {
                    SeerrAvailability.PENDING, SeerrAvailability.PROCESSING, SeerrAvailability.PARTIALLY_AVAILABLE,
                    SeerrAvailability.AVAILABLE,
                    -> AvailabilityIndicatorView(context).apply { bind(season.availability) }

                    else -> null
                }
            setLeading(indicator?.let { ListLeading.Custom(it) })
            setTrailingView(TvSwitchView(context).apply { this.checked = checked }.withSize(40, 24))
            isActionEnabled = season.editable
            setOnClickListener { onClick() }
        }

    private fun View.withSize(
        widthDp: Int,
        heightDp: Int,
    ): View =
        FrameLayout(context).also { box ->
            box.addView(this, FrameLayout.LayoutParams(context.dp(widthDp), context.dp(heightDp)))
        }

    private companion object {
        /** View ids of the season rows, so focus stays on the same row when the list is rebuilt */
        const val SEASON_ID_BASE = 0x5EA50000
    }
}
