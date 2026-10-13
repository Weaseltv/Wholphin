package com.github.damontecres.wholphin.ui.detail.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.StreamingProviderWordmark
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch

@Composable
fun CollectionDetailsHeader(
    collection: BaseItem,
    showLogo: Boolean,
    logoImageUrl: String?,
    overviewOnClick: () -> Unit,
    bringIntoViewRequester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val dto = collection.data
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neon = isWeaselTv()
    // Streaming collections show the service's wordmark on the right, like their Home row, so the
    // copy is narrower to leave it room
    val showWordmark = streaming && neon
    Box(modifier = modifier.fillMaxWidth()) {
        if (showWordmark) {
            StreamingProviderWordmark(
                name = collection.name,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(.5f)
                        .padding(end = 96.dp)
                        .height(110.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier,
        ) {
            TitleOrLogo(
                title = collection.name,
                showLogo = showLogo,
                logoImageUrl = logoImageUrl,
                modifier =
                    Modifier
                        .fillMaxWidth(if (showWordmark) .46f else .75f)
                        .padding(start = HeaderUtils.startPadding),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(if (showWordmark) .46f else .60f),
            ) {
                QuickDetails(
                    if (streaming) collection.ui.quickDetails.copy(officialRating = null) else collection.ui.quickDetails,
                    collection.timeRemainingOrRuntime,
                    Modifier.padding(start = HeaderUtils.startPadding),
                )

                dto.genres?.letNotEmpty {
                    GenreText(it, Modifier.padding(start = HeaderUtils.startPadding))
                }
                dto.taglines?.firstOrNull()?.let { tagline ->
                    Text(
                        text = tagline,
                        style = MaterialTheme.typography.bodyLarge,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = HeaderUtils.startPadding),
                    )
                }

                // Description
                dto.overview?.let { overview ->
                    if (streaming) {
                        // Short and fixed, so it's plain text: it can't take focus or open in a popup
                        Text(
                            text = overview.replace(" in WeaselPlex", "", ignoreCase = true),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp),
                        )
                        return@let
                    }
                    OverviewText(
                        overview = overview,
                        maxLines = 3,
                        onClick = overviewOnClick,
                        textBoxHeight = Dp.Unspecified,
                        modifier =
                            Modifier.onFocusChanged {
                                if (it.isFocused && !neon) {
                                    scope.launch(ExceptionHandler()) {
                                        bringIntoViewRequester.bringIntoView()
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}
