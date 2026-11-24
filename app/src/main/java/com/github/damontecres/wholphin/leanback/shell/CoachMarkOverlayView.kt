package com.github.damontecres.wholphin.leanback.shell

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkIds
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkState
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkStep
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the card goes and whether it is the compact one */
internal data class CardSpot(
    val x: Int,
    val y: Int,
    val compact: Boolean,
)

/**
 * Where the card goes without covering [hole]: the full card beside a narrow target (a menu item), otherwise below or
 * above it; then the shorter compact card below or above. Only when neither fits does the compact card go in the
 * bigger space, as clear of the target as it can be.
 */
internal fun placeCard(
    hole: RectF,
    fullW: Int,
    fullH: Int,
    compactW: Int,
    compactH: Int,
    screenWidth: Int,
    screenHeight: Int,
    gap: Int,
    compactGap: Int,
    marginX: Int,
    marginY: Int,
    compactMarginY: Int,
): CardSpot {
    fun x(v: Float) = v.roundToInt().coerceIn(marginX, max(marginX, screenWidth - marginX - fullW))

    fun y(v: Float) = v.roundToInt().coerceIn(marginY, max(marginY, screenHeight - marginY - fullH))

    val besideY = y(hole.centerY() - fullH / 2f)
    if (hole.width() < screenWidth * .4f) {
        if (hole.right + gap + fullW <= screenWidth - marginX) return CardSpot((hole.right + gap).roundToInt(), besideY, false)
        if (hole.left - gap - fullW >= marginX) return CardSpot((hole.left - gap - fullW).roundToInt(), besideY, false)
    }
    if (hole.bottom + gap + fullH <= screenHeight - marginY) return CardSpot(x(hole.left), (hole.bottom + gap).roundToInt(), false)
    if (hole.top - gap - fullH >= marginY) return CardSpot(x(hole.left), (hole.top - gap - fullH).roundToInt(), false)

    val compactX = (screenWidth - compactW) / 2
    val below = (hole.bottom + compactGap).roundToInt()
    val above = (hole.top - compactGap - compactH).roundToInt()
    val y =
        when {
            below + compactH <= screenHeight - compactMarginY -> below
            above >= compactMarginY -> above
            screenHeight - hole.bottom >= hole.top -> screenHeight - compactMarginY - compactH
            else -> compactMarginY
        }
    return CardSpot(compactX, y, compact = true)
}

/**
 * The tour drawn over the signed-in app (the old `CoachMarkOverlay`): the screen dimmed, the current step's target
 * cut out of the dim with a pulsing volt glow around it, and a short card beside it. Keys are handled by
 * [CoachMarkTour.onKey] from the activity; this view never takes focus.
 */
