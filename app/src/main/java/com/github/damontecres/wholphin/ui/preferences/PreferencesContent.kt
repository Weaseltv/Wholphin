package com.github.damontecres.wholphin.ui.preferences

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import coil3.SingletonImageLoader
import coil3.imageLoader
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.kids.PinCheckResult
import com.github.damontecres.wholphin.kids.PinRules
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.preferences.ExoPlayerPreferences
import com.github.damontecres.wholphin.preferences.ExperimentalPreference
import com.github.damontecres.wholphin.preferences.MpvPreferences
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.ScreensaverPreference
import com.github.damontecres.wholphin.preferences.SkipSegmentPreferences
import com.github.damontecres.wholphin.preferences.advancedPreferences
import com.github.damontecres.wholphin.preferences.basicPreferences
import com.github.damontecres.wholphin.preferences.experimentalPreferences
import com.github.damontecres.wholphin.preferences.kidHiddenPreferences
import com.github.damontecres.wholphin.preferences.lazyListWrapScrolling
import com.github.damontecres.wholphin.preferences.screensaverPreferences
import com.github.damontecres.wholphin.preferences.updatePlaybackPreferences
import com.github.damontecres.wholphin.services.Release
import com.github.damontecres.wholphin.services.SeerrConnectionStatus
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.ui.components.ConfirmDialog
import com.github.damontecres.wholphin.ui.components.DialogListEdge
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.kids.KidProfilesViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playOnClickSound
import com.github.damontecres.wholphin.ui.playSoundOnFocus
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings
import com.github.damontecres.wholphin.ui.setup.PinEntryDialog
import com.github.damontecres.wholphin.ui.setup.ReleaseNotes
import com.github.damontecres.wholphin.ui.setup.UpdateViewModel
import com.github.damontecres.wholphin.ui.setup.pinPromptFor
import com.github.damontecres.wholphin.ui.setup.seerr.AddSeerServerDialog
import com.github.damontecres.wholphin.ui.setup.seerr.SwitchSeerrViewModel
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.ui.theme.LocalNeonAccent
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonRule
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

