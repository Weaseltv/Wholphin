package com.github.damontecres.wholphin.leanback.settings

import android.Manifest
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.ui.formatBytes
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setup.UpdateViewModel
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app update page (`Destination.UpdateApp`, the old `InstallUpdatePage`), a View page: the release notes on the
 * left 60% (focusable; UP/DOWN scroll them), and beside them "Update available", the version change and the
 * "Download & Update" / Cancel buttons. While downloading the page dims behind a progress dialog (BACK cancels).
 *
 * WeaselFin: with `installNow` (the update popup's "Update") the download starts on its own once per visit for a newer
 * release; back from "Install unknown apps" with it now allowed, it carries on without a second press.
 */
@AndroidEntryPoint
class UpdateFragment : DestinationFragment() {
    private val viewModel: UpdateViewModel by viewModels()

    private val installNow: Boolean get() = requireDestination<Destination.UpdateApp>().installNow

    private var permissions = false

    /** Android 9 and older ask for storage first; the update carries on once it's allowed */
    private var awaitingStorage: Release? = null
    private var installNowDone = false
    private var downloadDialog: Dialog? = null
    private var downloadText: TextView? = null
    private var downloadProgress: ProgressBar? = null
    private var shownRelease: Release? = null

    private lateinit var root: FrameLayout
    private var content: View? = null

    /** The notes column, which the old page focused first (the first focusable in it) */
    private var firstFocus: View? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissions = true
                awaitingStorage?.let(viewModel::installRelease)
            }
            awaitingStorage = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = viewModel.updater.hasPermissions()
        installNowDone = savedInstanceState?.getBoolean(STATE_INSTALL_NOW_DONE) ?: false
        if (savedInstanceState == null) viewModel.init()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INSTALL_NOW_DONE, installNowDone)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                setBackgroundColor(AppTheme.palette.background)
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.state.collectWhileStarted { state ->
            render(state.loading, state.release)
            if (state.downloading) showDownloadDialog(state.contentLength) else hideDownloadDialog()
            downloadProgressUpdate(state.contentLength, viewModel.bytesDownloaded.value)
            content?.alpha = if (state.downloading) DIMMED else 1f
        }
        viewModel.bytesDownloaded.collectWhileStarted { bytes ->
            downloadProgressUpdate(viewModel.state.value.contentLength, bytes)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps" with it now allowed: carry on rather than wait for a second press. The saved
        // marker covers Android restarting the app when it's allowed, which loses needsInstallPermission.
        val state = viewModel.state.value
        val release = state.release ?: return
        if (state.loading != LoadingState.Success) return
        val waiting = state.needsInstallPermission || viewModel.updater.hasPendingInstall()
        if (waiting && viewModel.updater.canInstallPackages() && release.version.isGreaterThan(viewModel.currentVersion)) {
            onInstallRelease(release)
        }
    }

    private fun onInstallRelease(release: Release) {
        if (!permissions) {
            awaitingStorage = release
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.installRelease(release)
        }
    }

    private var renderedState: Any? = null

    private fun render(
        loading: LoadingState,
        release: Release?,
    ) {
        val key = loading to release
        if (key == renderedState) return
        renderedState = key
        val ctx = requireContext()
        root.removeAllViews()
        content = null
        firstFocus = null
        when (loading) {
            is LoadingState.Error -> {
                root.addView(
                    AppCompatTextView(ctx).apply {
                        applyTextRole(TextRole.TITLE_MEDIUM)
                        setTextColor(AppTheme.palette.error)
                        text = loading.localizedMessage
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }

            LoadingState.Loading, LoadingState.Pending -> {
                root.addView(
                    ProgressBar(ctx).apply { isIndeterminate = true },
                    FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.CENTER),
                )
            }

            LoadingState.Success -> {
                if (release == null) {
                    root.addView(
                        AppCompatTextView(ctx).apply {
                            SettingsLook.styleBody(this)
                            text = "No release found! Check the update URL."
                        },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
                    )
                } else {
                    val page = releaseContent(release)
                    content = page
                    root.addView(
                        page,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                    )
                    // WeaselFin: the popup's "Update" already asked, so don't make the user press "Download & Update"
                    // as well. Once per visit, and only for a newer release.
                    if (installNow && !installNowDone && release.version.isGreaterThan(viewModel.currentVersion)) {
                        installNowDone = true
                        onInstallRelease(release)
                    }
                    if (isResumed && view?.hasFocus() != true) firstFocus?.requestFocus()
                }
            }
        }
    }

    /** The notes column and the action card (the old `InstallUpdatePageContent`) */
    private fun releaseContent(release: Release): View {
        val ctx = requireContext()
        val neon = SettingsLook.neon
        val accent = pageStyle.sectionAccent
        val row =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        val notes =
            AppCompatTextView(ctx).apply {
                SettingsMarkdown.styleNotes(this)
                text = SettingsMarkdown.render(ctx, release.content)
            }
        val notesScroll =
            object : ScrollView(ctx) {
                override fun onFocusChanged(
                    gainFocus: Boolean,
                    direction: Int,
                    previouslyFocusedRect: android.graphics.Rect?,
                ) {
                    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                    background = notesBackground(gainFocus, accent)
                }
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                val pad = ctx.dp(16)
                setPadding(pad, pad, pad, pad)
                clipToPadding = false
                background = notesBackground(false, accent)
                addView(notes)
                // UP/DOWN scroll the notes by 100px, as before; LEFT/RIGHT still move focus
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            smoothScrollBy(0, SCROLL_PX)
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_UP -> {
                            smoothScrollBy(0, -SCROLL_PX)
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }
        firstFocus = notesScroll
        row.addView(notesScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .6f))
        val card =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = ctx.dp(16)
                setPadding(pad, pad, pad, pad)
                background = SettingsLook.surface(ctx, SettingsLook.tonalSurface(1f, accent), if (neon) 0f else 16f)
            }
        card.addView(
            AppCompatTextView(ctx).apply {
                if (neon) styleDialogTitle(getString(R.string.update_available)) else applyTextRole(TextRole.DISPLAY_SMALL)
                if (!neon) text = getString(R.string.update_available)
                setTextColor(AppTheme.palette.onSurface)
                gravity = Gravity.CENTER
            },
        )
        card.addView(
            AppCompatTextView(ctx).apply {
                applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.onSurface)
                text = "${viewModel.currentVersion} => ${release.version}"
            },
            gapParams(ctx),
        )
        card.addView(settingsButton(ctx, getString(R.string.download_and_update), accent) { onInstallRelease(release) }, gapParams(ctx))
        card.addView(
            settingsButton(ctx, getString(R.string.cancel), accent) { viewModel.navigationManager.goBack() },
            gapParams(ctx),
        )
        val cardScroll =
            ScrollView(ctx).apply {
                isFillViewport = false
                addView(card)
            }
        row.addView(
            FrameLayout(ctx).apply {
                addView(
                    cardScroll,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL,
                    ),
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .4f).apply { marginStart = ctx.dp(16) },
        )
        return row
    }

    private fun gapParams(ctx: android.content.Context) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dp(16)
            gravity = Gravity.CENTER_HORIZONTAL
        }

    /** The notes column: the 3dp surface while it has focus, 1dp otherwise; square on the WeaselTV theme */
    private fun notesBackground(
        focused: Boolean,
        accent: Int,
    ) = SettingsLook.surface(
        requireContext(),
        SettingsLook.tonalSurface(if (focused) 3f else 1f, accent),
        if (SettingsLook.neon) 0f else 16f,
    )

    /** "Downloading…" with a progress circle and the bytes so far; BACK cancels the download */
    private fun showDownloadDialog(contentLength: Long) {
        if (downloadDialog?.isShowing == true) return
        val ctx = requireContext()
        val dialog = SettingsDialog(ctx, widthDp = 420)
        val line =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        line.addView(
            AppCompatTextView(ctx).apply {
                text = getString(R.string.downloading)
                textSize = 24f
                setTextColor(AppTheme.palette.onSurface)
            },
        )
        val progress =
            ProgressBar(ctx, null, android.R.attr.progressBarStyle).apply {
                isIndeterminate = true
                indeterminateTintList =
                    android.content.res.ColorStateList
                        .valueOf(AppTheme.palette.border)
            }
        line.addView(progress, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)).apply { marginStart = ctx.dp(8) })
        dialog.column.gravity = Gravity.CENTER_HORIZONTAL
        dialog.column.addView(line)
        val bytes =
            AppCompatTextView(ctx).apply {
                textSize = 18f
                setTextColor(AppTheme.palette.onSurface)
                gravity = Gravity.CENTER
            }
        dialog.column.addView(bytes, gapParams(ctx))
        dialog.setOnCancelListener { viewModel.cancelDownload() }
        downloadDialog = dialog
        downloadText = bytes
        downloadProgress = progress
        dialog.show()
    }

    private fun downloadProgressUpdate(
        contentLength: Long,
        bytes: Long,
    ) {
        val text = downloadText ?: return
        if (contentLength > 0) {
            text.visibility = View.VISIBLE
            text.text = "${formatBytes(bytes)} / ${formatBytes(contentLength)}"
        } else {
            text.visibility = View.GONE
        }
    }

    private fun hideDownloadDialog() {
        downloadDialog?.setOnCancelListener(null)
        downloadDialog?.dismiss()
        downloadDialog = null
        downloadText = null
        downloadProgress = null
    }

    override fun onScreenFocus(restoring: Boolean) {
        if (!restoring && firstFocus?.requestFocus() == true) return
        super.onScreenFocus(restoring)
    }

    override fun onDestroyView() {
        hideDownloadDialog()
        renderedState = null
        content = null
        firstFocus = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_INSTALL_NOW_DONE = "installNowDone"
        const val SCROLL_PX = 100
        const val DIMMED = .4f
    }
}
