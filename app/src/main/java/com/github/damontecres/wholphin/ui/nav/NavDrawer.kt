package com.github.damontecres.wholphin.ui.nav

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.DrawerState
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.MusicService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.TimeDisplay
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.launchDefault
import com.github.damontecres.wholphin.ui.preferences.PreferenceScreenOption
import com.github.damontecres.wholphin.ui.setup.UserIconCardImage
import com.github.damontecres.wholphin.ui.spacedByWithFooter
import com.github.damontecres.wholphin.ui.theme.LocalTheme
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.ProvideNeonAccent
import com.github.damontecres.wholphin.ui.theme.RailAccents
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonDrawerItemColors
import com.github.damontecres.wholphin.ui.theme.neonIconGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.theme.neonTally
import com.github.damontecres.wholphin.ui.theme.sectionAccent
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.ui.tryRequestFocus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.CollectionType
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NavDrawerViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val navDrawerService: NavDrawerService,
        val navigationManager: NavigationManager,
        val setupNavigationManager: SetupNavigationManager,
        val backdropService: BackdropService,
        private val musicService: MusicService,
    ) : ViewModel() {
        val serviceState = navDrawerService.state

        private val _state = MutableStateFlow(NavDrawerState())
        val state: StateFlow<NavDrawerState> = _state

        fun onClickDrawerItem(
            index: Int,
            item: NavDrawerItem,
        ) {
            when (item) {
                NavDrawerItem.Favorites -> {
                    setIndex(index)
                    navigationManager.navigateToFromDrawer(
                        Destination.Favorites,
                    )
                }

                NavDrawerItem.More -> {
                    setShowMore(!state.value.moreExpanded)
                }

                NavDrawerItem.Discover -> {
                    setIndex(index)
                    navigationManager.navigateToFromDrawer(
                        Destination.Discover,
                    )
                }

                is ServerNavDrawerItem -> {
                    setIndex(index)
                    navigationManager.navigateToFromDrawer(item.destination)
                }
            }
        }

        fun setIndex(index: Int) {
            _state.update { it.copy(selectedIndex = index) }
        }

        fun setShowMore(value: Boolean) {
            _state.update { it.copy(moreExpanded = value) }
        }

        fun getUserImage(user: JellyfinUser): String = api.imageApi.getUserImageUrl(user.id)

        /**
         * Determine which nav drawer item should be highlighted as currently selected
         */
        fun updateSelectedIndex() {
            viewModelScope.launchDefault {
                val asDestinations =
                    buildList {
                        addAll(serviceState.value.items)
                        addAll(serviceState.value.moreItems)
                    }.map {
                        when (it) {
                            is ServerNavDrawerItem -> it.destination
                            is NavDrawerItem.Favorites -> Destination.Favorites
                            is NavDrawerItem.Discover -> Destination.Discover
                            else -> null
                        }
                    }

                val backstack = navigationManager.backStack.toList().reversed()
                for (i in 0..<backstack.size) {
                    val key = backstack[i]
                    val index =
                        if (key is Destination.Home) {
                            HOME_INDEX
                        } else if (key is Destination.Search) {
                            SEARCH_INDEX
                        } else if (key is Destination.NowPlaying) {
                            NOW_PLAYING_INDEX
                        } else {
                            val idx = asDestinations.indexOf(key)
                            if (idx >= 0) {
                                idx
                            } else {
                                null
                            }
                        }
                    Timber.v("Found $index => $key")
                    if (index != null) {
                        _state.update { it.copy(selectedIndex = index) }
                        break
                    }
                }
            }
        }

        fun navigateToSetup(userList: SetupDestination) {
            viewModelScope.launchDefault {
                musicService.stop()
                setupNavigationManager.navigateTo(userList)
            }
        }
    }

data class NavDrawerState(
    val selectedIndex: Int = -1,
    val moreExpanded: Boolean = false,
)

