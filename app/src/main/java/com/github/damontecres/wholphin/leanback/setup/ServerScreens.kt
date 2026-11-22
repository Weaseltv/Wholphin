package com.github.damontecres.wholphin.leanback.setup

import android.Manifest
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.leanback.theme.withAlpha
import com.github.damontecres.wholphin.ui.setup.PinnedServerViewModel
import com.github.damontecres.wholphin.ui.setup.ServerConnectionStatus
import com.github.damontecres.wholphin.ui.setup.ServerState
import com.github.damontecres.wholphin.ui.setup.ServerVersionSupported
import com.github.damontecres.wholphin.ui.setup.SwitchServerState
import com.github.damontecres.wholphin.ui.setup.SwitchServerViewModel
import com.github.damontecres.wholphin.ui.setup.serverErrorMessage
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * A tile with its caption under it (`ServerIconCard`, `UserIconCard`, `AddServerCard`, `AddUserCard`): the focusable
 * [surface] ([NeonSurfaces.tile]) and a two-line bold caption as wide as the tile, 24dp below it.
 */
internal class SetupTileView(
    context: Context,
    restFill: Int,
    focusedFill: Int,
) : LinearLayout(context) {
    val size = context.serverUserTileSize()
    val surface = SetupSurface(context, NeonSurfaces.tile(restFill, focusedFill))
    val caption: TextView =
        context.setupText(gravity = Gravity.CENTER, maxLines = 2) { applyTextRole(TextRole.BODY_LARGE, weight = WEIGHT_BOLD) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(surface, LayoutParams(size, size))
        caption.setPadding(context.dp(4), 0, context.dp(4), 0)
        addView(caption, LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(24) })
    }

    fun setFills(
        restFill: Int,
        focusedFill: Int,
    ) {
        surface.style = NeonSurfaces.tile(restFill, focusedFill)
    }
}

/** The "+" (or another icon) of an add tile, 40 % of the tile */
internal fun SetupTileView.setIcon(icon: Int) {
    surface.removeAllViews()
    surface.addView(
        ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(AppTheme.palette.onSurface)
        },
        frame((size * .4f).toInt(), (size * .4f).toInt(), Gravity.CENTER),
    )
}

/**
 * The server list (`SwitchServerContent`): the brand and "SELECT SERVER" over a centred row of server tiles and the Add
 * Server tile, the focused server's problem at the bottom, a long-press menu (Switch servers, Delete) and the Add Server
 * dialog (discovered servers, or a typed address).
 */
@AndroidEntryPoint
class ServerListFragment : SetupFragment() {
    private val viewModel: SwitchServerViewModel by viewModels()
    private val errorVm: SetupErrorViewModel by viewModels()

