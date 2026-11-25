package com.github.damontecres.wholphin.leanback.settings

import android.app.Dialog
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import coil3.SingletonImageLoader
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.KidSessionGateImpl
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.ExoPlayerPreferences
import com.github.damontecres.wholphin.preferences.ExperimentalPreference
import com.github.damontecres.wholphin.preferences.MpvPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.SkipSegmentPreferences
import com.github.damontecres.wholphin.preferences.advancedPreferences
import com.github.damontecres.wholphin.preferences.basicPreferences
import com.github.damontecres.wholphin.preferences.experimentalPreferences
import com.github.damontecres.wholphin.preferences.kidHiddenPreferences
import com.github.damontecres.wholphin.preferences.updatePlaybackPreferences
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.RequestStatusRepository
import com.github.damontecres.wholphin.services.SeerrConnectionStatus
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.coachmark.CoachMarkTour
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.LocaleChoiceViewModel
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.preferences.PreferencesViewModel
import com.github.damontecres.wholphin.ui.setup.UpdateViewModel
import com.github.damontecres.wholphin.ui.setup.seerr.SwitchSeerrViewModel
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject

/**
 * Settings (`Destination.Settings`) on Leanback Preference (no-Compose UI, 2026-11-22): every
 * [PreferenceScreenOption] (basic, advanced, ExoPlayer, MPV, skip segments, experimental), built from
 * the definitions in `preferences/AppPreference.kt` with the special rows the old `PreferencesContent` had: install
 * update, installed version (release notes; long press opens Debug), the tour, image cache, side menu pins, profile
 * protection and the kid-protection switch-off, sign-in, request notifications, Seerr, Quick Connect,
 * external player, interface language, experimental features and mpv.conf.
 *
 * The panel sits on the right 40%; while an "Install update" row has focus the release notes show beside it.
 */
@AndroidEntryPoint
class AppSettingsFragment : SettingsPageFragment() {
    private val viewModel: PreferencesViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()
    private val seerrViewModel: SwitchSeerrViewModel by viewModels()
    private val localeViewModel: LocaleChoiceViewModel by viewModels()

    @Inject lateinit var kidSessionGate: KidSessionGateImpl

    @Inject lateinit var requestStatus: RequestStatusRepository

    @Inject lateinit var coachMarkTour: CoachMarkTour

    private val option: PreferenceScreenOption get() = requireDestination<Destination.Settings>().screen

    private var prefs: AppPreferences? = null
    private var kidSession = false
    private var cacheSummary: String = ""
    private var lastBackend: PlayerBackend? = null
    private var lastUpdateKey: Pair<String, Boolean>? = null
    private var updateRowsFocused = 0
    private var notesPane: View? = null
    private var notesText: TextView? = null
    private val dialogs = mutableListOf<Dialog>()

    override val rootScreenKey: String = ROOT
    override val wrapAroundRows: Boolean = true

    override fun screenTitle(key: String): CharSequence =
        getString(
            when (key) {
                NAV_PINS -> R.string.nav_drawer_pins
                SEERR_LOGIN -> R.string.seerr_login
                else -> option.titleRes
            },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = initialAppPreferences
    }

    private val installedVersion get() = updateViewModel.currentVersion

    private val release: Release? get() = updateViewModel.state.value.release

    private val updateAvailable: Boolean
        get() = release?.version?.isGreaterThan(installedVersion) ?: false

