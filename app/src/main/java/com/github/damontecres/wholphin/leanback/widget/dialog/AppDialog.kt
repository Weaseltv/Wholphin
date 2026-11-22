package com.github.damontecres.wholphin.leanback.widget.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.NeonScope
import com.github.damontecres.wholphin.leanback.theme.NeonSizes
import com.github.damontecres.wholphin.leanback.theme.NeonTallyDrawable
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.provideNeonScope
import com.github.damontecres.wholphin.leanback.theme.resolveNeonScope
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.ControlTrackers

/** How a dialog panel is drawn */
enum class PanelLook {
    /**
     * `BasicDialogPanel`: Neon `card` with a 1dp `line2` edge, the 3dp tally and a 20dp shadow; stock themes an 8dp
     * rounded surface at the dialog's elevation
     */
    BASIC,

    /** `BasicDialogPanel(outline = …)` on the Neon theme: a 1.5dp border with a soft 8dp glow in that colour (What's New) */
    OUTLINE,

    /** `DialogPopupContent` (menus): as [BASIC] on the Neon theme; stock themes a 28dp rounded surface */
    MENU,

    /** No panel: the content draws itself */
    NONE,
}

/** Window and panel options of an [AppDialog] (the old `DialogProperties` plus `BasicDialog`'s parameters) */
data class DialogOptions(
    /** True: the platform's default dialog width limits the window (Compose `usePlatformDefaultWidth`) */
    val usePlatformDefaultWidth: Boolean = true,
    /** Stock themes: shadow and surface tint elevation in dp */
    val elevationDp: Float = 1f,
    /** The tally colour; null uses the accent (the delete dialog passes red) */
    val tallyColor: Int? = null,
    /** With [PanelLook.OUTLINE]: the border and glow colour */
    val outline: Int? = null,
    val look: PanelLook = PanelLook.BASIC,
    /** Window gravity (the playlist picker sat at the top) */
    val gravity: Int = Gravity.CENTER,
    /** BACK closes the dialog */
    val dismissOnBack: Boolean = true,
    /** The old `BasicDialog` counted itself for the request chips; plain Compose `Dialog`s (menus) did not */
    val countForRequestUi: Boolean = true,
)

/**
 * The base of every CONTROLS dialog (`BasicDialog` / Compose `Dialog` as an `android.app.Dialog`): a floating,
 * dimmed window holding a [DialogPanel]. Because it is a real window, the platform gives focus back to the view that
 * was focused before it opened when it closes; nobody restores focus by hand.
 *
 * [onDismissRequest] runs when the user closes it (BACK) and when the dialog closes itself after a choice
 * ([requestDismiss]), like the Compose callback; a plain `dismiss()` from code does not call it.
 *
 * The accent (tally, focus colour of controls inside) is [accent], else the opener's ([opener]'s view tree or page),
 * else volt.
 */
open class AppDialog(
    context: Context,
    val options: DialogOptions = DialogOptions(),
    accent: Int? = null,
    opener: View? = null,
) : Dialog(context, R.style.lb_widget_ctl_dialog) {
    /** The accent of this dialog and the controls inside it */
    val accent: Int = accent ?: opener?.resolveNeonScope()?.accent ?: NeonColors.VOLT

    /** Window root: centres the panel; with `usePlatformDefaultWidth = false` it may use the whole display width */
    val root: DialogRoot = DialogRoot(context, !options.usePlatformDefaultWidth)

    /** The panel; add the content with [setPanelContent] */
    val panel: DialogPanel = DialogPanel(context, options, this.accent)

    var onDismissRequest: (() -> Unit)? = null

    private var counted = false

    init {
        root.id = R.id.lb_widget_ctl_dialog_root
        root.provideNeonScope(NeonScope(accent = this.accent))
        root.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        setCancelable(options.dismissOnBack)
        setCanceledOnTouchOutside(true)
        setOnCancelListener { onDismissRequest?.invoke() }
    }

    /** Put [content] in the panel */
    fun setPanelContent(
        content: View,
        params: ViewGroup.LayoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    ) {
        panel.removeAllViews()
        panel.addView(content, params)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(root)
        window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(options.gravity)
        }
    }

    override fun onStart() {
        super.onStart()
        if (options.countForRequestUi && !counted) {
            counted = true
            ControlTrackers.dialogOpened()
        }
    }

    override fun onStop() {
        super.onStop()
        if (counted) {
            counted = false
            ControlTrackers.dialogClosed()
        }
    }

    /** Close because of the user's choice: dismiss and tell [onDismissRequest] */
    fun requestDismiss() {
        if (isShowing) dismiss()
        onDismissRequest?.invoke()
    }
}

