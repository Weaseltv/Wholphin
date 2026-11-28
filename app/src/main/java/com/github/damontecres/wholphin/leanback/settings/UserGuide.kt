package com.github.damontecres.wholphin.leanback.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.leanback.theme.NeonColors
import com.github.damontecres.wholphin.services.hilt.StandardOkHttpClient
import com.github.damontecres.wholphin.util.WholphinDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Settings → Help → Official user guide (owner, 2026-11-28): the WeaselPlex TV guide on theweasel.tv, shown full
 * screen inside the app like the WeaselTV TV app's privacy page.
 *
 * The guide is behind the website's sign-in, and the web view has no website session, so the app first asks the
 * website for a one-time link with the profile's own token (`POST /api/v1/weaselplex/tv/guide-link`,
 * weaseltv-platform `lib/server/weaselplex-guide-pass.ts`). Opening that link signs the web view in to the guide
 * only. If the link can't be had (offline, an older website), the guide's own address opens instead and the website
 * asks for a sign-in.
 */

/** The website's one-time guide link for the signed-in profile. Tokens are never logged. */
@Singleton
class UserGuideLinkApi
    @Inject
    constructor(
        @StandardOkHttpClient private val okHttpClient: OkHttpClient,
    ) {
        /** The full one-time link, or null when the website gives none */
        suspend fun signedInLink(accessToken: String): String? {
            val base = BuildConfig.WEASELPLEX_WEBSITE_URL.trimEnd('/').takeIf { it.isNotBlank() } ?: return null
            val request =
                Request
                    .Builder()
                    .url("$base$LINK_PATH")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header("X-WeaselPlex-Client", "android-tv/${BuildConfig.VERSION_NAME}")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            return try {
                withContext(WholphinDispatchers.IO) {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Timber.w("Guide link refused: HTTP %d", response.code)
                            return@use null
                        }
                        val path =
                            Json
                                .parseToJsonElement(response.body.string())
                                .jsonObject["path"]
                                ?.jsonPrimitive
                                ?.content
                        path?.takeIf { it.startsWith(OPEN_PATH) }?.let { "$base$it" }
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w("Guide link request failed: %s", ex.javaClass.simpleName)
                null
            }
        }

        private companion object {
            const val LINK_PATH = "/api/v1/weaselplex/tv/guide-link"
            const val OPEN_PATH = "/api/guide-open?"
        }
    }

/**
 * The guide full screen in a web view. BACK goes back a page in the guide, then closes it. The web view only goes to
 * the guide, its one-time link and the website's sign-in page; any other link stays put.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class UserGuideDialog(
    context: Context,
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val webView =
        WebView(context).apply {
            setBackgroundColor(NeonColors.STAGE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !allowedGuideNavigation(request.url)
                }
        }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        CookieManager.getInstance().setAcceptCookie(true)
        setContentView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window?.setBackgroundDrawable(ColorDrawable(NeonColors.STAGE))
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN && webView.canGoBack()
            }
        }
        setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }
    }

    override fun onStart() {
        super.onStart()
        webView.requestFocus()
    }

    /** Opens [url] if it is one the guide may show */
    fun load(url: String) {
        val uri = Uri.parse(url)
        if (allowedGuideNavigation(uri)) webView.loadUrl(url)
    }
}

/** HTTPS on the website's host, no credentials in the address, and only the guide, its link or the sign-in page */
internal fun allowedGuideNavigation(uri: Uri): Boolean {
    val site = Uri.parse(BuildConfig.WEASELPLEX_WEBSITE_URL.ifBlank { return false })
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.host.equals(site.host, ignoreCase = true) || uri.userInfo != null) return false
    if (uri.port != -1 && uri.port != 443) return false
    val path = uri.path ?: return false
    return path.startsWith("/weaselplex/guide/") || path == "/api/guide-open" || path == "/login"
}
