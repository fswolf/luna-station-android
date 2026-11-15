package imbored.luna.station.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.activity.compose.LocalActivity
import imbored.luna.station.MainActivity
import imbored.luna.station.station.LunaCa
import imbored.luna.station.station.Pairing
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.math.roundToInt

private const val TAG = "LunaPortrait"

/**
 * Her portrait, the same page the PC shows, served by the station server to paired
 * devices (web/station.py /portrait/). The 3D runs here, on the device.
 *
 * The WebView is NOT a Compose AndroidView. Hosted by Compose, the WebView on a Galaxy S24+
 * (Android 17) painted the page's colours but none of its GPU layers: no WebGL canvas, not
 * even a plain 2D canvas, while the same WebView added to the activity's own view tree drew
 * them fine (2026-11-15, tested with coloured squares). So MainActivity keeps a FrameLayout
 * under its Compose tree, this composable puts the WebView in it and only tells it where the
 * pane is; the Compose column goes see-through there (ChatScreen). Touches that nothing in
 * Compose claims fall through to it.
 *
 * The WebView has its own certificate checks and doesn't know Luna's authority, so every
 * TLS error lands in [LunaClient.onReceivedSslError]: it goes ahead only for her address and
 * port, with a certificate signed by the authority this device paired with. Anything else is
 * cancelled, and links never leave her page.
 *
 * Nothing here fails quietly. SSL decisions, load errors, HTTP errors and the page's console
 * go to logcat under [TAG], the first two also on screen. A debug build is inspectable from the
 * PC: chrome://inspect in Chrome with the phone on USB.
 */
@Composable
fun PortraitView(p: Pairing, modifier: Modifier = Modifier) {
    val host = (LocalActivity.current as? MainActivity)?.portraitHost
    if (host == null) {
        Log.e(TAG, "PortraitView needs MainActivity's portraitHost")
        return
    }
    val web = remember(p) { makeWebView(host.context, p) }
    DisposableEffect(web) {
        host.addView(web, FrameLayout.LayoutParams(0, 0))
        onDispose {
            // Leaving the view closes her event stream: an unwatched portrait costs the PC nothing.
            host.removeView(web)
            web.stopLoading()
            web.destroy()
        }
    }
    // An empty box where the portrait goes; the WebView underneath follows its bounds.
    Box(
        modifier.onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            val lp = web.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)
            val w = b.width.roundToInt(); val h = b.height.roundToInt()
            val l = b.left.roundToInt(); val t = b.top.roundToInt()
            if (lp.width != w || lp.height != h || lp.leftMargin != l || lp.topMargin != t) {
                lp.width = w; lp.height = h; lp.leftMargin = l; lp.topMargin = t
                web.layoutParams = lp
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")   // the portrait is a script; only her page ever loads
private fun makeWebView(ctx: Context, p: Pairing): WebView {
    val origin = "https://${if (p.host.contains(':')) "[${p.host}]" else p.host}:${p.port}"
    val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect on the PC, phone on USB
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        // Path=/ so the cookie goes with /portrait/ and everything under it
        setCookie(origin, "luna_station=${p.token}; Path=/; Secure")
        flush()
    }
    return WebView(ctx).apply {
        setBackgroundColor(LunaColors.Background.toArgb())
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true                 // the page keeps settings in localStorage
        settings.mediaPlaybackRequiresUserGesture = false  // her portrait sounds
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = LunaClient(p, debuggable)
        webChromeClient = LunaChrome()
        // Not loaded until the view has a size: the page sizes her canvas from innerWidth and
        // innerHeight the moment its script runs, and the view is 0 by 0 until the pane is placed.
        var loaded = false
        addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (loaded || r - l <= 0 || b - t <= 0) return@addOnLayoutChangeListener
            loaded = true
            Log.i(TAG, "loading $origin/portrait/ at ${r - l}x${b - t}")
            loadUrl("$origin/portrait/")
        }
    }
}

/** The page's own console (errors from three.js, a fetch that failed, ...) goes to logcat. */
private class LunaChrome : WebChromeClient() {
    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
        val line = "portrait js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})"
        when (m.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, line)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
        return true
    }
}

private class LunaClient(private val p: Pairing, private val debug: Boolean) : WebViewClient() {

