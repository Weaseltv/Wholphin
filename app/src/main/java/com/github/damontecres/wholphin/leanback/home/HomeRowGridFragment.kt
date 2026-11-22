package com.github.damontecres.wholphin.leanback.home

import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.viewModels
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.data.model.createStudioDestination
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.PageStyle
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.provideNeonAccent
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.CardImageScale
import com.github.damontecres.wholphin.leanback.widget.card.GenreCardView
import com.github.damontecres.wholphin.leanback.widget.card.GridCardView
import com.github.damontecres.wholphin.leanback.widget.card.StudioCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.leanback.widget.dialog.ContextMenuHelper
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.ui.components.GenreViewModel
import com.github.damontecres.wholphin.ui.components.Studio
import com.github.damontecres.wholphin.ui.components.StudioViewModel
import com.github.damontecres.wholphin.ui.detail.HomeRowGridViewModel
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.RequestPager
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Job
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/**
 * MoreHomeRow (no-Compose rewrite of ui/detail/HomeRowGrid.kt): every title of one Home row in a grid, under the row's
 * name, in the row's accent. Genre and Studio rows show the library's genre / studio tiles (4 columns, letter bar);
 * other rows a grid of posters (6 columns, 4 for wide images) with the random button above it. Opens on
 * [Destination.MoreHomeRow.initialPosition] (the first title past the Home row's last card).
 */
@dagger.hilt.android.AndroidEntryPoint
class HomeRowGridFragment : DestinationFragment() {
    private val dest: Destination.MoreHomeRow get() = requireDestination()

    private val viewModel: HomeRowGridViewModel by viewModels(
        extrasProducer = {
            defaultViewModelCreationExtras.withCreationCallback<HomeRowGridViewModel.Factory> {
                it.create(dest.title, dest.config)
            }
        },
    )
    private val errorViewModel: ErrorViewModel by viewModels()
    private val contextMenu by lazy { ContextMenuHelper(this, viewModel) { preferences } }

    private lateinit var root: LinearLayout
    private lateinit var title: GridTitleView
    private lateinit var content: FrameLayout
    private var grid: CardGridView? = null
    private var contentKind = 0
    private var changesJob: Job? = null