/**
 * The dialog window's root view. With [fullDisplayWidth] (Compose `usePlatformDefaultWidth = false`) its content may
 * be as wide as the display instead of the platform's default dialog width.
 */
class DialogRoot(
    context: Context,
    private val fullDisplayWidth: Boolean,
) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (fullDisplayWidth) {
            val metrics = resources.displayMetrics
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(metrics.widthPixels, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(metrics.heightPixels, MeasureSpec.AT_MOST),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

/**
 * The panel a dialog draws its content on (`BasicDialogPanel` / the menu panel of `DialogPopupContent`), as a
 * FrameLayout. See [PanelLook]. With [PanelLook.MENU] on the Neon theme it is exactly 448dp wide
 * (`NeonBoard.Size.DialogWidth`).
 */
class DialogPanel(
    context: Context,
    private val options: DialogOptions,
    private val accent: Int,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val look: PanelLook =
        when {
            options.look == PanelLook.OUTLINE && (!neon || options.outline == null) -> PanelLook.BASIC
            else -> options.look
        }

    init {
        clipChildren = false
        clipToPadding = false
        val corner =
            when {
                neon -> 0f
                look == PanelLook.MENU -> context.dpF(28f)
                look == PanelLook.NONE -> 0f
                else -> context.dpF(8f)
            }
        when (look) {
            PanelLook.NONE -> {}

            PanelLook.OUTLINE -> {
                val glow = context.dp(OUTLINE_GLOW_DP)
                setPadding(glow, glow, glow, glow)
                background = OutlinePanelDrawable(context, options.outline!!, glow.toFloat())
            }

            else -> {
                val fill = if (neon) NeonColors.CARD else AppTheme.palette.surfaceAtElevation(options.elevationDp)
                background = BasicPanelDrawable(context, fill, corner, neon, options.tallyColor ?: accent)
                elevation =
                    if (neon) {
                        context.dpF(20f)
                    } else {
                        context.dpF(options.elevationDp)
                    }
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(
                            view: View,
                            outline: Outline,
                        ) {
                            outline.setRoundRect(0, 0, view.width, view.height, corner)
                        }
                    }
                // Compose `Modifier.shadow` clips to its shape: content (and focus glow) stays inside the panel
                clipToOutline = true
            }
        }
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        if (look == PanelLook.MENU && neon) {
            val w = context.dp(NeonSizes.DIALOG_WIDTH)
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) w else minOf(w, available)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    companion object {
        /** Room left around an outlined panel for its glow (the window ends at the content) */
        const val OUTLINE_GLOW_DP = 8f
    }
}

/** Fill, the Neon 1dp `line2` edge and the tally, in that order (the old modifier chain) */
private class BasicPanelDrawable(
    context: Context,
    private val fill: Int,
    private val corner: Float,
    private val neon: Boolean,
    tallyColor: Int,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tally = NeonTallyDrawable(context, tallyColor)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        paint.color = fill
        canvas.drawRoundRect(rect, corner, corner, paint)
        if (neon) {
            val w = density
            border.strokeWidth = w
            border.color = NeonColors.LINE2
            rect.inset(w / 2f, w / 2f)
            canvas.drawRect(rect, border)
            tally.bounds = bounds
            tally.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The outlined panel: a soft glow fading out over [reach] around a `card` panel with a 1.5dp border */
private class OutlinePanelDrawable(
    context: Context,
    private val color: Int,
    private val reach: Float,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val inner = RectF(bounds.left + reach, bounds.top + reach, bounds.right - reach, bounds.bottom - reach)
        val steps = 10
        val step = reach / steps
        stroke.strokeWidth = step
        for (i in 0 until steps) {
            val spread = step * (i + .5f)
            stroke.color = withAlpha(color, .34f * (1f - i.toFloat() / steps))
            rect.set(inner.left - spread, inner.top - spread, inner.right + spread, inner.bottom + spread)
            canvas.drawRect(rect, stroke)
        }
        paint.color = NeonColors.CARD
        canvas.drawRect(inner, paint)
        val w = 1.5f * density
        stroke.strokeWidth = w
        stroke.color = color
        rect.set(inner)
        rect.inset(w / 2f, w / 2f)
        canvas.drawRect(rect, stroke)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
