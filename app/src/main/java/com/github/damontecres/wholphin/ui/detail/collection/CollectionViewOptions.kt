package com.github.damontecres.wholphin.ui.detail.collection

import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.preferences.AppSwitchPreference
import com.github.damontecres.wholphin.ui.components.ViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsAspectRatio
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsColumns
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsContentScale
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsImageType
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsReset
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsShowTitles
import com.github.damontecres.wholphin.ui.components.ViewOptions.Companion.ViewOptionsSpacing
import kotlinx.serialization.Serializable

/*
 * A collection's view options, moved out of the Compose file CollectionViewOptionsDialog.kt (no-Compose UI,
 * 2026-11-22) so the view model and the View dialog share them.
 */

@Serializable
data class CollectionViewOptions(
    val separateTypes: Boolean = false,
    val cardViewOptions: ViewOptions =
        ViewOptions(
            showTitles = true,
        ),
) {
    companion object {
        val SeparateTypes =
            AppSwitchPreference<CollectionViewOptions>(
                title = R.string.separate_types,
                defaultValue = false,
                getter = { it.separateTypes },
                setter = { vo, value -> vo.copy(separateTypes = value) },
            )

        val MixedOptions =
            listOf(
                ViewOptionsImageType,
                ViewOptionsAspectRatio,
                ViewOptionsShowTitles,
                ViewOptionsColumns,
                ViewOptionsSpacing,
                ViewOptionsContentScale,
                ViewOptionsReset,
            )

        val SeparateOptions =
            listOf(
                ViewOptionsShowTitles,
                ViewOptionsReset,
            )
    }
}
