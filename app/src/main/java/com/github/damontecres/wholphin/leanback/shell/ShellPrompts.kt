package com.github.damontecres.wholphin.leanback.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.isPlayback
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.neonGlowAccent
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.WhatsNewEntry
import com.github.damontecres.wholphin.services.WhatsNewService
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A dialog panel (the old `BasicDialogPanel`). WeaselTV theme: `card` with a 1dp `line2` edge and the [tally] accent
 * bar on the leading edge, or with [outline] a 1.5dp border in that colour and a soft 8dp glow outside it (What's
 * New). Other themes: a rounded 8dp tonal surface at [elevationDp].
 */
@SuppressLint("ViewConstructor")
internal class ShellPanel(
    context: Context,
    private val outline: Int? = null,
    private val tally: Int = NeonColors.VOLT,
    elevationDp: Float = 6f,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowRoom = if (neon && outline != null) context.dp(8) else 0

    init {
        setWillNotDraw(false)
        clipToPadding = false
        if (neon) {
            // The dialog window ends at its content, so leave room for the glow
            setPadding(glowRoom, glowRoom, glowRoom, glowRoom)
            if (outline == null) elevation = context.dpF(20f)
        } else {
            elevation = context.dpF(elevationDp)
            val color = surfaceAtElevation(elevationDp)
            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = context.dpF(8f)
                    setColor(color)
                }
        }
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    out: Outline,
                ) {
                    out.setRoundRect(glowRoom, glowRoom, view.width - glowRoom, view.height - glowRoom, if (neon) 0f else context.dpF(8f))
                }
            }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!neon) return
        val l = glowRoom.toFloat()
        val t = glowRoom.toFloat()
        val r = width - glowRoom.toFloat()
        val b = height - glowRoom.toFloat()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        if (outline != null) {
            // A soft glow just outside the panel's edge, fading out over 8dp
            val steps = 10
            val step = context.dpF(8f) / steps
            for (i in 0 until steps) {
                val spread = step * (i + .5f)
                paint.color = withAlpha(outline, .34f * (1f - i.toFloat() / steps))
                paint.strokeWidth = step
                canvas.drawRect(l - spread, t - spread, r + spread, b + spread, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = NeonColors.CARD
        canvas.drawRect(l, t, r, b, paint)
        if (outline == null) {
            // The selected / current marker: a 3dp accent bar with a 12dp glow, inside the bounds
            val glowW = context.dpF(12f)
            paint.shader = LinearGradient(l, 0f, l + glowW, 0f, withAlpha(neonGlowAccent(tally), .45f), 0, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, l + glowW, b, paint)
            paint.shader = null
            paint.color = tally
            canvas.drawRect(l, t, l + context.dpF(3f), b, paint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!neon) return
        val stroke = context.dpF(if (outline != null) 1.5f else 1f)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = outline ?: NeonColors.LINE2
        val h = stroke / 2f
        canvas.drawRect(glowRoom + h, glowRoom + h, width - glowRoom - h, height - glowRoom - h, paint)
        paint.style = Paint.Style.FILL
    }
}

/** A popup window around [content]: floating, framed by nothing, dimmed by [dimAmount] when given; BACK calls [onBack] */
internal class ShellDialog(
    context: Context,
    content: View,
    dimAmount: Float? = null,
    onBack: () -> Unit,
) : AppCompatDialog(context, R.style.LbShellDialog) {
    init {
        setContentView(content)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setOnCancelListener { onBack() }
        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (dimAmount != null) {
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(dimAmount)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
    }
}

/**
 * The update popup's content (the old `UpdatePromptContent`): "WEASELPLEX 1.2.6 IS AVAILABLE." and two full-width
 * buttons, Update and Not now, that keep their size when focused. With [picture] (the WeaselPlex tour) nothing can be
 * focused and Update looks chosen.
 */
@SuppressLint("ViewConstructor")
internal class UpdatePromptPanel(
    context: Context,
    version: Version,
    picture: Boolean,
    onUpdate: () -> Unit,
    onNotNow: () -> Unit,
) : LinearLayout(context) {
    val updateButton: ShellButton

    init {
        orientation = VERTICAL
        val p = context.dp(24)
        setPadding(p, p, p, p)
        val title =
            TextView(context).apply {
                text =
                    context
                        .getString(
                            R.string.update_prompt_title,
                            context.getString(R.string.app_name),
                            version.run { "$major.$minor.$patch" },
                        ).neonUpper()
                if (AppTheme.isNeon) ShellType.dialogTitle(this) else applyTextRole(TextRole.TITLE_LARGE)
                setTextColor(AppTheme.palette.onSurface)
            }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) })
        updateButton = promptButton(R.string.update, onUpdate, picture, looksFocused = picture)
        addView(updateButton, buttonParams(context.dp(16)))
        addView(promptButton(R.string.not_now, onNotNow, picture, looksFocused = false), buttonParams(context.dp(16)))
    }

    private fun buttonParams(top: Int) = LayoutParams(context.dp(520 - 48), context.dp(40)).apply { topMargin = top }

    private fun promptButton(
        text: Int,
        onClick: () -> Unit,
        picture: Boolean,
        looksFocused: Boolean,
    ): ShellButton =
        object : ShellButton(context) {
            override fun isFocused(): Boolean = looksFocused || super.isFocused()
        }.apply {
            setText(text)
            val ph = context.dp(8)
            setPadding(ph, context.dp(4), ph, context.dp(4))
            if (picture) {
                isFocusable = false
                isClickable = false
            } else {
                setOnClickListener { onClick() }
            }
            refresh()
        }

    companion object {
        /** The popup as a [ShellPanel] (elevation 6) */
        fun panel(
            context: Context,
            version: Version,
            picture: Boolean,
            onUpdate: () -> Unit = {},
            onNotNow: () -> Unit = {},
        ): Pair<ShellPanel, UpdatePromptPanel> {
            val content = UpdatePromptPanel(context, version, picture, onUpdate, onNotNow)
            val panel = ShellPanel(context, elevationDp = 6f)
            panel.addView(content, FrameLayout.LayoutParams(context.dp(520), FrameLayout.LayoutParams.WRAP_CONTENT))
            return panel to content
        }
    }
}

