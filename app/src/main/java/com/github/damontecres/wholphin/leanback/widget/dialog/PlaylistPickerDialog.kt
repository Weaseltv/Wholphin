package com.github.damontecres.wholphin.leanback.widget.dialog

import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.PlaylistInfo
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.control.AppListItem
import com.github.damontecres.wholphin.leanback.widget.control.CircularProgressView
import com.github.damontecres.wholphin.leanback.widget.control.EditTextBox
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.ListLeading
import com.github.damontecres.wholphin.leanback.widget.control.SearchEditTextBox
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.MediaType

/**
 * Pick (or create) a server playlist (the old `PlaylistDialog` in ui/detail/PlaylistList.kt, which the context menu's
 * "Add to playlist" opened): a 600dp panel at the top of the screen, at most 480dp tall, with the title, a search
 * field and the results: each playlist (name, media type, item count) and, when [createEnabled], "Create playlist",
 * which asks for a name.
 *
 * Focus: an invisible placeholder takes focus first, so the keyboard does not open, until the results load; then the
 * first result is focused unless the search field has focus. From the search field only DOWN leaves (to the
 * results); from the results UP returns to the search field and LEFT / RIGHT / DOWN stop. Typing searches after
 * 750 ms; the Search key searches at once. A choice closes the dialog.
 *
 * This is a CONTROLS-private version for the context menu; DETAILS owns the playlist pages and may reuse it.
 */
