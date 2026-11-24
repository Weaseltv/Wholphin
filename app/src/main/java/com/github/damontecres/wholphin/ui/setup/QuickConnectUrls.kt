package com.github.damontecres.wholphin.ui.setup

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.github.damontecres.wholphin.BuildConfig
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/*
 * WeaselPlex website links and the QR code renderer, moved out of the Compose QuickConnectQrCode.kt so the
 * no-Compose UI can use them.
 */

/**
 * The page a phone opens to approve this TV's Quick Connect [code], or null when the flavor has
 * no approve page (every upstream flavor) or there is no code yet. The web page signs the
 * customer in with their website account and approves the code; the TV keeps polling as usual.
 */
fun quickConnectApproveUrl(code: String?): String? {
    val base = BuildConfig.QUICK_CONNECT_APPROVE_URL
    if (base.isBlank() || code.isNullOrBlank()) return null
    return "$base?code=${Uri.encode(code)}&device=tv"
}

/**
 * True for the WeaselPlex flavor, whose only sign-in is the QR code approved on the website:
 * no username/password and no server switching (owner decision, 2026-10-02). False on every
 * upstream flavor, which keep the original screens.
 */
fun weaselPlexConnectOnly(): Boolean = BuildConfig.QUICK_CONNECT_APPROVE_URL.isNotBlank()

/**
 * The website page where a profile's PIN is reset (contract 11.2), eg `https://theweasel.tv/weaselplex/profiles`, or
 * null on every flavor without the WeaselPlex website.
 */
fun forgotPinUrl(): String? =
    BuildConfig.WEASELPLEX_WEBSITE_URL
        .takeIf { BuildConfig.KID_PROFILES_ENABLED && it.isNotBlank() }
        ?.let { "${it.trimEnd('/')}/weaselplex/profiles" }

/**
 * The website's Kids profiles page (contract 12.3), where a kid's page pairs it to a TV, eg
 * `https://theweasel.tv/weaselplex/profiles/kids`, or null on every flavor without the WeaselPlex website.
 */
fun pairKidUrl(): String? = forgotPinUrl()?.let { "$it/kids" }

/** A QR code for [content] as a [pixels]-square bitmap, black on white so a phone camera reads it off a TV screen */
internal fun renderQrCode(
    content: String,
    pixels: Int,
): Bitmap {
    val hints =
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val width = matrix.width
    val height = matrix.height
    val colors = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            colors[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.RGB_565)
}
