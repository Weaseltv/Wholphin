package com.github.damontecres.wholphin.leanback.settings

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.preferences.NavDrawerPreferencesViewModel
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.ui.preferences.move
import com.github.damontecres.wholphin.ui.setup.seerr.SwitchSeerrViewModel
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.launch

/**
 * The side menu (nav drawer) items: pin or unpin each and move it up or down (the old `NavDrawerPreference` dialog),
 * as a level of reorderable rows. Changes are saved when the level closes, as the old dialog saved on dismiss.
 */
internal object NavDrawerPinsScreen {
    fun build(
        page: SettingsPageFragment,
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val viewModel = ViewModelProvider(page)[NavDrawerPreferencesViewModel::class.java]
        val ctx = fragment.preferenceManager.context
        val focus = ReorderFocus()
        val rows = mutableMapOf<String, SettingsReorderPreference>()

        fun moveTo(
            id: String,
            items: List<com.github.damontecres.wholphin.ui.preferences.NavDrawerPin>,
        ) {
            viewModel.update(items)
            fragment.refresh()
            fragment.view?.post { fragment.selectPreference(id) }
        }

        fragment.onRefresh {
            val items = viewModel.state.value
            val ids = items.map { it.id }.toSet()
            rows.keys.filter { it !in ids }.forEach { id -> rows.remove(id)?.let(screen::removePreference) }
            items.forEachIndexed { index, pin ->
                val row =
                    rows.getOrPut(pin.id) {
                        SettingsReorderPreference(ctx, focus).also {
                            it.key = pin.id
                            it.accent = page.accent
                            screen.addPreference(it)
                        }
                    }
                row.order = index
                row.title = pin.title
                row.checked = pin.pinned
                row.onMainClick = {
                    val current = viewModel.state.value
                    val i = current.indexOfFirst { it.id == pin.id }
                    if (i >= 0) {
                        moveTo(pin.id, current.toMutableList().apply { set(i, current[i].copy(pinned = !current[i].pinned)) })
                    }
                }
                row.buttons =
                    listOf(
                        ReorderButton(ctx.getString(R.string.fa_caret_up), index > 0, "up") {
                            val current = viewModel.state.value
                            val i = current.indexOfFirst { it.id == pin.id }
                            if (i > 0) moveTo(pin.id, current.move(MoveDirection.UP, i))
                        },
                        ReorderButton(ctx.getString(R.string.fa_caret_down), index < items.lastIndex, "down") {
                            val current = viewModel.state.value
                            val i = current.indexOfFirst { it.id == pin.id }
                            if (i in 0 until current.lastIndex) moveTo(pin.id, current.move(MoveDirection.DOWN, i))
                        },
                    )
            }
        }
        // Saved when the level closes (BACK) or the page goes, as the old dialog saved on dismiss
        fragment.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && !fragment.requireActivity().isChangingConfigurations) viewModel.save()
            },
        )
        fragment.lifecycleScope.launch {
            viewModel.state.collect { fragment.refresh() }
        }
    }
}

/**
 * Quick Connect: authorise another device with its 6-digit code (the old `QuickConnectDialog`). An invalid code says
 * "Code must be 6 digits" above the Submit button and is not sent; the page closes when the server accepts it.
 */
class QuickConnectPage : SettingsPanelPage() {
    private val viewModel: PreferencesViewModel
        get() = ViewModelProvider(requireParentFragment())[PreferencesViewModel::class.java]

    override fun title(context: Context): CharSequence = context.getString(R.string.quick_connect)

    override fun onCreateContent(
        context: Context,
        container: LinearLayout,
    ) {
        val label =
            AppCompatTextView(context).apply {
                applyTextRole(TextRole.TITLE_MEDIUM)
                setTextColor(AppTheme.palette.onSecondaryContainer)
                text = context.getString(R.string.quick_connect_code)
            }
        val error =
            AppCompatTextView(context).apply {
                applyTextRole(TextRole.BODY_SMALL)
                setTextColor(AppTheme.palette.error)
                text = context.getString(R.string.quick_connect_code_error)
                visibility = View.GONE
                setPadding(context.dp(8), 0, 0, 0)
            }
        val field = settingsEditText(context, accent)

        fun isValid(code: String) = code.trim().let { it.length == 6 && it.all(Char::isDigit) }

        fun submit() {
            val code = field.text?.toString().orEmpty()
            if (isValid(code)) {
                error.visibility = View.GONE
                viewModel.authorizeQuickConnect(code.trim())
            } else {
                error.visibility = View.VISIBLE
            }
        }
        field.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(clearError { error.visibility = View.GONE })
        }
        val gap = context.dp(8)
        container.addView(label, lp())
        container.addView(field, lp(top = gap))
        container.addView(error, lp(top = gap))
        container.addView(
            settingsButton(context, context.getString(R.string.submit), accent) { submit() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = gap
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        field.post { field.requestFocus() }
    }

    override fun onDestroy() {
        if (parentFragment != null && !requireActivity().isChangingConfigurations) viewModel.resetQuickConnectStatus()
        super.onDestroy()
    }
}

/**
 * Adding a Seerr server (the old `AddSeerrServerApiKey` / `AddSeerrServerUsername`): the URL with an API key, or with a
 * Jellyfin or local username (the signed-in name filled in) and password. The server's error shows under the fields
 * until something is edited; Submit needs every field.
 */
class SeerrServerPage : SettingsPanelPage() {
    private val viewModel: SwitchSeerrViewModel
        get() = ViewModelProvider(requireParentFragment())[SwitchSeerrViewModel::class.java]

