package com.github.damontecres.wholphin.ui.main.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.SuggestionsWorker
import com.github.damontecres.wholphin.ui.ifElse
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemColors
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.tryRequestFocus
import org.jellyfin.sdk.model.api.CollectionType

@Composable
fun HomeLibraryRowTypeList(
    library: Library,
    onClick: (LibraryRowType) -> Unit,
    modifier: Modifier,
    firstFocus: FocusRequester = remember { FocusRequester() },
) {
    val items = remember(library) { getSupportedRowTypes(library) }
    LaunchedEffect(Unit) { firstFocus.tryRequestFocus() }
    Column(modifier = modifier) {
        TitleText(stringResource(R.string.add_row_for, library.name))
        HomeSettingsLazyColumn(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .focusRestorer(firstFocus),
        ) {
            itemsIndexed(items) { index, rowType ->
                ListItem(
                    shape = neonListItemShape(),
                    colors = neonListItemColors(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    selected = false,
                    headlineContent = {
                        Text(
                            text = stringResource(rowType.stringId),
                        )
                    },
                    onClick = { onClick.invoke(rowType) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .ifElse(index == 0, Modifier.focusRequester(firstFocus)),
                )
            }
        }
    }
}
