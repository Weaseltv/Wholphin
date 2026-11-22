package com.github.damontecres.wholphin.leanback.shell

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.leanback.theme.applyTextRole
import com.github.damontecres.wholphin.leanback.theme.dp
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.ui.nav.Destination
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * The page for a destination no area provides (the old `Text("Unsupported item type: ...")` branches of
 * `DestinationContent`). Until every area lands, it also stands in for pages that are not rewritten yet.
 */
@AndroidEntryPoint
class UnsupportedScreenFragment : DestinationFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val text =
            when (val d = destination) {
                is Destination.MediaItem -> {
                    "Unsupported item type: ${d.type}"
                }

                is Destination.DiscoveredItem -> {
                    if (d.item.type == SeerrItemType.UNKNOWN) "Unknown discover type" else "Unsupported page: ${d.item.type}"
                }

                else -> {
                    "Unsupported page: ${d::class.simpleName}"
                }
            }
        Timber.w("No screen for %s", destination)
        return FrameLayout(context).apply {
            addView(
                TextView(context).apply {
                    this.text = text
                    applyTextRole(TextRole.BODY_LARGE)
                    setTextColor(AppTheme.palette.onSurface)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }
}

/** An empty page: a kid session kept out of a Settings page shows nothing while it is sent Home */
@AndroidEntryPoint
class BlankScreenFragment : DestinationFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext())
}

/**
 * Stands in for a page the FragmentManager restores after the process was killed, when its destination is no longer
 * known; the shell removes it straight away and builds the page again from the restored back stack.
 */
class OrphanPageFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = null
}

/**
 * Stands in for a setup screen (server list, profile chooser) while the SETUP area has not provided it
 * (`leanback.setup.SetupScreens` returns null).
 */
class SetupPlaceholderFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val name = arguments?.getString(ARG_NAME) ?: ""
        return FrameLayout(context).apply {
            isFocusable = true
            addView(
                TextView(context).apply {
                    text = name
                    applyTextRole(TextRole.HEADLINE_SMALL)
                    setTextColor(AppTheme.palette.onSurface)
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
    }

    companion object {
        private const val ARG_NAME = "name"

        fun create(destination: SetupDestination): SetupPlaceholderFragment =
            SetupPlaceholderFragment().apply {
                arguments = Bundle().apply { putString(ARG_NAME, destination::class.simpleName) }
            }
    }
}

/**
 * The setup layer's loading spinner (SetupDestination.Loading, and the signed-in content hidden on stop): a 40dp
 * indeterminate spinner in the theme's border colour, in a 200dp box at the top start, as the Compose version was.
 */
internal fun loadingSpinner(context: android.content.Context): View =
    FrameLayout(context).apply {
        val box = FrameLayout(context)
        val spinner =
            ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList =
                    android.content.res.ColorStateList
                        .valueOf(AppTheme.palette.border)
            }
        box.addView(spinner, FrameLayout.LayoutParams(context.dp(40), context.dp(40), Gravity.CENTER))
        addView(box, FrameLayout.LayoutParams(context.dp(200), context.dp(200), Gravity.TOP or Gravity.START))
    }
