package com.github.damontecres.wholphin.ui.detail.movie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ChosenStreams
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.components.GenreText
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.OverviewText
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.components.TitleOrLogo
import com.github.damontecres.wholphin.ui.components.VideoStreamDetails
import com.github.damontecres.wholphin.ui.components.scaled
import com.github.damontecres.wholphin.ui.detail.KidMetadataLine
import com.github.damontecres.wholphin.ui.detail.KidTitlePage
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.letNotEmpty
import com.github.damontecres.wholphin.ui.roundMinutes
import com.github.damontecres.wholphin.ui.theme.isWeaselTv
import com.github.damontecres.wholphin.util.ExceptionHandler
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration

@Composable
fun MovieDetailsHeader(
    preferences: UserPreferences,
    movie: BaseItem,
    chosenStreams: ChosenStreams?,
    bringIntoViewRequester: BringIntoViewRequester,
    overviewOnClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The kid title page (owner mockup 2026-10-23): a short metadata line and the real description, nothing else */
    kid: Boolean = false,
) {
    val dto = movie.data
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val neon = isWeaselTv()
    val infoScale = HeaderUtils.detailInfoTextScale()
    val typography = MaterialTheme.typography
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        // Title
        TitleOrLogo(
            item = movie,
            showLogo = preferences.appPreferences.interfacePreferences.showLogos,
            modifier =
                Modifier
                    .fillMaxWidth(.75f)
                    .padding(start = HeaderUtils.startPadding),
        )

        if (kid) {
            KidMovieInfo(movie, infoScale)
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(.60f),
        ) {
            QuickDetails(
                movie.ui.quickDetails,
                movie.timeRemainingOrRuntime,
                Modifier.padding(start = HeaderUtils.startPadding),
                textStyle = typography.titleSmall.scaled(infoScale),
            )

            dto.genres?.letNotEmpty {
                GenreText(
                    it,
                    Modifier.padding(start = HeaderUtils.startPadding),
                    textStyle = typography.bodyMedium.scaled(infoScale),
                )
            }

            VideoStreamDetails(
                chosenStreams = chosenStreams,
                numberOfVersions = movie.data.mediaSourceCount ?: 0,
                modifier =
                    Modifier.padding(
                        start = HeaderUtils.startPadding,
                        top = 4.dp,
                        bottom = 16.dp,
                    ),
                fontSize = 12.sp * infoScale,
            )
            dto.taglines?.firstOrNull()?.let { tagline ->
                Text(
                    text = tagline,
                    style = typography.bodyLarge.scaled(infoScale),
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = HeaderUtils.startPadding),
                )
            }

            // Description
            dto.overview?.let { overview ->
                OverviewText(
                    overview = overview,
                    maxLines = 3,
                    onClick = overviewOnClick,
                    textBoxHeight = Dp.Unspecified,
                    textStyle = typography.bodyMedium.scaled(infoScale),
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

            val directorName =
                remember(movie.data.people) {
                    movie.data.people
                        ?.filter { it.type == PersonKind.DIRECTOR && it.name.isNotNullOrBlank() }
                        ?.joinToString(", ") { it.name!! }
                        ?.takeIf { it.isNotNullOrBlank() }
                }

            directorName
                ?.let {
                    Text(
                        text = stringResource(R.string.directed_by, it),
                        style = typography.bodyMedium.scaled(infoScale),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = HeaderUtils.startPadding),
                    )
                }
        }
    }
}

/**
 * The kid page's movie details: year, runtime and the rating badge, the genres, and the description up to
 * [KidTitlePage.overviewLines]. No scores, "Ends at", time left (it is under the Play button), stream chips, tagline or
 * director.
 */
@Composable
private fun KidMovieInfo(
    movie: BaseItem,
    infoScale: Float,
) {
    val dto = movie.data
    val typography = MaterialTheme.typography
    val resources = LocalResources.current
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(.60f),
    ) {
        val parts =
            remember(dto.productionYear, dto.runTimeTicks) {
                listOfNotNull(
                    dto.productionYear?.toString(),
                    dto.runTimeTicks
                        ?.ticks
                        ?.roundMinutes
                        ?.takeIf { it > Duration.ZERO }
                        ?.let { resources.formatDuration(it) },
                )
            }
        KidMetadataLine(
            parts = parts,
            rating = dto.officialRating,
            textStyle = typography.titleSmall.scaled(infoScale),
            modifier = Modifier.padding(start = HeaderUtils.startPadding, top = 4.dp),
        )
        dto.genres?.letNotEmpty {
            GenreText(
                it,
                Modifier.padding(start = HeaderUtils.startPadding),
                textStyle = typography.bodyMedium.scaled(infoScale),
            )
        }
        dto.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            OverviewText(
                overview = overview,
                maxLines = KidTitlePage.overviewLines(compact = false),
                compactMaxLines = KidTitlePage.overviewLines(compact = true),
                onClick = {},
                textBoxHeight = Dp.Unspecified,
                textStyle = typography.bodyMedium.scaled(infoScale),
            )
        }
    }
}
