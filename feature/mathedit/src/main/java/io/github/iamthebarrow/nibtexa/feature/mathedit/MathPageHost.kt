package io.github.iamthebarrow.nibtexa.feature.mathedit

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.BridgeProtocol
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.MessageGate
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** The local pages a [MathPageHost] can show. All are app assets under `assets/mathedit/`. */
enum class MathPage(val file: String) {
    /** One editable MathLive math field. */
    MathLiveEditor("mathfield.html"),

    /** Read-only MathLive fields, filled by [HostCommand.RenderList]. */
    MathLiveList("mathlist.html"),

    /** A printable document (the PDF export, Phase 12), laid out by [HostCommand.RenderDocument]. */
    MathLiveDocument("document.html"),
}

/**
 * Owns one WebView showing a local math page, locked down for offline use:
 * - pages are served from the APK by [WebViewAssetLoader]; every other request is refused
 *   and recorded in [blockedRequests]. Network loads are blocked too: the app has the
 *   INTERNET permission for Git sync, so this must not rely on the permission being absent;
 * - no file or content access, no navigation away from the page, no new windows;
 * - the only bridge is a message listener restricted to the asset origin, and every message
 *   passes [MessageGate] before the [listener] sees it.
 *
 * Use on the main thread. Call [destroy] when done.
 */
@SuppressLint("SetJavaScriptEnabled") // The pages are local assets only; see docs/design/MATH_BRIDGE.md.
class MathPageHost(
    context: Context,
    val page: MathPage,
    private val listener: (PageMessage) -> Unit,
) {
    val webView = WebView(context)
    private val blocked = CopyOnWriteArrayList<String>()
    private val rejected = CopyOnWriteArrayList<String>()
    private val gate = MessageGate { reason ->
        rejected += reason
        Log.w(TAG, "Rejected page message: $reason")
    }
    private var replyProxy: JavaScriptReplyProxy? = null

    /** Requests the WebView tried to make outside the app's assets. Should stay empty. */
    val blockedRequests: List<String> get() = blocked.toList()

    /** Reasons for page messages that were rejected. Should stay empty. */
    val rejectedMessages: List<String> get() = rejected.toList()

    /** True once the page has announced itself and can receive commands. */
    val isReady: Boolean get() = replyProxy != null

    init {
        check(isSupported()) { "WebView does not support WEB_MESSAGE_LISTENER" }
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(MessageGate.ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = false // Nothing remote is ever loaded, and lookups would need the network.
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.scheme == "https" && url.host == MessageGate.ASSET_HOST) {
                    assetLoader.shouldInterceptRequest(url)?.let { return it }
                }
                blocked += url.toString()
                Log.w(TAG, "Blocked request: $url")
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            // Pages never navigate; links and redirects are ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
                replyProxy = null
                listener(PageMessage.PageError(null, "renderer gone"))
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, JS_OBJECT, setOf(MessageGate.ASSET_ORIGIN)) { _, message, sourceOrigin, isMainFrame, proxy ->
                val decoded = gate.receive(sourceOrigin.toString(), isMainFrame, message.data) ?: return@addWebMessageListener
                if (decoded is PageMessage.Ready) replyProxy = proxy
                listener(decoded)
            }
        }
        webView.loadUrl(pageUrl(page))
    }

    /** Sends a command to the page. Returns false if the page is not ready yet. */
    @SuppressLint("RequiresFeature") // A reply proxy only exists if the feature-guarded listener was registered.
    fun send(command: HostCommand): Boolean {
        val proxy = replyProxy ?: return false
        proxy.postMessage(BridgeProtocol.encode(command))
        return true
    }

    fun destroy() {
        replyProxy = null
        webView.stopLoading()
        webView.destroy()
    }

    companion object {
        private const val TAG = "MathPageHost"

        /** Name of the object the WebView injects into the page for messaging. */
        const val JS_OBJECT = "nibtexaBridge"

        fun pageUrl(page: MathPage) = "${MessageGate.ASSET_ORIGIN}/assets/mathedit/${page.file}"

        fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
}