    /** The page's accent: the row's (`ProvideNeonSectionAccent(homeRowAccent(...))`) */
    private var style: PageStyle = PageStyle()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }
        title = GridTitleView(context)
        root.addView(
            title,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(27)
            },
        )
        content =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) },
        )
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        applyStyle()
        viewModel.navigationLibraries.collectWhileStarted { applyStyle() }
        viewModel.state.collectWhileStarted { render(it.loading) }
    }

    override fun onPageStyleChanged(style: PageStyle) {
        applyStyle()
        grid?.refresh()
    }

    override fun onBackPressed(): Boolean = grid?.onBackPressed() ?: false

    override fun onDestroyView() {
        grid = null
        contentKind = 0
        super.onDestroyView()
    }

    private fun applyStyle() {
        val accent = homeRowAccentFor(dest.config, viewModel.navigationLibraries.value.allLibraries, pageStyle.sectionAccent)
        style = pageStyle.copy(sectionAccent = accent)
        root.provideNeonAccent(accent)
        title.bind(dest.title.getString(requireContext()), accent)
    }

    private fun render(state: HomeRowLoadingState) {
        when (state) {
            is HomeRowLoadingState.Error -> {
                show(KIND_ERROR) { ErrorView(requireContext(), state.message, state.exception, onSendLogs = errorViewModel::sendLogs) }
            }

            is HomeRowLoadingState.Loading, is HomeRowLoadingState.Pending -> {
                show(KIND_LOADING) { LoadingPageView(requireContext()) }
            }

            is HomeRowLoadingState.Success -> {
                when (val config = dest.config) {
                    is HomeRowConfig.Genres -> show(KIND_GENRES) { genreGrid(config.parentId) }
                    is HomeRowConfig.Studios -> show(KIND_STUDIOS) { studioGrid(config.parentId) }
                    else -> showItems(state)
                }
            }
        }
    }

    private fun show(
        kind: Int,
        create: () -> View,
    ) {
        if (contentKind == kind) return
        contentKind = kind
        grid = null
        changesJob?.cancel()
        content.removeAllViews()
        val v = create()
        content.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (kind == KIND_ERROR) requestInitialFocus(v)
    }

    /** Any other row: the random button at the top right, then the posters */
    private fun showItems(state: HomeRowLoadingState.Success) {
        if (contentKind != KIND_ITEMS) {
            show(KIND_ITEMS) {
                val context = requireContext()
                val column =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        clipChildren = false
                        clipToPadding = false
                    }
                val buttons =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.END
                        clipChildren = false
                        setPadding(0, 0, context.dp(16), 0)
                    }
                buttons.addView(
                    faButton(context, R.string.sort_by_random, R.string.fa_dice) { viewModel.onClickRandom() }.also {
                        it.isActionEnabled = state.items.isNotEmpty()
                    },
                )
                column.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                val g = CardGridView(context)
                grid = g
                column.addView(
                    g,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = context.dp(8) },
                )
                column
            }
        }
        val g = grid ?: return
        val options = dest.config.viewOptions
        val ratio = options.aspectRatio.ratio
        g.setCards(
            items = state.items,
            presenter =
                cardPresenter<BaseItem, GridCardView>(::GridCardView) { card, item, _, width ->
                    card.bind(
                        item,
                        style,
                        width,
                        imageAspectRatio = ratio,
                        imageScale = CardImageScale.from(options.contentScale),
                        imageType = options.imageType.imageType,
                        showTitle = true,
                    )
                },
            callbacks =
                CardGridCallbacks(
                    onClick = { index, item -> viewModel.navigateTo(item.destination(index)) },
                    onLongClick = { index, item -> contextMenu.showContextMenu(index, item, g) },
                ),
            config =
                CardGridConfig(
                    columns = if (ratio > 1f) 4 else 6,
                    spacingDp = options.spacing.toFloat(),
                    showLetterButtons = false,
                    initialPosition = dest.initialPosition,
                ),
            pageStyle = style,
        )
        (state.items as? RequestPager<*>)?.let { pager ->
            changesJob?.cancel()
            changesJob = g.observeChanges(viewLifecycleOwner, pager.changes)
        }
        requestInitialFocus(g.grid)
    }

    /** `GenreCardGrid`: the library's genres, 4 columns of 16:9 tiles with the letter bar */
    private fun genreGrid(parentId: UUID): View {
        val vm =
            androidx.lifecycle.ViewModelProvider(
                viewModelStore,
                defaultViewModelProviderFactory,
                defaultViewModelCreationExtras.withCreationCallback<GenreViewModel.Factory> { it.create(parentId, null) },
            )["genres", GenreViewModel::class.java]
        vm.init(tileWidthPx(resources))
        return TileGrid(requireContext()).also { tiles ->
            vm.state.collectWhileStarted { st ->
                tiles.show(st.item) {
                    val item = (st.item as? DataLoadingState.Success)?.data ?: return@show
                    tiles.grid.setCards(
                        items = st.genres,
                        presenter =
                            cardPresenter<Genre, GenreCardView>(::GenreCardView) { card, genre, _, width ->
                                card.bind(genre?.id, genre?.name, genre?.imageUrl, style, widthPx = width)
                            },
                        callbacks =
                            CardGridCallbacks(
                                onClick = { _, genre ->
                                    vm.navigationManager.navigateTo(
                                        createGenreDestination(
                                            genreId = genre.id,
                                            genreName = genre.name,
                                            parentId = parentId,
                                            parentName = item.title,
                                            includeItemTypes = null,
                                            collectionType = CollectionType.UNKNOWN,
                                        ),
                                    )
                                },
                                letterPosition = { vm.positionOfLetter(it) },
                            ),
                        config = CardGridConfig(columns = 4, spacingDp = 16f, showLetterButtons = true, initialPosition = dest.initialPosition),
                        pageStyle = style,
                    )
                    requestInitialFocus(tiles.grid.grid)
                }
            }
            grid = tiles.grid
        }
    }

    /** `StudioCardGrid`: the library's studios, 4 columns of 16:9 tiles with the letter bar */
    private fun studioGrid(parentId: UUID): View {
        val vm =
            androidx.lifecycle.ViewModelProvider(
                viewModelStore,
                defaultViewModelProviderFactory,
                defaultViewModelCreationExtras.withCreationCallback<StudioViewModel.Factory> { it.create(parentId, null) },
            )["studios", StudioViewModel::class.java]
        vm.init(tileWidthPx(resources))
        return TileGrid(requireContext()).also { tiles ->
            vm.state.collectWhileStarted { st ->
                tiles.show(st.item) {
                    val item = (st.item as? DataLoadingState.Success)?.data ?: return@show
                    tiles.grid.setCards(
                        items = st.studios,
                        presenter =
                            cardPresenter<Studio, StudioCardView>(::StudioCardView) { card, studio, _, width ->
                                card.bind(studio?.id, studio?.name, studio?.imageUrl, style, widthPx = width)
                            },
                        callbacks =
                            CardGridCallbacks(
                                onClick = { _, studio ->
                                    vm.navigationManager.navigateTo(
                                        createStudioDestination(
                                            studioId = studio.id,
                                            name = studio.name,
                                            parentId = parentId,
                                            parentName = item.title,
                                            includeItemTypes = null,
                                        ),
                                    )
                                },
                                letterPosition = { vm.positionOfLetter(it) },
                            ),
                        config = CardGridConfig(columns = 4, spacingDp = 16f, showLetterButtons = true, initialPosition = dest.initialPosition),
                        pageStyle = style,
                    )
                    requestInitialFocus(tiles.grid.grid)
                }
            }
            grid = tiles.grid
        }
    }

    /**
     * The genre / studio grid's loading, error and loaded states (each tile grid had its own loading page and error).
     * The grid is created once and kept.
     */
    private inner class TileGrid(
        context: android.content.Context,
    ) : FrameLayout(context) {
        val grid = CardGridView(context)
        private var state: View? = null
        private var kind = 0

        init {
            clipChildren = false
            clipToPadding = false
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            grid.visibility = GONE
        }

        fun <T> show(
            loading: DataLoadingState<T>,
            onSuccess: () -> Unit,
        ) {
            when (loading) {
                DataLoadingState.Pending, DataLoadingState.Loading -> {
                    swap(1) { LoadingPageView(context) }
                }

                is DataLoadingState.Error -> {
                    swap(2) { ErrorView(context, loading, onSendLogs = errorViewModel::sendLogs) }?.let { requestInitialFocus(it) }
                }

                is DataLoadingState.Success -> {
                    if (kind != 3) {
                        kind = 3
                        state?.let { removeView(it) }
                        state = null
                        grid.visibility = VISIBLE
                    }
                    onSuccess()
                }
            }
        }

        private fun swap(
            newKind: Int,
            create: () -> View,
        ): View? {
            if (kind == newKind) return null
            kind = newKind
            state?.let { removeView(it) }
            grid.visibility = GONE
            return create().also {
                state = it
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
    }

    private companion object {
        const val KIND_LOADING = 1
        const val KIND_ERROR = 2
        const val KIND_ITEMS = 3
        const val KIND_GENRES = 4
        const val KIND_STUDIOS = 5

        /**
         * The tiles' image width: 16dp on either side and 16dp between 4 tiles of the screen width (not exact, as
         * before: it ignores the side menu and letters, but is close and quick)
         */
        fun tileWidthPx(resources: Resources): Int {
            val metrics = resources.displayMetrics
            val screenDp = metrics.widthPixels / metrics.density
            return ((screenDp - (2 * 16 + 3 * 16)) / 4 * metrics.density).toInt()
        }
    }
}