/**
 * WeaselFin: shows the "update available" popup when [UpdateChecker.maybePromptForUpdate] finds a newer release. It
 * waits while something is playing, and never covers the update page itself. An update that was waiting on "Install
 * unknown apps", now allowed, opens the update page to carry on.
 */
internal class UpdatePromptController(
    private val context: Context,
    private val updateChecker: UpdateChecker,
    private val navigationManager: NavigationManager,
) {
    private var dialog: ShellDialog? = null
    private var shown: Release? = null
    private var jobs = emptyList<Job>()

    fun start(scope: CoroutineScope) {
        stop()
        val current = navigationManager.backStackFlow.map { it.lastOrNull() }
        jobs =
            listOf(
                scope.launch {
                    combine(updateChecker.resumeInstall, current) { resume, top -> resume to top }.collect { (resume, top) ->
                        if (resume && !top.isPlayback) {
                            updateChecker.consumeResumeInstall()
                            updateChecker.dismissPrompt()
                            if (top !is Destination.UpdateApp) {
                                navigationManager.navigateTo(Destination.UpdateApp(installNow = true))
                            }
                        }
                    }
                },
                scope.launch {
                    combine(updateChecker.prompt, current) { release, top -> release to top }.collect { (release, top) ->
                        if (release != null && !top.isPlayback && top !is Destination.UpdateApp) show(release) else hide()
                    }
                },
            )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        hide()
    }

    private fun show(release: Release) {
        if (dialog?.isShowing == true && shown == release) return
        hide()
        shown = release
        val (panel, content) =
            UpdatePromptPanel.panel(
                context,
                release.version,
                picture = false,
                onUpdate = {
                    updateChecker.dismissPrompt()
                    navigationManager.navigateTo(Destination.UpdateApp(installNow = true))
                },
                onNotNow = { updateChecker.dismissPrompt() },
            )
        dialog =
            ShellDialog(context, panel, onBack = { updateChecker.dismissPrompt() }).also {
                it.setOnShowListener { content.updateButton.requestFocus() }
                it.show()
            }
    }

    private fun hide() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        shown = null
    }
}

/**
 * What's New once after an update, over Home (the old `WhatsNewPrompt`). It waits while the tour runs (or is about to),
 * while the update popup is up, and away from Home, then shows a moment (1 s) after Home settles so it doesn't flash
 * over a page that is still opening. Never for a kid profile.
 */
