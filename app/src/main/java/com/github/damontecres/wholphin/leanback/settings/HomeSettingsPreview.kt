package com.github.damontecres.wholphin.leanback.settings

import com.github.damontecres.wholphin.leanback.widget.card.clipToOwnHeight
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.GridLayoutManager
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.dpF
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The live preview beside Home settings (the old `HomePageContent` in preview mode: no clock, empty rows shown, no
 * "view more"): every configured row with its title and cards at the row's size, spacing, image type and aspect
 * ratio, updated as the settings change. The cards take focus (RIGHT from the panel) and update the backdrop like
 * Home; OK does nothing.
 *
 * A private, simpler version of HOME's rows (runbooks/no-compose/CONTRACT.md section 1): HOME's View cards are not in
 * this checkout. Replace it with HOME's row list once it exists.
 */
@SuppressLint("ViewConstructor")
internal class HomeSettingsPreview(
    context: Context,
    private val imageUrlService: ImageUrlService,
    private val onFocusItem: (BaseItem) -> Unit,
) : FrameLayout(context) {
    private val grid = VerticalGridView(context)
    private val adapter = RowsAdapter()
    var pageStyle: PageStyle = PageStyle()

    init {
        clipChildren = false
        grid.adapter = adapter
        grid.clipChildren = false
        grid.clipToPadding = false
        grid.clipToOwnHeight()
        grid.setPadding(context.dp(8), context.dp(24), context.dp(24), context.dp(24))
        grid.windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
        grid.windowAlignmentOffsetPercent = 0f
        grid.itemAlignmentOffsetPercent = 0f
        grid.isFocusable = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Shows [rows]; [loading] while the first rows are fetched */
    fun submit(
        rows: List<HomeRowLoadingState>,
        loading: Boolean,
    ) {
        adapter.rows = rows
        adapter.loading = loading && rows.isEmpty()
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    /** Scrolls the preview to the row at [index] (a row opened in the settings) */
    fun scrollToRow(index: Int) {
        if (index in 0 until adapter.itemCount) grid.setSelectedPositionSmooth(index)
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        var rows: List<HomeRowLoadingState> = emptyList()
        var loading = false

        override fun getItemCount(): Int = if (loading) 1 else rows.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RowHolder = RowHolder(parent.context)

        override fun onBindViewHolder(
            holder: RowHolder,
            position: Int,
        ) {
            if (loading) holder.bindLoading() else holder.bind(rows[position])
        }
    }

    private inner class RowHolder(
        context: Context,
    ) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val column = itemView as LinearLayout
        private val title = AppCompatTextView(context)
        private val rule = SettingsRuleView(context, pageStyle.sectionAccent)
        private val message = AppCompatTextView(context)
        private val cards = HorizontalGridView(context)
        private val cardAdapter = CardsAdapter()

        init {
            column.orientation = LinearLayout.VERTICAL
            column.clipChildren = false
            column.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
            column.addView(title)
            column.addView(
                rule,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = -context.dp(7)
                },
            )
            column.addView(message)
            cards.adapter = cardAdapter
            cards.clipChildren = false
            cards.clipToPadding = false
            // LEFT from the first card goes back to the settings panel
            (cards.layoutManager as? GridLayoutManager)?.setFocusOutAllowed(true, false)
            column.addView(cards, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            SettingsLook.styleBody(message)
        }

        fun bindLoading() {
            title.visibility = View.GONE
            rule.visibility = View.GONE
            cards.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = itemView.context.getString(R.string.loading)
        }

        fun bind(row: HomeRowLoadingState) {
            val ctx = itemView.context
            val options = (row as? HomeRowLoadingState.Success)?.viewOptions ?: HomeRowViewOptions()
            title.visibility = View.VISIBLE
            title.text = row.title.getString(ctx).let { if (SettingsLook.neon) it.uppercase() else it }
            if (SettingsLook.neon) {
                title.applyTextRole(TextRole.TITLE_LARGE, sizeSp = options.titleSizeSp.toFloat())
            } else {
                title.applyTextRole(TextRole.TITLE_LARGE)
            }
            title.setTextColor(AppTheme.palette.onSurface)
            title.setPadding(0, ctx.dp(options.rowGapDp), 0, ctx.dp(options.titleDividerGapDp))
            rule.visibility = if (SettingsLook.neon) View.VISIBLE else View.GONE
            when (row) {
                is HomeRowLoadingState.Success -> {
                    if (row.items.isEmpty()) {
                        showMessage(ctx.getString(R.string.no_results))
                    } else {
                        message.visibility = View.GONE
                        cards.visibility = View.VISIBLE
                        cards.setPadding(0, ctx.dp(options.dividerGapDp), 0, ctx.dp(options.verticalPaddingDp))
                        cards.setItemSpacing(ctx.dp(options.spacing))
                        cardAdapter.options = options
                        cardAdapter.items = row.items
                        @Suppress("NotifyDataSetChanged")
                        cardAdapter.notifyDataSetChanged()
                    }
                }

                is HomeRowLoadingState.Error -> {
                    showMessage(row.localizedMessage)
                }

                else -> {
                    showMessage(ctx.getString(R.string.loading))
                }
            }
        }

        private fun showMessage(text: String) {
            message.visibility = View.VISIBLE
            message.text = text
            cards.visibility = View.GONE
        }
    }

    private inner class CardsAdapter : RecyclerView.Adapter<CardHolder>() {
        var items: List<BaseItem?> = emptyList()
        var options = HomeRowViewOptions()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): CardHolder = CardHolder(parent.context)

        override fun onBindViewHolder(
            holder: CardHolder,
            position: Int,
        ) = holder.bind(items[position], options)
    }

    private inner class CardHolder(
        context: Context,
    ) : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val column = itemView as LinearLayout
        private val frame = FrameLayout(context)
        private val image = ImageView(context)
        private val name = AppCompatTextView(context)
        private val caption = AppCompatTextView(context)
        private var item: BaseItem? = null

        init {
            column.orientation = LinearLayout.VERTICAL
            column.clipChildren = false
            column.isFocusable = true
            column.isClickable = true
            frame.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            name.gravity = Gravity.CENTER
            name.setPadding(context.dp(8), 0, context.dp(8), 0)
            frame.addView(name, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            column.addView(frame)
            caption.maxLines = 1
            caption.ellipsize = android.text.TextUtils.TruncateAt.END
            column.addView(caption)
            column.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) pageStyle.cardAppearance.focusScalePercent / 100f else 1f
                v
                    .animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(NeonColors.FOCUS_MS)
                    .start()
                frame.foreground = if (hasFocus) focusBorder(v.context) else null
                if (hasFocus) item?.let(onFocusItem)
            }
        }

        fun bind(
            item: BaseItem?,
            options: HomeRowViewOptions,
        ) {
            this.item = item
            val ctx = itemView.context
            val episode = item?.type == BaseItemKind.EPISODE && !options.useSeries
            val aspect = if (episode) options.episodeAspectRatio else options.aspectRatio
            val imageType = if (episode) options.episodeImageType else options.imageType
            val scale = if (episode) options.episodeContentScale else options.contentScale
            val height = ctx.dp(options.heightDp)
            val width = (height * aspect.ratio).toInt()
            frame.layoutParams = LinearLayout.LayoutParams(width, height)
            val radius = ctx.dpF(pageStyle.cardAppearance.cornerRadiusDp.toFloat())
            frame.background =
                GradientDrawable().apply {
                    setColor(if (SettingsLook.neon) NeonColors.CARD else AppTheme.palette.surfaceVariant)
                    cornerRadius = radius
                }
            frame.clipToOutline = true
            image.scaleType =
                when (scale) {
                    PrefContentScale.CROP -> ImageView.ScaleType.CENTER_CROP
                    PrefContentScale.FIT -> ImageView.ScaleType.FIT_CENTER
                    else -> ImageView.ScaleType.FIT_XY
                }
            val url =
                imageUrlService.getItemImageUrl(
                    item,
                    imageType.imageType,
                    fillWidth = width,
                    fillHeight = height,
                    useSeriesForPrimary = if (item?.type == BaseItemKind.EPISODE) options.useSeries else null,
                )
            name.text = item?.title ?: item?.name
            name.applyTextRole(TextRole.TITLE_SMALL)
            name.setTextColor(AppTheme.palette.onSurface)
            name.visibility = View.VISIBLE
            image.setImageDrawable(null)
            if (url != null) {
                image.load(url) {
                    listener(onSuccess = { _, _ -> name.visibility = View.GONE })
                }
            }
            caption.visibility = if (options.showTitles) View.VISIBLE else View.GONE
            caption.text = item?.title ?: item?.name
            caption.applyTextRole(TextRole.BODY_SMALL)
            caption.setTextColor(AppTheme.palette.onSurface)
            caption.layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        private fun focusBorder(ctx: Context) =
            GradientDrawable().apply {
                val accent = pageStyle.homeCardAccent(pageStyle.accentFor(item))
                setStroke(
                    ctx.dp(pageStyle.cardAppearance.borderWidthDp.coerceAtLeast(1)),
                    if (SettingsLook.neon) accent else AppTheme.palette.border,
                )
                cornerRadius = ctx.dpF(pageStyle.cardAppearance.cornerRadiusDp.toFloat())
            }
    }
}
