package io.github.iamthebarrow.nibtexa.feature.mathedit.bridge

/**
 * Second line of defence behind the WebView's own origin rule: a message is handled only if
 * it comes from the main frame of the app's asset origin and decodes under [BridgeProtocol].
 * Everything else is counted and reported as rejected.
 */
class MessageGate(private val onRejected: (String) -> Unit = {}) {
    var rejectedCount = 0
        private set

    /** Returns the decoded message, or null if it was rejected. */
    fun receive(sourceOrigin: String, isMainFrame: Boolean, data: String?): PageMessage? {
        val reason = when {
            sourceOrigin != ASSET_ORIGIN -> "unexpected origin '$sourceOrigin'"
            !isMainFrame -> "message from a subframe"
            else -> when (val decoded = BridgeProtocol.decode(data)) {
                is Decoded.Accepted -> return decoded.value
                is Decoded.Rejected -> decoded.reason
            }
        }
        rejectedCount++
        onRejected(reason)
        return null
    }

    companion object {
        /** The fixed origin WebViewAssetLoader serves app assets from. */
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ASSET_ORIGIN = "https://$ASSET_HOST"
    }
}
