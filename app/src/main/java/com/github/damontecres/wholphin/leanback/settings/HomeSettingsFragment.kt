package com.github.damontecres.wholphin.leanback.settings

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.asDrawable
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.data.BoxSetSortOptions
import com.github.damontecres.wholphin.ui.data.SortAndDirection
import com.github.damontecres.wholphin.ui.data.getStringRes
import com.github.damontecres.wholphin.ui.detail.possibleFavoriteTypes
import com.github.damontecres.wholphin.ui.formatDateTime
import com.github.damontecres.wholphin.ui.formatTypeName
import com.github.damontecres.wholphin.ui.main.settings.HomeRowConfigAction
import com.github.damontecres.wholphin.ui.main.settings.HomeRowPresets
import com.github.damontecres.wholphin.ui.main.settings.HomeSettingsViewModel
import com.github.damontecres.wholphin.ui.main.settings.LibraryRowType
import com.github.damontecres.wholphin.ui.main.settings.MetaRowType
import com.github.damontecres.wholphin.ui.main.settings.MoveDirection
import com.github.damontecres.wholphin.ui.main.settings.Options
import com.github.damontecres.wholphin.ui.main.settings.RemovedNextUpContentViewModel
import com.github.damontecres.wholphin.ui.main.settings.getSupportedRowTypes
import com.github.damontecres.wholphin.ui.search.SearchForViewModel
import com.github.damontecres.wholphin.ui.search.SearchResult
import com.github.damontecres.wholphin.util.DataLoadingState
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Customize Home (`Destination.HomeSettings`, the old `HomeSettingsPage`) on Leanback Preference: a 360dp settings
 * panel on the left with its own levels, and the live Home preview beside it.
 *
 * Levels: the row list (add row, settings, display presets, then every row with ▲ ▼ and delete), add row (the
 * continue watching / next up rows, each library, Watchlist, a collection or a playlist), a library's row types,
 * Watchlist types, a collection/playlist search, a row's display options (with combine/split and the collection sort),
 * the global settings (items per row, rewatching, next up days, click behaviour, card size, removed Next Up, server
 * save/load, reset) and the presets. Changes save when the page closes (HomeSettingsViewModel).
 */
@AndroidEntryPoint
class HomeSettingsFragment : SettingsPageFragment() {
    private val viewModel: HomeSettingsViewModel by viewModels()

    @Inject lateinit var imageUrlService: ImageUrlService

    private var appPreferences: AppPreferences? = null
    private var preview: HomeSettingsPreview? = null
    private val rowFocus = ReorderFocus()

    /** A row to select after the next state update (a moved row keeps the focus) */
    private var pendingSelect: String? = null

    override val rootScreenKey: String = ROWS
    override val panelAtEnd: Boolean = false
    override val panelWidthDp: Int = 360
    override val panelStripe: Boolean = false
    override val plainHeadings: Boolean = true
    override val listHorizontalPaddingDp: Int = 16

    override fun pageBackground(): Int = Color.TRANSPARENT

    override fun panelBackground(): Int = SettingsLook.tonalSurface(1f, accent)

    private val state get() = viewModel.state.value