@Composable
fun PreferencesContent(
    initialPreferences: AppPreferences,
    preferenceScreenOption: PreferenceScreenOption,
    modifier: Modifier = Modifier,
    viewModel: PreferencesViewModel = hiltViewModel(),
    updateVM: UpdateViewModel = hiltViewModel(),
    seerrVm: SwitchSeerrViewModel = hiltViewModel(),
    kidViewModel: KidProfilesViewModel = hiltViewModel(),
    onFocus: (Int, Int) -> Unit = { _, _ -> },
    onUpdateNotesChange: (Release?) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val firstFocusRequester = remember { FocusRequester() }
    val lastFocusRequester = remember { FocusRequester() }

    var focusedIndex by rememberSaveable { mutableStateOf(Pair(0, 0)) }
    val state = rememberLazyListState()
    var preferences by remember { mutableStateOf(initialPreferences) }
    val currentUser by viewModel.currentUser.collectAsState()
    val kidSession by kidViewModel.isKidSession.collectAsState()
    var showPinFlow by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    val players by viewModel.externalPlayers.collectAsState()

    var cacheUsage by remember { mutableStateOf(CacheUsage(0, 0, 0)) }
    val seerrConnection by viewModel.seerrConnection.collectAsState()
    var seerrDialogMode by remember { mutableStateOf<SeerrDialogMode>(SeerrDialogMode.None) }
    var showQuickConnectDialog by remember { mutableStateOf(false) }
    var showLocaleChoiceDialog by remember { mutableStateOf(false) }
    // Kid profiles
    val multiProfile by viewModel.multiProfile.collectAsState()
    val deviceProtected by viewModel.deviceProtected.collectAsState()
    val canTurnOffProtection by viewModel.canTurnOffProtection.collectAsState()
    var showTurnOffProtectionPin by remember { mutableStateOf(false) }
    var turnOffProtectionMessage by remember { mutableStateOf<String?>(null) }
    var turnOffProtectionLockedUntil by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        viewModel.preferenceDataStore.data.collect {
            preferences = it
        }
    }
    LaunchedEffect(currentUser) {
        viewModel.refreshKidProtection()
    }
    var updateCache by remember { mutableStateOf(false) }
    LaunchedEffect(updateCache) {
        val imageUsedMemory = context.imageLoader.memoryCache?.size ?: 0L
        val imageMaxMemory = context.imageLoader.memoryCache?.maxSize ?: 0L
        val imageDisk = context.imageLoader.diskCache?.size ?: 0L
        cacheUsage = CacheUsage(imageUsedMemory, imageMaxMemory, imageDisk)
        updateCache = false
    }

    val updateState by updateVM.state.collectAsState()
    val release = updateState.release
    LaunchedEffect(preferences.updateUrl, preferences.autoCheckForUpdates) {
        if (UpdateChecker.ACTIVE && preferences.autoCheckForUpdates) {
            updateVM.init()
        }
    }

    val movementSounds = false
    val installedVersion = updateVM.currentVersion
    val updateAvailable =
        remember(updateState.release) {
            updateState.release?.version?.isGreaterThan(installedVersion) ?: false
        }

    // WeaselFin: while an "Install update" row has focus, PreferencesPage shows the release
    // notes in the empty space beside the list, the same text as the update page.
    var topUpdateFocused by remember { mutableStateOf(false) }
    var listUpdateFocused by remember { mutableStateOf(false) }
    LaunchedEffect(topUpdateFocused, listUpdateFocused, release, updateAvailable) {
        onUpdateNotesChange(release?.takeIf { updateAvailable && (topUpdateFocused || listUpdateFocused) })
    }

    val prefList =
        when (preferenceScreenOption) {
            PreferenceScreenOption.BASIC -> basicPreferences
            PreferenceScreenOption.ADVANCED -> advancedPreferences
            PreferenceScreenOption.EXO_PLAYER -> ExoPlayerPreferences
            PreferenceScreenOption.MPV -> MpvPreferences
            PreferenceScreenOption.SCREENSAVER -> screensaverPreferences
            PreferenceScreenOption.SKIP_SEGMENTS -> SkipSegmentPreferences
            PreferenceScreenOption.EXPERIMENTAL -> experimentalPreferences
        }
    val screenTitle =
        when (preferenceScreenOption) {
            PreferenceScreenOption.BASIC -> R.string.settings
            PreferenceScreenOption.ADVANCED -> R.string.advanced_settings
            PreferenceScreenOption.EXO_PLAYER -> R.string.exoplayer_options
            PreferenceScreenOption.MPV -> R.string.mpv_options
            PreferenceScreenOption.SCREENSAVER -> R.string.screensaver_settings
            PreferenceScreenOption.SKIP_SEGMENTS -> R.string.skip_behavior
            PreferenceScreenOption.EXPERIMENTAL -> R.string.experimental_settings
        }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Forces the animated to trigger
        visible = true
    }

    LaunchedEffect(preferences.playbackPreferences.playerBackend) {
        if (preferences.playbackPreferences.playerBackend == PlayerBackend.MPV) {
            Timber.d("Checking for libmpv")
            try {
                System.loadLibrary("mpv")
                System.loadLibrary("player")
            } catch (ex: UnsatisfiedLinkError) {
                Timber.w(ex, "Could not load libmpv")
                showToast(context, "MPV is not supported on this device")
                viewModel.preferenceDataStore.updateData {
                    it.updatePlaybackPreferences { playerBackend = PlayerBackend.EXO_PLAYER }
                }
            }
        }
    }

    val showUpdate =
        UpdateChecker.ACTIVE &&
            preferenceScreenOption == PreferenceScreenOption.BASIC &&
            preferences.autoCheckForUpdates &&
            updateAvailable
    val neon = isWeaselTv()
    val panelAccent = LocalNeonAccent.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
        modifier = modifier,
    ) {
        LaunchedEffect(Unit) {
            focusRequester.tryRequestFocus()
        }
        Column(
            modifier =
                Modifier
                    .background(if (neon) NeonBoard.Stage else MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                    .drawWithContent {
                        drawContent()
                        if (neon) {
                            drawRect(
                                color = panelAccent,
                                size = Size(5.dp.toPx(), size.height),
                            )
                        }
                    },
        ) {
            PreferencePanelHeader(stringResource(screenTitle))
            LazyColumn(
                state = state,
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(horizontal = if (neon) 32.dp else 16.dp, vertical = DialogListEdge),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showUpdate) {
                    item {
                        val updateFocusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            if (focusedIndex.first == 0 && focusedIndex.second == 0) {
                                // Only re-focus if the user hasn't moved
                                updateFocusRequester.tryRequestFocus()
                            }
                        }
                        ClickPreference(
                            title = stringResource(R.string.install_update),
                            onClick = {
                                if (movementSounds) playOnClickSound(context)
                                viewModel.navigationManager.navigateTo(Destination.UpdateApp())
                            },
                            summary = release?.version?.toString(),
                            modifier =
                                Modifier
                                    .focusRequester(updateFocusRequester)
                                    .focusRequester(firstFocusRequester)
                                    .playSoundOnFocus(movementSounds)
                                    .onFocusChanged { topUpdateFocused = it.hasFocus },
                        )
                    }
                }
                prefList.forEachIndexed { groupIndex, group ->
                    item {
                        Text(
                            text = stringResource(group.title),
                            style =
                                if (neon) {
                                    MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 22.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Start,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = if (neon) 8.dp else 0.dp, vertical = if (neon) 12.dp else 0.dp)
                                    .padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    val groupPreferences =
                        group.preferences +
                            group.conditionalPreferences
                                .filter { it.condition.invoke(preferences) }
                                .map { it.preferences }
                                .flatten()
                                .filterNot { kidSession && BuildConfig.KID_PROFILES_ENABLED && it in kidHiddenPreferences }
                    groupPreferences.forEachIndexed { prefIndex, pref ->
                        pref as AppPreference<AppPreferences, Any>
                        val isFirst = groupIndex == 0 && prefIndex == 0 && !showUpdate
                        val isLast =
                            groupIndex == prefList.lastIndex && prefIndex == groupPreferences.lastIndex
                        item {
                            val interactionSource = remember { MutableInteractionSource() }
                            val focused = interactionSource.collectIsFocusedAsState().value
                            val focusModifier =
                                Modifier
                                    .ifElse(
                                        groupIndex == focusedIndex.first && prefIndex == focusedIndex.second,
                                        Modifier.focusRequester(focusRequester),
                                    ).lazyListWrapScrolling(
                                        state,
                                        focused,
                                        isFirst,
                                        isLast,
                                        firstFocusRequester,
                                        lastFocusRequester,
                                    )
                            LaunchedEffect(focused) {
                                if (focused) {
                                    focusedIndex = Pair(groupIndex, prefIndex)
                                    if (movementSounds) playOnClickSound(context)
                                    onFocus.invoke(groupIndex, prefIndex)
                                }
                            }
                            when (pref) {
                                AppPreference.InstalledVersion -> {
                                    ClickPreference(
                                        title = stringResource(R.string.installed_version),
                                        onClick = {
                                            showVersionDialog = true
                                        },
                                        onLongClick = {
                                            viewModel.navigationManager.navigateTo(Destination.Debug)
                                        },
                                        summary = installedVersion.toString(),
                                        interactionSource = interactionSource,
                                        modifier = focusModifier,
                                    )
                                }

                                AppPreference.Update -> {
                                    LaunchedEffect(focused) { listUpdateFocused = focused }
                                    DisposableEffect(Unit) { onDispose { listUpdateFocused = false } }
                                    ClickPreference(
                                        title =
                                            if (release != null && updateAvailable) {
                                                stringResource(R.string.install_update)
                                            } else if (!preferences.autoCheckForUpdates && release == null) {
                                                stringResource(R.string.check_for_updates)
                                            } else {
                                                stringResource(R.string.no_update_available)
                                            },
                                        onClick = {
                                            if (movementSounds) playOnClickSound(context)
                                            if (release != null && updateAvailable) {
                                                release?.let {
                                                    viewModel.navigationManager.navigateTo(
                                                        Destination.UpdateApp(),
                                                    )
                                                }
                                            } else {
                                                updateVM.init()
                                            }
                                        },
                                        onLongClick = {
                                            if (movementSounds) playOnClickSound(context)
                                            viewModel.navigationManager.navigateTo(Destination.UpdateApp())
                                        },
                                        summary =
                                            if (updateAvailable) {
                                                release?.version?.toString()
                                            } else {
                                                null
                                            },
                                        interactionSource = interactionSource,
                                        modifier = focusModifier,
                                    )
                                }

                                AppPreference.ClearImageCache -> {
                                    val summary =
                                        remember(cacheUsage) {
                                            cacheUsage.let {
                                                val diskMB =
                                                    it.imageDiskUsed / AppPreference.MEGA_BIT
                                                val memoryUsedMB =
                                                    it.imageMemoryUsed / AppPreference.MEGA_BIT
                                                val memoryMaxMB =
                                                    it.imageMemoryMax / AppPreference.MEGA_BIT
                                                "Disk: ${diskMB}mb, Memory: ${memoryUsedMB}mb/${memoryMaxMB}mb"
                                            }
                                        }
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            SingletonImageLoader.get(context).let {
                                                it.memoryCache?.clear()
                                                it.diskCache?.clear()
                                                updateCache = true
                                            }
                                        },
                                        modifier = focusModifier,
                                        summary = summary,
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                AppPreference.UserPinnedNavDrawerItems -> {
                                    NavDrawerPreference(
                                        title = stringResource(pref.title),
                                        summary = pref.summary(context, null),
                                        modifier = focusModifier,
                                        interactionSource = interactionSource,
                                    )
                                }

                                AppPreference.SendAppLogs -> {
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            viewModel.sendAppLogs()
                                        },
                                        modifier = focusModifier,
                                        summary = pref.summary(context, null),
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                SubtitleSettings.Reset -> {
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            viewModel.resetSubtitleSettings()
                                        },
                                        modifier = focusModifier,
                                        summary = pref.summary(context, null),
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                AppPreference.ProtectProfilePreference -> {
                                    Column {
                                        val summary =
                                            when {
                                                currentUser?.requireLogin == true -> R.string.require_login
                                                currentUser?.hasPin == true -> R.string.require_pin_code
                                                else -> R.string.none
                                            }
                                        ChoicePreference(
                                            title = stringResource(pref.title),
                                            summary = stringResource(summary),
                                            interactionSource = interactionSource,
                                            possibleValues =
                                                listOf(
                                                    stringResource(R.string.none),
                                                    stringResource(R.string.require_pin_code),
                                                    stringResource(R.string.require_login),
                                                ),
                                            selectedIndex =
                                                when {
                                                    currentUser?.requireLogin == true -> ProfileProtection.LOGIN
                                                    currentUser?.hasPin == true -> ProfileProtection.PIN
                                                    else -> ProfileProtection.NONE
                                                }.ordinal,
                                            onValueChange = {
                                                currentUser?.let { user ->
                                                    val choice = ProfileProtection.entries[it]
                                                    if (BuildConfig.KID_PROFILES_ENABLED &&
                                                        deviceProtected &&
                                                        user.hasPin &&
                                                        choice != ProfileProtection.PIN
                                                    ) {
                                                        // Plan 4.2: PIN removal is blocked on a protected device
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                context.getString(R.string.kid_pin_removal_blocked),
                                                                Toast.LENGTH_LONG,
                                                            ).show()
                                                        return@let
                                                    }
                                                    when (choice) {
                                                        ProfileProtection.NONE -> {
                                                            viewModel.removeLoginAndPin(user)
                                                        }

                                                        ProfileProtection.PIN -> {
                                                            showPinFlow = true
                                                        }

                                                        ProfileProtection.LOGIN -> {
                                                            viewModel.setRequireLogin(user)
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = focusModifier,
                                        )
                                        if (canTurnOffProtection) {
                                            ClickPreference(
                                                title = stringResource(R.string.kid_turn_off_protection),
                                                onClick = {
                                                    if (currentUser?.hasPin == true) {
                                                        turnOffProtectionMessage = null
                                                        showTurnOffProtectionPin = true
                                                    } else {
                                                        Toast
                                                            .makeText(
                                                                context,
                                                                context.getString(R.string.kid_protection_needs_pin),
                                                                Toast.LENGTH_LONG,
                                                            ).show()
                                                    }
                                                },
                                                summary = stringResource(R.string.kid_turn_off_protection_summary),
                                                onLongClick = {},
                                                interactionSource = remember { MutableInteractionSource() },
                                            )
                                        }
                                    }
                                }

                                AppPreference.SignInAuto -> {
                                    if (BuildConfig.KID_PROFILES_ENABLED && multiProfile) {
                                        // Plan 4.5: with more than one profile the profile screen always shows
                                        ClickPreference(
                                            title = stringResource(pref.title),
                                            onClick = {},
                                            modifier = focusModifier,
                                            summary = stringResource(R.string.kid_sign_in_auto_always_on),
                                            onLongClick = {},
                                            interactionSource = interactionSource,
                                        )
                                    } else {
                                        val value = AppPreference.SignInAuto.getter.invoke(preferences)
                                        ComposablePreference(
                                            preference = AppPreference.SignInAuto,
                                            value = value,
                                            onNavigate = viewModel.navigationManager::navigateTo,
                                            onValueChange = { newValue ->
                                                scope.launch(ExceptionHandler()) {
                                                    preferences =
                                                        viewModel.preferenceDataStore.updateData { prefs ->
                                                            AppPreference.SignInAuto.setter(prefs, newValue)
                                                        }
                                                }
                                            },
                                            interactionSource = interactionSource,
                                            modifier = focusModifier,
                                        )
                                    }
                                }

                                AppPreference.SeerrIntegration -> {
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            seerrDialogMode =
                                                when (val conn = seerrConnection) {
                                                    is SeerrConnectionStatus.Error -> {
                                                        SeerrDialogMode.Error(
                                                            conn.server.url,
                                                            conn.ex,
                                                        )
                                                    }

                                                    SeerrConnectionStatus.NotConfigured -> {
                                                        SeerrDialogMode.Add
                                                    }

                                                    is SeerrConnectionStatus.Success -> {
                                                        SeerrDialogMode.Remove(
                                                            conn.current.server.url,
                                                        )
                                                    }
                                                }
                                        },
                                        modifier = focusModifier,
                                        summary =
                                            when (seerrConnection) {
                                                is SeerrConnectionStatus.Error -> {
                                                    stringResource(R.string.voice_error_server)
                                                }

                                                SeerrConnectionStatus.NotConfigured -> {
                                                    stringResource(
                                                        R.string.add_server,
                                                    )
                                                }

                                                is SeerrConnectionStatus.Success -> {
                                                    stringResource(R.string.enabled)
                                                }
                                            },
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                AppPreference.QuickConnect -> {
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            if (currentUser != null) {
                                                viewModel.resetQuickConnectStatus()
                                                showQuickConnectDialog = true
                                            }
                                        },
                                        modifier = focusModifier,
                                        summary = pref.summary(context, null),
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                ScreensaverPreference.Start -> {
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            viewModel.screensaverService.start()
                                        },
                                        modifier = focusModifier,
                                        summary = pref.summary(context, null),
                                        onLongClick = {},
                                        interactionSource = interactionSource,
                                    )
                                }

                                AppPreference.ExternalPlayerApp -> {
                                    val value = pref.getter.invoke(preferences).toString()
                                    val selectedIndex =
                                        remember(value, players) {
                                            players.indexOfFirstOrNull { it.identifier == value }
                                        } ?: 0
                                    ChoicePreference(
                                        title = stringResource(pref.title),
                                        summary = players[selectedIndex].name,
                                        interactionSource = interactionSource,
                                        possibleValues = players,
                                        selectedIndex = selectedIndex,
                                        onValueChange = { index ->
                                            scope.launch(ExceptionHandler()) {
                                                val newValue =
                                                    players.getOrNull(index)?.identifier ?: ""
                                                preferences =
                                                    viewModel.preferenceDataStore.updateData { prefs ->
                                                        pref.setter.invoke(prefs, newValue)
                                                    }
                                            }
                                        },
                                        valueDisplay = { index, item ->
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (item.icon != null) {
                                                    Image(
                                                        bitmap = item.icon,
                                                        contentDescription = null,
                                                        modifier = Modifier.width(40.dp),
                                                    )
                                                }
                                                Text(item.name)
                                            }
                                        },
                                        modifier = focusModifier,
                                    )
                                }

                                AppPreference.UserInterfaceLanguage -> {
                                    val locale =
                                        remember(currentUser?.uiLanguage) {
                                            currentUser?.uiLanguage?.let { Locale.forLanguageTag(it) }
                                                ?: Locale.getDefault()
                                        }
                                    ClickPreference(
                                        title = stringResource(pref.title),
                                        onClick = {
                                            showLocaleChoiceDialog = true
                                        },
                                        modifier = focusModifier,
                                        summary = locale.getDisplayName(locale),
                                        onLongClick = null,
                                        interactionSource = interactionSource,
                                    )
                                }

                                ExperimentalPreference.Enable -> {
                                    var showConfirm by remember { mutableStateOf(false) }
                                    pref as AppSwitchPreference<AppPreferences>
                                    val value = pref.getter.invoke(preferences)
                                    SwitchPreference(
                                        title = stringResource(pref.title),
                                        value = value,
                                        onClick = {
                                            if (value) {
                                                scope.launch(ExceptionHandler()) {
                                                    preferences =
                                                        viewModel.preferenceDataStore.updateData { prefs ->
                                                            pref.setter.invoke(prefs, false)
                                                        }
                                                }
                                            } else {
                                                showConfirm = true
                                            }
                                        },
                                        summaryOn = pref.summaryOn?.let { stringResource(pref.summaryOn) },
                                        summaryOff = pref.summaryOff?.let { stringResource(pref.summaryOff) },
                                    )
                                    if (showConfirm) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.confirm_enable_experimental_title),
                                            body = stringResource(R.string.confirm_enable_experimental_body),
                                            onCancel = { showConfirm = false },
                                            onConfirm = {
                                                showConfirm = false
                                                scope.launch(ExceptionHandler()) {
                                                    preferences =
                                                        viewModel.preferenceDataStore.updateData { prefs ->
                                                            pref.setter.invoke(prefs, true)
                                                        }
                                                }
                                            },
                                        )
                                    }
                                }

                                else -> {
                                    val value = pref.getter.invoke(preferences)
                                    ComposablePreference(
                                        preference = pref,
                                        value = value,
                                        onNavigate = viewModel.navigationManager::navigateTo,
                                        onValueChange = { newValue ->
                                            val validation = pref.validate(preferences, newValue)
                                            when (validation) {
                                                is PreferenceValidation.Invalid -> {
                                                    // TODO?
                                                    Toast
                                                        .makeText(
                                                            context,
                                                            validation.message,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                }

                                                PreferenceValidation.Valid -> {
                                                    scope.launch(ExceptionHandler()) {
                                                        preferences =
                                                            viewModel.preferenceDataStore.updateData { prefs ->
                                                                pref.setter(prefs, newValue)
                                                            }
                                                    }
                                                }
                                            }
                                        },
                                        interactionSource = interactionSource,
                                        modifier = focusModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPinFlow && currentUser != null) {
            currentUser?.let { user ->
                SetPinFlow(
                    currentPin = user.pin,
                    onAddPin = {
                        viewModel.setPin(user, it)
                        showPinFlow = false
                    },
                    onRemovePin = {
                        viewModel.setPin(user, null)
                        showPinFlow = false
                    },
                    onDismissRequest = { showPinFlow = false },
                    allowRemove = !(BuildConfig.KID_PROFILES_ENABLED && deviceProtected),
                )
            }
        }
        if (showTurnOffProtectionPin) {
            // Leaving Settings with the prompt up is a dismissal too
            DisposableEffect(Unit) { onDispose { viewModel.cancelTurnOffKidProtection() } }
            currentUser?.let { user ->
                PinEntryDialog(
                    // Dismissed: a PIN still being checked turns nothing off (review round 5)
                    onDismissRequest = {
                        viewModel.cancelTurnOffKidProtection()
                        showTurnOffProtectionPin = false
                    },
                    // Its old "Login via server" button only closed the prompt; Back does that now
                    length = PinRules.entryLength(user.pin),
                    lockedUntilMillis = turnOffProtectionLockedUntil,
                    prompt = pinPromptFor(user.shownName),
                    // Latched until the check and the write are done; a failure leaves the prompt usable
                    onSubmit = { pin ->
                        viewModel
                            .turnOffKidProtection(
                                user,
                                pin,
                                onError = { turnOffProtectionMessage = context.getString(R.string.kids_error_generic) },
                            ) { result, turnedOff ->
                                when {
                                    turnedOff -> {
                                        showTurnOffProtectionPin = false
                                        Toast
                                            .makeText(context, context.getString(R.string.kid_protection_turned_off), Toast.LENGTH_LONG)
                                            .show()
                                    }

                                    result is PinCheckResult.Wrong -> {
                                        turnOffProtectionMessage =
                                            context.getString(R.string.kid_pin_wrong, result.attemptsBeforeLockout)
                                    }

                                    result is PinCheckResult.LockedOut -> {
                                        val seconds = ((result.retryAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                                        turnOffProtectionMessage = context.getString(R.string.kid_pin_locked_out, seconds)
                                        turnOffProtectionLockedUntil = result.retryAtMillis
                                    }

                                    else -> {
                                        showTurnOffProtectionPin = false
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.kid_protection_not_turned_off),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                    }
                                }
                            }.join()
                    },
                    title = stringResource(R.string.kid_turn_off_protection),
                    message = turnOffProtectionMessage,
                    serverAuthRemovesPin = false,
                )
            }
        }
        when (val mode = seerrDialogMode) {
            is SeerrDialogMode.Remove -> {
                ConfirmDialog(
                    title = stringResource(R.string.remove_seerr_server),
                    body = mode.serverUrl,
                    onCancel = { seerrDialogMode = SeerrDialogMode.None },
                    onConfirm = {
                        seerrVm.removeServer()
                        seerrDialogMode = SeerrDialogMode.None
                    },
                )
            }

            SeerrDialogMode.Add -> {
                val currentUser by seerrVm.currentUser.collectAsState(null)
                val status by seerrVm.serverConnectionStatus.collectAsState(LoadingState.Pending)
                val serverAddedMessage = stringResource(R.string.seerr_server_added)
                LaunchedEffect(status) {
                    if (status == LoadingState.Success) {
                        Toast.makeText(context, serverAddedMessage, Toast.LENGTH_SHORT).show()
                        seerrDialogMode = SeerrDialogMode.None
                    }
                }
                AddSeerServerDialog(
                    currentUsername = currentUser?.name,
                    status = status,
                    onSubmit = seerrVm::submitServer,
                    onResetStatus = seerrVm::resetStatus,
                    onDismissRequest = { seerrDialogMode = SeerrDialogMode.None },
                )
            }

            is SeerrDialogMode.Error -> {
                val errorStr = stringResource(R.string.voice_error_server)
                val body =
                    remember(mode) {
                        """
                        ${mode.serverUrl}

                        $errorStr: ${mode.ex.localizedMessage}
                        """.trimIndent()
                    }
                ConfirmDialog(
                    title = stringResource(R.string.remove_seerr_server),
                    body = body,
                    onCancel = { seerrDialogMode = SeerrDialogMode.None },
                    onConfirm = {
                        seerrVm.removeServer()
                        seerrDialogMode = SeerrDialogMode.None
                    },
                    bodyColor = MaterialTheme.colorScheme.error,
                )
            }

            SeerrDialogMode.None -> {}
        }
    }

    if (showQuickConnectDialog) {
        val quickConnectStatus by viewModel.quickConnectStatus.collectAsState(LoadingState.Pending)
        val successMessage = stringResource(R.string.quick_connect_success)

        LaunchedEffect(quickConnectStatus) {
            when (val status = quickConnectStatus) {
                LoadingState.Success -> {
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                    showQuickConnectDialog = false
                }

                is LoadingState.Error -> {
                    val errorMessage = status.message ?: "Authorization failed"
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }

                else -> {}
            }
        }

        QuickConnectDialog(
            onSubmit = { code ->
                viewModel.authorizeQuickConnect(code)
            },
            onDismissRequest = {
                viewModel.resetQuickConnectStatus()
                showQuickConnectDialog = false
            },
        )
    }
    if (showVersionDialog) {
        LaunchedEffect(Unit) {
            viewModel.fetchReleaseNotes()
        }
        val release by viewModel.releaseNotes.collectAsState()
        ScrollableDialog(
            onDismissRequest = { showVersionDialog = false },
        ) {
            item {
                when (val r = release) {
                    is DataLoadingState.Error -> {
                        ErrorMessage(message = "Error", exception = r.exception)
                    }

                    DataLoadingState.Pending,
                    DataLoadingState.Loading,
                    -> {
                        LoadingPage()
                    }

                    is DataLoadingState.Success<Release> -> {
                        ReleaseNotes(r.data)
                    }
                }
            }
        }
    }
    if (showLocaleChoiceDialog) {
        LocaleChoiceDialog(
            onDismissRequest = { showLocaleChoiceDialog = false },
        )
    }
}

@Composable
fun PreferencesPage(
    initialPreferences: AppPreferences,
    preferenceScreenOption: PreferenceScreenOption,
    modifier: Modifier = Modifier,
) {
    var updateNotes by remember { mutableStateOf<Release?>(null) }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        // WeaselFin: the release notes beside a focused "Install update" row, styled like the
        // notes column on the update page.
        Crossfade(
            targetState = updateNotes,
            label = "update notes",
            modifier =
                Modifier
                    .fillMaxWidth(.6f)
                    .fillMaxHeight()
                    .align(Alignment.TopStart)
                    .padding(24.dp),
        ) { release ->
            if (release != null) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
                            .padding(16.dp),
                ) {
                    ReleaseNotes(release)
                }
            }
        }
        when (preferenceScreenOption) {
            PreferenceScreenOption.BASIC,
            PreferenceScreenOption.ADVANCED,
            PreferenceScreenOption.EXO_PLAYER,
            PreferenceScreenOption.MPV,
            PreferenceScreenOption.SCREENSAVER,
            PreferenceScreenOption.SKIP_SEGMENTS,
            PreferenceScreenOption.EXPERIMENTAL,
            -> {
                PreferencesContent(
                    initialPreferences,
                    preferenceScreenOption,
                    Modifier
                        .fillMaxWidth(.4f)
                        .fillMaxHeight()
                        .align(Alignment.TopEnd),
                    onUpdateNotesChange = { updateNotes = it },
                )
            }
        }
    }
}

data class CacheUsage(
    val imageMemoryUsed: Long,
    val imageMemoryMax: Long,
    val imageDiskUsed: Long,
)

private sealed interface SeerrDialogMode {
    data object None : SeerrDialogMode

    data object Add : SeerrDialogMode

    data class Remove(
        val serverUrl: String,
    ) : SeerrDialogMode

    data class Error(
        val serverUrl: String,
        val ex: Exception,
    ) : SeerrDialogMode
}
