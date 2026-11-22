package com.github.damontecres.wholphin.ui.util

import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.DisplayToggle
import java.util.EnumSet

/**
 * Represents various UI preferences (Compose code reads it from `LocalInterfaceCustomization`; View screens build it
 * from `preferences.appPreferences`)
 */
data class InterfaceCustomization(
    val enabledDisplayToggles: EnumSet<DisplayToggle>,
) {
    constructor(prefs: AppPreferences) : this(
        prefs.interfacePreferences.displayTogglesList.let {
            if (it.isEmpty()) {
                EnumSet.noneOf(DisplayToggle::class.java)
            } else {
                EnumSet.copyOf(it)
            }
        },
    )
}