    // Lint wants every SSL error cancelled. This one is: proceed() only for Luna's own address with
    // a certificate signed by the authority this device paired with, checked by whyNotLuna().
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val why = whyNotLuna(error)
        if (why == null) {
            Log.i(TAG, "ssl: accepted Luna's certificate for ${error.url}")
            handler.proceed()
            return
        }
        Log.w(TAG, "ssl: cancelled ${error.url} (error ${error.primaryError}): $why")
        handler.cancel()
        // the page itself (not one of its files): say why the screen would otherwise stay dark
        if (isPortraitPage(error.url)) {
            explain(view, "Luna's certificate didn't check out: $why Forget this PC and pair again if the PC made a new certificate.")
        }
    }

    /** Her page only: no navigating the portrait somewhere else. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !sameServer(request.url.toString())

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Log.i(TAG, "page started: $url")
        // about:blank is our own message page (explain() below), loaded with no address
        if (url != null && url != "about:blank" && !sameServer(url)) view.stopLoading()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        Log.i(TAG, "page finished: $url")
        // Debug builds: what the page sees of itself a few seconds in, viewport, her canvas and how
        // many pixels of it the GPU drew, the background. What chrome://inspect shows, without the cable.
        if (debug && isPortraitPage(url)) for (wait in longArrayOf(4000, 15000)) view.postDelayed({ probe(view, wait) }, wait)
    }

    private fun probe(view: WebView, after: Long) {
        view.evaluateJavascript(
            "(function(){var c=document.querySelector('canvas');var gl=c&&(c.getContext('webgl2')||c.getContext('webgl'));" +
                "var lit=0,tot=0;if(gl){var px=new Uint8Array(4);for(var y=0;y<c.height;y+=Math.max(1,c.height>>5)){gl.readPixels(c.width>>1,y,1,1,gl.RGBA,gl.UNSIGNED_BYTE,px);tot++;if(px[3]>0)lit++}}" +
                "return JSON.stringify({win:[innerWidth,innerHeight,devicePixelRatio],canvas:c?[c.width,c.height]:null," +
                "lost:gl?gl.isContextLost():'no gl',drawn:lit+'/'+tot,body:document.body.className," +
                "bg:getComputedStyle(document.body).backgroundImage.slice(0,120),model:!!(window.portrait)})})()",
        ) { Log.i(TAG, "probe ${after}ms: $it") }
    }

    // When the portrait page itself can't load, say so in the app's colours instead of
    // Android's white "Webpage not available". Failed files inside the page are its own business,
    // but they are logged, because a missing script is a dark screen too.
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        val what = "${error.errorCode} ${error.description}"
        Log.w(TAG, "load error ${request.url} (main=${request.isForMainFrame}): $what")
        if (request.isForMainFrame) {
            explain(view, "Can't reach Luna at ${p.host} ($what). Is the PC awake, with Relay stations on?")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        Log.w(TAG, "http ${response.statusCode} ${request.url} (main=${request.isForMainFrame})")
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
    } catch (_: Exception) {
        false
    }

    /** The portrait page itself, as against a script, picture or stream inside it. */
    private fun isPortraitPage(url: String?): Boolean = try {
        url != null && sameServer(url) && URI(url).path.trimEnd('/') == "/portrait"
    } catch (_: Exception) {
        false
    }

    /** Null when the certificate is Luna's for her address; otherwise the reason it isn't, in words. */
    private fun whyNotLuna(error: SslError): String? {
        val url = error.url ?: return "no address on the error."
        if (!sameServer(url)) return "the address $url isn't ${p.host}:${p.port}."
        val leaf = certificateOf(error.certificate) ?: return "the WebView gave no certificate to check."
        if (!LunaCa.vouchesFor(p.ca, leaf, p.host)) {
            return "the certificate for ${leaf.subjectX500Principal.name} (${sans(leaf)}) isn't signed by the paired authority for ${p.host}."
        }
        return null
    }

    // SslCertificate.getX509Certificate() can be null; the DER bytes the WebView saves in its
    // bundle are the fallback.
    private fun certificateOf(cert: SslCertificate?): X509Certificate? {
        if (cert == null) return null
        cert.x509Certificate?.let { return it }
        val der = SslCertificate.saveState(cert).getByteArray("x509-certificate") ?: return null
        return try {
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        } catch (e: Exception) {
            Log.w(TAG, "ssl: certificate bytes didn't parse: $e")
            null
        }
    }

    private fun sans(leaf: X509Certificate): String = try {
        (leaf.subjectAlternativeNames ?: emptyList()).joinToString(", ") { it[1].toString() }.ifEmpty { "no names" }
    } catch (_: Exception) {
        "names unreadable"
    }
}
