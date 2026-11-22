package com.github.damontecres.wholphin.leanback.search

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.surfaceAtElevation
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.leanback.widget.control.AppButton
import com.github.damontecres.wholphin.leanback.widget.dialog.AppDialog
import com.github.damontecres.wholphin.leanback.widget.dialog.DialogOptions
import com.github.damontecres.wholphin.leanback.widget.dialog.PanelLook
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceInputState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val ERROR_AUTO_DISMISS_DELAY_MS = 3000L
private const val SOUND_LEVEL_SCALE_FACTOR = 0.15f
private const val BUBBLE_SIZE_DP = 160f
private const val MIC_ICON_FONT_SP = 56f
private const val BUTTON_ICON_FONT_SP = 20f
private const val CONTENT_SPACING_DP = 48
private const val HORIZONTAL_PADDING_DP = 64
private const val DISMISS_HINT_BOTTOM_PADDING_DP = 32
private const val HINT_TEXT_ALPHA = 0.5f
private const val SOUND_LEVEL_ANIM_MS = 100L
private const val BASE_PULSE_ANIM_MS = 800L
private const val RIPPLE_ANIM_MS = 1500L
private const val DOTS_ANIM_MS = 1200L
private const val RIPPLE_CANVAS_SCALE = 1.8f
private const val MAX_RIPPLE_EXPANSION = 0.35f
private const val RIPPLE_STROKE_WIDTH_DP = 2f
private const val RIPPLE_MAX_ALPHA = 0.4f

private fun VoiceInputState.shouldShowOverlay() =
    this is VoiceInputState.Starting ||
        this is VoiceInputState.Listening ||
        this is VoiceInputState.Processing ||
        this is VoiceInputState.Error

/**
 * `VoiceSearchButton` (ui/components/VoiceSearchButton.kt) as Views: the 40dp microphone button, the microphone
 * permission request and the listening overlay.
 *
 * Create it while the fragment is created (it registers the permission request), then [createButton] in
 * `onCreateView` and [start] with the view's lifecycle. The button exists only when speech recognition is available.
 * Pressing it starts listening (asking for the microphone first when needed) or stops it while starting / listening.
 * A result calls [onSpeechResult] and is acknowledged 50 ms later; a non-retryable error closes itself after 3 s.
 * Listening stops when the page pauses or is hidden ([stop]).
 */
internal class VoiceSearch(
    private val fragment: Fragment,
    private val voice: VoiceInputManager,
    private val onSpeechResult: (String) -> Unit,
) {
    private val permissionLauncher: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.onPermissionGranted() else voice.onPermissionDenied()
        }

    private var overlay: VoiceOverlayDialog? = null

    /** Speech recognition exists on this device (the old button rendered nothing otherwise) */
    val isAvailable: Boolean get() = voice.isAvailable

    /** The microphone button: 40 x 40dp, the Font Awesome microphone at 20sp */
    fun createButton(context: Context): AppButton =
        AppButton(context).apply {
            id = R.id.lb_search_voice_button
            setContentPaddingDp(0f, 0f, 0f, 0f)
            contentHeightDp = 40f
            exactContentHeight = true
            minimumWidth = context.dp(40)
            setGlyph(R.string.fa_microphone, sizeSp = BUTTON_ICON_FONT_SP, boxDp = 40f)
            contentDescription = context.getString(R.string.voice_search)
            layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40))
            setOnClickListener { onClick() }
        }

    private fun onClick() {
        when (voice.state.value) {
            is VoiceInputState.Starting, is VoiceInputState.Listening -> {
                voice.stopListening()
            }

            else -> {
                if (voice.hasPermission) {
                    voice.startListening()
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    /** Follow the recognizer while [owner] (the view lifecycle) is started */
    fun start(owner: LifecycleOwner) {
        if (!voice.isAvailable) return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    voice.state.collectLatest { state ->
                        when (state) {
                            is VoiceInputState.Result -> {
                                onSpeechResult(state.text)
                                // Small delay to allow focus to be restored before the overlay closes
                                delay(50)
                                voice.acknowledge()
                            }

                            is VoiceInputState.Error -> {
                                if (!state.isRetryable) {
                                    delay(ERROR_AUTO_DISMISS_DELAY_MS)
                                    voice.acknowledge()
                                }
                            }

                            else -> {}
                        }
                    }
                }
                combine(voice.state, voice.soundLevel, voice.partialResult) { s, l, p -> Triple(s, l, p) }
                    .collect { (state, level, partial) -> updateOverlay(state, level, partial) }
            }
        }
    }

    private fun updateOverlay(
        state: VoiceInputState,
        soundLevel: Float,
        partial: String,
    ) {
        if (!state.shouldShowOverlay()) {
            overlay?.dismiss()
            overlay = null
            return
        }
        val dialog =
            overlay ?: VoiceOverlayDialog(
                fragment.requireContext(),
                opener = fragment.view,
                onRetry = { voice.startListening() },
                onDismiss = { voice.stopListening() },
            ).also {
                overlay = it
                it.show()
            }
        val error = state as? VoiceInputState.Error
        dialog.update(
            soundLevel = soundLevel,
            partialResult = partial,
            isStarting = state is VoiceInputState.Starting,
            isProcessing = state is VoiceInputState.Processing,
            errorMessage = error?.messageResId?.let { fragment.getString(it) },
            isRetryable = error?.isRetryable == true,
        )
    }

    /** The page paused or was hidden: stop listening (the old `LifecycleResumeEffect` dispose) */
    fun stop() {
        if (voice.isAvailable) voice.stopListening()
    }

    /** The view is going away */
    fun release() {
        overlay?.dismiss()
        overlay = null
    }
}

