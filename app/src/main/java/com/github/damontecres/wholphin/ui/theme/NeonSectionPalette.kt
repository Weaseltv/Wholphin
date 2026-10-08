package com.github.damontecres.wholphin.ui.theme

import androidx.compose.ui.graphics.Color

/** Owner-approved section border/glow pairs (2026-10-08), shared by all Neon components. */
object NeonSectionPalette {
    data class Colors(val border: Color, val glow: Color)

    val Search = Colors(Color(0xFF0A2CFF), Color(0xFF00A3FF))
    val Watchlist = Colors(Color(0xFF00B8D9), Color(0xFF00E1FF))
    val Home = Colors(Color(0xFF0B8A42), Color(0xFF00E04B))
    val Collections = Colors(Color(0xFFFFD300), Color(0xFFFFFF33))
    val Movies = Colors(NeonBoard.Orange, NeonBoard.OrangeGlow)
    val TvShows = Colors(Color(0xFFE8112D), Color(0xFFFF1A1A))
    val StandUpComedy = Colors(Color(0xFF5A00FF), Color(0xFF9B30FF))
    val Sports = Colors(Color(0xFFFF0099), Color(0xFFFF85D0))

    private val glowByBorder = listOf(
        Search, Watchlist, Home, Collections, Movies, TvShows, StandUpComedy, Sports,
    ).associate { it.border to it.glow }

    fun glowAccent(accent: Color): Color =
        glowByBorder[accent.copy(alpha = 1f)]?.copy(alpha = accent.alpha) ?: accent
}
