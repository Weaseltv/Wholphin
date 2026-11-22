package com.github.damontecres.wholphin.util.mpv

import com.github.damontecres.wholphin.mpv.MPVLib

/**
 * Set an MPV colour property from an ARGB int, in MPV's `r/g/b/a` float format (each channel 0..1), exactly as the
 * old Compose `Color` version formatted it.
 */
fun MPVLib.setPropertyColor(
    property: String,
    argb: Int,
) = setPropertyString(property, mpvColorFormat(argb))

/** `r/g/b/a` with each channel as a 0..1 float */
fun mpvColorFormat(argb: Int): String {
    val a = ((argb ushr 24) and 0xFF) / 255f
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return "$r/$g/$b/$a"
}
