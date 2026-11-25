package com.github.damontecres.wholphin.leanback.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.Lifecycle
import coil3.load
import com.github.damontecres.wholphin.isPlayback
import com.github.damontecres.wholphin.leanback.theme.AppFonts
import com.github.damontecres.wholphin.leanback.theme.NeonSections
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.RequestEvent
import com.github.damontecres.wholphin.services.RequestStageKind
import com.github.damontecres.wholphin.services.RequestStatusItem
import com.github.damontecres.wholphin.services.RequestStatusRepository
import com.github.damontecres.wholphin.services.RequestSubmission
import com.github.damontecres.wholphin.services.RequestsState
import com.github.damontecres.wholphin.services.eventKey
import com.github.damontecres.wholphin.services.presentationKey
import com.github.damontecres.wholphin.services.requestPresentationKey
import com.github.damontecres.wholphin.ui.nav.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

private const val REQUEST_CHIP_TIMEOUT_MILLIS = 60_000L
private const val REQUEST_SUBMISSION_TIMEOUT_MILLIS = 6_000L

/**
 * The request status chips (the old `RequestChipHost`): a nonmodal card in the bottom end corner when a request is
 * ready or has news, and a short "Request sent" confirmation. Never over playback, a dialog (the window loses
 * focus), text entry or the tour; a chip hidden by one of those is not used up.
 *
 * The card takes focus when it appears (after 500 ms, so navigation settles), OK only counts 700 ms after it appeared,
 * BACK closes it, and it closes itself after a minute without input. Closing gives focus back to where it was.
 */
