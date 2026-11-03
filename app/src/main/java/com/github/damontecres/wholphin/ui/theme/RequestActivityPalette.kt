package com.github.damontecres.wholphin.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colors for the Requests ACTIVITY page (owner mockup 2026-11-03). Each role reuses the
 * existing section constant with the same color; Waiting has no existing constant.
 */
object RequestActivityPalette {
    /** ACTIVE section: the Search glow (#00A3FF). */
    val Active = NeonSectionPalette.Search.glow

    /** WAITING section (#FFC400). */
    val Waiting = Color(0xFFFFC400)

    /** READY TO WATCH section: the Home glow (#00E04B). */
    val Ready = NeonSectionPalette.Home.glow

    /** "Movie" label, bar and percentage: the Movies glow (#00E1FF). */
    val Movie = NeonSectionPalette.Movies.glow

    /** "Series" label, bar and percentage: the TV Shows glow (#FF1A1A). */
    val Series = NeonSectionPalette.TvShows.glow

    /** Progress bar track. */
    val Track = NeonBoard.Line

    /** Row dividers and the rule after each section heading. */
    val Divider = NeonBoard.Line

    /** Column headings, years, secondary lines and legend notes. */
    val Muted = NeonBoard.Mid

    /** Empty-state circle border, glow and icon. */
    val EmptyBorder = NeonSectionPalette.Requests.border
    val EmptyGlow = NeonSectionPalette.Requests.glow
}