/**
 * An item that can be shown in the nav drawer
 *
 * Some are built-in such as Favorites. Others are created dynamically for libraries.
 */
sealed interface NavDrawerItem {
    val id: String

    fun name(context: Context): String

    object Favorites : NavDrawerItem {
        override val id: String
            get() = "a_favorites"

        override fun name(context: Context): String = context.getString(R.string.favorites)
    }

    object More : NavDrawerItem {
        override val id: String
            get() = "a_more"

        override fun name(context: Context): String = context.getString(R.string.more)
    }

    object Discover : NavDrawerItem {
        override val id: String
            get() = "a_discover"

        override fun name(context: Context): String = context.getString(R.string.discover)
    }
}

/**
 * A server provided nav drawer item, typically a library
 */
data class ServerNavDrawerItem(
    val itemId: UUID,
    val name: String,
    val destination: Destination,
    val type: CollectionType,
) : NavDrawerItem {
    override val id: String = getId(itemId)

    override fun name(context: Context): String = name

    companion object {
        fun getId(itemId: UUID) = "s_" + itemId.toServerString()
    }
}

private const val HOME_INDEX = -1
private const val SEARCH_INDEX = -2
private const val NOW_PLAYING_INDEX = -3

/**
 * Display the left side navigation drawer with [DestinationContent] on the right
 */
