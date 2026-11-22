package com.github.damontecres.wholphin.leanback.settings

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.preference.PreferenceScreen
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.leanback.theme.AppTheme
import com.github.damontecres.wholphin.leanback.theme.TextRole
import com.github.damontecres.wholphin.ui.components.DependencyAttribution
import com.github.damontecres.wholphin.ui.components.DependencyLicense
import com.github.damontecres.wholphin.util.WholphinDispatchers
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Open-source licenses (`Destination.License`, the old `LicenseInfo`) as a Leanback Preference list over the whole
 * page: one row per dependency (name, authors, licenses, version); OK opens its description, website and full license
 * texts in a scrolling dialog.
 */
@AndroidEntryPoint
class LicenseFragment : SettingsPageFragment() {
    private var libraries: List<DependencyAttribution>? = null

    override val rootScreenKey: String = "licenses"
    override val panelFraction: Float = 1f
    override val panelStripe: Boolean = false

    override fun panelBackground(): Int = SettingsLook.pageBackground

    override fun screenTitle(key: String): CharSequence = getString(R.string.license_info)

    override fun buildScreen(
        fragment: SettingsListFragment,
        key: String,
        screen: PreferenceScreen,
    ) {
        val ctx = fragment.preferenceManager.context
        val loading =
            SettingsInfoPreference(ctx).apply {
                key = "loading"
                accent = this@LicenseFragment.accent
                title = getString(R.string.loading)
            }
        screen.addPreference(loading)
        var added = false
        fragment.onRefresh {
            val libs = libraries ?: return@onRefresh
            loading.isVisible = false
            if (added) return@onRefresh
            added = true
            libs.forEachIndexed { index, library ->
                screen.addPreference(
                    SettingsClickPreference(ctx).apply {
                        key = "${library.id}-$index"
                        accent = this@LicenseFragment.accent
                        title = library.name
                        summary =
                            listOfNotNull(
                                library.authors.takeIf { it.isNotBlank() },
                                library.licenses.joinToString(" • ") { it.name },
                            ).joinToString("\n")
                        trailingText = library.version
                        onClick = { showLicense(library) }
                    },
                )
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        if (libraries == null) {
            val ctx = requireContext().applicationContext
            launchWhileStarted {
                val loaded =
                    try {
                        withContext(WholphinDispatchers.IO) { Libs.Builder().withContext(ctx).build() }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Could not read the license data")
                        null
                    }
                libraries =
                    loaded?.libraries?.map { library ->
                        DependencyAttribution(
                            id = library.uniqueId,
                            name = library.name,
                            version = library.artifactVersion,
                            authors =
                                library.developers
                                    .mapNotNull { it.name }
                                    .joinToString(", ")
                                    .ifBlank { library.organization?.name.orEmpty() },
                            description = library.description,
                            website = library.website,
                            licenses = library.licenses.map { DependencyLicense(it.name, it.licenseContent, it.url) },
                        )
                    } ?: emptyList()
                refreshScreens()
            }
        }
    }

    /** The old license dialog: name, description, website, then each license's name, text and link */
    private fun showLicense(library: DependencyAttribution) {
        val ctx = requireContext()
        val weasel = AppTheme.useWeaselFonts
        val titleSp = (if (weasel) TextRole.TITLE_MEDIUM.neonSp else TextRole.TITLE_MEDIUM.stockSp).toInt()
        val text =
            SpannableStringBuilder().apply {
                fun paragraph(value: String?) {
                    if (value.isNullOrBlank()) return
                    if (isNotEmpty()) append("\n\n")
                    append(value)
                }
                library.description?.let(::paragraph)
                library.website?.let(::paragraph)
                library.licenses.forEach { license ->
                    if (isNotEmpty()) append("\n\n")
                    val start = length
                    append(license.name)
                    setSpan(AbsoluteSizeSpan(titleSp, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    paragraph(license.content)
                    paragraph(license.url)
                }
            }
        val dialog = SettingsDialog(ctx, widthDp = 640)
        dialog.column.addView(
            androidx.appcompat.widget.AppCompatTextView(ctx).apply { styleDialogTitle(library.name) },
        )
        val body =
            scrollingText(ctx, text).apply {
                maxHeight = (resources.displayMetrics.heightPixels * .7f).toInt()
            }
        dialog.column.addView(
            body,
            android.widget.LinearLayout
                .LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() },
        )
        dialog.show()
        body.requestFocus()
    }
}
