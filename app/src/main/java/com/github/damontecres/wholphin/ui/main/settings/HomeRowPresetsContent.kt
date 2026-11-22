package com.github.damontecres.wholphin.ui.main.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions.Companion.genreDefault
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

@Composable
fun HomeRowPresetsContent(
    onApply: (HomeRowPresets) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets =
        listOf(
            stringResource(R.string.display_preset_default) to HomeRowPresets.WholphinDefault,
            stringResource(R.string.display_preset_compact) to HomeRowPresets.WholphinCompact,
            stringResource(R.string.display_preset_series_thumb) to HomeRowPresets.SeriesThumbs,
            stringResource(R.string.display_preset_episode_thumbnails) to HomeRowPresets.EpisodeThumbnails,
        )

    val focusRequesters = remember { List(presets.size) { FocusRequester() } }
    LaunchedEffect(Unit) { focusRequesters[0].tryRequestFocus() }
    Column(modifier = modifier) {
        TitleText(stringResource(R.string.display_presets))
        HomeSettingsLazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .focusRestorer(focusRequesters[0]),
        ) {
            itemsIndexed(presets) { index, (title, preset) ->
                HomeSettingsListItem(
                    selected = false,
                    headlineText = title,
                    onClick = {
                        onApply.invoke(preset)
                    },
                    modifier = Modifier.focusRequester(focusRequesters[index]),
                )
            }
        }
    }
}
