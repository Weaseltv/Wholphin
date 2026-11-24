package com.github.damontecres.wholphin.leanback.playback

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.damontecres.wholphin.leanback.shell.DestinationFragment
import com.github.damontecres.wholphin.services.ServerReportService
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.playback.PlayExternalState
import com.github.damontecres.wholphin.ui.playback.PlayExternalViewModel
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject

/**
 * Plays in the external player app (no-Compose rewrite of `PlayExternalPage`): prepares the stream and intent,
 * launches the player once (the activity-scoped view model remembers it across recreation) and reports the result.
 */
@AndroidEntryPoint
class PlayExternalFragment : DestinationFragment() {
    @Inject
    lateinit var serverReportService: ServerReportService

    // Scoped to the activity, as the old page's hiltViewModel(viewModelStoreOwner = activity)
    private val viewModel: PlayExternalViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<android.content.Intent>
    private lateinit var root: FrameLayout
    private var shown: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.onResult(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        root = FrameLayout(requireContext())
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        if (!viewModel.launched.value) viewModel.init(destination)
        combine(viewModel.state, viewModel.launched) { s, l -> s to l }.collectWhileStarted { (state, launched) -> render(state, launched) }
    }

    private fun render(
        state: PlayExternalState,
        launched: Boolean,
    ) {
        when (val l = state.loading) {
            LoadingState.Pending -> {
                show(LoadingState.Pending) { PlaybackLoadingView(requireContext(), focusEnabled = false) }
            }

            LoadingState.Loading -> {
                show(LoadingState.Loading) { PlaybackLoadingView(requireContext()) }
            }

            is LoadingState.Error -> {
                show(l) {
                    PlaybackErrorView(requireContext(), l.message, l.exception, pageStyle.accentFor(null), ::sendLogs)
                }
            }

            LoadingState.Success -> {
                show(LoadingState.Success) { PlaybackLoadingView(requireContext()) }
                // LifecycleStartEffect: launch once while started
                if (!launched) {
                    Timber.i("Launching external playback")
                    viewModel.launched.update { true }
                    try {
                        if (viewModel.mayLaunch()) launcher.launch(state.intent)
                    } catch (ex: Exception) {
                        viewModel.reportException(ex)
                    }
                }
            }
        }
    }

    private fun show(
        key: Any,
        make: () -> View,
    ) {
        if (shown == key) return
        shown = key
        root.removeAllViews()
        val v = make()
        root.addView(
            v,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        if (v.isFocusable) v.requestFocus()
    }

    private fun sendLogs() {
        viewLifecycleOwner.lifecycleScope.launchIO { serverReportService.sendAppLogs() }
    }

    override fun onScreenFocus(restoring: Boolean) {
        val v = root.getChildAt(0)
        if (v == null || !v.requestFocus()) super.onScreenFocus(restoring)
    }
}