    private lateinit var root: FrameLayout
    private var shownLoading: LoadingState? = null
    private var content: ServerListContent? = null
    private var addServerDialog: AddServerDialog? = null
    private var deleteDialog: MenuDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.discoverServers() else addServerDialog?.showEnterAddress()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root =
            FrameLayout(requireContext()).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(AppTheme.palette.background)
            }
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.init()
        viewModel.state.collectWhileStarted { render(it) }
    }

    private fun render(state: SwitchServerState) {
        val kind = state.loading.kind()
        if (kind != shownLoading.kind() || (state.loading is LoadingState.Error && state.loading != shownLoading)) {
            shownLoading = state.loading
            root.removeAllViews()
            content = null
            when (val st = state.loading) {
                is LoadingState.Error -> {
                    root.addView(
                        requireContext().errorMessageView(st.message, st.exception, onSendLogs = { errorVm.sendLogs() }),
                        frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }

                LoadingState.Loading, LoadingState.Pending -> {
                    val loading = SetupLoadingView(requireContext())
                    root.addView(loading, frame())
                    loading.requestFocus()
                }

                LoadingState.Success -> {
                    val c = ServerListContent(requireContext())
                    content = c
                    root.addView(c.view, frame())
                }
            }
        }
        content?.bind(state)
        addServerDialog?.bind(state)
    }

    private fun showDeleteDialog(server: JellyfinServer) {
        val context = requireContext()
        deleteDialog =
            track(
                MenuDialog(
                    context,
                    title = server.name ?: server.url,
                    items =
                        listOf(
                            MenuItem(
                                context.getString(R.string.switch_servers),
                                glyph = context.getString(R.string.fa_arrow_left_arrow_right),
                            ) {
                                viewModel.switchServer(server)
                            },
                            MenuItem(
                                context.getString(R.string.delete),
                                icon = R.drawable.lb_setup_ic_delete,
                                iconTint = withAlpha(0xFFFF0000.toInt(), .8f),
                            ) { viewModel.removeServer(server) },
                        ),
                    onDismissRequest = { closeDeleteDialog() },
                    dismissOnClick = true,
                    waitToLoad = true,
                    elevationDp = 5f,
                ),
            )
        content?.updateDim()
    }

    private fun closeDeleteDialog() {
        close(deleteDialog)
        deleteDialog = null
        content?.updateDim()
    }

    private fun openAddServer() {
        val dialog =
            AddServerDialog(requireContext(), viewModel) {
                close(addServerDialog)
                addServerDialog = null
                content?.updateDim()
            }
        addServerDialog = track(dialog)
        content?.updateDim()
        dialog.bind(viewModel.state.value)
        // Discover nearby servers, asking for the local network permission where Android needs it
        viewModel.clearAddServerState()
        if (viewModel.hasPermission) {
            viewModel.discoverServers()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            Timber.w("No ACCESS_LOCAL_NETWORK permission, but API <%s: %s", Build.VERSION_CODES.CINNAMON_BUN, Build.VERSION.SDK_INT)
        }
    }

    /** The list page shown once the saved servers are loaded */
    private inner class ServerListContent(
        context: Context,
    ) {
        val view = FrameLayout(context).apply { noClip() }
        private val page = FrameLayout(context).apply { noClip() }
        private val row = TileRow(context, 24f)
        private val adapter = ServerAdapter()
        private val errorText =
            context.setupText(color = AppTheme.palette.error, gravity = Gravity.CENTER, maxLines = 2) {
                applyTextRole(TextRole.BODY_MEDIUM)
            }
        private var servers: List<ServerState> = emptyList()
        private var focusedIndex = 0
        private var focusedOnce = false

        init {
            val column =
                vertical(context).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                }
            // Match the Select User header height (title + subtitle) to align the tiles across both screens
            val header = vertical(context).apply { gravity = Gravity.CENTER_HORIZONTAL }

            fun addHeader(
                v: View,
                params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
            ) {
                if (header.childCount > 0) params.topMargin = context.dp(8)
                header.addView(v, params)
            }
            if (AppTheme.isNeon) {
                addHeader(context.neonBrandRow())
                addHeader(context.neonEyebrow(context.getString(R.string.app_name), NeonColors.MID))
                addHeader(
                    context.setupText(context.getString(R.string.select_server).uppercase(), gravity = Gravity.CENTER) {
                        neonHeader(40f, TextRole.HEADLINE_SMALL)
                    },
                )
                addHeader(NeonRuleView(context), LinearLayout.LayoutParams(context.dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                addHeader(
                    context.setupText(
                        context.getString(R.string.select_server),
                        gravity = Gravity.CENTER,
                    ) { applyTextRole(TextRole.DISPLAY_SMALL) },
                )
                // Invisible subtitle placeholder to mirror the server name line on the Select User screen
                addHeader(
                    context.setupText("Server placeholder", color = 0, gravity = Gravity.CENTER) { applyTextRole(TextRole.TITLE_LARGE) },
                )
            }
            column.addView(header, wrap(Gravity.CENTER_HORIZONTAL))
            row.adapter = adapter
            row.setPadding(context.dp(48), context.dp(32), context.dp(48), context.dp(32))
            val rowBox = FrameLayout(context).apply { noClip() }
            rowBox.addView(row, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            column.addView(rowBox, matchWidth().apply { topMargin = context.dp(24) })
            // Non-focusable spacer mirroring the "Switch servers" button on the Select User screen
            column.addView(
                View(context),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56)).apply {
                    topMargin =
                        context.dp(24)
                },
            )
            page.addView(column, frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            view.addView(page, frame())
            errorText.setPadding(context.dp(32), 0, context.dp(32), context.dp(32))
            view.addView(errorText, frame(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }

        fun bind(state: SwitchServerState) {
            servers = state.servers
            adapter.submit(state.servers)
            updateError()
            if (!focusedOnce && (state.loading == LoadingState.Success || state.loading == LoadingState.Pending)) {
                focusedOnce = true
                // The first server (or Add Server when there is none) once the row is laid out
                row.resetFocusMemory()
                row.focusWhenLaidOut()
            }
        }

        fun updateDim() = page.dimAndBlur(addServerDialog != null || deleteDialog != null)

        fun onFocused(index: Int) {
            focusedIndex = index
            updateError()
        }

        private fun updateError() {
            val message = serverErrorMessage(servers.getOrNull(focusedIndex), resources)
            if (message != errorText.text?.toString()?.takeIf { it.isNotEmpty() }) {
                errorText.text = message ?: ""
                // Slides up into place, as the old AnimatedContent did
                errorText.translationY = errorText.height / 2f
                errorText
                    .animate()
                    .translationY(0f)
                    .setDuration(220)
                    .start()
            }
        }

        private inner class ServerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private var items: List<ServerState> = emptyList()

            fun submit(list: List<ServerState>) {
                val sizeChanged = list.size != items.size
                val old = items
                items = list
                if (sizeChanged) {
                    notifyDataSetChanged()
                } else {
                    list.indices.forEach { if (list[it] != old[it]) notifyItemChanged(it) }
                }
            }

            override fun getItemCount(): Int = items.size + 1

            override fun getItemViewType(position: Int): Int = if (position < items.size) 0 else 1

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(
                    SetupTileView(parent.context, 0, 0).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    },
                ) {}

            override fun onBindViewHolder(
                holder: RecyclerView.ViewHolder,
                position: Int,
            ) {
                val tile = holder.itemView as SetupTileView
                if (position >= items.size) {
                    // Add Server: always rightmost
                    val fill = AppTheme.palette.surfaceVariant
                    tile.setFills(withAlpha(fill, .4f), withAlpha(fill, .6f))
                    tile.setIcon(R.drawable.lb_setup_ic_add)
                    tile.caption.text = getString(R.string.add_server)
                    tile.surface.contentDescription = getString(R.string.add_server)
                    tile.surface.setOnClickListener { openAddServer() }
                    tile.surface.setOnLongClickListener(null)
                    tile.surface.isLongClickable = false
                    tile.surface.setOnFocusChangeListener { _, has -> if (has) onFocused(-1) }
                    return
                }
                val server = items[position]
                tile.setFills(idColor(server.server.id, .5f), idColor(server.server.id, .7f))
                bindServerContent(tile, server)
                tile.caption.text = server.server.name?.ifBlank { null } ?: server.server.url
                tile.surface.contentDescription = server.server.name ?: server.server.url
                tile.surface.setOnClickListener {
                    when (server.status) {
                        is ServerConnectionStatus.Success -> {
                            viewModel.switchServer(server.server)
                        }

                        ServerConnectionStatus.Pending -> {}

                        is ServerConnectionStatus.Error -> {
                            viewModel.testServer(server.server)
                        }
                    }
                }
                tile.surface.setOnLongClickListener {
                    showDeleteDialog(server.server)
                    true
                }
                tile.surface.setOnFocusChangeListener { _, has -> if (has) onFocused(holder.bindingAdapterPosition) }
            }
        }
    }

    /** The tile's middle: a warning over the letter, the big letter, or a spinner (`ServerIconCard`) */
    private fun bindServerContent(
        tile: SetupTileView,
        server: ServerState,
    ) {
        val context = tile.context
        val size = tile.size
        val letter =
            (server.server.name ?: server.server.url.replace(Regex("^https?://"), ""))
                .firstOrNull()
                ?.uppercase() ?: "?"
        tile.surface.removeAllViews()
        val status = server.status
        when {
            status is ServerConnectionStatus.Error || server.versionSupported != ServerVersionSupported.SUPPORTED -> {
                val column =
                    vertical(context).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(
                            ImageView(context).apply {
                                setImageResource(R.drawable.lb_setup_ic_warning)
                                imageTintList = ColorStateList.valueOf(AppTheme.palette.errorContainer)
                                contentDescription = (status as? ServerConnectionStatus.Error)?.message
                            },
                            LinearLayout.LayoutParams((size * .3f).toInt(), (size * .3f).toInt()),
                        )
                        addView(
                            context.setupText(letter, gravity = Gravity.CENTER) { boldRole(TextRole.TITLE_LARGE) },
                            wrap(Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(4) },
                        )
                    }
                tile.surface.addView(
                    column,
                    frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }

            status is ServerConnectionStatus.Success -> {
                tile.surface.addView(
                    context.setupText(letter, gravity = Gravity.CENTER) { boldRole(TextRole.DISPLAY_LARGE) },
                    frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }

            status == ServerConnectionStatus.Pending -> {
                tile.surface.addView(
                    RingSpinner(context, AppTheme.palette.border, 0, 4f),
                    frame((size * .4f).toInt(), (size * .4f).toInt(), Gravity.CENTER),
                )
            }
        }
    }
}

/**
 * Add Server (`AddServerDialog`): the servers found on the network first, with "Enter server address" for a typed URL
 * (Go submits; errors show under the field; Submit spins while it connects).
 */
internal class AddServerDialog(
    context: Context,
    private val viewModel: SwitchServerViewModel,
    private val onClose: () -> Unit,
) : BasicDialog(context, onDismissRequest = {}, elevationDp = 10f, widthFraction = .4f) {
    private var enterAddress = false
    private var shownDiscovered: List<JellyfinServer>? = null
    private var urlField: SetupEditText? = null
    private var addressError: TextView? = null
    private var submit: SetupButton? = null
    private var discoveredList: LinearLayout? = null
    private var searching: TextView? = null
    private var focusedFirstServer = false

    init {
        onDismissRequest = {
            enterAddress = false
            viewModel.clearAddServerState()
            onClose()
        }
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        buildDiscovery()
    }

    /** No permission to look on the network: straight to the typed address */
    fun showEnterAddress() {
        if (enterAddress) return
        enterAddress = true
        buildAddress()
    }

    private fun add(view: View) {
        val params = (view.layoutParams as? LinearLayout.LayoutParams) ?: wrap(Gravity.CENTER_HORIZONTAL)
        if (content.childCount > 0) params.topMargin = context.dp(8)
        content.addView(view, params)
    }

    private fun buildDiscovery() {
        content.removeAllViews()
        shownDiscovered = null
        add(
            context.setupText(context.getString(R.string.discovered_servers), gravity = Gravity.CENTER) {
                applyTextRole(TextRole.TITLE_MEDIUM)
            },
        )
        val searchingText =
            context.setupText(context.getString(R.string.searching), gravity = Gravity.CENTER) { applyTextRole(TextRole.BODY_MEDIUM) }
        searching = searchingText
        add(searchingText)
        val list = vertical(context)
        discoveredList = list
        val scroll =
            object : ScrollView(context) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(context.dp(300), MeasureSpec.AT_MOST))
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(list)
                layoutParams = matchWidth()
            }
        add(scroll)
        add(context.setupButton(R.string.enter_server_address) { showEnterAddress() })
        bind(viewModel.state.value)
    }

    private fun buildAddress() {
        content.removeAllViews()
        discoveredList = null
        searching = null
        add(context.setupText(context.getString(R.string.enter_server_url)))
        val field =
            SetupEditText(context).apply {
                inputType = SetupEditText.URI
                imeOptions = EditorInfo.IME_ACTION_GO
                tag = "server_url_text"
                layoutParams = matchWidth()
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO) {
                        submitUrl()
                        true
                    } else {
                        false
                    }
                }
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {}

                        override fun afterTextChanged(s: Editable?) {
                            viewModel.clearAddServerState()
                            updateSubmit()
                        }
                    },
                )
            }
        urlField = field
        add(field)
        val error = context.setupText(color = AppTheme.palette.error)
        addressError = error
        add(error)
        val button = SetupButton(context, context.getString(R.string.submit)).apply { setOnClickListener { submitUrl() } }
        submit = button
        add(button)
        viewModel.clearAddServerState()
        bind(viewModel.state.value)
        field.requestFocus()
    }

    private fun submitUrl() {
        viewModel.addServer(urlField?.text?.toString().orEmpty(), showToast = false)
    }

    private fun updateSubmit() {
        val state = viewModel.state.value.addServerState
        submit?.isEnabled = !urlField?.text.isNullOrBlank() && state == LoadingState.Pending
        submit?.showProgress = state == LoadingState.Loading
    }

    fun bind(state: SwitchServerState) {
        if (enterAddress) {
            val st = state.addServerState
            addressError?.apply {
                text = (st as? LoadingState.Error)?.let { it.message ?: it.exception?.localizedMessage ?: "An error occurred" }
                visibility = if (st is LoadingState.Error) View.VISIBLE else View.GONE
            }
            updateSubmit()
            return
        }
        val list = discoveredList ?: return
        if (state.discoveredServers == shownDiscovered) return
        shownDiscovered = state.discoveredServers
        searching?.visibility = if (state.discoveredServers.isEmpty()) View.VISIBLE else View.GONE
        (list.parent as View).visibility = if (state.discoveredServers.isEmpty()) View.GONE else View.VISIBLE
        val focusedUrl = (list.findFocus()?.tag as? JellyfinServer)?.url
        list.removeAllViews()
        state.discoveredServers.forEachIndexed { index, server ->
            val item =
                SetupListItem(context, server.name?.ifBlank { null } ?: server.url, server.url).apply {
                    tag = server
                    setOnClickListener { viewModel.addServer(server.url, showToast = true) }
                }
            list.addView(
                item,
                matchWidth().apply {
                    topMargin = context.dp(8)
                    bottomMargin = context.dp(8)
                },
            )
            if (server.url == focusedUrl) item.requestFocus()
            // Default focus on the first discovered server once there is one
            if (index == 0 && !focusedFirstServer) {
                focusedFirstServer = true
                initialFocus = item
                if (isShowing) item.requestFocus()
            }
        }
    }
}

