package io.github.iamthebarrow.nibtexa.export

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import io.github.iamthebarrow.nibtexa.core.export.PdfLayout
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathPage
import io.github.iamthebarrow.nibtexa.feature.mathedit.MathPageHost
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.BridgeProtocol
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.DocumentBlock
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.HostCommand
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.PageMessage
import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.ProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.math.ceil

/**
 * Maths typeset by MathLive in the document page ([MathPage.MathLiveDocument]) and captured as
 * bitmaps at print resolution (Phase 12): equations for the PDF, and inline maths for the PDF and
 * the reading view (Phase 16).
 *
 * While capturing, the page is attached off-screen to [activity]'s window. Use from the main
 * thread; [capture] suspends while the page lays out.
 */
class MathCapture(private val activity: Activity) {

    /**
     * One piece of maths, cut to its ink. [baseline] is the text baseline in pixels from the
     * bitmap's top, for inline maths; [scale] is pixels per PDF point.
     */
    class Captured(val bitmap: Bitmap, val baseline: Float, val scale: Float)

    /**
     * Each piece of maths, in order: display equations ([inline] false) or inline maths at body text
     * size, as a black ink mask on transparency so it can be drawn in any colour.
     */
    suspend fun capture(latex: List<String>, inline: Boolean = false): List<Captured> {
        if (latex.isEmpty()) return emptyList()
        val density = activity.resources.displayMetrics.density
        val scale = density * PAGE_SCALE
        val messages = Channel<PageMessage>(Channel.UNLIMITED)
        val host = MathPageHost(activity, MathPage.MathLiveDocument) { messages.trySend(it) }
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val width = ceil(PdfLayout.CONTENT_WIDTH * PAGE_SCALE * density).toInt()
        // Off-screen but attached, so the WebView lays out and draws.
        root.addView(host.webView, FrameLayout.LayoutParams(width, width).apply { leftMargin = OFF_SCREEN })
        val captured = mutableListOf<Captured>()
        try {
            withTimeout(READY_TIMEOUT_MS) { messages.receiveMatching<PageMessage.Ready>() }
            var requestId = 1L
            val kind = if (inline) DocumentBlock.Kind.INLINE_MATH else DocumentBlock.Kind.MATH
            for (chunk in chunks(latex.map { DocumentBlock(kind, if (inline) "\\textstyle $it" else it) })) {
                val id = requestId++
                check(host.send(HostCommand.RenderDocument(id, chunk))) { "document page not ready" }
                val laidOut = withTimeout(LAYOUT_TIMEOUT_MS) { messages.receiveMatching<PageMessage.LaidOut> { it.requestId == id } }
                if (laidOut.failed.isNotEmpty()) throw IOException("Could not typeset ${laidOut.failed.size} equation(s)")
                // The whole chunk must be inside the view's bounds to be drawn.
                host.webView.layoutParams = host.webView.layoutParams.apply { height = ceil(laidOut.height * density).toInt().coerceAtLeast(1) }
                awaitFrame()
                awaitFrame()
                delay(SETTLE_MS)
                for (span in laidOut.spans) {
                    val height = ceil((span.bottom - span.top) * density).toInt().coerceAtLeast(1)
                    val (bitmap, top) = crop(stableCapture(host, width, height, (span.top * density).toFloat())) ?: throw IOException("An equation rendered empty")
                    val baseline = ((span.baseline - span.top) * density).toFloat() - top
                    captured += Captured(if (inline) mask(bitmap) else bitmap, baseline, scale)
                }
            }
            return captured
        } catch (e: Throwable) {
            captured.forEach { it.bitmap.recycle() }
            throw e
        } finally {
            // Even when cancelled (the reading view closed), the WebView is let go on the main thread.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                root.removeView(host.webView)
                host.destroy()
            }
        }
    }

    /**
     * Draws the band of the page at [top] until two draws, a frame apart, are identical. The
     * WebView redraws asynchronously after a resize, so a single draw may catch it half done; this
     * also makes the PDF the same bytes every time (Git sync commits it).
     */
    private suspend fun stableCapture(host: MathPageHost, width: Int, height: Int, top: Float): Bitmap {
        fun draw() = createBitmap(width, height).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                withTranslation(0f, -top) { host.webView.draw(this) }
            }
        }
        var previous = draw()
        repeat(MAX_CAPTURES) {
            awaitFrame()
            val next = draw()
            if (next.sameAs(previous)) {
                previous.recycle()
                return next
            }
            previous.recycle()
            previous = next
        }
        throw IOException("An equation did not finish drawing")
    }

    /** The bitmap cut to its ink, with a little white around it, and the rows cut off its top; null if it has no ink. */
    private fun crop(bitmap: Bitmap): Pair<Bitmap, Int>? {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var top = -1
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                if (Color.red(row[x]) < INK_THRESHOLD || Color.green(row[x]) < INK_THRESHOLD || Color.blue(row[x]) < INK_THRESHOLD) {
                    any = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (any) {
                if (top < 0) top = y
                bottom = y
            }
        }
        if (top < 0) {
            bitmap.recycle()
            return null
        }
        val pad = CROP_PADDING_PX
        val x0 = (left - pad).coerceAtLeast(0)
        val y0 = (top - pad).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bitmap, x0, y0, (right + pad + 1).coerceAtMost(w) - x0, (bottom + pad + 1).coerceAtMost(h) - y0)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped to y0
    }

    /** Black on white as black ink on transparency: darkness becomes opacity. */
    private fun mask(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lightness = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            pixels[i] = Color.argb(255 - lightness, 0, 0, 0)
        }
        val masked = createBitmap(bitmap.width, bitmap.height)
        masked.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return masked
    }

    /** Groups blocks into commands within the bridge's limits (items and message size). */
    private fun chunks(blocks: List<DocumentBlock>): List<List<DocumentBlock>> {
        val chunks = mutableListOf<MutableList<DocumentBlock>>()
        for (block in blocks) {
            val last = chunks.lastOrNull()
            if (last != null && last.size < BridgeProtocol.MAX_LIST_ITEMS && fits(last + block)) last += block else chunks += mutableListOf(block)
        }
        return chunks
    }

    private fun fits(blocks: List<DocumentBlock>): Boolean = try {
        BridgeProtocol.encode(HostCommand.RenderDocument(0, blocks)).length < BridgeProtocol.MAX_MESSAGE_CHARS - SIZE_MARGIN
    } catch (e: ProtocolException) {
        false
    }

    private suspend inline fun <reified T : PageMessage> Channel<PageMessage>.receiveMatching(predicate: (T) -> Boolean = { true }): T {
        while (true) {
            when (val message = receive()) {
                is T -> if (predicate(message)) return message
                is PageMessage.PageError -> throw IOException("PDF page: ${message.message}")
                else -> Unit
            }
        }
    }

    companion object {
        /** The document page is laid out at twice print size, so maths is captured at print resolution. */
        const val PAGE_SCALE = 2f
        private const val INK_THRESHOLD = 235
        private const val CROP_PADDING_PX = 4
        private const val SIZE_MARGIN = 1024
        private const val OFF_SCREEN = -100_000
        private const val READY_TIMEOUT_MS = 30_000L
        private const val LAYOUT_TIMEOUT_MS = 60_000L
        private const val SETTLE_MS = 100L
        private const val MAX_CAPTURES = 30

        /** Inline maths is set at this size in CSS pixels (document.css), for the body text's 11 pt. */
        const val INLINE_CSS_PX = 22f
    }
}
