package com.github.damontecres.wholphin.ui.detail

/**
 * An item shown in a card grid: its stable id, whether the remote's play key plays it, and the name its first letter
 * is taken from for the letter jump bar. Compose-free (no-Compose UI, 2026-11-22): used by both the old `CardGrid` and
 * the View `CardGridView`.
 */
interface CardGridItem {
    val gridId: String
    val playable: Boolean
    val sortName: String
}