/**
 * The pinned-server first screen (`PinnedServerContent`): goes straight to that server's profiles, adding the server
 * first on a fresh install; a spinner while it connects, "Couldn't reach the server" with Retry when it fails.
 */
@AndroidEntryPoint
class PinnedServerFragment : SetupFragment() {
    private val viewModel: PinnedServerViewModel by viewModels()
    private lateinit var column: LinearLayout
    private var shownError: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val root = FrameLayout(context).apply { setBackgroundColor(AppTheme.palette.background) }
        column =
            vertical(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            }
        root.addView(column, frame(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.connect()
        viewModel.state.collectWhileStarted { render(it is LoadingState.Error) }
    }

    private fun render(error: Boolean) {
        if (error == shownError) return
        shownError = error
        val context = requireContext()
        column.removeAllViews()

        fun add(
            v: View,
            params: LinearLayout.LayoutParams = wrap(Gravity.CENTER_HORIZONTAL),
        ) {
            if (column.childCount > 0) params.topMargin = context.dp(24)
            column.addView(v, params)
        }
        if (AppTheme.isNeon) add(context.neonBrandRow())
        if (error) {
            add(
                context.setupText(context.getString(R.string.pinned_server_error), gravity = Gravity.CENTER) {
                    applyTextRole(TextRole.TITLE_MEDIUM)
                },
            )
            val retry = context.setupButton(R.string.retry) { viewModel.connect() }
            add(retry, wrap(Gravity.CENTER_HORIZONTAL))
            retry.requestFocus()
        } else {
            add(
                context.circularProgress(64f),
                LinearLayout.LayoutParams(context.dp(64), context.dp(64)).apply {
                    gravity =
                        Gravity.CENTER_HORIZONTAL
                },
            )
        }
    }
}

/** A LoadingState's kind, ignoring an error's details */
internal fun LoadingState?.kind(): Int =
    when (this) {
        null -> -1
        LoadingState.Pending -> 0
        LoadingState.Loading -> 0
        LoadingState.Success -> 1
        is LoadingState.Error -> 2
    }