internal class RequestChipHost(
    private val host: FrameLayout,
    private val repository: RequestStatusRepository,
    private val navigation: NavigationManager,
    private val ownerRowId: Int,
    private val presentationBlocked: Flow<Boolean>,
    private val lifecycle: Lifecycle,
    /** Gives focus back to the page when the view focused before the chip has gone */
    private val focusPage: () -> Unit,
) {
    private val context = host.context

    /** Window focus, lifecycle and text entry changes poke this so the rules are checked again */
    val environment = MutableStateFlow(0)

    private var state = RequestsState()
    private var blocked = false
    private val progressHost = Any()

    // Per request scope
    private var scope: String? = null
    private var events: List<RequestEvent> = emptyList()
    private var closedKeys: Set<String> = emptySet()
    private var nextPresentationAt = 0L
    private var displayed = false
    private var returnFocus: View? = null

    private var lastInput = 0L
    private var shownAt = 0L
    private var armed = false
    private var validPress = false
    private var remaining = 1f
    private var submissionRemaining = 1f
    private var submission: RequestSubmission? = null

    private var card: RequestNotificationCardView? = null
    private var cardIsSubmission = false

    private var coroutineScope: CoroutineScope? = null
    private val jobs = mutableListOf<Job>()
    private var presentJob: Job? = null
    private var presentKey: Any? = null
    private var metadataKey: Any? = null
    private var displayJob: Job? = null
    private var submissionJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        coroutineScope = scope
        jobs +=
            scope.launch {
                combine(repository.state, presentationBlocked, environment) { s, b, _ -> s to b }
                    .collect { (s, b) ->
                        state = s
                        blocked = b
                        evaluate()
                    }
            }
        jobs +=
            scope.launch {
                navigation.backStackFlow.collect { evaluate() }
            }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        presentJob?.cancel()
        displayJob?.cancel()
        submissionJob?.cancel()
        presentJob = null
        displayJob = null
        submissionJob = null
        presentKey = null
        metadataKey = null
        repository.progress.removeHost(progressHost)
        removeCard()
        displayed = false
        events = emptyList()
        coroutineScope = null
    }

    private val windowFocused get() = host.hasWindowFocus()
    private val resumed get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private val playback get() = navigation.backStack.lastOrNull().isPlayback

    /** Text entry anywhere in the window (search, dialogs' fields) */
    private val textInputActive get() = host.rootView?.findFocus() is EditText

    private val chipFocused get() = card?.hasFocus() == true

    private fun pageSafe(): Boolean =
        state.ownerRowId == ownerRowId && state.connected && windowFocused && resumed && !playback &&
            !textInputActive && !blocked

    private fun candidates(): List<RequestEvent> {
        val deliveredKeys = state.notifications.delivered.mapTo(mutableSetOf()) { it.requestPresentationKey() }
        return state.notifications.pending.filter { e ->
            e.presentationKey() !in closedKeys && e.presentationKey() !in deliveredKeys &&
                state.items.any {
                    it.request.id == e.requestId && it.fresh && it.eventKey()?.requestPresentationKey() == e.presentationKey()
                }
        }
    }

    private fun evaluate() {
        val scope = coroutineScope ?: return
        if (state.scope != this.scope) {
            // A new request scope (user/server) starts clean
            this.scope = state.scope
            events = emptyList()
            closedKeys = emptySet()
            nextPresentationAt = 0L
            displayed = false
            returnFocus = null
            remaining = 1f
            displayJob?.cancel()
        }
        val pageSafe = pageSafe()
        repository.progress.setAllowed(progressHost, pageSafe)
        val safe = pageSafe && state.notifications.enabled
        val candidates = candidates()
        val candidateKeys = candidates.map { it.presentationKey() }

        val newMetadataKey = Triple(safe, state.scope, candidateKeys)
        if (newMetadataKey != metadataKey) {
            metadataKey = newMetadataKey
            if (safe) candidates.forEach { repository.loadMetadata(it.requestId) }
        }

        val eventRows =
            state.items
                .filter { row -> events.any { it.requestId == row.request.id } }
                .map { it.eventKey() to it.fresh }
        val newPresentKey = listOf(safe, state.scope, candidateKeys, eventRows)
        if (newPresentKey != presentKey) {
            presentKey = newPresentKey
            presentJob?.cancel()
            presentJob =
                scope.launch {
                    if (!safe) {
                        // Do not consume a chip that was hidden by a dialog, playback, dimming or app stop
                        if (events.isNotEmpty()) {
                            if (chipFocused && windowFocused && resumed && !playback) restore()
                            events = emptyList()
                            setDisplayed(false)
                        }
                    } else if (events.isEmpty() && candidates.isNotEmpty()) {
                        // Let navigation settle; back off if focus was temporarily refused
                        delay(maxOf(500L, nextPresentationAt - SystemClock.uptimeMillis()))
                        events = candidates.toList()
                        returnFocus = host.rootView?.findFocus()
                        shownAt = SystemClock.uptimeMillis()
                        lastInput = shownAt
                        remaining = 1f
                        armed = false
                        validPress = false
                        setDisplayed(true)
                    } else if (events.isNotEmpty() &&
                        events.none { e ->
                            state.items.any {
                                it.request.id == e.requestId && it.eventKey()?.requestPresentationKey() == e.presentationKey()
                            }
                        }
                    ) {
                        if (chipFocused) restore()
                        events = emptyList()
                        setDisplayed(false)
                    }
                }
        }

        if (state.submission != submission) {
            submission = state.submission
            submissionJob?.cancel()
            submissionRemaining = 1f
            val sub = submission
            if (sub != null) {
                submissionJob =
                    scope.launch {
                        while (true) {
                            val left = (REQUEST_SUBMISSION_TIMEOUT_MILLIS - (SystemClock.uptimeMillis() - sub.createdAt)).coerceAtLeast(0)
                            submissionRemaining = left.toFloat() / REQUEST_SUBMISSION_TIMEOUT_MILLIS
                            card?.takeIf { cardIsSubmission }?.setRemaining(submissionRemaining)
                            if (left == 0L) {
                                repository.clearSubmission(sub)
                                break
                            }
                            delay(50)
                        }
                    }
            }
        }
        render(pageSafe, safe)
    }

    private fun setDisplayed(value: Boolean) {
        if (displayed == value && !value) {
            render(pageSafe(), pageSafe() && state.notifications.enabled)
            return
        }
        displayed = value
        displayJob?.cancel()
        render(pageSafe(), pageSafe() && state.notifications.enabled)
        if (value) {
            val scope = coroutineScope ?: return
            displayJob =
                scope.launch {
                    val focused = card?.focusPrimary() == true
                    if (!focused) {
                        nextPresentationAt = SystemClock.uptimeMillis() + 5_000
                        events = emptyList()
                        displayed = false
                        render(pageSafe(), false)
                        return@launch
                    }
                    while (displayed) {
                        val now = SystemClock.uptimeMillis()
                        armed = now - shownAt >= 700
                        val left = (REQUEST_CHIP_TIMEOUT_MILLIS - (now - lastInput)).coerceAtLeast(0)
                        remaining = left.toFloat() / REQUEST_CHIP_TIMEOUT_MILLIS
                        card?.takeIf { !cardIsSubmission }?.setRemaining(remaining)
                        if (left == 0L) {
                            close()
                            break
                        }
                        delay(50)
                    }
                }
        }
    }

    private fun render(
        pageSafe: Boolean,
        safe: Boolean,
    ) {
        val sub = submission
        if (displayed && safe && events.isNotEmpty()) {
            val rows = events.mapNotNull { event -> state.items.firstOrNull { it.request.id == event.requestId } }
            val single = rows.singleOrNull().takeIf { events.size == 1 }
            val canWatch = single?.stage?.ready == true && single.jellyfinId != null
            val c = ensureCard(submission = false)
            c.bind(rows = rows, eventCount = events.size, canWatch = canWatch, interactive = true, confirmationLabel = null)
            c.setRemaining(remaining)
            c.onPrimary = {
                if (armed) {
                    close(restoreFocus = false)
                    repository.markOpened(single?.request?.id)
                    val itemId = single?.jellyfinId
                    if (canWatch && itemId != null) {
                        navigation.navigateTo(
                            Destination.MediaItem(itemId, if (single.request.type == "tv") BaseItemKind.SERIES else BaseItemKind.MOVIE),
                        )
                    } else {
                        navigation.navigateToFromDrawer(Destination.Discover(1, single?.request?.id))
                    }
                }
            }
            c.onDismiss = { if (armed) close() }
        } else if (sub != null && pageSafe && !displayed) {
            val c = ensureCard(submission = true)
            c.bind(
                rows = listOf(sub.item),
                eventCount = 1,
                canWatch = false,
                interactive = false,
                confirmationLabel = if (sub.updated) "Request updated" else "Request sent",
            )
            c.setRemaining(submissionRemaining)
        } else {
            removeCard()
        }
    }

    private fun ensureCard(submission: Boolean): RequestNotificationCardView {
        val existing = card
        if (existing != null && cardIsSubmission == submission) return existing
        removeCard()
        val c = RequestNotificationCardView(context)
        c.keyFilter = { event -> onCardKey(event) }
        val lp =
            FrameLayout.LayoutParams(context.dp(360), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = context.dp(24)
                marginStart = context.dp(24)
                marginEnd = context.dp(24)
            }
        host.addView(c, lp)
        card = c
        cardIsSubmission = submission
        return c
    }

    private fun removeCard() {
        card?.let { host.removeView(it) }
        card = null
    }

    /** Every key while the card has focus; true consumes */
    private fun onCardKey(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (event.keyCode == KeyEvent.KEYCODE_BACK && chipFocused) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        if (event.keyCode in okKeys) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount > 0) return true
                validPress = armed && event.downTime >= shownAt + 700
                if (!validPress) return true
            } else if (event.action == KeyEvent.ACTION_UP && !validPress) {
                return true
            }
        }
        return false
    }

    private fun restore() {
        // Transfer focus while the chip is still attached, so removing it cannot select the rail
        val target = returnFocus
        returnFocus = null
        if (target != null && target.isAttachedToWindow && target.isShown && target.requestFocus()) return
        focusPage()
    }

    private fun close(restoreFocus: Boolean = true) {
        if (events.isNotEmpty()) {
            closedKeys = closedKeys + events.map { it.presentationKey() }
            repository.delivered(events)
        }
        if (restoreFocus && chipFocused) {
            restore()
        } else {
            returnFocus = null
        }
        events = emptyList()
        displayed = false
        displayJob?.cancel()
        render(pageSafe(), pageSafe() && state.notifications.enabled)
    }

    private companion object {
        val okKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}