    private val method: String get() = requireArguments().getString(ARG_METHOD) ?: METHOD_API_KEY
    private var errorView: TextView? = null
    private var submitView: TextView? = null
    private var hasError = false
    private val fields = mutableListOf<EditText>()

    override fun title(context: Context): CharSequence =
        context.getString(if (method == METHOD_API_KEY) R.string.enter_url_api_key else R.string.username_or_password)

    override fun onCreateContent(
        context: Context,
        container: LinearLayout,
    ) {
        fields.clear()
        val gap = context.dp(8)

        fun addField(
            label: Int,
            input: Int,
            ime: Int,
            initial: String = "",
        ): EditText {
            container.addView(
                AppCompatTextView(context).apply {
                    SettingsLook.styleBody(this)
                    text = context.getString(label)
                },
                lp(top = if (fields.isEmpty()) 0 else gap),
            )
            val field =
                settingsEditText(context, accent).apply {
                    inputType = input
                    imeOptions = ime
                    isSingleLine = true
                    setText(initial)
                    addTextChangedListener(
                        clearError {
                            hasError = false
                            errorView?.visibility = View.GONE
                            updateSubmit()
                        },
                    )
                }
            container.addView(field, lp(top = context.dp(4)))
            fields += field
            return field
        }
        val plain = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val url = addField(R.string.url, plain or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT)
        val last: EditText
        if (method == METHOD_API_KEY) {
            last = addField(R.string.api_key, password, EditorInfo.IME_ACTION_GO)
        } else {
            val current =
                ViewModelProvider(requireParentFragment())[PreferencesViewModel::class.java]
                    .currentUser.value
                    ?.name
                    .orEmpty()
            addField(R.string.username, plain, EditorInfo.IME_ACTION_NEXT, current)
            last = addField(R.string.password, password, EditorInfo.IME_ACTION_GO)
        }
        last.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        errorView =
            AppCompatTextView(context).apply {
                applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.error)
                visibility = View.GONE
            }.also { container.addView(it, lp(top = gap)) }
        submitView =
            settingsButton(context, context.getString(R.string.submit), accent) { submit() }.also {
                container.addView(
                    it,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = gap
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
        updateSubmit()
        url.post { url.requestFocus() }
    }

    private fun updateSubmit() {
        submitView?.isEnabled = !hasError && fields.all { it.text?.isNotBlank() == true }
        submitView?.isFocusable = submitView?.isEnabled == true
    }

    private fun submit() {
        if (submitView?.isEnabled != true) return
        val values = fields.map { it.text?.toString().orEmpty() }
        when (method) {
            METHOD_API_KEY -> viewModel.submitServer(values[0], "", values[1], SeerrAuthMethod.API_KEY)
            METHOD_JELLYFIN -> viewModel.submitServer(values[0], values[1], values[2], SeerrAuthMethod.JELLYFIN)
            else -> viewModel.submitServer(values[0], values[1], values[2], SeerrAuthMethod.LOCAL)
        }
    }

    /** The server check's result, from the page */
    fun onStatus(status: LoadingState) {
        val error = (status as? LoadingState.Error)?.localizedMessage
        hasError = error != null
        errorView?.text = error
        errorView?.visibility = if (error != null) View.VISIBLE else View.GONE
        updateSubmit()
    }

    override fun onDestroyView() {
        errorView = null
        submitView = null
        fields.clear()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_METHOD = "method"
        const val METHOD_API_KEY = "api_key"
        const val METHOD_JELLYFIN = "jellyfin"
        const val METHOD_LOCAL = "local"

        fun newInstance(method: String): SeerrServerPage = SeerrServerPage().apply { arguments = bundleOf(ARG_METHOD to method) }
    }
}

private fun lp(top: Int = 0) =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

private fun clearError(block: () -> Unit) =
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

        override fun afterTextChanged(s: Editable?) = block()
    }
