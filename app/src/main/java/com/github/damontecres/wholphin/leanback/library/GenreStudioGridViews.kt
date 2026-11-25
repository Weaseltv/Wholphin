package com.github.damontecres.wholphin.leanback.library

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.createGenreDestination
import com.github.damontecres.wholphin.data.model.createStudioDestination
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.widget.card.CardGridCallbacks
import com.github.damontecres.wholphin.leanback.widget.card.CardGridConfig
import com.github.damontecres.wholphin.leanback.widget.card.CardGridView
import com.github.damontecres.wholphin.leanback.widget.card.GenreCardView
import com.github.damontecres.wholphin.leanback.widget.card.StudioCardView
import com.github.damontecres.wholphin.leanback.widget.card.cardPresenter
import com.github.damontecres.wholphin.leanback.widget.card.jumpToPosition
import com.github.damontecres.wholphin.leanback.widget.control.ErrorView
import com.github.damontecres.wholphin.leanback.widget.control.LoadingPageView
import com.github.damontecres.wholphin.ui.components.ErrorViewModel
import com.github.damontecres.wholphin.ui.components.Genre
import com.github.damontecres.wholphin.ui.components.GenreGridState
import com.github.damontecres.wholphin.ui.components.GenreViewModel
import com.github.damontecres.wholphin.ui.components.Studio
import com.github.damontecres.wholphin.ui.components.StudioGridState
import com.github.damontecres.wholphin.ui.components.StudioViewModel
import com.github.damontecres.wholphin.util.DataLoadingState
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/**
 * The card width the Genres and Studios tabs ask the server images for: the screen width less 16dp on both sides and
 * three 16dp gaps, over four columns (not exact, but close and quick, as before).
 */
internal fun genreCardWidthPx(context: Context): Int {
    val screenWidthDp = context.resources.configuration.screenWidthDp
    return context.dp((screenWidthDp - (2 * 16 + 3 * 16)) / 4f)
}

/**
 * Shared shell of the Genres and Studios tabs (`GenreCardGrid` / `StudioCardGrid`): a focusable loading picture or error
 * while loading, then a four-column grid of cards (16dp apart, letter bar), which takes focus when it appears.
 */
@SuppressLint("ViewConstructor")
abstract class NameCardGridContentView(
    context: Context,
    protected val fragment: DestinationFragment,
    private val errorViewModel: ErrorViewModel,
) : FrameLayout(context),
    LibraryTabContent {
    override val view: View get() = this
    protected val grid = CardGridView(context)
    private var overlay: View? = null
    private var kind = -1

    init {
        clipChildren = false
        clipToPadding = false
        addView(grid, LayoutParams(MATCH, MATCH))
        grid.visibility = GONE
    }

    protected fun showState(
        item: DataLoadingState<BaseItem>,
        bindGrid: (BaseItem) -> Unit,
    ) {
        when (item) {
            DataLoadingState.Pending, DataLoadingState.Loading -> {
                if (kind != KIND_LOADING) {
                    kind = KIND_LOADING
                    showOverlay(LoadingPageView(context))
                }
            }

            is DataLoadingState.Error -> {
                if (kind != KIND_ERROR) {
                    kind = KIND_ERROR
                    showOverlay(ErrorView(context, item, errorViewModel::sendLogs))
                    if (fragment.isTopScreen && isShown) overlay?.requestFocus()
                }
            }

            is DataLoadingState.Success -> {
                val entered = kind != KIND_SUCCESS
                kind = KIND_SUCCESS
                grid.visibility = VISIBLE
                bindGrid(item.data)
                // LaunchedEffect(Unit) { gridFocusRequester.tryRequestFocus() }, before the loading picture goes
                if (entered && fragment.isTopScreen && isShown) grid.grid.requestFocus()
                if (entered) showOverlay(null)
            }
        }
    }

    private fun showOverlay(view: View?) {
        val old = overlay
        overlay = view
        if (view != null) {
            addView(view, LayoutParams(MATCH, MATCH))
            grid.visibility = GONE
        }
        old?.let { removeView(it) }
    }

    override fun onShown(firstTime: Boolean) {
        // The old tab content started over at its first card when selected again (initialPosition = 0)
        if (!firstTime && kind == KIND_SUCCESS && fragment.isTopScreen) {
            grid.grid.jumpToPosition(0)
            grid.grid.requestFocus()
        }
    }

    override fun onBackPressed(): Boolean = grid.onBackPressed()

    override fun onPageStyleChanged() = grid.refresh()

    protected companion object {
        const val KIND_LOADING = 0
        const val KIND_ERROR = 1
        const val KIND_SUCCESS = 2

        fun config() = CardGridConfig(columns = 4, spacingDp = 16f, showLetterButtons = true, initialPosition = 0)
    }
}

