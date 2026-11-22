package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.leanback.preference.LeanbackListPreferenceDialogFragmentCompat
import androidx.leanback.preference.LeanbackPreferenceDialogFragmentCompat
import androidx.leanback.widget.VerticalGridView
import androidx.preference.ListPreference
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.RailAccents
import com.github.damontecres.wholphin.leanback.theme.dp
import androidx.leanback.preference.R as LeanbackPreferenceR

/** A panel fragment that consumes BACK itself first (eg to confirm discarding a change) */
interface SettingsBackHandler {
    /** @return true when BACK was handled and the panel should stay */
    fun handleBack(): Boolean
}

private fun Fragment.settingsAccent(): Int = (parentFragment as? SettingsPageFragment)?.accent ?: RailAccents.SETTINGS

private fun Fragment.themedInflater(inflater: LayoutInflater): LayoutInflater =
    inflater.cloneInContext(ContextThemeWrapper(requireActivity(), R.style.lb_settings_PreferenceTheme))

/**
 * The choices of a single or multiple choice row: Leanback's own list panel (`LeanbackListPreferenceDialogFragmentCompat`:
 * a radio button or check box per choice, OK picks), restyled as a WeaselPlex settings panel with the panel heading
 * and the settings row look. A single choice closes the panel; multiple choices apply each toggle at once.
 */
class SettingsListDialogFragment : LeanbackListPreferenceDialogFragmentCompat() {
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater = themedInflater(super.onGetLayoutInflater(savedInstanceState))

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val accent = settingsAccent()
        val frame = view.findViewById<LinearLayout>(LeanbackPreferenceR.id.main_frame) ?: view as? LinearLayout ?: return
        frame.background = null
        frame.elevation = 0f
        frame.clipChildren = false
        val titleContainer = view.findViewById<View>(LeanbackPreferenceR.id.decor_title_container)
        val title = view.findViewById<TextView>(LeanbackPreferenceR.id.decor_title)?.text ?: preference?.dialogTitle ?: ""
        titleContainer?.visibility = View.GONE
        val header = SettingsPanelHeader(requireContext(), accent).apply { this.title = title }
        frame.addView(header, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        view.findViewById<TextView>(android.R.id.message)?.let { SettingsLook.styleBody(it) }
        val grid = view.findViewById<VerticalGridView>(android.R.id.list) ?: return
        val horizontal = view.context.dp(if (SettingsLook.neon) 32 else 16)
        val vertical = view.context.dp(SettingsLook.LIST_EDGE_DP)
        grid.setPadding(horizontal, vertical, horizontal, vertical)
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) = styleChoice(child, accent)

                override fun onChildViewDetachedFromWindow(child: View) {}
            },
        )
        // Open on the current value, as the old choice dialogs marked it
        (preference as? ListPreference)?.let { pref ->
            val index = pref.findIndexOfValue(pref.value)
            if (index >= 0) grid.selectedPosition = index
        }
    }

    private fun styleChoice(
        item: View,
        accent: Int,
    ) {
        item.background = SettingsLook.rowBackground(item.context, accent)
        item.minimumHeight = item.context.dp(48)
        item.findViewById<TextView>(android.R.id.title)?.let(SettingsLook::styleTitle)
        (item as? ViewGroup)?.children?.filterIsInstance<CompoundButton>()?.forEach { button ->
            val on = if (SettingsLook.neon) accent else AppTheme.palette.primary
            val off = if (SettingsLook.neon) NeonColors.MID else AppTheme.palette.onSurfaceVariant
            button.buttonTintList =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(on, off),
                )
        }
    }

    companion object {
        fun newInstance(key: String): SettingsListDialogFragment =
            SettingsListDialogFragment().apply { arguments = bundleOf(LeanbackPreferenceDialogFragmentCompat.ARG_KEY to key) }
    }
}

/**
 * The text editor of a text row (the old `StringInputDialog`), in the settings panel: the title, the field (up to
 * [SettingsEditTextPreference.maxLines] lines) and Cancel / Save. Done on the keyboard saves a one-line field. With
 * [SettingsEditTextPreference.confirmDiscard], leaving with changes asks to discard them.
 */