class PlaylistPickerDialog(
    context: Context,
    title: String,
    private val state: StateFlow<PlaylistLoadingState>,
    private val onClick: (PlaylistInfo) -> Unit,
    private val onSearch: (String) -> Unit,
    private val createEnabled: Boolean,
    private val onCreatePlaylist: (String) -> Unit,
    elevationDp: Float = 3f,
    accent: Int? = null,
    opener: View? = null,
) : AppDialog(
        context,
        DialogOptions(usePlatformDefaultWidth = false, elevationDp = elevationDp, gravity = Gravity.TOP),
        accent,
        opener,
    ) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var searchJob: Job? = null
    private var searchClicked = false
    private var query = ""

    private val search =
        SearchEditTextBox(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    private val placeholder =
        View(context).apply {
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }
    private val results = FrameLayout(context)
    private var firstResult: View? = null
    private var rendered: PlaylistLoadingState? = null

    init {
        val column =
            object : LinearLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec(context.dp(600), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(context.dp(480), MeasureSpec.AT_MOST),
                    )
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                val p = context.dp(16)
                setPadding(p, p, p, p)
            }
        column.addView(
            TextView(context).apply {
                text = title
                applyTextRole(TextRole.HEADLINE_SMALL)
                setTextColor(AppTheme.palette.onSurface)
                gravity = Gravity.CENTER_HORIZONTAL
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        column.addView(search, (search.layoutParams as LinearLayout.LayoutParams).apply { topMargin = context.dp(8) })
        column.addView(placeholder, LinearLayout.LayoutParams(0, 0).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(
            results,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = context.dp(8)
            },
        )
        setPanelContent(column)

        search.onTextChanged = { text ->
            query = text
            val previous = (state.value as? PlaylistLoadingState.Success)?.query ?: ""
            searchJob?.cancel()
            if (previous != text) {
                searchJob =
                    scope.launch {
                        delay(750)
                        if (searchClicked) {
                            searchClicked = false
                            return@launch
                        }
                        onSearch.invoke(text)
                    }
            }
        }
        search.onSearch = {
            if (query.isNotBlank()) {
                searchClicked = true
                onSearch.invoke(query)
            }
        }
        // Only DOWN leaves the search field, to the results
        search.editText.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    firstResult?.requestFocus()
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    true
                }

                else -> {
                    false
                }
            }
        }
        if (state.value !is PlaylistLoadingState.Success) placeholder.requestFocus()
        render(state.value, initial = true)
    }

    override fun onStart() {
        super.onStart()
        scope.launch { state.collect { render(it, initial = false) } }
    }

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }

    private fun render(
        value: PlaylistLoadingState,
        initial: Boolean,
    ) {
        if (value == rendered) return
        rendered = value
        results.removeAllViews()
        firstResult = null
        when (value) {
            PlaylistLoadingState.Pending,
            PlaylistLoadingState.Loading,
            -> {
                results.addView(
                    CircularProgressView(context),
                    FrameLayout.LayoutParams(context.dp(48), context.dp(48), Gravity.CENTER_HORIZONTAL),
                )
            }

            is PlaylistLoadingState.Error -> {
                results.addView(ErrorView(context, value.message, value.exception, onSendLogs = {}))
            }

            is PlaylistLoadingState.Success -> {
                val list = buildList(value.items)
                results.addView(list)
                // Once results load, focus them unless the search field has focus
                if (!search.editText.hasFocus()) {
                    firstResult?.requestFocus()
                }
            }
        }
    }

    private fun buildList(playlists: List<PlaylistInfo>): View {
        val items =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        val rows = mutableListOf<View>()
        val gap = context.dp(8)
        playlists.forEach { playlist ->
            val row =
                AppListItem(context).apply {
                    setHeadline(playlist.name)
                    setSupporting(
                        context.getString(
                            when (playlist.mediaType) {
                                MediaType.UNKNOWN -> R.string.unknown
                                MediaType.VIDEO -> R.string.video
                                MediaType.AUDIO -> R.string.audio
                                MediaType.PHOTO -> R.string.photos
                                MediaType.BOOK -> R.string.unknown
                            },
                        ),
                    )
                    setTrailing(context.resources.getQuantityString(R.plurals.items, playlist.count, playlist.count))
                    setOnClickListener {
                        onClick.invoke(playlist)
                        dismiss()
                    }
                }
            rows.add(row)
            items.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (items.childCount > 0) topMargin = gap
                },
            )
        }
        if (createEnabled) {
            items.addView(
                MenuDividerView(context),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply {
                    if (items.childCount > 0) topMargin = gap
                },
            )
            val create =
                AppListItem(context).apply {
                    setHeadline(context.getString(R.string.create_playlist))
                    setLeading(ListLeading.Icon(R.drawable.lb_widget_ctl_ic_add))
                    setOnClickListener { showCreate() }
                }
            rows.add(create)
            items.addView(create, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // UP goes back to the search field; LEFT / RIGHT / DOWN out of the results stop
        rows.forEachIndexed { index, row ->
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        index == rows.lastIndex
                    }

                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (index == 0) {
                            search.editText.requestFocus()
                            true
                        } else {
                            false
                        }
                    }

                    else -> {
                        false
                    }
                }
            }
        }
        firstResult = rows.firstOrNull()
        return MenuListView(context).apply {
            setPadding(
                context.dp(16),
                context.dp(MenuDialog.DIALOG_LIST_EDGE_DP),
                context.dp(16),
                context.dp(MenuDialog.DIALOG_LIST_EDGE_DP),
            )
            addView(items, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** "Create playlist": a name field (Done creates) and Submit, enabled once the name is not blank */
    private fun showCreate() {
        val nameDialog = AppDialog(context, DialogOptions(usePlatformDefaultWidth = false, elevationDp = 10f), accent)
        val field =
            EditTextBox(context, EditTextBox.Variant.VALUE).apply {
                editText.inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                    android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                editText.imeOptions = EditorInfo.IME_ACTION_DONE
            }
        val submit =
            AppButton(context).apply {
                setLabel(R.string.submit)
                isActionEnabled = false
            }

        fun create() {
            nameDialog.dismiss()
            dismiss()
            onCreatePlaylist.invoke(field.text)
        }
        field.onTextChanged = { submit.isActionEnabled = it.isNotNullOrBlank() }
        field.onImeAction = { action ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                create()
                true
            } else {
                false
            }
        }
        submit.setOnClickListener { create() }
        val column =
            object : LinearLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val width = (resources.displayMetrics.widthPixels * .4f).toInt()
                    super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                clipToPadding = false
                val p = context.dp(16)
                setPadding(p, p, p, p)
                addView(
                    TextView(context).apply {
                        setText(R.string.name)
                        applyTextRole(TextRole.BODY_LARGE)
                        setTextColor(AppTheme.palette.onSurface)
                    },
                )
                addView(
                    field,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(8)
                    },
                )
                addView(
                    submit,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = context.dp(8)
                    },
                )
            }
        nameDialog.setPanelContent(column)
        field.editText.requestFocus()
        nameDialog.show()
    }
}