@Composable
fun NavDrawer(
    destination: Destination,
    preferences: UserPreferences,
    user: JellyfinUser,
    server: JellyfinServer,
    drawerState: DrawerState,
    navDrawerListState: LazyListState,
    onClearBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NavDrawerViewModel =
        hiltViewModel(
            LocalView.current.findViewTreeViewModelStoreOwner()!!,
            key = "${server.id}_${user.id}", // Keyed to the server & user to ensure its reset when switching either
        ),
) {
    LaunchedEffect(Unit) { viewModel.updateSelectedIndex() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    val focusRequester = remember { FocusRequester() }

    // If the user presses back while on the home page, open the nav drawer, another back press will quit the app
    BackHandler(enabled = (drawerState.currentValue == DrawerValue.Closed && destination is Destination.Home)) {
        drawerState.setValue(DrawerValue.Open)
        focusRequester.requestFocus()
    }
    val serviceState by viewModel.serviceState.collectAsState()
    val state by viewModel.state.collectAsState()
    val moreExpanded = state.moreExpanded
    // A negative index is a built-in page, >=0 is a library
    val selectedIndex = state.selectedIndex

    BackHandler(enabled = moreExpanded && drawerState.currentValue == DrawerValue.Open) {
        viewModel.setShowMore(false)
    }

    val closedDrawerWidth = collapsedDrawerItemWidth()
    val openDrawerWidth = expandedDrawerItemWidth()
    val neon = isWeaselTv()
    val offset by animateIntOffsetAsState(
        targetValue =
            IntOffset(
                x =
                    with(density) {
                        if (drawerState.isOpen) (openDrawerWidth - closedDrawerWidth).roundToPx() else 0
                    },
                y = 0,
            ),
        animationSpec =
            spring(
                stiffness = DrawerAnimationStiffness,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
    )

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            val isOpen = drawerValue.isOpen
            val spacedBy = 4.dp
            val searchFocusRequester = remember { FocusRequester() }

            ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacedBy),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    // Even though some must be clicked, focusing on it should clear other focused items
                    val interactionSource = remember { MutableInteractionSource() }
                    val userImageUrl = remember(user) { viewModel.getUserImage(user) }
                    if (neon) {
                        RailBrand(expanded = isOpen)
                    }
                    ProfileIcon(
                        user = user,
                        imageUrl = userImageUrl,
                        serverName = server.name ?: server.url,
                        drawerOpen = isOpen,
                        interactionSource = interactionSource,
                        onClick = {
                            viewModel.navigateToSetup(
                                SetupDestination.UserList(server),
                            )
                        },
                        modifier = Modifier,
                    )
                    AnimatedVisibility(
                        visible = serviceState.nowPlayingEnabled,
                        enter = expandVertically(expandFrom = Alignment.Top),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top),
                    ) {
                        val interactionSource = remember { MutableInteractionSource() }
                        IconNavItem(
                            text = stringResource(R.string.now_playing),
                            subtext = serviceState.nowPlayingTitle,
                            icon = Icons.Default.PlayArrow,
                            accent = RailAccents.NowPlaying,
                            selected = selectedIndex == NOW_PLAYING_INDEX,
                            drawerOpen = isOpen,
                            interactionSource = interactionSource,
                            onClick = {
                                viewModel.setIndex(NOW_PLAYING_INDEX)
                                viewModel.navigationManager.navigateTo(Destination.NowPlaying)
                            },
                            modifier =
                                Modifier
                                    .ifElse(
                                        selectedIndex == NOW_PLAYING_INDEX,
                                        Modifier.focusRequester(focusRequester),
                                    ),
                        )
                    }
                    LazyColumn(
                        state = navDrawerListState,
                        contentPadding = PaddingValues(0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedByWithFooter(spacedBy),
                        modifier =
                            Modifier
                                .focusGroup()
                                .focusProperties {
                                    onEnter = {
                                        if (requestedFocusDirection == FocusDirection.Down) {
                                            searchFocusRequester.tryRequestFocus()
                                        } else {
                                            focusRequester.tryRequestFocus()
                                        }
                                    }
                                }.fillMaxHeight(),
                    ) {
                        item {
                            val interactionSource = remember { MutableInteractionSource() }
                            IconNavItem(
                                text = stringResource(R.string.search),
                                weaselIcon = WeaselNavIcons.SEARCH,
                                accent = RailAccents.Search,
                                icon = Icons.Default.Search,
                                selected = selectedIndex == SEARCH_INDEX,
                                drawerOpen = isOpen,
                                interactionSource = interactionSource,
                                onClick = {
                                    viewModel.setIndex(SEARCH_INDEX)
                                    viewModel.navigationManager.navigateToFromDrawer(Destination.Search())
                                },
                                modifier =
                                    Modifier
                                        .focusRequester(searchFocusRequester)
                                        .ifElse(
                                            selectedIndex == SEARCH_INDEX,
                                            Modifier.focusRequester(focusRequester),
                                        ),
                            )
                        }
                        item {
                            val interactionSource = remember { MutableInteractionSource() }
                            IconNavItem(
                                text = stringResource(R.string.home),
                                weaselIcon = WeaselNavIcons.HOME,
                                icon = Icons.Default.Home,
                                selected = selectedIndex == HOME_INDEX,
                                drawerOpen = isOpen,
                                interactionSource = interactionSource,
                                onClick = {
                                    viewModel.setIndex(HOME_INDEX)
                                    if (destination is Destination.Home) {
                                        viewModel.navigationManager.reloadHome()
                                        onClearBackdrop.invoke()
                                    } else {
                                        viewModel.navigationManager.goToHome()
                                    }
                                },
                                modifier =
                                    Modifier
                                        .ifElse(
                                            selectedIndex == HOME_INDEX,
                                            Modifier.focusRequester(focusRequester),
                                        ),
                            )
                        }
                        itemsIndexed(serviceState.items) { index, it ->
                            val interactionSource = remember { MutableInteractionSource() }
                            NavItem(
                                library = it,
                                selected = selectedIndex == index,
                                moreExpanded = moreExpanded,
                                drawerOpen = isOpen,
                                interactionSource = interactionSource,
                                onClick = {
                                    viewModel.onClickDrawerItem(index, it)
                                },
                                modifier =
                                    Modifier
                                        .ifElse(
                                            selectedIndex == index,
                                            Modifier.focusRequester(focusRequester),
                                        ),
                            )
                        }
                        if (serviceState.moreItems.isNotEmpty()) {
                            item {
                                val index = serviceState.items.size
                                val interactionSource = remember { MutableInteractionSource() }
                                NavItem(
                                    library = NavDrawerItem.More,
                                    selected = false,
                                    moreExpanded = moreExpanded,
                                    drawerOpen = isOpen,
                                    interactionSource = interactionSource,
                                    onClick = {
                                        viewModel.onClickDrawerItem(index, NavDrawerItem.More)
                                    },
                                    modifier =
                                        Modifier
                                            .ifElse(
                                                selectedIndex == index,
                                                Modifier.focusRequester(focusRequester),
                                            ),
                                )
                            }
                        }
                        if (moreExpanded) {
                            itemsIndexed(serviceState.moreItems) { index, it ->
                                val adjustedIndex =
                                    remember(serviceState) { (index + serviceState.items.size) }
                                val interactionSource = remember { MutableInteractionSource() }
                                NavItem(
                                    library = it,
                                    selected = selectedIndex == adjustedIndex,
                                    moreExpanded = moreExpanded,
                                    drawerOpen = isOpen,
                                    onClick = {
                                        viewModel.onClickDrawerItem(
                                            adjustedIndex,
                                            it,
                                        )
                                    },
                                    containerColor =
                                        if (isOpen) {
                                            MaterialTheme.colorScheme.surface.copy(alpha = .5f)
                                        } else {
                                            Color.Unspecified
                                        },
                                    interactionSource = interactionSource,
                                    modifier =
                                        Modifier
                                            .ifElse(
                                                selectedIndex == adjustedIndex,
                                                Modifier.focusRequester(focusRequester),
                                            ),
                                )
                            }
                        }
                        item {
                            val interactionSource = remember { MutableInteractionSource() }
                            IconNavItem(
                                text = stringResource(R.string.settings),
                                weaselIcon = WeaselNavIcons.SETTINGS,
                                accent = RailAccents.Settings,
                                icon = Icons.Default.Settings,
                                selected = false,
                                drawerOpen = isOpen,
                                interactionSource = interactionSource,
                                onClick = {
                                    viewModel.navigationManager.navigateTo(
                                        Destination.Settings(
                                            PreferenceScreenOption.BASIC,
                                        ),
                                    )
                                },
                                modifier = Modifier,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Drawer content. Neon Board: content starts at x 120 (rail 72 at x 24 plus a
            // 24dp gutter) and stops 48dp short of the right edge, the overscan safe area.
            // The page takes the SECTION accent of the rail item it belongs to; details
            // pages override it with their item's type accent.
            val sectionAccent =
                remember(selectedIndex, serviceState) {
                    when {
                        selectedIndex == NOW_PLAYING_INDEX -> {
                            NeonBoard.Green
                        }

                        selectedIndex >= 0 -> {
                            (serviceState.items + serviceState.moreItems)
                                .getOrNull(selectedIndex)
                                ?.let { sectionAccent(it) }
                                ?: NeonBoard.Volt
                        }

                        else -> {
                            NeonBoard.Volt
                        }
                    }
                }
            ProvideNeonAccent(sectionAccent) {
                DestinationContent(
                    destination = destination,
                    preferences = preferences,
                    onClearBackdrop = onClearBackdrop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .offset {
                                offset
                            }.then(
                                if (neon) {
                                    // Owner (2026-09-17): the rail sits on the screen edge and rows run
                                    // to the right edge, so no overscan inset on either side.
                                    Modifier.padding(
                                        start = closedDrawerWidth + NeonBoard.Size.OverscanX / 2,
                                        end = 8.dp,
                                    )
                                } else {
                                    Modifier.padding(start = closedDrawerWidth + 8.dp, end = 16.dp)
                                },
                            ),
                )
            }
            if (preferences.appPreferences.interfacePreferences.showClock) {
                TimeDisplay()
            }
        }
    }
}

@Composable
fun NavigationDrawerScope.ProfileIcon(
    user: JellyfinUser,
    imageUrl: String?,
    serverName: String,
    drawerOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    NavigationDrawerItem(
        modifier = modifier,
        selected = false,
        onClick = onClick,
        shape = neonListItemShape(),
        colors = neonDrawerItemColors(NeonBoard.Volt),
        border = neonListItemBorder(NeonBoard.Volt),
        glow = neonListItemGlow(NeonBoard.Volt),
        leadingContent = {
            UserIconCardImage(
                id = user.id,
                name = user.name,
                imageUrl = imageUrl,
                alpha = if (drawerOpen || isWeaselTv()) 1f else .5f,
                modifier = Modifier.size(DrawerIconSize),
            )
        },
        supportingContent = {
            Text(
                text = serverName,
                maxLines = 1,
            )
        },
        interactionSource = interactionSource,
    ) {
        Text(
            modifier = Modifier,
            text = user.name ?: user.id.toString(),
            maxLines = 1,
        )
    }
}

@Composable
fun NavigationDrawerScope.IconNavItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean,
    drawerOpen: Boolean,
    modifier: Modifier = Modifier,
    subtext: String? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    weaselIcon: String? = null,
    accent: Color = NeonBoard.Volt,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val context = LocalContext.current
    NavigationDrawerItem(
        modifier = modifier.neonTally(accent, selected),
        selected = false,
        onClick = onClick,
        shape = neonListItemShape(),
        colors = neonDrawerItemColors(accent),
        border = neonListItemBorder(accent),
        glow = neonListItemGlow(accent),
        leadingContent = {
            // WeaselFin ships its own drawable for the fixed items. Null on every upstream
            // flavor, where the Material vector below is used exactly as before.
            val weasel = remember(weaselIcon) { weaselIcon?.let { WeaselNavIcons.fixed(context, it) } }
            val color = railGlyphColor(accent, selected, focused) ?: navItemColor(selected, focused, drawerOpen)
            val glyphModifier = Modifier.size(DrawerIconSize).neonIconGlow(accent, selected)
            if (weasel != null) {
                Icon(
                    painter = painterResource(weasel),
                    contentDescription = null,
                    tint = color,
                    modifier = glyphModifier,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = glyphModifier,
                )
            }
        },
        supportingContent =
            subtext?.let {
                {
                    Text(
                        text = it,
                        maxLines = 1,
                    )
                }
            },
        interactionSource = interactionSource,
    ) {
        Text(
            modifier = Modifier,
            text = text,
            maxLines = 1,
            style = if (isWeaselTv()) NeonType.railLabel(selected) else LocalTextStyle.current,
        )
    }
}

@Composable
fun NavigationDrawerScope.NavItem(
    library: NavDrawerItem,
    onClick: () -> Unit,
    selected: Boolean,
    moreExpanded: Boolean,
    drawerOpen: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    containerColor: Color = Color.Unspecified,
) {
    val context = LocalContext.current
    val useFont = library !is ServerNavDrawerItem || library.type != CollectionType.LIVETV
    val icon =
        remember(library) {
            when (library) {
                NavDrawerItem.Favorites -> {
                    R.string.fa_heart
                }

                NavDrawerItem.More -> {
                    R.string.fa_ellipsis
                }

                NavDrawerItem.Discover -> {
                    R.string.fa_magnifying_glass_plus
                }

                is ServerNavDrawerItem -> {
                    when (library.type) {
                        CollectionType.MOVIES -> R.string.fa_film
                        CollectionType.TVSHOWS -> R.string.fa_tv
                        CollectionType.HOMEVIDEOS -> R.string.fa_video
                        CollectionType.LIVETV -> R.drawable.gf_dvr
                        CollectionType.MUSIC -> R.string.fa_music
                        CollectionType.BOXSETS -> R.string.fa_open_folder
                        CollectionType.PLAYLISTS -> R.string.fa_list_ul
                        else -> R.string.fa_film
                    }
                }
            }
        }
    // Neon Board: the section accent — volt for the built-ins, the library's type color
    // (movies orange, shows yellow, live green, Boxing / UFC cyan by name).
    val accent = remember(library) { sectionAccent(library) }
    val focused by interactionSource.collectIsFocusedAsState()
    NavigationDrawerItem(
        modifier = modifier.neonTally(accent, selected),
        selected = false,
        onClick = onClick,
        shape = neonListItemShape(),
        colors = neonDrawerItemColors(accent, containerColor),
        border = neonListItemBorder(accent),
        glow = neonListItemGlow(accent),
        leadingContent = {
            val color = railGlyphColor(accent, selected, focused) ?: navItemColor(selected, focused, drawerOpen)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .neonIconGlow(accent, selected),
                contentAlignment = Alignment.Center,
            ) {
                // WeaselFin ships its own drawable set; null on every upstream flavor, which
                // falls through to the Font Awesome glyph exactly as before.
                val weasel = remember(library) { WeaselNavIcons.navIconFor(context, library) }
                if (weasel != null) {
                    Icon(
                        painter = painterResource(weasel),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(DrawerIconSize),
                    )
                } else if (useFont) {
                    Text(
                        text = stringResource(icon),
                        textAlign = TextAlign.Center,
                        fontSize = 16.sp,
                        fontFamily = FontAwesome,
                        color = color,
                        modifier = Modifier,
                    )
                } else {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(DrawerIconSize),
                    )
                }
            }
        },
        trailingContent =
            if (library is NavDrawerItem.More) {
                {
                    Icon(
                        imageVector = if (moreExpanded) Icons.Default.ArrowDropDown else Icons.Default.KeyboardArrowLeft,
                        contentDescription = null,
                    )
                }
            } else {
                null
            },
        interactionSource = interactionSource,
    ) {
        Text(
            modifier = Modifier,
            text = library.name(context),
            maxLines = 1,
            style = if (isWeaselTv()) NeonType.railLabel(selected) else LocalTextStyle.current,
        )
    }
}

