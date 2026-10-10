package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.ui.preferences.PreferencePanelHeader
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.ui.theme.neonListItemBorder
import com.github.damontecres.wholphin.ui.theme.neonListItemColors
import com.github.damontecres.wholphin.ui.theme.neonListItemGlow
import com.github.damontecres.wholphin.ui.theme.neonListItemShape
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/** Dependency attribution, with remote-friendly focus and license reading on TV. */
@Composable
fun LicenseInfo(modifier: Modifier = Modifier) {
    val libraries by produceLibraries()
    if (isWeaselTv()) {
        libraries?.let { libs ->
            LicenseInfoContent(
                libs.libraries.map { library ->
                    DependencyAttribution(
                        id = library.uniqueId,
                        name = library.name,
                        version = library.artifactVersion,
                        authors =
                            library.developers
                                .mapNotNull { it.name }
                                .joinToString(
                                    ", ",
                                ).ifBlank { library.organization?.name.orEmpty() },
                        description = library.description,
                        website = library.website,
                        licenses = library.licenses.map { DependencyLicense(it.name, it.licenseContent, it.url) },
                    )
                },
                modifier,
            )
        } ?: LoadingPage(modifier)
    } else {
        LibrariesContainer(libraries, modifier)
    }
}

data class DependencyLicense(
    val name: String,
    val content: String?,
    val url: String?,
)

data class DependencyAttribution(
    val id: String,
    val name: String,
    val version: String?,
    val authors: String,
    val description: String?,
    val website: String?,
    val licenses: List<DependencyLicense>,
)

@Composable
fun LicenseInfoContent(
    libraries: List<DependencyAttribution>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<DependencyAttribution?>(null) }
    val firstFocus = remember { FocusRequester() }
    Column(modifier) {
        PreferencePanelHeader(stringResource(R.string.license_info))
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(libraries, key = { index, library -> "${library.id}-$index" }) { index, library ->
                ListItem(
                    selected = false,
                    onClick = { selected = library },
                    headlineContent = {
                        Text(library.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            val authors = library.authors
                            if (authors.isNotBlank()) Text(authors)
                            Text(library.licenses.joinToString(" • ") { it.name })
                        }
                    },
                    trailingContent = { library.version?.let { Text(it) } },
                    colors = neonListItemColors(),
                    shape = neonListItemShape(),
                    border = neonListItemBorder(),
                    glow = neonListItemGlow(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .keepFocusedItemVisible()
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                )
            }
        }
    }
    LaunchedEffect(libraries) { if (libraries.isNotEmpty()) firstFocus.tryRequestFocus() }
    selected?.let { library ->
        ScrollableDialog(onDismissRequest = { selected = null }) {
            item { Text(library.name, style = NeonType.dialogTitle(), color = MaterialTheme.colorScheme.onSurface) }
            library.description?.takeIf { it.isNotBlank() }?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurface) } }
            library.website?.takeIf { it.isNotBlank() }?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurface) } }
            library.licenses.forEach { license ->
                item { Text(license.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
                license.content?.takeIf { it.isNotBlank() }?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurface) } }
                license.url?.takeIf { it.isNotBlank() }?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurface) } }
            }
        }
    }
}
