package io.github.iamthebarrow.nibtexa.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.withMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import java.util.concurrent.Executors
import androidx.ink.strokes.Stroke as InkStroke

/**
 * Draws the document's finished strokes. Strokes are stored in page units and drawn through
 * the page's [PageTransform] (zoom and position, Phase 17): chunks are recorded once in page
 * units and the view is applied when they are drawn, so zooming never re-records the ink.
 *
 * Converted strokes are cached per model stroke object. A moved stroke keeps its id but is a
 * new object, so it is converted again; unchanged strokes are reused.
 *
 * Two costs are kept off the writing path (Phase 8):
 * - Converting many strokes at once (opening a page) runs on a background thread; the page
 *   appears when it is done. Small changes, such as a new stroke, are converted at once.
 * - On hardware-accelerated canvases (API 29+), strokes are recorded in fixed-size chunks of
 *   [RenderNode]s. A new stroke re-records only the last chunk instead of the whole page.
 */
internal class FinishedStrokesView(context: Context) : View(context) {

    private val renderer = CanvasStrokeRenderer.create()
    /** For the renderer's detail only (the canvas does the transform): ink at 100%. */
    private val detail = Matrix().apply {
        val density = resources.displayMetrics.density
        setScale(density, density)
    }
    private var transform = PageTransform(resources.displayMetrics.density)

    /** The page's view; set again (and redrawn) whenever it changes. */
    fun setTransform(value: PageTransform) {
        transform = value
        invalidate()
    }

    private var cache: Map<Long, Pair<Stroke, InkStroke>> = emptyMap()
    private var ordered: List<InkStroke> = emptyList()

    /** One brush per pen and kind, shared by every stroke written with it; remade when the colours change. */
    private var brushes = InkStrokes.allBrushes(InkColors.LIGHT)
    private var colors = InkColors.LIGHT
    private var document: InkDocument? = null

    /** The page's colours (light or dark paper, Phase 13). Every stroke is rebuilt with them. */
    fun setColors(value: InkColors) {
        if (value == colors) return
        colors = value
        brushes = InkStrokes.allBrushes(value)
        cache = emptyMap()
        document?.let(::setDocument)
    }

    /**
     * Strokes that have just finished but are not in a published document yet. They keep
     * the ink visible between the in-progress layer handing a stroke off and the
     * document update arriving, which avoids a one-frame flicker.
     */
    private val pending = mutableListOf<InkStroke>()

    /** Bumped on every [setDocument]; a background conversion applies only if still current. */
    private var generation = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread each time a document is shown, for tests. */
    @VisibleForTesting
    var onDocumentApplied: (() -> Unit)? = null

    private val chunks = mutableListOf<Chunk>()

    fun setDocument(document: InkDocument) {
        this.document = document
        val current = cache
        val generation = ++generation
        val missing = document.strokes.count { stroke -> current[stroke.id]?.first !== stroke }
        if (missing <= SYNC_LIMIT) {
            apply(document, convert(document, current))
        } else {
            converter.execute {
                val next = convert(document, current)
                main.post { if (generation == this.generation) apply(document, next) }
            }
        }
    }

    private fun convert(document: InkDocument, current: Map<Long, Pair<Stroke, InkStroke>>) = document.strokes.associate { stroke ->
        val cached = current[stroke.id]?.takeIf { it.first === stroke }
        stroke.id to (cached ?: (stroke to InkStrokes.toInkStroke(stroke.tool, stroke.points, brushes.getValue(stroke.pen to stroke.kind))))
    }

    private fun apply(document: InkDocument, next: Map<Long, Pair<Stroke, InkStroke>>) {
        cache = next
        ordered = document.strokes.map { next.getValue(it.id).second }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assignChunks()
        pending.clear()
        invalidate()
        onDocumentApplied?.invoke()
    }

    /** Splits [ordered] into chunks; a chunk whose strokes are unchanged keeps its recording. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun assignChunks() {
        val count = (ordered.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        while (chunks.size > count) chunks.removeAt(chunks.lastIndex).node.discardDisplayList()
        while (chunks.size < count) chunks += Chunk()
        for ((i, chunk) in chunks.withIndex()) chunk.assign(ordered.subList(i * CHUNK_SIZE, minOf(ordered.size, (i + 1) * CHUNK_SIZE)))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) chunks.forEach { it.dirty = true }
    }

    fun addPending(stroke: CapturedStroke) {
        pending += InkStrokes.toInkStroke(stroke.tool, stroke.points, brushes.getValue(stroke.pen to stroke.kind))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
            drawChunks(canvas)
        } else {
            // CanvasStrokeRenderer expects the canvas to be transformed already; the matrix
            // argument only tells it the stroke-to-screen scale for rendering detail. Without the
            // concat, dp strokes were drawn at pixel scale (half size on a 2x display).
            canvas.withMatrix(transform.matrix) { for (stroke in ordered) renderer.draw(this, stroke, transform.matrix) }
        }
        canvas.withMatrix(transform.matrix) { for (stroke in pending) renderer.draw(this, stroke, transform.matrix) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawChunks(canvas: Canvas) {
        canvas.withMatrix(transform.matrix) {
            for (chunk in chunks) {
                if (chunk.dirty || !chunk.node.hasDisplayList()) chunk.record(width, height)
                drawRenderNode(chunk.node)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class Chunk {
        val node = RenderNode("ink-chunk").apply { setClipToBounds(false) }
        private var strokes: List<InkStroke> = emptyList()
        var dirty = true

        fun assign(next: List<InkStroke>) {
            if (next.size == strokes.size && next.indices.all { next[it] === strokes[it] }) return
            strokes = next.toList()
            dirty = true
        }

        fun record(width: Int, height: Int) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording()
            try {
                // In page units; the view is applied when the chunk is drawn.
                for (stroke in strokes) renderer.draw(recording, stroke, detail)
            } finally {
                node.endRecording()
            }
            dirty = false
            recordedChunks++
        }
    }

    /** How many chunks were recorded so far, for tests. */
    @VisibleForTesting
    var recordedChunks = 0
        private set

    private companion object {
        /** Up to this many strokes are converted on the main thread (a stroke, a paste). */
        const val SYNC_LIMIT = 64

        /** Strokes per recorded chunk: small enough to re-record quickly, few enough to draw. */
        const val CHUNK_SIZE = 64

        /** One shared thread: conversions finish in order, and only the latest one is shown. */
        val converter = Executors.newSingleThreadExecutor { r -> Thread(r, "ink-convert").apply { isDaemon = true } }
    }
}
