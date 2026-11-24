package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.ui.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * The app clock (the old `LocalClock` / `ProvideLocalClock`): the current time, refreshed every 2 seconds while anyone
 * collects [now]. Views format it with their own context ([formatTime]), so an in-app language switch is honoured.
 */
object AppClock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val now: StateFlow<LocalDateTime> =
        flow {
            while (true) {
                emit(LocalDateTime.now())
                delay(2_000)
            }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), LocalDateTime.now())
}

/**
 * The clock in a page corner (`TimeDisplay`): the time in bodyLarge at 18sp, onSurface, with 24dp side and 16dp
 * vertical padding. Place it top-end in a FrameLayout (`gravity = TOP or END`), as the old `BoxScope.TimeDisplay` did.
 * It updates while attached.
 */
class TimeDisplayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : AppCompatTextView(context, attrs) {
        private var job: Job? = null
        private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        init {
            applyTextRole(TextRole.BODY_LARGE, sizeSp = 18f)
            setTextColor(AppTheme.palette.onSurface)
            setPaddingRelative(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
            maxLines = 1
            text = formatTime(context, LocalDateTime.now())
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            job = mainScope.launch { AppClock.now.collect { text = formatTime(context, it) } }
        }

        override fun onDetachedFromWindow() {
            job?.cancel()
            job = null
            super.onDetachedFromWindow()
        }

        companion object {
            /**
             * Space that page headings leave for the clock overlaid in their corner (`clockHeaderEndPadding`): the
             * width of the time (or "12:59 PM") in bodyLarge 18sp, plus 40dp.
             */
            fun headerEndPaddingPx(context: Context): Int {
                val probe = AppCompatTextView(context).apply { applyTextRole(TextRole.BODY_LARGE, sizeSp = 18f) }
                val paint =
                    Paint(probe.paint).apply {
                        textSize =
                            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics)
                    }
                val time = formatTime(context, LocalDateTime.now()).ifBlank { "12:59 PM" }
                return paint.measureText(time).toInt() + context.dp(40)
            }
        }
    }