internal class WhatsNewController(
    private val context: Context,
    private val service: WhatsNewService,
    private val updateChecker: UpdateChecker,
    private val navigationManager: NavigationManager,
) {
    private var dialog: ShellDialog? = null
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        blocked: Flow<Boolean>,
    ) {
        stop()
        val onHome = navigationManager.backStackFlow.map { it.lastOrNull() is Destination.Home }
        job =
            scope.launch {
                combine(service.entry, updateChecker.prompt, onHome, blocked) { entry, prompt, home, block ->
                    entry != null && home && !block && prompt == null
                }.distinctUntilChanged()
                    .collectLatest { clear ->
                        if (clear) {
                            delay(1_000)
                            service.entry.value?.let { show(it) }
                        } else {
                            hide()
                        }
                    }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        hide()
    }

    private fun show(entry: WhatsNewEntry) {
        if (dialog?.isShowing == true) return
        val yellow = NeonColors.YELLOW
        val panel = ShellPanel(context, outline = yellow, tally = yellow, elevationDp = 6f)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(26), context.dp(27), context.dp(26), context.dp(25))
            }
        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.whats_new_version, entry.version).neonUpper()
                ShellType.eyebrow(this)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                if (AppTheme.isNeon) setLineHeightPx(context.spToPx(12f))
                setTextColor(yellow)
            },
        )
        column.addView(
            TextView(context).apply {
                text = context.getString(R.string.whats_new_title).neonUpper()
                ShellType.header(this, 30f, TextRole.HEADLINE_SMALL)
                setTextColor(NeonColors.TEXT)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6)
            },
        )
        column.addView(
            View(context).apply { setBackgroundColor(NeonColors.LINE) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(14) },
        )
        entry.notes.forEachIndexed { i, note ->
            column.addView(
                bullet(note, yellow),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(14)
                },
            )
        }
        // As in the mockup: a dark fill and white OK; the yellow border and glow come from the button's own focus
        // style under the yellow accent
        val ok =
            ShellButton(context).apply {
                accent = yellow
                restFill = 0
                focusedFill = NeonColors.CARD2
                restLabel = NeonColors.TEXT
                focusedLabel = NeonColors.TEXT
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setText(R.string.whats_new_ok)
                ShellType.button(this)
                setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
                refresh()
            }
        column.addView(
            ok,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)).apply { topMargin = context.dp(23) },
        )
        panel.addView(column, FrameLayout.LayoutParams(context.dp(520), FrameLayout.LayoutParams.WRAP_CONTENT))
        // Dim Home behind the pop-up
        val d =
            ShellDialog(context, panel, dimAmount = .72f, onBack = { service.dismiss() }).also {
                it.setOnShowListener { ok.requestFocus() }
            }
        ok.setOnClickListener { service.dismiss() }
        dialog = d
        d.show()
    }

    private fun bullet(
        text: String,
        yellow: Int,
    ): View {
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
            }
        val icon =
            object : ImageView(context) {
                private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

                override fun onDraw(canvas: Canvas) {
                    // The mockup's faint glow around each check
                    val r = minOf(width, height) * .75f
                    glow.shader =
                        RadialGradient(
                            width / 2f,
                            height / 2f,
                            r,
                            intArrayOf(withAlpha(yellow, .28f), withAlpha(yellow, .28f), 0),
                            floatArrayOf(0f, .5f, 1f),
                            Shader.TileMode.CLAMP,
                        )
                    canvas.drawCircle(width / 2f, height / 2f, r, glow)
                    super.onDraw(canvas)
                }
            }.apply {
                setImageResource(R.drawable.lb_shell_ic_check_circle)
                imageTintList = ColorStateList.valueOf(yellow)
            }
        row.addView(
            icon,
            LinearLayout.LayoutParams(context.dp(17), context.dp(17)).apply { topMargin = context.dpF(1.5f).toInt() },
        )
        row.addView(
            TextView(context).apply {
                this.text = text
                ShellType.rowSubtitle(this)
                if (AppTheme.isNeon) setLineHeightPx(context.spToPx(20f))
                setTextColor(withAlpha(NeonColors.TEXT, .93f))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(10) },
        )
        return row
    }

    private fun hide() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }
}
