package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.NeonBoard
import com.github.damontecres.wholphin.ui.theme.NeonType
import com.github.damontecres.wholphin.ui.theme.isWeaselTv

/** Bounded text keeps wrapping and ellipsis effective, including while the card is focused. */
@Composable
fun PosterCardTitle(title: String?, focused: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = title.orEmpty(),
        style = if (isWeaselTv()) NeonType.cardTitle() else MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (isWeaselTv()) (if (focused) NeonBoard.Text else NeonBoard.Mid) else Color.Unspecified,
        textAlign = TextAlign.Center,
        maxLines = 2,
        softWrap = true,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
fun PosterCardSubtitle(subtitle: String?, modifier: Modifier = Modifier) {
    if (subtitle.isNullOrBlank()) return

    Text(
        text = subtitle.orEmpty(),
        style = if (isWeaselTv()) NeonType.cardSubtitle() else MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Normal,
        color = if (isWeaselTv()) NeonBoard.Low else Color.Unspecified,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}