    override fun screenTitle(key: String): CharSequence =
        when {
            key == ROWS -> getString(R.string.customize_home)
            key == ADD -> getString(R.string.add_row)
            key == FAVORITES -> getString(R.string.add_row_for, getString(R.string.favorites))
            key == GLOBAL -> getString(R.string.settings)
            key == PRESETS -> getString(R.string.display_presets)
            key == REMOVED -> REMOVED_TITLE
            key.startsWith(LIBRARY) -> getString(R.string.add_row_for, libraryFor(key)?.name.orEmpty())
            key.startsWith(ROW) -> rowFor(key)?.title?.getString(requireContext()).orEmpty()
            key.startsWith(SORT) -> getString(R.string.sort)
            key.startsWith(SEARCH) -> searchTitle(searchKind(key))
            else -> ""
        }

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        when {
            key == ROWS -> buildRows(fragment, screen)
            key == ADD -> buildAddRow(fragment, screen)
            key == FAVORITES -> buildFavorites(fragment, screen)
            key == GLOBAL -> buildGlobal(fragment, screen)
            key == PRESETS -> buildPresets(fragment, screen)
            key == REMOVED -> buildRemoved(fragment, screen)
            key.startsWith(LIBRARY) -> buildLibrary(fragment, screen, key)
            key.startsWith(ROW) -> buildRowSettings(fragment, screen, key)
            key.startsWith(SORT) -> buildSort(fragment, screen, key)
            key.startsWith(SEARCH) -> buildSearch(fragment, screen, searchKind(key))
        }
    }

    // ---- helpers ----

    private fun click(
        fragment: SettingsListFragment,
        key: String,
        title: CharSequence,
        summary: CharSequence? = null,
        onClick: () -> Unit,
    ) = SettingsClickPreference(fragment.preferenceManager.context).apply {
        this.key = key
        this.accent = this@HomeSettingsFragment.accent
        this.title = title
        this.summary = summary
        this.onClick = onClick
    }

    private fun heading(
        fragment: SettingsListFragment,
        key: String,
        title: CharSequence,
        parent: PreferenceGroup,
    ) {
        parent.addPreference(
            SettingsCategory(fragment.preferenceManager.context).apply {
                this.key = key
                this.title = title
                role = TextRole.TITLE_LARGE
                large = false
            },
        )
        parent.addPreference(SettingsDividerPreference(fragment.preferenceManager.context).apply { this.key = "$key.divider" })
    }

    private fun glyph(
        glyph: Int,
        title: Int,
    ): CharSequence = SettingsLook.glyphTitle(requireContext(), getString(glyph), getString(title))

    /**
     * Adds a row (the old `addRow`): back to the row list, waits for the row to load, then scrolls the preview to the
     * end where it was added.
     */
    private fun addRow(
        scrollToBottom: Boolean = true,
        add: () -> Job,
    ) {
        while (childFragmentManager.backStackEntryCount > 0 && !childFragmentManager.isStateSaved) {
            childFragmentManager.popBackStackImmediate()
        }
        pageScope.launch(ExceptionHandler(autoToast = true)) {
            add().join()
            if (scrollToBottom) preview?.scrollToRow(state.rows.lastIndex)
        }
    }

    private fun confirm(
        body: Int,
        onConfirm: () -> Unit,
    ) {
        showConfirmDialog(requireContext(), accent, getString(R.string.confirm), getString(body), onConfirm = onConfirm)
    }

    // ---- the row list ----

    private fun buildRows(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        screen.addPreference(click(fragment, "add", glyph(R.string.lb_settings_fa_plus, R.string.add_row)) { openScreen(ADD) })
        screen.addPreference(click(fragment, "global", glyph(R.string.lb_settings_fa_gear, R.string.settings)) { openScreen(GLOBAL) })
        screen.addPreference(
            click(
                fragment,
                "presets",
                glyph(R.string.fa_sliders, R.string.display_presets),
                getString(R.string.display_presets_description),
            ) { openScreen(PRESETS) },
        )
        val rowsHeading =
            SettingsCategory(fragment.preferenceManager.context).apply {
                key = "rows.heading"
                role = TextRole.TITLE_LARGE
                large = false
            }
        screen.addPreference(rowsHeading)
        screen.addPreference(SettingsDividerPreference(fragment.preferenceManager.context).apply { key = "rows.divider" })
        val ctx = fragment.preferenceManager.context
        val rows = mutableMapOf<Int, SettingsReorderPreference>()
        fragment.onRefresh {
            val list = state.rows
            rowsHeading.title = getString(R.string.home_rows) + " (${list.size})"
            val ids = list.map { it.id }.toSet()
            rows.keys.filter { it !in ids }.forEach { id -> rows.remove(id)?.let(screen::removePreference) }
            list.forEachIndexed { index, row ->
                val pref =
                    rows.getOrPut(row.id) {
                        SettingsReorderPreference(ctx, rowFocus).also {
                            it.key = "row${row.id}"
                            it.accent = accent
                            screen.addPreference(it)
                        }
                    }
                pref.order = ROW_ORDER_START + index
                pref.title = row.title.getString(ctx)
                pref.onMainClick = { openRow(row.id) }
                pref.buttons =
                    listOf(
                        ReorderButton(getString(R.string.fa_caret_up), index > 0, "up") { moveRow(row.id, MoveDirection.UP) },
                        ReorderButton(getString(R.string.fa_caret_down), index != list.lastIndex, "down") {
                            moveRow(row.id, MoveDirection.DOWN)
                        },
                        ReorderButton(getString(R.string.lb_settings_fa_trash), list.size > 1, "delete") {
                            val at = state.rows.indexOfFirst { it.id == row.id }
                            if (at >= 0) viewModel.deleteRow(at)
                        },
                    )
            }
            pendingSelect?.let { key ->
                pendingSelect = null
                fragment.view?.post { fragment.selectPreference(key) }
            }
        }
    }

    private fun moveRow(
        id: Int,
        direction: MoveDirection,
    ) {
        val index = state.rows.indexOfFirst { it.id == id }
        if (index < 0) return
        pendingSelect = "row$id"
        viewModel.moveRow(direction, index)
    }

    /** Opens a row's options, scrolls the preview to it and shows its first item as the backdrop */
    private fun openRow(id: Int) {
        val index = state.rows.indexOfFirst { it.id == id }
        if (index < 0) return
        openScreen("$ROW$id")
        Timber.v("Scroll to $index")
        preview?.scrollToRow(index)
        (state.rowData.getOrNull(index) as? HomeRowLoadingState.Success)
            ?.items
            ?.firstOrNull()
            ?.let(viewModel::updateBackdrop)
    }

    // ---- adding rows ----

    private fun buildAddRow(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        listOf(MetaRowType.CONTINUE_WATCHING, MetaRowType.NEXT_UP, MetaRowType.COMBINED_CONTINUE_WATCHING).forEach { type ->
            screen.addPreference(click(fragment, "meta.${type.name}", getString(type.stringId)) { onMeta(type) })
        }
        heading(fragment, "library.heading", getString(R.string.library), screen)
        val libraries = mutableMapOf<UUID, Preference>()
        fragment.onRefresh {
            state.libraries.forEachIndexed { index, library ->
                libraries.getOrPut(library.itemId) {
                    click(fragment, "library.${library.itemId}", library.name) { openScreen("$LIBRARY${library.itemId}") }.also {
                        it.order = LIBRARY_ORDER_START + index
                        screen.addPreference(it)
                    }
                }
            }
        }
        val more =
            SettingsCategory(fragment.preferenceManager.context).apply {
                key = "more.heading"
                title = getString(R.string.more)
                role = TextRole.TITLE_LARGE
                large = false
                order = MORE_ORDER_START
            }
        screen.addPreference(more)
        screen.addPreference(
            SettingsDividerPreference(fragment.preferenceManager.context).apply {
                key = "more.divider"
                order = MORE_ORDER_START + 1
            },
        )
        listOf(MetaRowType.FAVORITES, MetaRowType.COLLECTION, MetaRowType.PLAYLIST).forEachIndexed { index, type ->
            screen.addPreference(
                click(fragment, "meta.${type.name}", getString(type.stringId)) { onMeta(type) }.apply {
                    order = MORE_ORDER_START + 2 + index
                },
            )
        }
    }

    private fun onMeta(type: MetaRowType) {
        when (type) {
            MetaRowType.CONTINUE_WATCHING, MetaRowType.NEXT_UP, MetaRowType.COMBINED_CONTINUE_WATCHING -> addRow { viewModel.addRow(type) }
            MetaRowType.FAVORITES -> openScreen(FAVORITES)
            MetaRowType.COLLECTION -> openScreen("$SEARCH${BaseItemKind.BOX_SET.name}")
            MetaRowType.PLAYLIST -> openScreen("$SEARCH${BaseItemKind.PLAYLIST.name}")
            // Discover rows are not offered yet (the old page had them switched off)
            MetaRowType.DISCOVER -> {}
        }
    }

    private fun libraryFor(key: String) =
        key.removePrefix(LIBRARY).let { id -> state.libraries.firstOrNull { it.itemId.toString() == id } }

    private fun buildLibrary(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
        key: String,
    ) {
        val library = libraryFor(key) ?: return
        getSupportedRowTypes(library).forEach { type ->
            screen.addPreference(
                click(fragment, "type.${type.name}", getString(type.stringId)) {
                    when (type) {
                        LibraryRowType.COLLECTION -> openScreen("$SEARCH${BaseItemKind.BOX_SET.name}")
                        LibraryRowType.PLAYLIST -> openScreen("$SEARCH${BaseItemKind.PLAYLIST.name}")
                        else -> addRow { viewModel.addRow(library, type) }
                    }
                },
            )
        }
    }

    private fun buildFavorites(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        state.libraries.possibleFavoriteTypes(true).forEach { type ->
            screen.addPreference(
                click(fragment, "favorite.${type.name}", getString(formatTypeName(type))) {
                    addRow { viewModel.addFavoriteRow(type) }
                },
            )
        }
    }

    // ---- collection / playlist search (the old SearchForDialog) ----

    private fun searchKind(key: String): BaseItemKind =
        runCatching { BaseItemKind.valueOf(key.removePrefix(SEARCH)) }.getOrDefault(BaseItemKind.BOX_SET)

    private fun searchTitle(kind: BaseItemKind): String {
        val what =
            when (kind) {
                BaseItemKind.BOX_SET -> getString(R.string.collections)
                BaseItemKind.PLAYLIST -> getString(R.string.playlists)
                else -> ""
            }
        return getString(R.string.search_for, what)
    }

    private fun searchViewModel(kind: BaseItemKind): SearchForViewModel =
        ViewModelProvider(
            viewModelStore,
            defaultViewModelProviderFactory,
            defaultViewModelCreationExtras.withCreationCallback<SearchForViewModel.Factory> { it.create(kind) },
        )["searchFor:${kind.name}", SearchForViewModel::class.java]

    /**
     * A search field, the results and suggestions (recently added); OK on one adds a row for it. Typing searches after
     * 750ms, the keyboard's search action at once.
     */
    private fun buildSearch(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
        kind: BaseItemKind,
    ) {
        val vm = searchViewModel(kind)
        val ctx = fragment.preferenceManager.context
        var searchJob: Job? = null
        val search =
            SettingsSearchPreference(ctx).apply {
                key = "search"
                accent = this@HomeSettingsFragment.accent
            }
        screen.addPreference(search)
        search.onQuery = { query ->
            searchJob?.cancel()
            searchJob =
                fragment.lifecycleScope.launch {
                    delay(SEARCH_DELAY_MS)
                    vm.search(kind, query)
                }
        }
        search.onSearch = {
            searchJob?.cancel()
            vm.search(kind, search.query)
        }

        fun section(
            name: String,
            title: Int,
            result: () -> SearchResult,
        ) {
            val category =
                SettingsCategory(ctx).apply {
                    key = "$name.heading"
                    this.title = getString(title)
                    role = TextRole.TITLE_LARGE
                    large = false
                }
            screen.addPreference(category)
            val message =
                SettingsInfoPreference(ctx).apply {
                    key = "$name.message"
                    accent = this@HomeSettingsFragment.accent
                }
            category.addPreference(message)
            val rows = mutableListOf<SettingsClickPreference>()
            fragment.onRefresh {
                val items =
                    when (val r = result()) {
                        is SearchResult.Success -> r.items.filterNotNull()
                        else -> emptyList()
                    }
                message.title =
                    when (val r = result()) {
                        is SearchResult.Error -> "Error: ${r.ex.localizedMessage}"
                        SearchResult.NoQuery -> getString(R.string.no_results)
                        SearchResult.Searching -> getString(R.string.searching)
                        is SearchResult.Success -> if (items.isEmpty()) getString(R.string.no_results) else null
                        else -> "Not supported"
                    }
                message.isVisible = message.title != null
                while (rows.size < items.size) {
                    rows +=
                        SettingsClickPreference(ctx).also {
                            it.key = "$name.${rows.size}"
                            it.accent = accent
                            category.addPreference(it)
                        }
                }
                rows.forEachIndexed { index, row ->
                    val item = items.getOrNull(index)
                    row.isVisible = item != null
                    if (item != null) {
                        row.title = item.name
                        row.onClick = { addRow { viewModel.addRow(kind, item) } }
                        loadIcon(row, imageUrlService.getItemImageUrl(item, ImageType.PRIMARY))
                    }
                }
            }
        }
        section("results", R.string.results) { vm.state.value.results }
        screen.addPreference(SettingsDividerPreference(ctx).apply { key = "search.divider" })
        section("recent", R.string.suggestions) { vm.state.value.recent }
        fragment.lifecycleScope.launch { vm.state.collect { fragment.refresh() } }
    }

    /** Loads a poster into a row's icon (48dp tall) */
    private fun loadIcon(
        row: Preference,
        url: String?,
    ) {
        if (url == null) {
            row.icon = null
            return
        }
        val ctx = requireContext()
        val size = ctx.dp(48)
        val request =
            ImageRequest
                .Builder(ctx)
                .data(url)
                .size(size * 2, size)
                .target(onSuccess = { image -> row.icon = image.asDrawable(ctx.resources) })
                .build()
        SingletonImageLoader.get(ctx).enqueue(request)
    }

    // ---- a row's options (the old HomeRowSettings) ----

    private fun rowFor(key: String) = key.removePrefix(ROW).toIntOrNull()?.let { id -> state.rows.firstOrNull { it.id == id } }

    private fun buildRowSettings(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
        key: String,
    ) {
        val rowId = key.removePrefix(ROW).toIntOrNull() ?: return
        val initial = state.rows.firstOrNull { it.id == rowId } ?: return
        val options =
            when (initial.config) {
                is HomeRowConfig.ContinueWatching, is HomeRowConfig.ContinueWatchingCombined -> Options.OPTIONS_EPISODES
                is HomeRowConfig.Genres -> Options.GENRE_OPTIONS
                else -> Options.OPTIONS
            }
        val defaultViewOptions =
            when (initial.config) {
                is HomeRowConfig.Genres -> HomeRowViewOptions.genreDefault
                else -> HomeRowViewOptions()
            }

        fun current() = state.rows.firstOrNull { it.id == rowId }
        val binder =
            AppPreferenceBinder<HomeRowViewOptions>(
                fragment = fragment,
                accent = accent,
                current = { current()?.config?.viewOptions },
                update = { transform -> current()?.let { viewModel.updateViewOptions(rowId, transform(it.config.viewOptions)) } },
                navigate = {},
                onClickable = { pref ->
                    val viewOptions = current()?.config?.viewOptions ?: return@AppPreferenceBinder
                    when (pref) {
                        Options.ViewOptionsReset -> {
                            viewModel.updateViewOptions(rowId, defaultViewOptions)
                        }

                        Options.ViewOptionsApplyAll -> {
                            viewModel.updateViewOptionsForAll(viewOptions)
                        }

                        Options.ViewOptionsUseThumb -> {
                            viewModel.updateViewOptions(
                                rowId,
                                viewOptions.copy(
                                    heightDp = Options.HEIGHT_EPISODE,
                                    spacing = 20,
                                    imageType = ViewOptionImageType.THUMB,
                                    aspectRatio = AspectRatio.WIDE,
                                    contentScale = PrefContentScale.FIT,
                                    episodeImageType = ViewOptionImageType.THUMB,
                                    episodeAspectRatio = AspectRatio.WIDE,
                                    episodeContentScale = PrefContentScale.FIT,
                                ),
                            )
                        }
                    }
                },
                largeCategories = false,
                showCategories = { it > 1 },
            )
        binder.addGroups(screen, options)
        // The row's own actions after its display options
        when (val config = initial.config) {
            is HomeRowConfig.ContinueWatchingCombined -> {
                screen.addPreference(
                    click(fragment, "split", getString(R.string.split_into_separate_rows)) {
                        current()?.let { viewModel.onConfigAction(it, HomeRowConfigAction.Split) }
                        closePanel()
                    },
                )
            }

            is HomeRowConfig.ContinueWatching, is HomeRowConfig.NextUp -> {
                screen.addPreference(
                    click(fragment, "combine", getString(R.string.combine_continue_next)) {
                        current()?.let { viewModel.onConfigAction(it, HomeRowConfigAction.Combine) }
                        closePanel()
                    },
                )
            }

            is HomeRowConfig.ByParent -> {
                val sort = click(fragment, "sort", getString(R.string.sort)) { openScreen("$SORT$rowId") }
                screen.addPreference(sort)
                fragment.onRefresh {
                    val byParent = current()?.config as? HomeRowConfig.ByParent ?: config
                    sort.summary = sortText(byParent.sort ?: SortAndDirection(ItemSortBy.DEFAULT, SortOrder.ASCENDING))
                }
            }

            else -> {}
        }
        fragment.onRefresh {
            // The row was removed (eg combined into another): leave its options
            if (current() == null && fragment.isResumed && topList === fragment) closePanel()
        }
    }

    /** "▲ Name" for a sort (the old `toAnnotatedString`) */
    private fun sortText(sort: SortAndDirection): CharSequence =
        SettingsLook.glyphTitle(
            requireContext(),
            getString(if (sort.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down),
            getString(getStringRes(sort.sort)),
        )

    /** The collection sort choice (the old `ByParentSortDialogContent`): the current one shows its direction */
    private fun buildSort(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
        key: String,
    ) {
        val rowId = key.removePrefix(SORT).toIntOrNull() ?: return
        val ctx = fragment.preferenceManager.context
        val rows =
            BoxSetSortOptions.map { sort ->
                click(fragment, "sort.${sort.name}", getString(getStringRes(sort))) {
                    val row = state.rows.firstOrNull { it.id == rowId } ?: return@click
                    val config = row.config as? HomeRowConfig.ByParent ?: return@click
                    val newSort =
                        if (config.sort?.sort == sort) {
                            config.sort.flip()
                        } else if (sort == ItemSortBy.DEFAULT) {
                            null
                        } else {
                            SortAndDirection(sort, SortOrder.ASCENDING)
                        }
                    viewModel.onConfigChange(row, config.copy(sort = newSort))
                    closePanel()
                }.also { screen.addPreference(it) } to sort
            }
        fragment.onRefresh {
            val config = state.rows.firstOrNull { it.id == rowId }?.config as? HomeRowConfig.ByParent
            val current = config?.sort ?: SortAndDirection(ItemSortBy.DEFAULT, SortOrder.ASCENDING)
            rows.forEach { (row, sort) ->
                val selected = sort == current.sort
                row.title =
                    SettingsLook.glyphTitle(
                        ctx,
                        getString(if (current.direction == SortOrder.ASCENDING) R.string.fa_caret_up else R.string.fa_caret_down),
                        getString(getStringRes(sort)),
                        glyphVisible = selected,
                    )
            }
        }
    }

    // ---- global settings ----

    private fun buildGlobal(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context
        val binder =
            AppPreferenceBinder<AppPreferences>(
                fragment = fragment,
                accent = accent,
                current = { appPreferences },
                update = { transform ->
                    pageScope.launch(ExceptionHandler()) { viewModel.preferencesDataStore.updateData { transform(it) } }
                },
                navigate = {},
            )
        listOf(AppPreference.HomePageItems, AppPreference.RewatchNextUp, AppPreference.MaxDaysNextUp, AppPreference.HomeClickToPlay)
            .forEachIndexed { index, pref -> binder.add(screen, pref, "global$index") }
        screen.addPreference(SettingsDividerPreference(ctx).apply { key = "divider1" })
        screen.addPreference(click(fragment, "bigger", glyph(R.string.lb_settings_fa_chevron_up, R.string.increase_all_cards_size)) { viewModel.resizeCards(1) })
        screen.addPreference(click(fragment, "smaller", glyph(R.string.lb_settings_fa_chevron_down, R.string.decrease_all_cards_size)) { viewModel.resizeCards(-1) })
        screen.addPreference(SettingsDividerPreference(ctx).apply { key = "divider2" })
        screen.addPreference(click(fragment, "removed", glyph(R.string.fa_eye, R.string.view_removed_next_up)) { openScreen(REMOVED) })
        screen.addPreference(
            click(fragment, "save", glyph(R.string.fa_cloud_arrow_up, R.string.save_to_server)) {
                confirm(R.string.overwrite_server_settings) { viewModel.saveToRemote() }
            },
        )
        screen.addPreference(
            click(fragment, "load", glyph(R.string.fa_cloud_arrow_down, R.string.load_from_server)) {
                confirm(R.string.overwrite_local_settings) { viewModel.loadFromRemote() }
            },
        )
        screen.addPreference(
            click(fragment, "loadWeb", glyph(R.string.fa_download, R.string.load_from_web_client)) {
                confirm(R.string.overwrite_local_settings) { viewModel.loadFromRemoteWeb() }
            },
        )
        screen.addPreference(
            click(fragment, "reset", glyph(R.string.fa_arrows_rotate, R.string.reset)) {
                confirm(R.string.overwrite_local_settings) { addRow(false) { viewModel.resetToDefault() } }
            },
        )
    }

    private fun buildPresets(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        listOf(
            R.string.display_preset_default to HomeRowPresets.WholphinDefault,
            R.string.display_preset_compact to HomeRowPresets.WholphinCompact,
            R.string.display_preset_series_thumb to HomeRowPresets.SeriesThumbs,
            R.string.display_preset_episode_thumbnails to HomeRowPresets.EpisodeThumbnails,
        ).forEachIndexed { index, (title, preset) ->
            screen.addPreference(click(fragment, "preset$index", getString(title)) { viewModel.applyPreset(preset) })
        }
    }

    // ---- removed from Next Up (the old RemovedNextUpContent dialog) ----

    private fun buildRemoved(
        fragment: SettingsListFragment,
        screen: PreferenceScreen,
    ) {
        val vm = ViewModelProvider(this)[RemovedNextUpContentViewModel::class.java]
        val ctx = fragment.preferenceManager.context
        val focus = ReorderFocus()
        val message =
            SettingsInfoPreference(ctx).apply {
                key = "message"
                accent = this@HomeSettingsFragment.accent
            }
        screen.addPreference(message)
        val rows = mutableMapOf<UUID, SettingsReorderPreference>()
        fragment.onRefresh {
            val removedState = vm.state.value
            val items = (removedState.loading as? DataLoadingState.Success)?.data.orEmpty()
            message.title =
                when (val s = removedState.loading) {
                    DataLoadingState.Pending, DataLoadingState.Loading -> getString(R.string.loading)
                    is DataLoadingState.Error -> s.message ?: s.exception?.localizedMessage ?: "Error"
                    is DataLoadingState.Success -> if (s.data.isEmpty()) getString(R.string.no_results) else null
                }
            message.isVisible = message.title != null
            val ids = items.map { it.series.id }.toSet()
            rows.keys.filter { it !in ids }.forEach { id -> rows.remove(id)?.let(screen::removePreference) }
            items.forEachIndexed { index, item ->
                val row =
                    rows.getOrPut(item.series.id) {
                        SettingsReorderPreference(ctx, focus).also {
                            it.key = "removed.${item.series.id}"
                            it.accent = accent
                            screen.addPreference(it)
                            loadIcon(it, item.imageUrl)
                        }
                    }
                row.order = index + 1
                row.title = item.series.title ?: item.series.id.toString()
                row.summary = formatDateTime(item.datetime)
                row.buttons =
                    listOf(ReorderButton(getString(R.string.lb_settings_fa_trash), removedState.removedEnabled, "delete") { vm.remove(item) })
            }
        }
        fragment.lifecycleScope.launch { vm.state.collect { fragment.refresh() } }
    }

    // ---- page ----

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        preview =
            HomeSettingsPreview(ctx, imageUrlService, onFocusItem = viewModel::updateBackdrop).also {
                it.pageStyle = pageStyle
                // The preview starts after an 8dp gap from the panel
                sidePane.addView(
                    it,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        marginStart = ctx.dp(8)
                    },
                )
            }
        viewModel.state.collectWhileStarted { s ->
            preview?.submit(s.rowData, s.loading != LoadingState.Success)
            refreshScreens()
        }
        viewModel.preferencesDataStore.data.collectWhileStarted {
            appPreferences = it
            refreshScreens()
        }
    }

    override fun onPageStyleChanged(style: com.github.damontecres.wholphin.leanback.theme.PageStyle) {
        super.onPageStyleChanged(style)
        preview?.pageStyle = style
    }

    override fun onDestroyView() {
        preview = null
        super.onDestroyView()
    }

    companion object {
        private const val ROWS = "rows"
        private const val ADD = "add"
        private const val FAVORITES = "favorites"
        private const val GLOBAL = "global"
        private const val PRESETS = "presets"
        private const val REMOVED = "removed"
        private const val LIBRARY = "library:"
        private const val ROW = "row:"
        private const val SORT = "sort:"
        private const val SEARCH = "search:"
        private const val ROW_ORDER_START = 100
        private const val LIBRARY_ORDER_START = 10
        private const val MORE_ORDER_START = 1000
        private const val SEARCH_DELAY_MS = 750L

        /** The old literal heading */
        private const val REMOVED_TITLE = "Removed from next up"
    }
}