/**
 * `VoiceSearchOverlay`: a dialog 85 % of the screen wide and at most 480dp tall (WeaselTV: square `card` with a 1dp
 * `line2` edge; other themes: a 16dp rounded surface at elevation 3) with the pulsing microphone bubble and its ripple
 * rings at the left, the status text (headlineMedium, scrolling within 240dp; UP / DOWN scroll it 100px) and Retry for
 * a retryable error at the right, and "Press back to cancel" at the bottom. BACK or a tap outside stops listening.
 */
internal class VoiceOverlayDialog(
    context: Context,
    opener: View?,
    private val onRetry: () -> Unit,
    onDismiss: () -> Unit,
) : AppDialog(
        context,
        DialogOptions(usePlatformDefaultWidth = false, look = PanelLook.NONE, countForRequestUi = false),
        opener = opener,
    ) {
    private val neon = AppTheme.isNeon
    private val primaryColor = if (neon) accent else AppTheme.palette.primary
    private val errorColor = AppTheme.palette.error
    private val bubble = VoiceBubbleView(context, primaryColor, errorColor)
    private val statusText =
        TextView(context).apply {
            applyTextRole(TextRole.HEADLINE_MEDIUM)
        }
    private val statusScroll =
        object : ScrollView(context) {
            override fun onKeyDown(
                keyCode: Int,
                event: KeyEvent,
            ): Boolean {
                val amount =
                    when {
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN && canScrollVertically(1) -> 100
                        keyCode == KeyEvent.KEYCODE_DPAD_UP && canScrollVertically(-1) -> -100
                        else -> return super.onKeyDown(keyCode, event)
                    }
                smoothScrollBy(0, amount)
                return true
            }

            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                val max = context.dp(240)
                val spec =
                    when (MeasureSpec.getMode(heightMeasureSpec)) {
                        MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)
                        else -> MeasureSpec.makeMeasureSpec(minOf(max, MeasureSpec.getSize(heightMeasureSpec)), MeasureSpec.AT_MOST)
                    }
                super.onMeasure(widthMeasureSpec, spec)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(statusText)
        }
    private val retry: AppButton =
        AppButton.text(context, R.string.retry) { onRetry() }.apply { visibility = View.GONE }
    private val dots = ValueAnimator.ofFloat(0f, 4f)
    private var dotCount = 0
    private var state = OverlayState()

    private data class OverlayState(
        val partialResult: String = "",
        val isStarting: Boolean = true,
        val isProcessing: Boolean = false,
        val errorMessage: String? = null,
        val isRetryable: Boolean = false,
    )

    init {
        onDismissRequest = onDismiss
        val content =
            object : FrameLayout(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    val metrics = resources.displayMetrics
                    val width = (metrics.widthPixels * .85f).toInt()
                    val maxHeight = minOf(context.dp(480), metrics.heightPixels)
                    super.onMeasure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
                    )
                }
            }
        content.background =
            GradientDrawable().apply {
                if (neon) {
                    setColor(NeonColors.CARD)
                    setStroke(context.dp(1), NeonColors.LINE2)
                } else {
                    setColor(AppTheme.palette.surfaceAtElevation(3f))
                    cornerRadius = context.dpF(16f)
                }
            }
        content.clipToOutline = !neon
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(
                    context.dp(HORIZONTAL_PADDING_DP),
                    context.dp(32),
                    context.dp(HORIZONTAL_PADDING_DP),
                    context.dp(64),
                )
            }
        row.addView(bubble, LinearLayout.LayoutParams(bubble.canvasSizePx, bubble.canvasSizePx))
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        column.addView(statusScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(
            retry,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(16) + if (neon) 0 else context.dp(16)
            },
        )
        row.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                // The bubble's ripple canvas is 1.8x the bubble: the old 48dp gap was measured from the canvas
                marginStart = context.dp(CONTENT_SPACING_DP)
            },
        )
        content.addView(
            row,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        content.addView(
            TextView(context).apply {
                setText(R.string.press_back_to_cancel)
                applyTextRole(TextRole.BODY_SMALL)
                setTextColor(withAlpha(AppTheme.palette.onSurface, HINT_TEXT_ALPHA))
            },
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    bottomMargin = context.dp(DISMISS_HINT_BOTTOM_PADDING_DP)
                },
        )
        setPanelContent(content)
        dots.apply {
            duration = DOTS_ANIM_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                val count = (it.animatedValue as Float).toInt()
                if (count != dotCount) {
                    dotCount = count
                    render()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dots.start()
        bubble.startAnimations()
        statusScroll.requestFocus()
    }

    override fun onStop() {
        dots.cancel()
        bubble.stopAnimations()
        super.onStop()
    }

    fun update(
        soundLevel: Float,
        partialResult: String,
        isStarting: Boolean,
        isProcessing: Boolean,
        errorMessage: String?,
        isRetryable: Boolean,
    ) {
        state = OverlayState(partialResult, isStarting, isProcessing, errorMessage, isRetryable)
        bubble.setSoundLevel(soundLevel)
        // Ripples only while actually listening (not starting, processing or in error)
        bubble.setState(rippling = !isStarting && !isProcessing && errorMessage == null, error = errorMessage != null)
        val showRetry = errorMessage != null && isRetryable
        retry.visibility = if (showRetry) View.VISIBLE else View.GONE
        render()
    }

    private fun render() {
        val s = state
        val dotsText = ".".repeat(dotCount)
        val (text, description) =
            when {
                s.errorMessage != null -> s.errorMessage to s.errorMessage
                s.isProcessing -> (context.getString(R.string.processing) + dotsText) to context.getString(R.string.processing)
                s.isStarting -> (context.getString(R.string.voice_starting) + dotsText) to context.getString(R.string.voice_starting)
                s.partialResult.isNotBlank() -> s.partialResult to s.partialResult
                else -> (context.getString(R.string.voice_search_prompt) + dotsText) to context.getString(R.string.voice_search_prompt)
            }
        if (statusText.text?.toString() != text) statusText.text = text
        statusText.setTextColor(if (s.errorMessage != null) errorColor else 0xFFFFFFFF.toInt())
        statusScroll.contentDescription = description
    }
}

