package com.github.damontecres.wholphin.ui.search

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.cards.BannerCardWithTitle
import com.github.damontecres.wholphin.ui.cards.PersonCard
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.cards.personRowCardWidth
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@Composable
fun SearchPageCard(
    item: BaseItem?,
    type: BaseItemKind,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (type) {
        BaseItemKind.EPISODE -> {
            BannerCardWithTitle(
                title = item?.title,
                subtitle = item?.subtitle,
                item = item,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.padding(horizontal = 8.dp),
                cardHeight = Cards.heightEpisode,
            )
        }

        BaseItemKind.PERSON -> {
            val imageUrlService = LocalImageUrlService.current
            val imageUrl =
                remember(item) {
                    imageUrlService.getItemImageUrl(
                        item,
                        ImageType.PRIMARY,
                    )
                }
            PersonCard(
                name = item?.name ?: "",
                role = null,
                imageUrl = imageUrl,
                favorite = item?.favorite ?: false,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.width(personRowCardWidth),
            )
        }

        else -> {
            SeasonCard(
                item = item,
                onClick = onClick,
                onLongClick = onLongClick,
                imageHeight = Cards.height2x3,
                showImageOverlay = true,
                modifier = modifier,
            )
        }
    }
}
