package com.github.damontecres.wholphin.leanback.setup

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.SeerrAuthMethod
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.util.LoadingState

/**
 * Adding a Seerr (Requests) server (`AddSeerServerDialog`): first the sign-in kind (API key, Jellyfin user, local user),
 * then the URL with the API key, or the URL, username and password. Back in a form goes back to the choice. A successful
 * connection closes it ([onDismissRequest]). WeaselFin never offers Quick Connect here: it is made from the Jellyfin
 * session automatically.
 *
 * Entry point for SETTINGS (and anything else that adds a Seerr server): create it with the `SwitchSeerrViewModel`'s
 * callbacks, [show] it, and pass every new `serverConnectionStatus` to [bindStatus]:
 *
 * ```
 * val dialog = SeerrServerDialog(context, currentUsername = user?.name, onSubmit = seerrVm::submitServer,
 *     onResetStatus = seerrVm::resetStatus, onDismissRequest = { dialog.dismiss() })
 * dialog.show(); seerrVm.serverConnectionStatus.collect { dialog.bindStatus(it) }
 * ```
 */
class SeerrServerDialog(
    private val context: Context,
    private val currentUsername: String?,
    private val onSubmit: (url: String, username: String, passwordOrApiKey: String, method: SeerrAuthMethod) -> Unit,
    private val onResetStatus: () -> Unit,
    private val onDismissRequest: () -> Unit,
) {
    private var chooser: MenuDialog? = null
    private var form: SeerrFormDialog? = null
    private var status: LoadingState = LoadingState.Pending

    /** Shows the sign-in kind choice */
    fun show() {
        showChooser()
    }

    /** Closes whatever is showing */
    fun dismiss() {
        chooser?.dismiss()
        chooser = null
        form?.dismiss()
        form = null
    }

    /** The view model's connection status: a success closes the dialog, an error shows in the form */
    fun bindStatus(status: LoadingState) {
        this.status = status
        if (status is LoadingState.Success) {
            onDismissRequest()
            return
        }
        form?.bindStatus(status)
    }

    private fun showChooser() {
        chooser =
            MenuDialog(
                context,
                title = context.getString(R.string.seerr_login),
                items =
                    listOf(
                        MenuItem(context.getString(R.string.api_key)) { choose(SeerrAuthMethod.API_KEY) },
                        MenuItem(context.getString(R.string.seerr_jellyfin_user)) { choose(SeerrAuthMethod.JELLYFIN) },
                        MenuItem(context.getString(R.string.seerr_local_user)) { choose(SeerrAuthMethod.LOCAL) },
                    ),
                onDismissRequest = onDismissRequest,
                dismissOnClick = false,
                waitToLoad = false,
            ).also { it.show() }
    }

    private fun choose(method: SeerrAuthMethod) {
        onResetStatus()
        chooser?.dismiss()
        chooser = null
        form =
            SeerrFormDialog(context, method, currentUsername.orEmpty(), onSubmit) {
                // Back in a form goes back to the choice
                form?.dismiss()
                form = null
                showChooser()
            }.also {
                it.bindStatus(status)
                it.show()
            }
    }
}

/**
 * The URL + API key form (`AddSeerrServerApiKey`) or the URL + username + password form (`AddSeerrServerUsername`):
 * Next moves down the fields, Go on the last one submits, an error shows under them (and clears as soon as a field
 * changes), and Submit is enabled once the fields are filled; the username form spins while it connects.
 */
private class SeerrFormDialog(
    context: Context,
    private val method: SeerrAuthMethod,
    username: String,
    private val onSubmit: (String, String, String, SeerrAuthMethod) -> Unit,
    onDismissRequest: () -> Unit,
) : BasicDialog(context, onDismissRequest, maxHeightDp = 440f) {
    private val apiKey = method == SeerrAuthMethod.API_KEY
    private val url = field(SetupEditText.PLAIN, EditorInfo.IME_ACTION_NEXT)
    private val user = if (apiKey) null else field(SetupEditText.PLAIN, EditorInfo.IME_ACTION_NEXT).apply { setText(username) }
    private val secret = field(SetupEditText.PASSWORD, EditorInfo.IME_ACTION_GO)
    private val errorText: TextView = context.setupText(color = AppTheme.palette.error) { applyTextRole(TextRole.TITLE_LARGE) }
    private val submit = SetupButton(context, context.getString(R.string.submit), textButton = apiKey)
    private var error: String? = null
    private var status: LoadingState = LoadingState.Pending

    init {
        content.setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        content.minimumWidth = context.dp(320)
        val title = context.getString(if (apiKey) R.string.enter_url_api_key else R.string.username_or_password)
        add(
            context.setupText(if (AppTheme.isNeon) title.uppercase() else title, gravity = Gravity.CENTER) {
                if (AppTheme.isNeon) neonHeader(26f, TextRole.TITLE_MEDIUM) else applyTextRole(TextRole.TITLE_MEDIUM)
            },
        )
        add(row(R.string.url, url))
        user?.let { add(row(R.string.username, it)) }
        add(row(if (apiKey) R.string.api_key else R.string.password, secret))
        add(errorText, wrap())
        submit.setOnClickListener { doSubmit() }
        add(submit)
        val fields = listOfNotNull(url, user, secret)
        fields.forEachIndexed { index, f ->
            f.setOnEditorActionListener { _, actionId, _ ->
                when (actionId) {
                    EditorInfo.IME_ACTION_NEXT -> {
                        fields.getOrNull(index + 1)?.requestFocus()
                        true
                    }

                    EditorInfo.IME_ACTION_GO -> {
                        doSubmit()
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
            f.addTextChangedListener(
                afterChanged {
                    error = null
                    render()
                },
            )
        }
        initialFocus = url
        render()
    }

    private fun field(
        type: Int,
        ime: Int,
    ) = SetupEditText(context).apply {
        inputType = type
        imeOptions = ime
    }

    private fun add(
        view: View,
        params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
    ) {
        if (content.childCount > 0) params.topMargin = context.dp(8)
        content.addView(view, params)
    }

    private fun row(
        label: Int,
        field: SetupEditText,
    ): View =
        horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(
                context.setupText(context.getString(label)).apply { setPadding(0, 0, context.dp(8), 0) },
                LinearLayout.LayoutParams(context.dp(90), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(field)
        }

    private fun doSubmit() {
        if (apiKey) {
            onSubmit(url.text.toString(), "", secret.text.toString(), SeerrAuthMethod.API_KEY)
        } else {
            onSubmit(url.text.toString(), user?.text.toString(), secret.text.toString(), method)
        }
    }

    fun bindStatus(status: LoadingState) {
        if (status != this.status) {
            this.status = status
            // A new status brings its own error back
            error = (status as? LoadingState.Error)?.localizedMessage
        }
        render()
    }

    private fun render() {
        errorText.text = error
        errorText.visibility = if (error != null) View.VISIBLE else View.GONE
        val filled =
            if (apiKey) {
                !url.text.isNullOrBlank() && !secret.text.isNullOrBlank()
            } else {
                !url.text.isNullOrBlank() && !user?.text.isNullOrBlank()
            }
        val loading = !apiKey && status == LoadingState.Loading
        submit.isEnabled = error.isNullOrBlank() && filled && !loading
        submit.showProgress = loading
    }
}