@SuppressLint("ViewConstructor")
internal class CoachMarkOverlayView(
    context: Context,
    private val tour: CoachMarkTour,
    private val navigationManager: NavigationManager,
) : FrameLayout(context) {
    private val scrim = ScrimView(context)
    private val fullCard = CardView(context, compact = false)
    private val compactCard = CardView(context, compact = true)
    private var picture: View? = null

    private var state: CoachMarkState? = null
    private var target: RectF? = null
    private val hole = RectF()
    private var holePlaced = false
    private var holeFrom = RectF()
    private var holeTo = RectF()
    private var holeAnimator: ValueAnimator? = null
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var cardAnimator: ValueAnimator? = null
    private var shownIndex = -1
    private var following = false
    private var pendingJob: Job? = null
    private var stateJob: Job? = null
    private val origin = IntArray(2)

    private val frame =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!following) return
                follow()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        visibility = GONE
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fullCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(compactCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Follow the tour while the app content shows */
    fun start(scope: CoroutineScope) {
        stop()
        stateJob =
            scope.launch {
                launch {
                    tour.pendingFlow.collect { pending ->
                        pendingJob?.cancel()
                        if (pending) pendingJob = launch { waitForHome() }
                    }
                }
                tour.stateFlow.collect { onState(it) }
            }
    }

    fun stop() {
        stateJob?.cancel()
        stateJob = null
        pendingJob?.cancel()
        pendingJob = null
        // The tour ends when its overlay goes away (the old DisposableEffect)
        tour.end()
        hideOverlay()
    }

    /**
     * Started from Settings: wait for Home, then for it to focus the starting row, and for the move there to settle.
     * Home not coming up within a minute (gone elsewhere) drops it.
     */
    private suspend fun waitForHome() {
        val home =
            withTimeoutOrNull(60_000 - (SystemClock.uptimeMillis() - tour.pendingSince)) {
                while (navigationManager.backStack.lastOrNull() !is Destination.Home) delay(100)
            }
        if (home == null) {
            tour.end()
            return
        }
        withTimeoutOrNull(10_000) {
            while (!tour.homeRowReady || tour.bounds(CoachMarkStep.HOME_ROW) == null) delay(100)
        }
        delay(700)
        tour.begin()
    }

    private fun onState(s: CoachMarkState?) {
        val old = state
        state = s
        if (s == null) {
            hideOverlay()
            return
        }
        if (old == null) {
            // The overlay fades in over 250 ms when the tour starts
            visibility = VISIBLE
            holePlaced = false
            target = null
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(PathInterpolator(.4f, 0f, .2f, 1f))
                .start()
            startPulse()
            following = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
        val step = s.step
        if (old?.step != step) {
            // A menu row that's scrolled out of sight is scrolled back first
            scope?.launch { tour.rail?.bringIntoView(step) }
        }
        fullCard.bind(s)
        compactCard.bind(s)
        updatePicture(step)
        if (s.index != shownIndex) {
            shownIndex = s.index
            cardAnimator?.cancel()
            setCardAlpha(0f)
            cardAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 300
                    startDelay = 120
                    interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
                    addUpdateListener { setCardAlpha(it.animatedValue as Float) }
                    start()
                }
        }
        follow()
        requestLayout()
        invalidate()
    }

    /** Set by the shell: where the rail's scroll runs */
    var scope: CoroutineScope? = null

    private fun setCardAlpha(a: Float) {
        fullCard.alpha = a
        compactCard.alpha = a
        picture?.alpha = a
    }

    private fun updatePicture(step: CoachMarkStep) {
        if (step.picture && picture == null) {
            // The "update available" popup as it looks when WeaselPlex opens, offering the next version; only a picture
            val version =
                Version.fromStringLenient(BuildConfig.VERSION_NAME)?.let { it.copy(patch = it.patch + 1, numCommits = null, hash = null) }
                    ?: Version(1, 3, 0)
            val (panel, _) = UpdatePromptPanel.panel(context, version, picture = true)
            picture = panel
            addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // Tagged like the real things, so the spotlight finds it the same way
            CoachMarkTargets.register(CoachMarkIds.UPDATE_PROMPT, panel)
        } else if (!step.picture && picture != null) {
            picture?.let {
                CoachMarkTargets.unregister(CoachMarkIds.UPDATE_PROMPT, it)
                removeView(it)
            }
            picture = null
        }
    }

    private fun hideOverlay() {
        following = false
        Choreographer.getInstance().removeFrameCallback(frame)
        pulseAnimator?.cancel()
        holeAnimator?.cancel()
        cardAnimator?.cancel()
        animate().cancel()
        picture?.let {
            CoachMarkTargets.unregister(CoachMarkIds.UPDATE_PROMPT, it)
            removeView(it)
        }
        picture = null
        state = null
        shownIndex = -1
        holePlaced = false
        target = null
        visibility = GONE
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    scrim.invalidate()
                }
                start()
            }
    }

    /** Follow the target as it moves (a row scrolling into place); leaving Home ends the tour */
    private fun follow() {
        val s = state ?: return
        if (navigationManager.backStack.lastOrNull() !is Destination.Home) {
            tour.end()
            return
        }
        val step = s.step
        // The opening card follows the next step's target unseen, so the spotlight is ready when it appears
        val follow = if (step.hasTarget) step else s.steps.getOrNull(s.index + 1) ?: step
        val bounds = tour.bounds(follow) ?: return
        getLocationInWindow(origin)
        val pad = context.dpF(follow.paddingDp)
        val t =
            RectF(
                bounds.left - origin[0] - pad,
                bounds.top - origin[1] - pad,
                bounds.right - origin[0] + pad,
                bounds.bottom - origin[1] + pad,
            )
        if (t == target) return
        target = t
        moveHole(t)
        requestLayout()
    }

    private fun moveHole(t: RectF) {
        if (!holePlaced) {
            hole.set(t)
            holePlaced = true
            scrim.invalidate()
            requestLayout()
            return
        }
        holeFrom.set(hole)
        holeTo.set(t)
        holeAnimator?.cancel()
        // spring(stiffness = Spring.StiffnessMediumLow), critically damped
        holeAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator =
                    android.animation.TimeInterpolator { x ->
                        val w = sqrt(400.0)
                        val tt = x * .42
                        val end = 1 - (1 + w * .42) * exp(-w * .42)
                        ((1 - (1 + w * tt) * exp(-w * tt)) / end).toFloat()
                    }
                addUpdateListener {
                    val f = it.animatedValue as Float
                    hole.set(
                        holeFrom.left + (holeTo.left - holeFrom.left) * f,
                        holeFrom.top + (holeTo.top - holeFrom.top) * f,
                        holeFrom.right + (holeTo.right - holeFrom.right) * f,
                        holeFrom.bottom + (holeTo.bottom - holeFrom.bottom) * f,
                    )
                    scrim.invalidate()
                }
                start()
            }
    }

    /** [r] cut to the screen less [inset] on every side */
    private fun onScreen(
        r: RectF,
        inset: Float,
    ): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return RectF(
            r.left.coerceIn(inset, w - inset),
            r.top.coerceIn(inset, h - inset),
            r.right.coerceIn(inset, w - inset),
            r.bottom.coerceIn(inset, h - inset),
        )
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        scrim.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val marginX = context.dp(48)
        // Wide enough for most bodies to fit on two lines
        fullCard.measure(
            MeasureSpec.makeMeasureSpec(minOf(context.dp(740), w), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        compactCard.measure(
            MeasureSpec.makeMeasureSpec(max(0, w - 2 * marginX), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        picture?.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val w = right - left
        val h = bottom - top
        scrim.layout(0, 0, w, h)
        val s = state
        fullCard.visibility = INVISIBLE
        compactCard.visibility = INVISIBLE
        if (s == null) return
        val step = s.step
        val gap = context.dp(28)
        val compactGap = context.dp(22)
        val marginY = context.dp(27)
        val compactMarginY = context.dp(18)
        val pic = picture
        if (pic != null) {
            // The popup and the card under it, together in the middle of the screen
            val useFull = pic.measuredHeight + gap + fullCard.measuredHeight <= h - 2 * marginY
            val card = if (useFull) fullCard else compactCard
            val g = if (useFull) gap else compactGap
            val y0 = max(0, (h - pic.measuredHeight - g - card.measuredHeight) / 2)
            val px = (w - pic.measuredWidth) / 2
            pic.layout(px, y0, px + pic.measuredWidth, y0 + pic.measuredHeight)
            place(card, (w - card.measuredWidth) / 2, y0 + pic.measuredHeight + g)
            return
        }
        if (!step.hasTarget) {
            place(fullCard, (w - fullCard.measuredWidth) / 2, (h - fullCard.measuredHeight) / 2)
            return
        }
        // The opening card has no target; any other step waits for its target's first position
        if (!holePlaced) return
        // Placed for where the spotlight is going, so the card doesn't hop while it moves
        val spot =
            placeCard(
                hole = onScreen(target ?: hole, context.dpF(6f)),
                fullW = fullCard.measuredWidth,
                fullH = fullCard.measuredHeight,
                compactW = compactCard.measuredWidth,
                compactH = compactCard.measuredHeight,
                screenWidth = w,
                screenHeight = h,
                gap = gap,
                compactGap = compactGap,
                marginX = context.dp(48),
                marginY = marginY,
                compactMarginY = compactMarginY,
            )
        place(if (spot.compact) compactCard else fullCard, spot.x, spot.y)
    }

    private fun place(
        card: View,
        x: Int,
        y: Int,
    ) {
        card.visibility = VISIBLE
        card.layout(x, y, x + card.measuredWidth, y + card.measuredHeight)
    }

    /** The dim with the cut-out and the glow */
    private inner class ScrimView(
        context: Context,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            val s = state ?: return
            val step = s.step
            if (step.hasTarget && !holePlaced) return
            paint.style = Paint.Style.FILL
            paint.color = 0xD9050608.toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (!step.hasTarget) return
            // The outline stays this far inside the screen, so all four sides show even for the menu, which touches the edges
            val r = onScreen(hole, context.dpF(6f))
            val corner = context.dpF(14f)
            canvas.drawRoundRect(r, corner, corner, clear)
            // Soft glow: wide faint rings breathing outwards, then a crisp edge
            val spread = context.dpF(6f + 10f * pulse)
            paint.style = Paint.Style.STROKE
            for (ring in 1..3) {
                val inset = spread * ring / 3f
                paint.color = withAlpha(NeonColors.VOLT, (.32f - .08f * ring) * (.5f + .5f * pulse))
                paint.strokeWidth = spread / 2f
                val g = RectF(r.left - inset, r.top - inset, r.right + inset, r.bottom + inset)
                canvas.drawRoundRect(g, corner + inset, corner + inset, paint)
            }
            paint.color = withAlpha(NeonColors.VOLT, .7f + .3f * pulse)
            paint.strokeWidth = context.dpF(3f)
            canvas.drawRoundRect(r, corner, corner, paint)
        }
    }

    /**
     * The step's card. [compact] packs the same things into fewer lines: the step count beside the title, and the hint
     * beside the buttons.
     */
    private class CardView(
        context: Context,
        private val compact: Boolean,
    ) : LinearLayout(context) {
        private val count = TextView(context)
        private val title = TextView(context)
        private val body = TextView(context)
        private val primary = TextView(context)
        private val skip = TextView(context)
        private val hint = TextView(context)
        private val buttons = LinearLayout(context)

        init {
            orientation = VERTICAL
            background =
                GradientDrawable().apply {
                    cornerRadius = context.dpF(16f)
                    setColor(NeonColors.CARD)
                    setStroke(context.dp(1), NeonColors.LINE2)
                }
            if (compact) {
                setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
            } else {
                val p = context.dp(24)
                setPadding(p, p, p, p)
            }
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            ShellType.eyebrow(count)
            count.setTextColor(NeonColors.MID)
            // Volt only for the title and the chosen button
            ShellType.dialogTitle(title)
            title.setTextColor(NeonColors.VOLT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) title.isAccessibilityHeading = true
            ShellType.rowSubtitle(body)
            body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            body.setLineHeightPx(context.spToPx(24f))
            body.setTextColor(withAlpha(NeonColors.TEXT, .86f))
            ShellType.rowSubtitle(hint)
            hint.setTextColor(NeonColors.MID)
            listOf(primary, skip).forEach {
                ShellType.button(it)
                it.setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
            }
            val spacing = context.dp(if (compact) 8 else 12)
            if (compact) {
                val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                row.addView(count)
                addView(row)
            } else {
                addView(count)
                addView(title, params(spacing))
            }
            addView(body, params(spacing))
            buttons.orientation = HORIZONTAL
            buttons.gravity = Gravity.CENTER_VERTICAL
            buttons.addView(primary)
            buttons.addView(skip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(12) })
            if (compact) {
                buttons.addView(Space(context), LayoutParams(0, 0, 1f))
                buttons.addView(
                    hint,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginStart =
                            context.dp(12)
                    },
                )
            }
            addView(
                buttons,
                params(spacing + context.dp(if (compact) 4 else 8)).apply {
                    width = if (compact) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                },
            )
            if (!compact) addView(hint, params(spacing))
        }

        private fun params(top: Int) =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = top
            }

        fun bind(state: CoachMarkState) {
            val step = state.step
            count.text = context.getString(R.string.coach_step_count, state.index + 1, state.steps.size).uppercase()
            title.text = context.getString(step.title)
            body.text = context.getString(step.body)
            primary.text =
                context
                    .getString(
                        when {
                            state.isLast -> R.string.coach_done
                            !step.hasTarget -> R.string.coach_start
                            else -> R.string.coach_next
                        },
                    ).uppercase()
            pill(primary, selected = !state.skipSelected)
            skip.visibility = if (state.isLast) GONE else VISIBLE
            skip.text = context.getString(R.string.coach_skip).uppercase()
            pill(skip, selected = state.skipSelected)
            hint.text =
                context.getString(
                    when {
                        state.isLast -> R.string.coach_hint_last
                        state.index == 0 -> R.string.coach_hint_first
                        else -> R.string.coach_hint
                    },
                )
        }

        /** A button look; the remote's ◀ ▶ choose it and OK takes it (see [CoachMarkTour.onKey]) */
        private fun pill(
            view: TextView,
            selected: Boolean,
        ) {
            view.background =
                GradientDrawable().apply {
                    cornerRadius = context.dpF(100f)
                    if (selected) setColor(NeonColors.VOLT) else setStroke(context.dp(1), NeonColors.LINE2)
                }
            view.setTextColor(if (selected) NeonColors.ON_ACCENT else NeonColors.TEXT)
        }
    }
}