/** The Genres tab: genre cards (a random backdrop of each genre under its name); OK opens the genre's page */
@SuppressLint("ViewConstructor")
class GenreGridContentView(
    context: Context,
    fragment: DestinationFragment,
    errorViewModel: ErrorViewModel,
    private val viewModel: GenreViewModel,
    private val itemId: UUID,
    private val includeItemTypes: List<BaseItemKind>?,
    private val collectionType: CollectionType,
) : NameCardGridContentView(context, fragment, errorViewModel) {
    private val presenter =
        cardPresenter<Genre, GenreCardView>(::GenreCardView) { card, genre, _, width ->
            card.bind(genre?.id, genre?.name, genre?.imageUrl, fragment.pageStyle, widthPx = width)
        }

    fun bind(state: GenreGridState) {
        showState(state.item) { item ->
            grid.setCards(
                items = state.genres,
                presenter = presenter,
                callbacks =
                    CardGridCallbacks(
                        onClick = { _, genre ->
                            viewModel.navigationManager.navigateTo(
                                createGenreDestination(
                                    genreId = genre.id,
                                    genreName = genre.name,
                                    parentId = itemId,
                                    parentName = item.title,
                                    includeItemTypes = includeItemTypes,
                                    collectionType = collectionType,
                                ),
                            )
                        },
                        letterPosition = { viewModel.positionOfLetter(it) },
                    ),
                config = config(),
                pageStyle = fragment.pageStyle,
            )
        }
    }

    override fun onShown(firstTime: Boolean) {
        // OneTimeLaunchedEffect { viewModel.init(cardWidthPx) }: keeps what is loaded for this width
        viewModel.init(genreCardWidthPx(context))
        super.onShown(firstTime)
    }
}

/** The Studios tab: studio cards (the studio's thumb); OK opens the studio's page */
@SuppressLint("ViewConstructor")
class StudioGridContentView(
    context: Context,
    fragment: DestinationFragment,
    errorViewModel: ErrorViewModel,
    private val viewModel: StudioViewModel,
    private val itemId: UUID,
    private val includeItemTypes: List<BaseItemKind>?,
) : NameCardGridContentView(context, fragment, errorViewModel) {
    private val presenter =
        cardPresenter<Studio, StudioCardView>(::StudioCardView) { card, studio, _, width ->
            card.bind(studio?.id, studio?.name, studio?.imageUrl, fragment.pageStyle, widthPx = width)
        }

    fun bind(state: StudioGridState) {
        showState(state.item) { item ->
            grid.setCards(
                items = state.studios,
                presenter = presenter,
                callbacks =
                    CardGridCallbacks(
                        onClick = { _, studio ->
                            viewModel.navigationManager.navigateTo(
                                createStudioDestination(
                                    studioId = studio.id,
                                    name = studio.name,
                                    parentId = itemId,
                                    parentName = item.title,
                                    includeItemTypes = includeItemTypes,
                                ),
                            )
                        },
                        letterPosition = { viewModel.positionOfLetter(it) },
                    ),
                config = config(),
                pageStyle = fragment.pageStyle,
            )
        }
    }

    override fun onShown(firstTime: Boolean) {
        viewModel.init(genreCardWidthPx(context))
        super.onShown(firstTime)
    }
}
