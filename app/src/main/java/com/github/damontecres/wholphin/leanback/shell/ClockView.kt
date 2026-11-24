package com.github.damontecres.wholphin.leanback.shell

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.ui.formatTime
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * The app's clock (the old `ProvideLocalClock` / `LocalClock`): the time formatted for the device every 2 seconds.
 * Screens that showed `LocalClock.current.timeString` collect [time].
 */
object AppClock {
    private val _time = MutableStateFlow("")

    /** The current time, formatted like the old `LocalClock.timeString` */
    val time: StateFlow<String> = _time.asStateFlow()

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var users = 0

    /** A clock on screen: the time ticks while at least one is */
    internal fun acquire(context: Context) {
        users++
        start(context)
    }

    internal fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    private fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        val s = scope ?: MainScope().also { scope = it }
        job =
            s.launch(WholphinDispatchers.Default) {
                while (isActive) {
                    val time = formatTime(appContext, LocalDateTime.now())
                    withContext(WholphinDispatchers.Main) { _time.value = time }
                    delay(2_000)
                }
            }
    }

    private fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * The clock in the top end corner of pages with the side menu and of the screensaver (the old `TimeDisplay`): 18sp
 * body text in `onSurface`, 16dp from the top and 24dp from the side.
 */
@SuppressLint("ViewConstructor")
internal class ClockView(
    context: Context,
) : AppCompatTextView(context) {
    private var job: Job? = null

    init {
        applyTextRole(TextRole.BODY_LARGE, sizeSp = 18f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(AppTheme.palette.onSurface)
        maxLines = 1
        isFocusable = false
    }

    fun onThemeChanged() {
        applyTextRole(TextRole.BODY_LARGE, sizeSp = 18f)
        setTextColor(AppTheme.palette.onSurface)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppClock.acquire(context)
        job =
            MainScope().launch {
                AppClock.time.collect { text = it }
            }
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        job = null
        AppClock.release()
        super.onDetachedFromWindow()
    }
}