/**
 * The overlay's microphone: a 160dp bubble pulsing between 1 and 1.05 (800 ms, reversing) and growing with the sound
 * level (+15 % at full level, eased over 100 ms), in the accent (the error colour on errors), with the microphone glyph
 * in onPrimary; behind it three ripple rings (2dp) expanding up to 35 % of the bubble and fading out every 1.5 s,
 * drawn only while listening.
 */
private class VoiceBubbleView(
    context: Context,
    private val primaryColor: Int,
    private val errorColor: Int,
) : View(context) {
    private val bubblePx = context.dpF(BUBBLE_SIZE_DP)
    val canvasSizePx: Int = (bubblePx * RIPPLE_CANVAS_SCALE).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dpF(RIPPLE_STROKE_WIDTH_DP)
        }
    private val glyph =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = AppFonts.icons(context)
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIC_ICON_FONT_SP, resources.displayMetrics)
            color = AppTheme.palette.onPrimary
        }
    private val mic = context.getString(R.string.fa_microphone)
    private var pulse = 1f
    private var ripple = 0f
    private var level = 0f
    private var rippling = false
    private var error = false
    private var levelAnimator: ValueAnimator? = null

    private val pulseAnimator =
        ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = BASE_PULSE_ANIM_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
        }
    private val rippleAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RIPPLE_ANIM_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                ripple = if (rippling) it.animatedValue as Float else 0f
                invalidate()
            }
        }

    init {
        contentDescription = context.getString(R.string.voice_search)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun startAnimations() {
        pulseAnimator.start()
        rippleAnimator.start()
    }

    fun stopAnimations() {
        pulseAnimator.cancel()
        rippleAnimator.cancel()
        levelAnimator?.cancel()
    }

    fun setSoundLevel(target: Float) {
        levelAnimator?.cancel()
        levelAnimator =
            ValueAnimator.ofFloat(level, target).apply {
                duration = SOUND_LEVEL_ANIM_MS
                addUpdateListener {
                    level = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    fun setState(
        rippling: Boolean,
        error: Boolean,
    ) {
        this.rippling = rippling
        this.error = error
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = bubblePx / 2f
        if (rippling) {
            val maxExpansion = bubblePx * MAX_RIPPLE_EXPANSION
            for (i in 0..2) {
                val progress = (ripple + i * 0.33f) % 1f
                ring.color = withAlpha(primaryColor, (1f - progress) * RIPPLE_MAX_ALPHA)
                canvas.drawCircle(cx, cy, base + progress * maxExpansion, ring)
            }
        }
        val scale = pulse + level * SOUND_LEVEL_SCALE_FACTOR
        paint.color = if (error) errorColor else primaryColor
        canvas.drawCircle(cx, cy, base * scale, paint)
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val fm = glyph.fontMetrics
        canvas.drawText(mic, cx, cy - (fm.ascent + fm.descent) / 2f, glyph)
        canvas.restore()
    }
}