/**
 * The brand mark at the top of the rail: the white mascot (30dp) and, when the rail is
 * expanded, the text wordmark — `WEASEL` in `text`, `PLEX` in volt, Barlow Condensed 800.
 * The mascot drawable ships only in the `weaselfin` flavor and is resolved by name.
 */
@Composable
fun RailBrand(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mascot = remember(context) { WeaselNavIcons.fixed(context, WeaselNavIcons.MASCOT) }
    val lockup = remember(context) { WeaselNavIcons.fixed(context, WeaselNavIcons.LOCKUP) }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .padding(start = if (expanded) 12.dp else 0.dp, top = 8.dp, bottom = 4.dp)
                .height(44.dp),
    ) {
        if (expanded && lockup != null) {
            Image(
                painter = painterResource(lockup),
                contentDescription = "WeaselPlex",
                modifier = Modifier.height(44.dp),
            )
        } else if (mascot != null) {
            Image(
                painter = painterResource(mascot),
                contentDescription = "WeaselPlex",
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

/**
 * Neon Board rail glyphs: `low` at rest, `text` when focused, the section accent when
 * selected. Null on every other theme so [navItemColor] runs unchanged for them.
 */
@Composable
@ReadOnlyComposable
fun railGlyphColor(
    accent: Color,
    selected: Boolean,
    focused: Boolean,
): Color? {
    if (!isWeaselTv()) return null
    return when {
        selected -> accent
        focused -> NeonBoard.Text
        else -> NeonBoard.Low
    }
}

@Composable
@ReadOnlyComposable
fun navItemColor(
    selected: Boolean,
    focused: Boolean,
    drawerOpen: Boolean,
): Color {
    val theme = LocalTheme.current
    if (theme == AppThemeColors.OLED_BLACK) {
        return when {
            selected && focused -> Color.Black
            selected && !drawerOpen -> Color.White.copy(alpha = .5f)
            selected && drawerOpen -> Color.White.copy(alpha = .85f)
            focused -> Color.Black.copy(alpha = .5f)
            drawerOpen -> Color(0xFF707070)
            else -> Color(0xFF505050).copy(alpha = .66f)
        }
    } else {
        val alpha =
            when {
                drawerOpen -> .85f
                selected && !drawerOpen -> .5f
                else -> .2f
            }
        return when {
            selected && focused -> {
                when (theme) {
                    AppThemeColors.UNRECOGNIZED,
                    AppThemeColors.PURPLE,
                    AppThemeColors.BLUE,
                    AppThemeColors.GREEN,
                    AppThemeColors.ORANGE,
                    AppThemeColors.RED,
                    AppThemeColors.BROWN,
                    AppThemeColors.WEASELTV,
                    -> MaterialTheme.colorScheme.border

                    AppThemeColors.BOLD_BLUE,
                    AppThemeColors.OLED_BLACK,
                    -> MaterialTheme.colorScheme.primary
                }
            }

            selected -> {
                MaterialTheme.colorScheme.border
            }

            focused -> {
                LocalContentColor.current
            }

            else -> {
                MaterialTheme.colorScheme.onSurface
            }
        }.copy(alpha = alpha)
    }
}

val DrawerState.isOpen: Boolean get() = this.currentValue.isOpen

val DrawerValue.isOpen: Boolean get() = this == DrawerValue.Open

/**
 * WeaselFin nav rail icon set (Set A).
 *
 * Drawables live ONLY in the `weaselfin` flavor's res/drawable. Main code therefore cannot
 * reference `R.drawable.ic_nav_*` directly - that would fail to compile for every upstream
 * flavor - so they are resolved BY NAME at runtime. On any flavor without them the lookup
 * returns 0, [WeaselNavIcons.navIconFor] returns null, and the original Font Awesome glyph
 * path runs unchanged. That is what keeps this flavor-only with no upstream behaviour diff.
 *
 * Builtins match on their stable [NavDrawerItem.id]; server libraries match on NAME, because
 * a library's id differs per server and cannot be baked into a build.
 *
 * Colors are NOT here any more: the Neon Board tints every glyph the same way (`low` at
 * rest, the section accent when selected), see [sectionAccent].
 */
internal object WeaselNavIcons {
    /** Key is a NavDrawerItem id for builtins, or a lowercased library name for server items. */
    private val map: Map<String, String> =
        mapOf(
            "a_favorites" to "ic_nav_favorites",
            "a_discover" to "ic_nav_requests",
            "movies" to "ic_nav_movies",
            "tv shows" to "ic_nav_tvshows",
            "stand up comedy" to "ic_nav_standup",
            "ufc" to "ic_nav_ufc",
            "boxing" to "ic_nav_boxing",
            "4k movies (lan)" to "ic_nav_4k_movies",
            "4k tv shows (lan)" to "ic_nav_4k_tv",
        )

    /** Fixed items, rendered outside the dynamic list. */
    const val SEARCH = "ic_nav_search"
    const val HOME = "ic_nav_home"
    const val SETTINGS = "ic_nav_settings"
    const val MASCOT = "weaselplex_mascot"
    const val LOCKUP = "weaselplex_lockup"

    private fun resId(
        context: Context,
        name: String,
    ): Int = context.resources.getIdentifier(name, "drawable", context.packageName)

    /** Drawable for a nav item, or null to fall back to upstream's glyph. */
    fun navIconFor(
        context: Context,
        item: NavDrawerItem,
    ): Int? {
        val key = if (item is ServerNavDrawerItem) item.name.trim().lowercase() else item.id
        val entry = map[key] ?: return null
        return fixed(context, entry)
    }

    /** Drawable for a fixed entry, or null when this flavor does not ship it. */
    fun fixed(
        context: Context,
        name: String,
    ): Int? = resId(context, name).takeIf { it != 0 }
}