    private val showUpdate: Boolean
        get() =
            UpdateChecker.ACTIVE &&
                option == PreferenceScreenOption.BASIC &&
                prefs?.autoCheckForUpdates == true &&
                updateAvailable

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        when (key) {
            NAV_PINS -> NavDrawerPinsScreen.build(this, fragment, screen)
            SEERR_LOGIN -> buildSeerrLogin(fragment, screen)
            else -> buildRoot(fragment, screen)
        }
    }

    private fun buildRoot(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context
        val binder =
            AppPreferenceBinder<AppPreferences>(
                fragment = fragment,
                accent = accent,
                current = { prefs },
                update = { transform ->
                    pageScope.launch(ExceptionHandler()) { viewModel.preferenceDataStore.updateData { transform(it) } }
                },
                navigate = viewModel.navigationManager::navigateTo,
                onClickable = {},
                hidden = { pref ->
                    (kidSession && BuildConfig.KID_PROFILES_ENABLED && pref in kidHiddenPreferences) ||
                        (pref == AppPreference.SignInAuto && signInAlwaysOn)
                },
            )
        binder.special = { pref -> special(fragment, binder, pref) }
        binder.afterAdd = { pref, _, parent -> afterAdd(fragment, pref, parent) }
        if (option == PreferenceScreenOption.BASIC && UpdateChecker.ACTIVE) {
            // The release found by the update check, above the groups
            val install =
                SettingsClickPreference(ctx).apply {
                    key = INSTALL_ROW
                    accent = this@AppSettingsFragment.accent
                    title = getString(R.string.install_update)
                    onClick = { viewModel.navigationManager.navigateTo(Destination.UpdateApp()) }
                    onFocusChange = ::onUpdateRowFocus
                }
            screen.addPreference(install)
            fragment.onRefresh {
                install.isVisible = showUpdate
                install.summary = release?.version?.toString()
            }
        }
        binder.addGroups(screen, option.groups)
    }

    private val signInAlwaysOn: Boolean
        get() = BuildConfig.KID_PROFILES_ENABLED && viewModel.multiProfile.value

    /** Rows added right after a definition's row (the kid-protection switch-off, the always-on sign-in) */
    private fun afterAdd(
        fragment: SettingsListFragment,
        pref: AppPreference<AppPreferences, *>,
        parent: androidx.preference.PreferenceGroup,
    ) {
        val ctx = fragment.preferenceManager.context
        when (pref) {
            AppPreference.ProtectProfilePreference -> {
                val row =
                    SettingsClickPreference(ctx).apply {
                        key = "turnOffProtection"
                        accent = this@AppSettingsFragment.accent
                        title = getString(R.string.kid_turn_off_protection)
                        summary = getString(R.string.kid_turn_off_protection_summary)
                        onClick = ::onTurnOffProtection
                    }
                parent.addPreference(row)
                fragment.onRefresh { row.isVisible = viewModel.canTurnOffProtection.value }
            }

            AppPreference.SignInAuto -> {
                // Plan 4.5: with more than one profile the profile screen always shows
                val row =
                    SettingsClickPreference(ctx).apply {
                        key = "signInAlwaysOn"
                        accent = this@AppSettingsFragment.accent
                        title = getString(pref.title)
                        summary = getString(R.string.kid_sign_in_auto_always_on)
                    }
                parent.addPreference(row)
                fragment.onRefresh { row.isVisible = signInAlwaysOn }
            }

            else -> {}
        }
    }

    /** The rows the old PreferencesContent handled itself; null uses the generic row */
    private fun special(
        fragment: SettingsListFragment,
        binder: AppPreferenceBinder<AppPreferences>,
        pref: AppPreference<AppPreferences, *>,
    ): Preference? {
        val ctx = fragment.preferenceManager.context
        return when (pref) {
            AppPreference.InstalledVersion -> {
                SettingsClickPreference(ctx).apply {
                    title = getString(R.string.installed_version)
                    summary = installedVersion.toString()
                    onClick = ::showVersionDialog
                    onLongClick = { viewModel.navigationManager.navigateTo(Destination.Debug) }
                }
            }

            AppPreference.Update -> {
                SettingsClickPreference(ctx).also { row ->
                    row.onClick = {
                        if (release != null && updateAvailable) {
                            viewModel.navigationManager.navigateTo(Destination.UpdateApp())
                        } else {
                            updateViewModel.init()
                        }
                    }
                    row.onLongClick = { viewModel.navigationManager.navigateTo(Destination.UpdateApp()) }
                    row.onFocusChange = ::onUpdateRowFocus
                    fragment.onRefresh {
                        row.title =
                            getString(
                                when {
                                    release != null && updateAvailable -> R.string.install_update
                                    prefs?.autoCheckForUpdates == false && release == null -> R.string.check_for_updates
                                    else -> R.string.no_update_available
                                },
                            )
                        row.summary = if (updateAvailable) release?.version?.toString() else null
                    }
                }
            }

            AppPreference.CoachMarkTour -> {
                SettingsClickPreference(ctx).apply {
                    title = getString(pref.title)
                    summary = pref.summary(ctx, null)
                    onClick = {
                        coachMarkTour.start()
                        viewModel.navigationManager.goToHome()
                    }
                }
            }

            AppPreference.ClearImageCache -> {
                SettingsClickPreference(ctx).also { row ->
                    row.title = getString(pref.title)
                    row.onClick = {
                        SingletonImageLoader.get(requireContext()).let {
                            it.memoryCache?.clear()
                            it.diskCache?.clear()
                        }
                        updateCacheUsage()
                        refreshScreens()
                    }
                    fragment.onRefresh { row.summary = cacheSummary }
                }
            }

            AppPreference.UserPinnedNavDrawerItems -> {
                SettingsClickPreference(ctx).apply {
                    title = getString(pref.title)
                    summary = pref.summary(ctx, null)
                    onClick = { openScreen(NAV_PINS) }
                }
            }

            AppPreference.SendAppLogs -> {
                SettingsClickPreference(ctx).apply {
                    title = getString(pref.title)
                    summary = pref.summary(ctx, null)
                    onClick = { viewModel.sendAppLogs() }
                }
            }

            AppPreference.ProtectProfilePreference -> {
                protectionRow(fragment, pref)
            }

            AppPreference.RequestNotifications -> {
                SettingsSwitchPreference(ctx).also { row ->
                    row.title = getString(R.string.request_notifications)
                    row.summary = REQUEST_NOTIFICATIONS_SUMMARY
                    row.setOnPreferenceChangeListener { _, newValue ->
                        requestStatus.setNotificationsEnabled(newValue as Boolean)
                        true
                    }
                    fragment.onRefresh { row.isChecked = requestStatus.state.value.notifications.enabled }
                }
            }

            AppPreference.SeerrIntegration -> {
                SettingsClickPreference(ctx).also { row ->
                    row.title = getString(pref.title)
                    row.onClick = ::onSeerrClick
                    fragment.onRefresh {
                        row.summary =
                            getString(
                                when (viewModel.seerrConnection.value) {
                                    is SeerrConnectionStatus.Error -> R.string.voice_error_server
                                    SeerrConnectionStatus.NotConfigured -> R.string.requests_not_connected
                                    is SeerrConnectionStatus.Expired -> R.string.pending
                                    is SeerrConnectionStatus.Success -> R.string.enabled
                                },
                            )
                    }
                }
            }

            AppPreference.QuickConnect -> {
                SettingsClickPreference(ctx).apply {
                    title = getString(pref.title)
                    summary = pref.summary(ctx, null)
                    onClick = {
                        if (viewModel.currentUser.value != null) {
                            viewModel.resetQuickConnectStatus()
                            openPanel(QuickConnectPage())
                        }
                    }
                }
            }

            AppPreference.ExternalPlayerApp -> {
                externalPlayerRow(fragment, binder, pref)
            }

            AppPreference.UserInterfaceLanguage -> {
                languageRow(fragment)
            }

            ExperimentalPreference.Enable -> {
                experimentalRow(fragment, binder, pref as AppSwitchPreference<AppPreferences>)
            }

            AppPreference.MpvConfFile -> {
                mpvConfRow(fragment, pref)
            }

            else -> {
                null
            }
        }
    }

    /** None / Require PIN / Require login, with the kid-profile rules (plan 4.2) and the account PIN (contract 11.2) */
    private fun protectionRow(
        fragment: SettingsListFragment,
        pref: AppPreference<AppPreferences, *>,
    ): Preference {
        val ctx = fragment.preferenceManager.context
        val labels = listOf(R.string.none, R.string.require_pin_code, R.string.require_login).map(ctx::getString)
        return SettingsListPreference(ctx).also { row ->
            row.title = getString(pref.title)
            row.dialogTitle = row.title
            row.entries = Array<CharSequence>(labels.size) { labels[it] }
            row.entryValues = arrayOf<CharSequence>("0", "1", "2")
            fragment.onRefresh {
                val user = viewModel.currentUser.value
                val index =
                    when {
                        user?.requireLogin == true -> 2
                        user?.hasPin == true -> 1
                        else -> 0
                    }
                row.value = index.toString()
                row.summary = labels[index]
            }
            row.setOnPreferenceChangeListener { _, newValue ->
                val user = viewModel.currentUser.value ?: return@setOnPreferenceChangeListener false
                val choice = (newValue as String).toInt()
                if (BuildConfig.KID_PROFILES_ENABLED && viewModel.deviceProtected.value && user.hasPin && choice != 1) {
                    // Plan 4.2: PIN removal is blocked on a protected device
                    toast(getString(R.string.kid_pin_removal_blocked), long = true)
                    return@setOnPreferenceChangeListener false
                }
                when (choice) {
                    0 -> {
                        viewModel.removeLoginAndPin(user)
                    }

                    1 -> {
                        // Contract 11.2: the account's PIN first, so the flow asks for the PIN that is current
                        viewModel.refreshAccountPin { showSetPinFlow() }
                    }

                    else -> {
                        viewModel.setRequireLogin(user)
                    }
                }
                // The row shows the saved state when the profile changes
                false
            }
        }
    }

    private fun showSetPinFlow() {
        if (view == null) return
        val user = viewModel.currentUser.value ?: return
        SettingsSetPinFlow(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            currentPin = user.pin,
            onAddPin = {
                // Saved in the background, the account first; a failure says so and changes nothing
                viewModel.setPin(user, it)
                true
            },
            onRemovePin = { viewModel.setPin(user, null) },
            onDismiss = {},
            allowRemove = !(BuildConfig.KID_PROFILES_ENABLED && viewModel.deviceProtected.value),
            checkCurrentPin = { viewModel.checkOwnPin(user, it) },
        ).show()
    }

    /** "Turn off kid protection on this TV": the adult's PIN, checked by the gate (review round 5) */
    private fun onTurnOffProtection() {
        val user = viewModel.currentUser.value ?: return
        if (!user.hasPin) {
            toast(getString(R.string.kid_protection_needs_pin), long = true)
            return
        }
        viewModel.refreshAccountPin { showTurnOffProtectionPin() }
    }

    private fun showTurnOffProtectionPin() {
        if (view == null) return
        val user = viewModel.currentUser.value ?: return
        val ctx = requireContext()
        var dialog: SettingsPinDialog? = null
        var wrong = false
        var lockedUntil = 0L
        val pinDialog =
            SettingsPinDialog(
                ctx,
                viewLifecycleOwner.lifecycleScope,
                onSubmit = { pin ->
                    wrong = false
                    viewModel
                        .turnOffKidProtection(
                            user,
                            pin,
                            onError = { dialog?.message = ctx.getString(R.string.kids_error_generic) },
                        ) { result, turnedOff ->
                            when {
                                turnedOff -> {
                                    dialog?.dismiss()
                                    toast(ctx.getString(R.string.kid_protection_turned_off), long = true)
                                }

                                result is PinCheckResult.Wrong -> {
                                    wrong = true
                                    dialog?.message = ctx.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                                }

                                result is PinCheckResult.LockedOut -> {
                                    wrong = true
                                    val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                                    dialog?.message = ctx.getString(R.string.kid_pin_locked_out, seconds)
                                    lockedUntil = result.retryAtMillis
                                    dialog?.lockedUntilMillis = lockedUntil
                                }

                                else -> {
                                    dialog?.dismiss()
                                    toast(ctx.getString(R.string.kid_protection_not_turned_off), long = true)
                                }
                            }
                        }.join()
                    if (wrong) SettingsPinOutcome.WRONG else SettingsPinOutcome.OTHER
                },
                // Dismissed (or Settings closed with it up): a PIN still being checked turns nothing off
                onDismiss = { viewModel.cancelTurnOffKidProtection() },
            ).apply {
                pinTitle = ctx.getString(R.string.kid_turn_off_protection)
                prompt = pinPromptFor(ctx, user.shownName)
                length = PinRules.entryLength(user.pin)
            }
        dialog = pinDialog
        track(pinDialog).show()
    }

    private fun externalPlayerRow(
        fragment: SettingsListFragment,
        binder: AppPreferenceBinder<AppPreferences>,
        pref: AppPreference<AppPreferences, *>,
    ): Preference {
        val ctx = fragment.preferenceManager.context

        @Suppress("UNCHECKED_CAST")
        val stringPref = pref as AppPreference<AppPreferences, String>
        return SettingsListPreference(ctx).also { row ->
            row.title = getString(pref.title)
            row.dialogTitle = row.title
            row.setOnPreferenceChangeListener { _, newValue ->
                val players = viewModel.externalPlayers.value
                val identifier = players.getOrNull((newValue as String).toInt())?.identifier ?: ""
                binder.save(stringPref, identifier)
            }
            fragment.onRefresh {
                val players = viewModel.externalPlayers.value
                val iconSize = ctx.dp(40)
                // Each app with its icon, 40dp wide, as the old choice dialog showed them
                row.entries =
                    players
                        .map<_, CharSequence> { player ->
                            val icon = player.icon
                            if (icon == null) {
                                player.name
                            } else {
                                SpannableStringBuilder("  ${player.name}").apply {
                                    val drawable = BitmapDrawable(ctx.resources, icon)
                                    val height = (iconSize * icon.height.toFloat() / icon.width.coerceAtLeast(1)).toInt()
                                    drawable.setBounds(0, 0, iconSize, height)
                                    setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                        }.toTypedArray()
                row.entryValues = Array<CharSequence>(players.size) { it.toString() }
                val value = prefs?.let { stringPref.getter(it) }
                val index = players.indexOfFirst { it.identifier == value }.takeIf { it >= 0 } ?: 0
                if (players.isNotEmpty()) row.value = index.toString()
                row.summary = players.getOrNull(index)?.name
            }
        }
    }

    /** The interface language: the app's locales by their own name, with the English name below */
    private fun languageRow(fragment: SettingsListFragment): Preference {
        val ctx = fragment.preferenceManager.context
        return SettingsListPreference(ctx).also { row ->
            row.title = getString(R.string.user_interface_language)
            row.dialogTitle = row.title
            row.setOnPreferenceChangeListener { _, newValue ->
                localeViewModel.changeLocale(Locale.forLanguageTag(newValue as String))
                false
            }
            fragment.onRefresh {
                val state = localeViewModel.state.value
                row.entries =
                    state.availableLocales
                        .map { AppPreferenceBinder.withSubtitle(it.getDisplayName(it), it.getDisplayName(Locale.ENGLISH)) }
                        .toTypedArray()
                row.entryValues = Array<CharSequence>(state.availableLocales.size) { state.availableLocales[it].toLanguageTag() }
                if (state.availableLocales.isNotEmpty()) row.value = state.userLocale.toLanguageTag()
                val locale =
                    viewModel.currentUser.value
                        ?.uiLanguage
                        ?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
                row.summary = locale.getDisplayName(locale)
            }
        }
    }

    /** Turning experimental features on asks first */
    private fun experimentalRow(
        fragment: SettingsListFragment,
        binder: AppPreferenceBinder<AppPreferences>,
        pref: AppSwitchPreference<AppPreferences>,
    ): Preference =
        SettingsSwitchPreference(fragment.preferenceManager.context).also { row ->
            row.title = getString(pref.title)
            fragment.onRefresh {
                prefs?.let {
                    row.isChecked = pref.getter(it)
                    row.summary = pref.summary(fragment.preferenceManager.context, row.isChecked)
                }
            }
            row.setOnPreferenceChangeListener { _, newValue ->
                if (newValue == false) {
                    binder.save(pref, false)
                } else {
                    track(
                        showConfirmDialog(
                            requireContext(),
                            accent,
                            getString(R.string.confirm_enable_experimental_title),
                            getString(R.string.confirm_enable_experimental_body),
                            onConfirm = { binder.save(pref, true) },
                        ),
                    )
                    false
                }
            }
        }

    /** mpv.conf in the app's files, edited as text (10 lines, asks before discarding changes) */
    private fun mpvConfRow(
        fragment: SettingsListFragment,
        pref: AppPreference<AppPreferences, *>,
    ): Preference {
        val ctx = fragment.preferenceManager.context
        val file = File(ctx.filesDir, "mpv.conf")
        return SettingsEditTextPreference(ctx).apply {
            title = getString(pref.title)
            dialogTitle = title
            maxLines = 10
            confirmDiscard = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            beforeOpen = { text = if (file.exists()) file.readText() else "" }
            setOnPreferenceChangeListener { _, newValue ->
                file.writeText(newValue as String)
                true
            }
        }
    }

    private fun onSeerrClick() {
        val ctx = requireContext()
        when (val conn = viewModel.seerrConnection.value) {
            is SeerrConnectionStatus.Error -> {
                val body = "${conn.server.url}\n\n${ctx.getString(R.string.voice_error_server)}: ${conn.ex.localizedMessage}"
                track(
                    showConfirmDialog(ctx, accent, getString(R.string.remove_seerr_server), body, bodyColor = errorColor()) {
                        seerrViewModel.removeServer()
                    },
                )
            }

            SeerrConnectionStatus.NotConfigured -> {
                openScreen(SEERR_LOGIN)
            }

            is SeerrConnectionStatus.Expired -> {
                track(
                    showConfirmDialog(ctx, accent, getString(R.string.remove_seerr_server), conn.current.server.url) {
                        seerrViewModel.removeServer()
                    },
                )
            }

            is SeerrConnectionStatus.Success -> {
                track(
                    showConfirmDialog(ctx, accent, getString(R.string.remove_seerr_server), conn.current.server.url) {
                        seerrViewModel.removeServer()
                    },
                )
            }
        }
    }

    private fun errorColor(): Int = com.github.damontecres.wholphin.leanback.theme.AppTheme.palette.error

    /** The Seerr sign-in choice (the old `ChooseSeerrLoginType`); Quick Connect is never offered here */
    private fun buildSeerrLogin(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context
        listOf(
            R.string.api_key to SeerrServerPage.METHOD_API_KEY,
            R.string.seerr_jellyfin_user to SeerrServerPage.METHOD_JELLYFIN,
            R.string.seerr_local_user to SeerrServerPage.METHOD_LOCAL,
        ).forEach { (title, method) ->
            screen.addPreference(
                SettingsClickPreference(ctx).apply {
                    key = "seerr.$method"
                    accent = this@AppSettingsFragment.accent
                    this.title = getString(title)
                    onClick = {
                        seerrViewModel.resetStatus()
                        openPanel(SeerrServerPage.newInstance(method))
                    }
                },
            )
        }
    }

    /** Release notes of the installed version (the old version dialog) */
    private fun showVersionDialog() {
        viewModel.fetchReleaseNotes()
        val ctx = requireContext()
        val dialog = SettingsDialog(ctx, widthDp = 640)
        val text = scrollingText(ctx, "")
        SettingsMarkdown.styleNotes(text)
        text.maxHeight = (resources.displayMetrics.heightPixels * .7f).toInt()
        dialog.column.addView(text)
        track(dialog).show()
        text.requestFocus()
        val job =
            launchWhileStarted {
                viewModel.releaseNotes.collect { state ->
                    text.text =
                        when (state) {
                            is DataLoadingState.Error -> "Error: ${state.message ?: state.exception?.localizedMessage}"
                            DataLoadingState.Pending, DataLoadingState.Loading -> ctx.getString(R.string.loading)
                            is DataLoadingState.Success -> SettingsMarkdown.render(ctx, state.data.content)
                        }
                }
            }
        dialog.setOnDismissListener { job.cancel() }
    }

    private fun track(dialog: Dialog): Dialog {
        dialogs += dialog
        return dialog
    }

    private fun track(dialog: SettingsPinDialog): SettingsPinDialog {
        dialogs += dialog
        return dialog
    }

    private fun onUpdateRowFocus(focused: Boolean) {
        updateRowsFocused = (updateRowsFocused + if (focused) 1 else -1).coerceAtLeast(0)
        renderNotes()
    }

    /** The release notes beside a focused "Install update" row, styled like the notes column on the update page */
    private fun renderNotes() {
        val pane = notesPane ?: return
        val notes = release?.takeIf { updateAvailable && updateRowsFocused > 0 }
        if (notes != null) {
            notesText?.text = SettingsMarkdown.render(requireContext(), notes.content)
            pane.animate().cancel()
            pane.visibility = View.VISIBLE
            pane
                .animate()
                .alpha(1f)
                .setDuration(CROSSFADE_MS)
                .start()
        } else if (pane.visibility == View.VISIBLE) {
            pane
                .animate()
                .alpha(0f)
                .setDuration(CROSSFADE_MS)
                .withEndAction { pane.visibility = View.GONE }
                .start()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val card =
            AppCompatTextView(ctx).apply {
                SettingsMarkdown.styleNotes(this)
                background = SettingsLook.surface(ctx, SettingsLook.tonalSurface(1f, accent), radiusDp = 16f)
                val pad = ctx.dp(16)
                setPadding(pad, pad, pad, pad)
            }
        notesText = card
        notesPane =
            FrameLayout(ctx)
                .apply {
                    val pad = ctx.dp(24)
                    setPadding(pad, pad, pad, pad)
                    visibility = View.GONE
                    alpha = 0f
                    addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                }.also {
                    sidePane.addView(
                        it,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                    )
                }
        updateCacheUsage()

        viewModel.preferenceDataStore.data.collectWhileStarted { latest ->
            prefs = latest
            onPreferences(latest)
            refreshScreens()
        }
        viewModel.currentUser.collectWhileStarted {
            viewModel.refreshKidProtection()
            refreshScreens()
        }
        kidSessionGate.isKidSession.collectWhileStarted {
            kidSession = it
            refreshScreens()
        }
        viewModel.multiProfile.collectWhileStarted { refreshScreens() }
        viewModel.deviceProtected.collectWhileStarted { refreshScreens() }
        viewModel.canTurnOffProtection.collectWhileStarted { refreshScreens() }
        viewModel.seerrConnection.collectWhileStarted { refreshScreens() }
        viewModel.externalPlayers.collectWhileStarted { refreshScreens() }
        localeViewModel.state.collectWhileStarted { refreshScreens() }
        requestStatus.state.collectWhileStarted { refreshScreens() }
        updateViewModel.state.collectWhileStarted {
            val before = topList?.selectedKey
            refreshScreens()
            renderNotes()
            // The update row appeared: take focus only if the user hasn't moved from the first row
            if (showUpdate && before == firstRowKey) {
                // After the list has added the row (Preference visibility changes apply on the next frame)
                view.post { topList?.selectPreference(INSTALL_ROW) }
            }
        }
        viewModel.quickConnectStatus.collectWhileStarted { status ->
            when (status) {
                LoadingState.Success -> {
                    toast(getString(R.string.quick_connect_success))
                    (childFragmentManager.findFragmentById(R.id.lb_settings_panel) as? QuickConnectPage)?.close()
                }

                is LoadingState.Error -> {
                    toast(status.message ?: "Authorization failed")
                }

                else -> {}
            }
        }
        seerrViewModel.serverConnectionStatus.collectWhileStarted { status ->
            (childFragmentManager.findFragmentById(R.id.lb_settings_panel) as? SeerrServerPage)?.onStatus(status)
            if (status == LoadingState.Success && childFragmentManager.findFragmentById(R.id.lb_settings_panel) is SeerrServerPage) {
                toast(getString(R.string.seerr_server_added))
                // Back to the settings list: the form and the sign-in choice close
                childFragmentManager.popBackStack()
                childFragmentManager.popBackStack()
            }
        }
    }

    /** The first regular row of the groups (the update row takes focus only if the user is still there) */
    private val firstRowKey: String = "g0.p0"

    /** Side effects of a new preference value (the old LaunchedEffects) */
    private fun onPreferences(latest: AppPreferences) {
        val backend = latest.playbackPreferences.playerBackend
        if (backend != lastBackend) {
            lastBackend = backend
            if (backend == PlayerBackend.MPV) checkMpv()
        }
        val updateKey = latest.updateUrl to latest.autoCheckForUpdates
        if (updateKey != lastUpdateKey) {
            lastUpdateKey = updateKey
            if (UpdateChecker.ACTIVE && latest.autoCheckForUpdates) updateViewModel.init()
        }
    }

    private fun checkMpv() {
        Timber.d("Checking for libmpv")
        try {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
        } catch (ex: UnsatisfiedLinkError) {
            Timber.w(ex, "Could not load libmpv")
            toast("MPV is not supported on this device")
            pageScope.launch(ExceptionHandler()) {
                viewModel.preferenceDataStore.updateData {
                    it.updatePlaybackPreferences { playerBackend = PlayerBackend.EXO_PLAYER }
                }
            }
        }
    }

    private fun updateCacheUsage() {
        val loader = SingletonImageLoader.get(requireContext())
        val disk = (loader.diskCache?.size ?: 0L) / AppPreference.MEGA_BIT
        val used = (loader.memoryCache?.size ?: 0L) / AppPreference.MEGA_BIT
        val max = (loader.memoryCache?.maxSize ?: 0L) / AppPreference.MEGA_BIT
        cacheSummary = "Disk: ${disk}mb, Memory: ${used}mb/${max}mb"
    }

    override fun onDestroyView() {
        dialogs.forEach { if (it.isShowing) it.dismiss() }
        dialogs.clear()
        notesPane = null
        notesText = null
        updateRowsFocused = 0
        super.onDestroyView()
    }

    companion object {
        const val ROOT = "root"
        const val NAV_PINS = "navpins"
        const val SEERR_LOGIN = "seerr.login"
        private const val INSTALL_ROW = "installUpdate"
        private const val CROSSFADE_MS = 300L

        /** The old literal summary of the request notifications switch */
        private const val REQUEST_NOTIFICATIONS_SUMMARY = "Show request updates while browsing"
    }
}

/** The groups of each settings screen */
internal val PreferenceScreenOption.groups
    get() =
        when (this) {
            PreferenceScreenOption.BASIC -> basicPreferences
            PreferenceScreenOption.ADVANCED -> advancedPreferences
            PreferenceScreenOption.EXO_PLAYER -> ExoPlayerPreferences
            PreferenceScreenOption.MPV -> MpvPreferences
            PreferenceScreenOption.SKIP_SEGMENTS -> SkipSegmentPreferences
            PreferenceScreenOption.EXPERIMENTAL -> experimentalPreferences
        }

/** The panel title of each settings screen */
internal val PreferenceScreenOption.titleRes: Int
    get() =
        when (this) {
            PreferenceScreenOption.BASIC -> R.string.settings
            PreferenceScreenOption.ADVANCED -> R.string.advanced_settings
            PreferenceScreenOption.EXO_PLAYER -> R.string.exoplayer_options
            PreferenceScreenOption.MPV -> R.string.mpv_options
            PreferenceScreenOption.SKIP_SEGMENTS -> R.string.skip_behavior
            PreferenceScreenOption.EXPERIMENTAL -> R.string.experimental_settings
        }
