package com.github.damontecres.wholphin.ui.data

import kotlinx.serialization.Serializable

@Serializable
data class RowColumn(
    val row: Int,
    val column: Int,
)
