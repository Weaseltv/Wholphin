package com.github.damontecres.wholphin.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import com.github.damontecres.wholphin.preferences.DisplayToggle
import java.util.EnumSet

/*
 * LEGACY (no-Compose rewrite, 2026-11-22): the Compose local for [InterfaceCustomization], kept for the old Compose
 * cards and pages until integration deletes them. Safe to delete with them.
 */
val LocalInterfaceCustomization =
    staticCompositionLocalOf<InterfaceCustomization> {
        InterfaceCustomization(EnumSet.allOf(DisplayToggle::class.java))
    }
