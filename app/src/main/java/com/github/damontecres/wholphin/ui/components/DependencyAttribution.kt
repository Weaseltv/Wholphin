package com.github.damontecres.wholphin.ui.components

/*
 * Open-source dependency attributions for the license page, Compose-free (moved from LicenseInfo.kt, 2026-11-22).
 */

/** One license of a dependency */
data class DependencyLicense(
    val name: String,
    val content: String?,
    val url: String?,
)

data class DependencyAttribution(
    val id: String,
    val name: String,
    val version: String?,
    val authors: String,
    val description: String?,
    val website: String?,
    val licenses: List<DependencyLicense>,
)
