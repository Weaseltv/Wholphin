package com.github.damontecres.wholphin.leanback.widget.card

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.ui.detail.CardGridItem

/**
 * Creates and binds the cards of a [CardRowAdapter] / [CardGridAdapter] (the old `cardContent` lambda).
 *
 * Several card kinds in one row (Home: banner, genre and studio cards) use [viewType]. [key] gives a card its identity
 * across updates (default: its index, as `LazyRow` without keys; Home's streaming rows key by item id so focus follows a
 * card being reordered). [sameContent] decides whether an update rebinds the card.
 */
abstract class CardPresenter<T : Any> {
    open fun viewType(
        item: T?,
        position: Int,
    ): Int = 0

    abstract fun create(
        parent: ViewGroup,
        viewType: Int,
    ): BaseCardView

    /** @param cellWidthPx the column width in a grid; 0 in a row (row cards size themselves) */
    abstract fun bind(
        card: BaseCardView,
        item: T?,
        position: Int,
        cellWidthPx: Int,
    )

    open fun key(
        item: T?,
        position: Int,
    ): Any = position

    open fun sameContent(
        old: T?,
        new: T?,
    ): Boolean = old == new

    /** Recycling: cancel the card's image loads */
    open fun recycle(card: BaseCardView) = card.recycle()
}

/**
 * A [CardPresenter] from lambdas, for one card kind:
 * ```
 * cardPresenter<BaseItem, SeasonCardView>(::SeasonCardView) { card, item, _, _ -> card.bind(item, style, 170f) }
 * ```
 */
inline fun <T : Any, reified V : BaseCardView> cardPresenter(
    crossinline create: (Context) -> V,
    noinline key: ((item: T?, position: Int) -> Any)? = null,
    crossinline bind: (card: V, item: T?, position: Int, cellWidthPx: Int) -> Unit,
): CardPresenter<T> =
    object : CardPresenter<T>() {
        override fun create(
            parent: ViewGroup,
            viewType: Int,
        ): BaseCardView = create(parent.context)

        override fun bind(
            card: BaseCardView,
            item: T?,
            position: Int,
            cellWidthPx: Int,
        ) = bind(card as V, item, position, cellWidthPx)

        override fun key(
            item: T?,
            position: Int,
        ): Any = key?.invoke(item, position) ?: position
    }

/**
 * What happens on a card: click and long click (OK held; open the context menu) get the card's index and its item
 * (not called for a null placeholder, as the old rows did), focus gets every card. [onKey] sees key events on a focused
 * card first (Home: the play key plays the item); return true to consume.
 */
class CardCallbacks<T : Any>(
    val onClick: ((index: Int, item: T) -> Unit)? = null,
    val onLongClick: ((index: Int, item: T) -> Unit)? = null,
    val onFocus: ((index: Int, item: T?) -> Unit)? = null,
    val onKey: ((index: Int, item: T?, event: KeyEvent) -> Boolean)? = null,
)

/**
 * The trailing "View more" card of a row: [bind] sizes it like the row's cards (call [ViewMoreCardView.bind]),
 * [onClick] opens the full list. Its index is the number of items.
 */
class ViewMoreSpec(
    val bind: (ViewMoreCardView) -> Unit,
    val onClick: () -> Unit,
    val onFocus: (() -> Unit)? = null,
)

/** A card's ViewHolder; [card] is the card view */
class CardViewHolder(
    val card: BaseCardView,
) : RecyclerView.ViewHolder(card)

/** Long adapter ids for presenter keys, stable for the adapter's life */
internal class StableIds {
    private val ids = HashMap<Any, Long>()
    private var next = 1L

    fun of(key: Any): Long = ids.getOrPut(key) { next++ }
}

/**
 * Adapter of a card row (`ItemRow`): a list of items (nulls are placeholders) and an optional trailing View more card.
 * [submit] diffs by [CardPresenter.key] with stable ids, so updates rebind cards in place and focus stays on its card.
 */
