package com.github.damontecres.wholphin.leanback.settings

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.viewModels
import androidx.leanback.widget.VerticalGridView
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.ui.detail.DebugViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The debug page (`Destination.Debug`, opened by a long press on "Installed version") as Leanback Preference rows over
 * the whole page: app and device information, display modes, audio devices, the app preferences, the user, the
 * playback database and the last 500 logcat lines coloured by level. UP/DOWN move a line; fast-forward / page down
 * and skip-back / page up move ten.
 */
@AndroidEntryPoint
class DebugFragment : SettingsPageFragment() {
    private val viewModel: DebugViewModel by viewModels()

    override val rootScreenKey: String = "debug"
    override val panelFraction: Float = 1f
    override val panelStripe: Boolean = false
    override val listHorizontalPaddingDp: Int = 16

    override fun panelBackground(): Int = AppTheme.palette.surface

    override fun screenTitle(key: String): CharSequence = "Debug"

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context

        fun section(
            title: String,
            role: TextRole = TextRole.HEADLINE_MEDIUM,
            parent: PreferenceGroup = screen,
        ): SettingsCategory =
            SettingsCategory(ctx).also {
                it.key = "section:$title"
                it.title = title
                it.role = role
                it.large = false
                parent.addPreference(it)
            }

        /** Lines under [parent] that follow [lines] (rows are reused by position) */
        fun lines(
            parent: PreferenceGroup,
            name: String,
            lines: () -> List<Pair<String, Int?>>,
        ) {
            val rows = mutableListOf<SettingsInfoPreference>()
            fragment.onRefresh {
                val current = lines()
                while (rows.size < current.size) {
                    rows +=
                        SettingsInfoPreference(ctx).also {
                            it.key = "$name:${rows.size}"
                            it.accent = accent
                            it.compact = true
                            parent.addPreference(it)
                        }
                }
                rows.forEachIndexed { index, row ->
                    val line = current.getOrNull(index)
                    row.isVisible = line != null
                    if (line != null) {
                        row.title = line.first
                        row.textColor = line.second
                    }
                }
            }
        }

        fun plain(list: List<String>) = list.map { it to null }

        val state = { viewModel.state.value }
        lines(section("App Information"), "app") { plain(state().appInfo) }
        val device = section("Device Information")
        lines(device, "device") { plain(state().deviceInfo) }
        lines(section("Display Modes", TextRole.TITLE_MEDIUM, device), "modes") { plain(state().displayModes) }
        lines(section("Audio Devices", TextRole.TITLE_MEDIUM, device), "audio") { plain(state().audioInfo) }
        lines(section("AppPreferences"), "prefs") {
            listOf(
                screenHost.userPreferences.value
                    ?.appPreferences
                    ?.toString()
                    .orEmpty() to null,
            )
        }
        lines(section("User Information"), "user") {
            plain(
                listOf(
                    "Current server: ${viewModel.serverRepository.currentServer}",
                    "Current user: ${viewModel.serverRepository.currentUser}",
                    "User server settings: ${viewModel.serverRepository.currentUserDto?.configuration}",
                ),
            )
        }
        val database = section("Database")
        lines(section("ItemPlayback", TextRole.TITLE_MEDIUM, database), "playback") {
            plain(viewModel.itemPlaybacks.value.map { it.toString() })
        }
        lines(section("Logcat"), "logcat") {
            viewModel.logcat.value.map { (level, line) -> line to logColor(level) }
        }
    }

    /** The old logcat colours */
    private fun logColor(level: Int): Int? =
        when (level) {
            Log.DEBUG -> 0xff2bc4cf.toInt()
            Log.INFO -> 0xff2bcf8b.toInt()
            Log.WARN -> 0xffdde663.toInt()
            Log.ERROR -> 0xffe67063.toInt()
            else -> null
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.state.collectWhileStarted { refreshScreens() }
        viewModel.itemPlaybacks.collectWhileStarted { refreshScreens() }
        viewModel.logcat.collectWhileStarted { refreshScreens() }
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val jump =
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_ROWS
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_PAGE_UP -> -PAGE_ROWS
                else -> return false
            }
        val grid = topList?.listView as? VerticalGridView ?: return false
        val count = grid.adapter?.itemCount ?: return false
        grid.setSelectedPositionSmooth((grid.selectedPosition + jump).coerceIn(0, (count - 1).coerceAtLeast(0)))
        return true
    }

    private companion object {
        const val PAGE_ROWS = 10
    }
}