/**
 * The flat, square Requests card shared by readiness and the noninteractive submission feedback (the old
 * `RequestNotificationCard`): poster, status, title, details, what happened, Watch now / Details / View requests and
 * Dismiss, and a 2dp bar counting down.
 */
@SuppressLint("ViewConstructor")
internal class RequestNotificationCardView(
    context: Context,
) : LinearLayout(context) {
    private val accent = NeonSections.Requests.glow
    private val edge = NeonSections.Requests.border
    private val cardColor = 0xFF0B0E13.toInt()
    private val textColor = 0xFFE4E7EF.toInt()
    private val muted = 0xFF828A9B.toInt()

    private val posterLetter = TextView(context)
    private val poster = ImageView(context)
    private val more = TextView(context)
    private val status = TextView(context)
    private val title = TextView(context)
    private val metadata = TextView(context)
    private val instruction = TextView(context)
    private val buttons = LinearLayout(context)
    private val primary = CardButton(context, filled = true)
    private val dismiss = CardButton(context, filled = false)
    private val bar = ProgressBarView(context)
    private val paint = Paint()
    private var posterUrl: String? = null

    var onPrimary: () -> Unit = {}
    var onDismiss: () -> Unit = {}

    /** Sees every key before the focused button */
    var keyFilter: (KeyEvent) -> Boolean = { false }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                setPadding(context.dp(18), context.dp(13), context.dp(14), context.dp(14))
            }
        val posterBox =
            object : FrameLayout(context) {
                private val p = Paint().apply { style = Paint.Style.STROKE }

                override fun dispatchDraw(canvas: Canvas) {
                    super.dispatchDraw(canvas)
                    p.color = 0xFF29313D.toInt()
                    p.strokeWidth = 0f
                    canvas.drawRect(0f, 0f, width - 1f, height - 1f, p)
                }
            }
        posterBox.setBackgroundColor(0xFF142528.toInt())
        posterLetter.gravity = Gravity.CENTER
        display(posterLetter)
        posterLetter.setTextColor(withAlpha(accent, .4f))
        posterBox.addView(
            posterLetter,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        posterBox.addView(poster, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        label(more)
        more.setTextColor(textColor)
        more.setBackgroundColor(0xE60B0E13.toInt())
        more.setPadding(context.dp(5), context.dp(3), context.dp(5), context.dp(3))
        posterBox.addView(
            more,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END,
            ),
        )
        row.addView(posterBox, LayoutParams(context.dp(56), context.dp(84)))

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        label(status)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        status.setLineHeightPx(context.spToPx(11f))
        status.letterSpacing = .16f
        status.setTextColor(accent)
        status.maxLines = 2
        status.ellipsize = TextUtils.TruncateAt.END
        column.addView(status)
        display(title)
        title.setTextColor(textColor)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        column.addView(title, spaced())
        body(metadata)
        metadata.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        metadata.setLineHeightPx(context.spToPx(13f))
        metadata.setTextColor(muted)
        column.addView(metadata, spaced())
        body(instruction)
        instruction.setTextColor(textColor)
        column.addView(instruction, spaced())
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.CENTER_VERTICAL
        primary.minWidth = context.dp(100)
        primary.setPadding(context.dp(12), 0, context.dp(12), 0)
        primary.setOnClickListener { onPrimary() }
        buttons.addView(primary, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(25)))
        dismiss.text = "DISMISS"
        dismiss.setPadding(context.dp(8), 0, context.dp(8), 0)
        dismiss.setOnClickListener { onDismiss() }
        buttons.addView(dismiss, LayoutParams(context.dp(66), context.dp(25)).apply { marginStart = context.dp(8) })
        column.addView(buttons, spaced(context.dp(4) + context.dp(2)))
        row.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(14) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(2)))
    }

    private fun spaced(top: Int = context.dp(4)) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun body(view: TextView) {
        view.typeface = AppFonts.body(context, 400)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        view.setLineHeightPx(context.spToPx(14f))
        view.letterSpacing = 0f
    }

    private fun display(view: TextView) {
        view.typeface = AppFonts.display(context)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        view.setLineHeightPx(context.spToPx(23f))
        view.letterSpacing = .02f
    }

    private fun label(view: TextView) {
        view.typeface = AppFonts.body(context, 700)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        view.setLineHeightPx(context.spToPx(13f))
        view.letterSpacing = .08f
        view.maxLines = 1
    }

    fun bind(
        rows: List<RequestStatusItem>,
        eventCount: Int,
        canWatch: Boolean,
        interactive: Boolean,
        confirmationLabel: String?,
    ) {
        val single = rows.singleOrNull().takeIf { eventCount == 1 }
        val cover = single ?: rows.firstOrNull { it.item?.posterUrl != null } ?: rows.firstOrNull()
        val allReady = rows.size == eventCount && rows.all { it.stage.kind == RequestStageKind.READY }
        val confirmation = confirmationLabel != null
        status.text = (confirmationLabel ?: single?.stage?.label ?: if (allReady) "Ready to watch" else "Request updates").uppercase()
        title.text = (single?.title ?: if (allReady) "$eventCount requests ready" else "$eventCount request updates").uppercase()
        primary.text =
            when {
                eventCount > 1 -> "View requests"
                canWatch -> "Watch now"
                else -> "Details"
            }.uppercase()
        primary.glow = canWatch
        instruction.text =
            when {
                confirmation -> "We'll let you know when it's ready to watch."
                eventCount > 1 -> "Your requests have updates."
                single?.stage?.kind == RequestStageKind.READY -> "Your request is ready."
                single?.stage?.kind == RequestStageKind.PARTIAL -> "Some episodes are ready."
                else -> "Your request has an update."
            }
        val meta =
            single?.let { row ->
                listOfNotNull(
                    row.item
                        ?.releaseDate
                        ?.year
                        ?.toString(),
                    if (row.request.type == "tv") "TV series" else "Movie",
                    "4K".takeIf { row.request.is4k == true },
                ).joinToString(" · ")
            }
        metadata.text = meta
        metadata.visibility = if (meta != null) VISIBLE else GONE
        posterLetter.text = cover?.title?.take(1)?.uppercase() ?: "W"
        val url = cover?.item?.posterUrl
        if (url != posterUrl) {
            posterUrl = url
            poster.load(url)
        }
        more.text = "+${eventCount - 1}"
        more.visibility = if (eventCount > 1) VISIBLE else GONE
        val showButtons = !confirmation && interactive
        buttons.visibility = if (showButtons) VISIBLE else GONE
        primary.isFocusable = showButtons
        dismiss.isFocusable = showButtons
    }

    fun setRemaining(value: Float) {
        bar.fraction = value.coerceIn(0f, 1f)
    }

    /** Focus the first button; false when the window refused it */
    fun focusPrimary(): Boolean = primary.requestFocus()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyFilter(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = cardColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = accent
        canvas.drawRect(0f, 0f, context.dpF(3f), height.toFloat(), paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0f
        paint.color = edge
        canvas.drawRect(0f, 0f, width - 1f, height - 1f, paint)
        paint.style = Paint.Style.FILL
    }

    /** Square, no scale: filled with the accent (Watch now / Details) or quiet (Dismiss); a 1dp ring 2dp in when focused */
    private inner class CardButton(
        context: Context,
        private val filled: Boolean,
    ) : AppCompatTextView(context) {
        var glow = false
            set(value) {
                field = value
                refresh()
            }
        private val box = ShellBoxDrawable(context)
        private val ring = Paint().apply { style = Paint.Style.STROKE }

        init {
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER
            maxLines = 1
            this@RequestNotificationCardView.label(this)
            background = box
            refresh()
        }

        override fun onFocusChanged(
            gainFocus: Boolean,
            direction: Int,
            previouslyFocusedRect: Rect?,
        ) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            refresh()
        }

        private fun refresh() {
            if (filled) {
                box.set(fill = accent, glow = if (isFocused && glow) withAlpha(accent, .35f) else 0, glowDp = 8f)
                setTextColor(0xFF071519.toInt())
            } else {
                box.set(fill = cardColor, border = if (isFocused) 0 else 0xFF29313D.toInt(), borderDp = .5f)
                setTextColor(textColor)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isFocused) {
                val inset = context.dpF(2f)
                ring.color = textColor
                ring.strokeWidth = context.dpF(1f)
                val h = ring.strokeWidth / 2f
                canvas.drawRect(inset + h, inset + h, width - inset - h, height - inset - h, ring)
            }
        }
    }

    private inner class ProgressBarView(
        context: Context,
    ) : View(context) {
        var fraction = 1f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        private val p = Paint()

        override fun onDraw(canvas: Canvas) {
            p.color = withAlpha(accent, .15f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = accent
            canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), p)
        }
    }
}