class CardRowAdapter<T : Any>(
    val presenter: CardPresenter<T>,
    var callbacks: CardCallbacks<T> = CardCallbacks(),
) : RecyclerView.Adapter<CardViewHolder>() {
    private sealed interface Entry<out T> {
        val key: Any

        class Card<T>(
            val item: T?,
            override val key: Any,
        ) : Entry<T>

        object More : Entry<Nothing> {
            override val key: Any = MoreKey
        }
    }

    private object MoreKey

    private var entries: List<Entry<T>> = emptyList()
    private var viewMore: ViewMoreSpec? = null
    private val ids = StableIds()

    /** The current items (without the View more card) */
    var items: List<T?> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    /** Replace the items (and View more card); unchanged cards keep their views and focus */
    fun submit(
        items: List<T?>,
        viewMore: ViewMoreSpec? = null,
    ) {
        val old = entries
        val new =
            buildList<Entry<T>> {
                items.forEachIndexed { index, item -> add(Entry.Card(item, presenter.key(item, index))) }
                if (viewMore != null) add(Entry.More)
            }
        this.items = items
        this.viewMore = viewMore
        entries = new
        DiffUtil
            .calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size

                    override fun getNewListSize() = new.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = old[oldItemPosition].key == new[newItemPosition].key

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ): Boolean {
                        val o = old[oldItemPosition]
                        val n = new[newItemPosition]
                        return when {
                            o is Entry.Card && n is Entry.Card -> {
                                oldItemPosition == newItemPosition &&
                                    presenter.sameContent(o.item, n.item)
                            }

                            // The View more card's size follows the row's options: rebind it on every submit
                            else -> {
                                false
                            }
                        }
                    }
                },
                false,
            ).dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = ids.of(entries[position].key)

    override fun getItemViewType(position: Int): Int =
        when (val e = entries[position]) {
            is Entry.Card -> presenter.viewType(e.item, position)
            Entry.More -> VIEW_TYPE_MORE
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): CardViewHolder {
        val card = if (viewType == VIEW_TYPE_MORE) ViewMoreCardView(parent.context) else presenter.create(parent, viewType)
        card.layoutParams = RecyclerView.LayoutParams(WRAP, WRAP)
        val holder = CardViewHolder(card)
        wire(holder, card.frame)
        return holder
    }

    private fun wire(
        holder: CardViewHolder,
        frame: View,
    ) {
        frame.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            when (val e = entries.getOrNull(pos)) {
                is Entry.Card -> {
                    e.item?.let { callbacks.onClick?.invoke(pos, it) }
                }

                Entry.More -> {
                    viewMore?.onClick?.invoke()
                }

                null -> {}
            }
        }
        frame.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            val e = entries.getOrNull(pos)
            if (e is Entry.Card) e.item?.let { callbacks.onLongClick?.invoke(pos, it) }
            // Consumed either way: a long press never also clicks (the old onLongClick = {} did the same)
            true
        }
        frame.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            val pos = holder.bindingAdapterPosition
            when (val e = entries.getOrNull(pos)) {
                is Entry.Card -> {
                    callbacks.onFocus?.invoke(pos, e.item)
                }

                Entry.More -> {
                    viewMore?.onFocus?.invoke()
                }

                null -> {}
            }
        }
        frame.setOnKeyListener { _, _, event ->
            val pos = holder.bindingAdapterPosition
            val e = entries.getOrNull(pos)
            if (e is Entry.Card) callbacks.onKey?.invoke(pos, e.item, event) ?: false else false
        }
    }

    override fun onBindViewHolder(
        holder: CardViewHolder,
        position: Int,
    ) {
        when (val e = entries[position]) {
            is Entry.Card -> presenter.bind(holder.card, e.item, position, 0)
            Entry.More -> viewMore?.bind?.invoke(holder.card as ViewMoreCardView)
        }
    }

    override fun onViewRecycled(holder: CardViewHolder) {
        if (holder.itemViewType == VIEW_TYPE_MORE) holder.card.recycle() else presenter.recycle(holder.card)
    }

    companion object {
        const val VIEW_TYPE_MORE = Int.MIN_VALUE + 1
    }
}

/**
 * Adapter of a [CardGridView]: a positional list (usually a `RequestPager`, whose `get` loads the page of a missing
 * item). Ids are positions, so [refresh] after the pager's `changes` rebinds loaded cards in place without moving
 * focus. Reading an item happens only when its card is bound, as the Compose grid read only composed items.
 */
class CardGridAdapter<T : CardGridItem>(
    val presenter: CardPresenter<T>,
    var callbacks: CardCallbacks<T> = CardCallbacks(),
) : RecyclerView.Adapter<CardViewHolder>() {
    var items: List<T?> = emptyList()
        private set
    private var count = 0

    /** Column width in px, set by the grid */
    internal var cellWidthPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                if (count > 0) notifyItemRangeChanged(0, count, REFRESH)
            }
        }

    init {
        setHasStableIds(true)
    }

    /** Show [items]; the same list instance again only refreshes (see [refresh]) */
    fun setItems(items: List<T?>) {
        if (items === this.items) {
            refresh()
            return
        }
        this.items = items
        count = items.size
        notifyDataSetChanged()
    }

    /** The list changed in place (a page loaded or its size changed): rebind the cards shown */
    fun refresh() {
        val n = items.size
        if (n != count) {
            count = n
            notifyDataSetChanged()
        } else if (n > 0) {
            notifyItemRangeChanged(0, n, REFRESH)
        }
    }

    /** The item at [position] if in range (reading a pager may start loading its page) */
    fun itemAt(position: Int): T? = if (position in 0 until count) items[position] else null

    override fun getItemCount(): Int = count

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getItemViewType(position: Int): Int = presenter.viewType(itemAt(position), position)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): CardViewHolder {
        val card = presenter.create(parent, viewType)
        card.layoutParams = RecyclerView.LayoutParams(WRAP, WRAP)
        val holder = CardViewHolder(card)
        val frame = card.frame
        frame.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            itemAt(pos)?.let { callbacks.onClick?.invoke(pos, it) }
        }
        frame.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            itemAt(pos)?.let { callbacks.onLongClick?.invoke(pos, it) }
            true
        }
        frame.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) callbacks.onFocus?.invoke(pos, itemAt(pos))
            }
        }
        frame.setOnKeyListener { _, _, event ->
            val pos = holder.bindingAdapterPosition
            callbacks.onKey?.invoke(pos, itemAt(pos), event) ?: false
        }
        return holder
    }

    override fun onBindViewHolder(
        holder: CardViewHolder,
        position: Int,
    ) {
        presenter.bind(holder.card, itemAt(position), position, cellWidthPx)
    }

    override fun onViewRecycled(holder: CardViewHolder) = presenter.recycle(holder.card)

    private companion object {
        val REFRESH = Any()
    }
}
