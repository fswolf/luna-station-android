package imbored.luna.station.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import imbored.luna.station.station.LunaCa
import imbored.luna.station.station.Pairing
import java.net.URI

/**
 * Her portrait, the same page the PC shows, served by the station server to paired
 * devices (web/station.py /portrait/). The 3D runs here, on the device.
 *
 * The WebView has its own certificate checks and doesn't know Luna's authority, so every
 * TLS error lands in [onReceivedSslError]: it goes ahead only for her address and port,
 * with a certificate signed by the authority this device paired with. Anything else is
 * cancelled, and links never leave her page.
 */
@SuppressLint("SetJavaScriptEnabled")   // the portrait is a script; only her page ever loads
@Composable
fun PortraitView(p: Pairing, modifier: Modifier = Modifier) {
    val origin = "https://${if (p.host.contains(':')) "[${p.host}]" else p.host}:${p.port}"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie(origin, "luna_station=${p.token}; Secure")
                flush()
            }
            WebView(ctx).apply {
                setBackgroundColor(LunaColors.Background.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true                 // the page keeps settings in localStorage
                settings.mediaPlaybackRequiresUserGesture = false  // her portrait sounds
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = LunaClient(p)
                loadUrl("$origin/portrait/")
            }
        },
        onRelease = { web ->
            // Leaving the view closes her event stream, so a portrait nobody watches costs nothing.
            web.stopLoading()
            web.destroy()
        },
    )
}

private class LunaClient(private val p: Pairing) : WebViewClient() {

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (isLuna(error)) handler.proceed() else handler.cancel()
    }

    /** Her page only: no navigating the portrait somewhere else. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !sameServer(request.url.toString())

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // about:blank is our own message page (explain() below), loaded with no address
        if (url != null && url != "about:blank" && !sameServer(url)) view.stopLoading()
    }

    // When the portrait page itself can't load, say so in the app's colours instead of
    // Android's white "Webpage not available". Failed files inside the page are its own business.
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            explain(view, "Can't reach Luna at ${p.host}. Is the PC awake, with Relay stations on?")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (!request.isForMainFrame) return
        explain(
            view,
            when (response.statusCode) {
                401 -> "This device isn't paired with Luna anymore. Tap Luna at the top to forget it and pair again."
                404 -> "This Luna doesn't serve the portrait to stations yet. It needs a newer version of Luna."
                else -> "The portrait didn't load (Luna answered ${response.statusCode})."
            },
        )
    }

    private fun explain(view: WebView, words: String) {
        val text = words.replace("&", "&amp;").replace("<", "&lt;")
        view.loadDataWithBaseURL(
            null,
            "<html><body style=\"margin:0;height:100vh;display:grid;place-items:center;" +
                "background:#14111F;color:#9A93B0;font:16px sans-serif;text-align:center;padding:0 24px\">" +
                "<p>$text</p></body></html>",
            "text/html", "utf-8", null,
        )
    }

    private fun sameServer(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host?.removeSurrounding("[", "]") == p.host && u.port == p.port
    } catch (e: Exception) {
        false
    }

    private fun isLuna(error: SslError): Boolean {
        if (!sameServer(error.url)) return false
        val leaf = error.certificate?.x509Certificate ?: return false
        return LunaCa.vouchesFor(p.ca, leaf, p.host)
    }
}
