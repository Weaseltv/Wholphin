package com.github.damontecres.wholphin.leanback.widget.text

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.load
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.ControlTextStyle
import com.github.damontecres.wholphin.leanback.theme.NeonType
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.logCoilError
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * The logo URL of [item] (`rememberLogoUrl`): an episode uses its series' logo when it has one, anything else its own
 * logo; null without one.
 */
fun logoUrlFor(
    item: BaseItem?,
    imageUrlService: ImageUrlService,
): String? =
    if (item?.type == BaseItemKind.EPISODE && item.data.seriesId != null && item.data.parentLogoImageTag != null) {
        imageUrlService.getItemImageUrl(item.data.seriesId!!, ImageType.LOGO)
    } else if (ImageType.LOGO in item?.data?.imageTags.orEmpty()) {
        imageUrlService.getItemImageUrl(item, ImageType.LOGO)
    } else {
        null
    }

/**
 * A title as the item's logo or as text (`TitleOrLogo`): with [showLogo] and a logo URL, the logo fitted in a box
 * [HeaderDimens.LOGO_HEIGHT] tall (44dp in a compact detail header, 28dp when [small]) and at most 320dp wide; if
 * there is no logo or it fails to load, the title as text. On the WeaselTV theme the text is uppercase in the hero
 * type (48sp, 32sp compact, 20sp small) shrinking to fit two lines (one when small), never breaking a word; elsewhere
 * headlineMedium SemiBold.
 *
 * [fillLogo] is `SimpleTitleOrLogo`: the logo fills the view and both are centred.
 */
class TitleOrLogoView(
    context: Context,
    private val fillLogo: Boolean = false,
) : FrameLayout(context) {
    private val neon = AppTheme.isNeon
    private val logoView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = !fillLogo
            visibility = View.GONE
        }
    private val titleView = FittedTitleView(context, maxLines = 2)
    private var title: String? = null
    private var logoUrl: String? = null
    private var showLogo = false
    private var logoFailed = false

    /** Small variant (`compact = true`): 28dp logo, one line of 20sp text */
    var small: Boolean = false
        set(value) {
            field = value
            render()
        }

    /** The detail header is compact (`LocalCompactDetailHeader`): 44dp logo, 32sp text */
    var compactHeader: Boolean = false
        set(value) {
            field = value
            render()
        }

    init {
        addView(
            logoView,
            if (fillLogo) {
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
            } else {
                LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(HeaderDimens.LOGO_HEIGHT))
            },
        )
        addView(
            titleView,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                if (fillLogo) Gravity.CENTER else Gravity.START or Gravity.TOP,
            ),
        )
        titleView.setTextColor(AppTheme.palette.onSurface)
    }

    fun bind(
        title: String?,
        logoUrl: String?,
        showLogo: Boolean,
    ) {
        if (logoUrl != this.logoUrl) logoFailed = false
        this.title = title
        this.logoUrl = logoUrl
        this.showLogo = showLogo
        render()
    }

    /** Bind [item]'s title and logo (`TitleOrLogo(item, showLogo)`) */
    fun bind(
        item: BaseItem?,
        showLogo: Boolean,
        imageUrlService: ImageUrlService,
    ) = bind(item?.title, logoUrlFor(item, imageUrlService), showLogo)

    private fun render() {
        val url = logoUrl
        if (showLogo && url != null && !logoFailed) {
            titleView.visibility = View.GONE
            logoView.visibility = View.VISIBLE
            if (!fillLogo) {
                logoView.layoutParams =
                    (logoView.layoutParams as LayoutParams).apply {
                        height =
                            context.dp(
                                when {
                                    small -> 28f
                                    compactHeader -> 44f
                                    else -> HeaderDimens.LOGO_HEIGHT
                                },
                            )
                    }
                logoView.maxWidth = context.dp(320)
            }
            logoView.contentDescription = title
            logoView.load(url) {
                listener(
                    onError = { _, result ->
                        logCoilError(url, result)
                        logoFailed = true
                        render()
                    },
                )
            }
        } else {
            logoView.visibility = View.GONE
            titleView.visibility = View.VISIBLE
            val heading = (title ?: "").let { if (neon) it.uppercase() else it }
            titleView.maxLines = if (small) 1 else 2
            titleView.fit = neon
            titleView.setStyledText(
                heading,
                if (neon) {
                    NeonType.hero(
                        when {
                            small -> 20f
                            compactHeader && !fillLogo -> 32f
                            else -> 48f
                        },
                    )
                } else {
                    ControlTextStyle.of(TextRole.HEADLINE_MEDIUM, weight = 600)
                },
            )
        }
    }
}