class SettingsEditTextFragment :
    LeanbackPreferenceDialogFragmentCompat(),
    SettingsBackHandler {
    private var field: EditText? = null
    private var initial: String = ""

    private val editPreference: SettingsEditTextPreference? get() = preference as? SettingsEditTextPreference

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater = themedInflater(super.onGetLayoutInflater(savedInstanceState))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = inflater.context
        val accent = settingsAccent()
        val pref = editPreference
        initial = pref?.text.orEmpty()
        val column =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
        column.addView(
            SettingsPanelHeader(ctx, accent).apply { title = pref?.dialogTitle ?: pref?.title ?: "" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        val body =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = ctx.dp(if (SettingsLook.neon) 32 else 16)
                setPadding(pad, ctx.dp(16), pad, ctx.dp(16))
            }
        val maxLines = pref?.maxLines ?: 1
        val edit =
            settingsEditText(ctx, accent).apply {
                setText(initial)
                inputType =
                    (pref?.inputType ?: InputType.TYPE_CLASS_TEXT).let {
                        if (maxLines > 1) it or InputType.TYPE_TEXT_FLAG_MULTI_LINE else it
                    }
                if (maxLines > 1) {
                    isSingleLine = false
                    setLines(maxLines.coerceAtMost(10))
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    imeOptions = EditorInfo.IME_ACTION_NONE
                    isVerticalScrollBarEnabled = true
                } else {
                    isSingleLine = true
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setOnEditorActionListener { _, actionId, event ->
                        if (actionId == EditorInfo.IME_ACTION_DONE ||
                            (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                        ) {
                            save()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        field = edit
        body.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
        buttons.addView(
            settingsButton(ctx, ctx.getString(R.string.cancel), accent) { cancel() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(8) },
        )
        buttons.addView(
            settingsButton(ctx, ctx.getString(R.string.save), accent) { save() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(8) },
        )
        body.addView(
            buttons,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(16)
            },
        )
        column.addView(
            android.widget.ScrollView(ctx).apply {
                isFillViewport = false
                addView(body)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return column
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        field?.requestFocus()
    }

    private fun save() {
        val pref = editPreference ?: return close()
        val text = field?.text?.toString().orEmpty()
        if (pref.callChangeListener(text)) pref.text = text
        close()
    }

    private fun cancel() {
        if (!handleBack()) close()
    }

    private fun close() {
        (parentFragment as? SettingsPageFragment)?.closePanel() ?: parentFragmentManager.popBackStack()
    }

    override fun handleBack(): Boolean {
        val pref = editPreference
        val changed = field?.text?.toString().orEmpty() != initial
        if (pref?.confirmDiscard == true && changed) {
            showConfirmDialog(
                requireContext(),
                settingsAccent(),
                getString(R.string.discard_change),
                null,
                onConfirm = { close() },
            )
            return true
        }
        return false
    }

    override fun onDestroyView() {
        field = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(key: String): SettingsEditTextFragment =
            SettingsEditTextFragment().apply { arguments = bundleOf(ARG_KEY to key) }
    }
}

/**
 * A custom page inside the settings panel (Quick Connect, PIN, release notes, license text, Seerr server forms):
 * the panel heading over [onCreateContent]. Leanback Preference has no widget for these, so they are plain
 * fragments in the panel's stack, like Leanback's own preference dialogs.
 */
abstract class SettingsPanelPage : Fragment() {
    protected val accent: Int get() = settingsAccent()

    protected val page: SettingsPageFragment? get() = parentFragment as? SettingsPageFragment

    /** The heading */
    abstract fun title(context: Context): CharSequence

    /** The content under the heading */
    abstract fun onCreateContent(
        context: Context,
        container: LinearLayout,
    )

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        val column =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
            }
        column.addView(
            SettingsPanelHeader(ctx, accent).apply { title = title(ctx) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        val content =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = ctx.dp(if (SettingsLook.neon) 32 else 16)
                setPadding(pad, ctx.dp(16), pad, ctx.dp(16))
                clipChildren = false
                clipToPadding = false
            }
        onCreateContent(ctx, content)
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    /** Closes this page */
    fun close() {
        page?.closePanel() ?: parentFragmentManager.popBackStack()
    }
}
