package com.github.damontecres.wholphin.ui.theme

import android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.surfaceColorAtElevation
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppThemeColors
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.cards.WatchedIcon
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.nav.NavDrawerItem
import com.github.damontecres.wholphin.ui.nav.NavItem
import com.github.damontecres.wholphin.ui.playback.overlay.PlaybackButton
import com.github.damontecres.wholphin.ui.preferences.SliderPreference
import com.github.damontecres.wholphin.ui.preferences.SwitchPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Neon Board focus-recipe board (`screens/TvStates.png`): cards at rest / focused /
 * in progress / watched; rail rows at rest / focused / selected / both; primary, outline
 * and glass buttons at rest and focused. The fastest visual check on a real box.
 */
@Preview(
    device = "spec:width=960dp,height=540dp,dpi=320",
    backgroundColor = 0xFF050608,
    uiMode = UI_MODE_TYPE_TELEVISION,
)
@Composable
private fun NeonBoardFocusRecipePreview() {
    NeonBoardFocusRecipe()
}

@Composable
fun NeonBoardFocusRecipe(modifier: Modifier = Modifier) {
    val focus = remember { PreviewInteractionSource() }
    WholphinTheme(appThemeColors = AppThemeColors.WEASELTV) {
        CompositionLocalProvider(LocalContentColor provides NeonBoard.Text) {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier =
                    modifier
                        .fillMaxSize()
                        .background(NeonBoard.Stage)
                        .padding(horizontal = NeonBoard.Size.OverscanX, vertical = NeonBoard.Size.OverscanY),
            ) {
                ProvideNeonAccent(NeonBoard.Orange) {
                    NeonEyebrow("Focus recipe · one state for everything")
                    Text(text = "ONE FOCUS STATE FOR EVERYTHING", style = NeonType.pageTitle(), color = NeonBoard.Text)
                    Text(
                        text =
                            "Focused = 1dp accent border + glow + scale 1.06 on cards. Selected / current = 3dp tally. " +
                                "Nothing animates except the 120 ms focus transition.",
                        style = NeonType.body(),
                        color = NeonBoard.Mid,
                    )

                    BoardSection("Cards")
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                        BoardCard("Rest", played = false, percent = 0.0)
                        BoardCard("Focused", played = false, percent = 0.0, interactionSource = focus)
                        BoardCard("In progress", played = false, percent = 40.0)
                        BoardCard("Watched", played = true, percent = 0.0)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardSection("Rail items")
                            val navScope = NavScope(true)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                navScope.apply {
                                    NavItem(NavDrawerItem.Favorites, {}, selected = false, moreExpanded = false, drawerOpen = false)
                                    NavItem(
                                        NavDrawerItem.Favorites,
                                        {},
                                        selected = false,
                                        moreExpanded = false,
                                        drawerOpen = false,
                                        interactionSource = focus,
                                    )
                                    NavItem(NavDrawerItem.Favorites, {}, selected = true, moreExpanded = false, drawerOpen = false)
                                    NavItem(
                                        NavDrawerItem.Favorites,
                                        {},
                                        selected = true,
                                        moreExpanded = false,
                                        drawerOpen = false,
                                        interactionSource = focus,
                                    )
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BoardSection("Buttons")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                BoardPrimary("Play")
                                BoardPrimary("Play", interactionSource = focus)
                                Button(onClick = {}) { Text("TRAILER") }
                                Button(onClick = {}, interactionSource = focus) { Text("TRAILER") }
                                PlaybackButton(iconRes = R.drawable.baseline_pause_24, onClick = {}, onControllerInteraction = {})
                                PlaybackButton(
                                    iconRes = R.drawable.baseline_pause_24,
                                    onClick = {},
                                    onControllerInteraction = {},
                                    interactionSource = focus,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardSection(text: String) {
    Text(
        text = text.uppercase(),
        style = NeonType.count(),
        color = NeonBoard.Mid,
    )
}

@Composable
private fun BoardCard(
    label: String,
    played: Boolean,
    percent: Double,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SeasonCard(
            title = null,
            subtitle = null,
            name = label,
            imageUrl = null,
            isFavorite = false,
            isPlayed = played,
            unplayedItemCount = 0,
            playedPercentage = percent,
            numberOfVersions = 0,
            onClick = {},
            onLongClick = {},
            imageHeight = 180.dp,
            interactionSource = interactionSource,
            showImageOverlay = true,
            aspectRatio = AspectRatios.TALL,
        )
        Text(text = label, style = NeonType.cardTitle(), color = NeonBoard.Mid)
    }
}

@Composable
private fun BoardPrimary(
    label: String,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val accent = LocalNeonAccent.current
    Button(
        onClick = {},
        colors = neonPrimaryColors(accent),
        border = neonPrimaryBorder(),
        glow = neonPrimaryGlow(accent),
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Text(label.uppercase(), modifier = Modifier.padding(start = 8.dp))
    }
}

/** Every theme side by side, so a change to a shared helper is checked against all of them. */
@Preview(
    device = "spec:width=1200dp,height=3000dp",
    backgroundColor = 0xFF383535,
    uiMode = UI_MODE_TYPE_TELEVISION,
)
@Composable
private fun ThemePreview() {
    val themes = AppThemeColors.entries.filterNot { it == AppThemeColors.UNRECOGNIZED }
    Column {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(themes) {
                ThemeExample(it)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ThemeExample(theme: AppThemeColors) {
    val source = remember { PreviewInteractionSource() }
    WholphinTheme(appThemeColors = theme) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                Text(
                    text = theme.toString(),
                    color = Color.White,
                    modifier = Modifier.padding(8.dp),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(8.dp),
                ) {
                    SectionTitle("Cards")
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        WatchedIcon()
                        SeasonCard(
                            title = "Card",
                            subtitle = "2025",
                            name = "N/A",
                            imageUrl = "abc",
                            isFavorite = true,
                            isPlayed = true,
                            unplayedItemCount = 2,
                            playedPercentage = 50.0,
                            numberOfVersions = 2,
                            onClick = { },
                            onLongClick = {},
                            imageHeight = 120.dp,
                            interactionSource = source,
                            showImageOverlay = true,
                            aspectRatio = AspectRatios.TALL,
                        )
                        SeasonCard(
                            title = "Watched",
                            subtitle = "2025",
                            name = "N/A",
                            imageUrl = "abc",
                            isFavorite = false,
                            isPlayed = true,
                            unplayedItemCount = 2,
                            playedPercentage = 0.0,
                            numberOfVersions = 0,
                            onClick = { },
                            onLongClick = {},
                            imageHeight = 120.dp,
                            showImageOverlay = true,
                            aspectRatio = AspectRatios.SQUARE,
                        )
                    }
                    SectionTitle("Controls")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SliderPreference(
                            preference = AppPreference.AutoPlayNextDelay,
                            title = "Slider",
                            summary = "5 seconds",
                            value = 30,
                            onChange = {},
                            modifier = Modifier.weight(1f),
                        )
                        SliderPreference(
                            preference = AppPreference.AutoPlayNextDelay,
                            title = "Slider",
                            summary = "5 seconds",
                            value = 30,
                            onChange = {},
                            modifier = Modifier.weight(1f),
                            interactionSource = source,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SwitchPreference(
                            title = "Switch",
                            value = false,
                            onClick = {},
                            summaryOn = "Enabled",
                            summaryOff = "Disabled",
                            modifier = Modifier.weight(1f),
                        )
                        SwitchPreference(
                            title = "Switch",
                            value = true,
                            onClick = {},
                            summaryOn = "Enabled",
                            summaryOff = "Disabled",
                            modifier = Modifier.weight(1f),
                            interactionSource = source,
                        )
                    }
                    SectionTitle("Nav drawer")
                    val navScope = NavScope(true)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                    ) {
                        navScope.apply {
                            NavItem(
                                library = NavDrawerItem.Favorites,
                                onClick = { },
                                selected = false,
                                moreExpanded = false,
                                drawerOpen = true,
                                modifier = Modifier,
                            )
                            NavItem(
                                library = NavDrawerItem.Favorites,
                                onClick = { },
                                selected = true,
                                moreExpanded = false,
                                drawerOpen = true,
                                modifier = Modifier,
                                interactionSource = source,
                            )
                        }
                    }
                    SectionTitle("Playback controls")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        PlaybackButton(
                            modifier = Modifier,
                            iconRes = R.drawable.baseline_play_arrow_24,
                            onClick = {},
                            onControllerInteraction = {},
                        )
                        PlaybackButton(
                            modifier = Modifier,
                            iconRes = R.drawable.baseline_pause_24,
                            onClick = {},
                            onControllerInteraction = {},
                            interactionSource = source,
                        )
                        Button(onClick = {}, modifier = Modifier.width(120.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                    }
                    SectionTitle("List items")
                    Column(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                    ) {
                        ListItem(
                            selected = false,
                            enabled = true,
                            headlineContent = { Text("Headline content") },
                            supportingContent = { Text("Support content") },
                            onClick = {},
                        )
                        ListItem(
                            selected = true,
                            enabled = true,
                            headlineContent = { Text("Headline content") },
                            supportingContent = { Text("Support content") },
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

class PreviewInteractionSource : MutableInteractionSource {
    override val interactions: Flow<Interaction>
        get() = flowOf(FocusInteraction.Focus())

    override suspend fun emit(interaction: Interaction) {
    }

    override fun tryEmit(interaction: Interaction): Boolean = false
}

class NavScope(
    override val hasFocus: Boolean,
) : NavigationDrawerScope
