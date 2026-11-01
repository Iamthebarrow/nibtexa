package io.github.iamthebarrow.nibtexa.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds

/** What a region's mark on the page says about its result. */
enum class RegionMark { NEEDS_CONVERSION, RECOGNIZING, NEEDS_CONFIRMATION, CONFIRMED, NEEDS_ATTENTION }

/**
 * A region as drawn on the page (Phase 13): a soft [tint] in its pen's colour, plus a dot or
 * ring in [accent] for its state. [editing] marks the region open in the results panel.
 */
data class RegionOutline(
    val id: String,
    val bounds: Bounds,
    val mark: RegionMark,
    val tint: Int = 0,
    val accent: Int = 0,
    val editing: Boolean = false,
)

/**
 * Draws on top of the ink: region tints and state marks, the lasso selection (offset while it
 * is dragged), and the lasso loop or eraser path being drawn. Inputs are in page units, drawn
 * through the page's [PageTransform] (Phase 17); marks keep their size on screen.
 *
 * Marks (Phase 13): a confirmed region is only tinted; one waiting for confirmation has a dot
 * at its corner; one being recognized has a dashed ring; one that needs attention (stale or
 * failed) has a dot in the attention colour; the region being edited has a solid ring.
 */
internal class SelectionOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var regions: List<RegionOutline> = emptyList()
    private var selection: List<Bounds> = emptyList()
    private var loop: List<Pair<Float, Float>> = emptyList()
    private var loopClosed = true
    private var offsetX = 0f
    private var offsetY = 0f
    private var colors = InkColors.LIGHT
    private var transform = PageTransform(density)
    private var highlight: Bounds? = null
    private var highlightStrength = 0f

    /** A region jumped to (Phase 28), marked at [strength] from 0 to 1 as it fades. */
    fun setHighlight(bounds: Bounds?, strength: Float) {
        if (bounds == highlight && strength == highlightStrength) return
        highlight = bounds
        highlightStrength = strength
        invalidate()
    }

    fun setTransform(value: PageTransform) {
        transform = value
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val loopPaint = Paint(selectionPaint)
    private val pendingDash = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    private val path = Path()

    fun setColors(value: InkColors) {
        if (value == colors) return
        colors = value
        invalidate()
    }

    fun setRegions(value: List<RegionOutline>) {
        if (value != regions) {
            regions = value
            invalidate()
        }
    }

    fun setSelection(value: List<Bounds>) {
        if (value != selection) {
            selection = value
            invalidate()
        }
    }

    fun setLoop(value: List<Pair<Float, Float>>, closed: Boolean) {
        loop = value
        loopClosed = closed
        invalidate()
    }

    /** Where the eraser is and how far it reaches, in page units (Phase 35). */
    fun setEraser(x: Float, y: Float, radius: Float) {
        eraserX = x
        eraserY = y
        eraserRadius = radius
        invalidate()
    }

    fun clearEraser() {
        if (eraserRadius == 0f) return
        eraserRadius = 0f
        invalidate()
    }

    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserRadius = 0f

    /** The eraser's ring, for tests: its radius on the page, or 0 when none is shown. */
    @androidx.annotation.VisibleForTesting
    val eraserShown: Float get() = eraserRadius

    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eraserEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    fun setOffset(dx: Float, dy: Float) {
        if (dx == offsetX && dy == offsetY) return
        offsetX = dx
        offsetY = dy
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = transform
        val pad = PADDING * density
        val corner = CORNER * density
        for (region in regions) {
            val b = region.bounds
            val left = t.screenX(b.left) - pad
            val top = t.screenY(b.top) - pad
            val right = t.screenX(b.right) + pad
            val bottom = t.screenY(b.bottom) + pad
            if (region.tint != 0) {
                fill.color = region.tint
                canvas.drawRoundRect(left, top, right, bottom, corner, corner, fill)
            }
            when {
                region.editing -> {
                    ring.color = region.accent
                    ring.pathEffect = null
                    canvas.drawRoundRect(left, top, right, bottom, corner, corner, ring)
                }
                region.mark == RegionMark.RECOGNIZING -> {
                    ring.color = region.accent
                    ring.pathEffect = pendingDash
                    canvas.drawRoundRect(left, top, right, bottom, corner, corner, ring)
                }
            }
            val dotColor = when (region.mark) {
                RegionMark.NEEDS_CONFIRMATION -> colors.selection
                RegionMark.NEEDS_ATTENTION -> colors.attention
                else -> 0
            }
            if (dotColor != 0) {
                val r = DOT_RADIUS * density
                dotEdge.color = colors.paper
                canvas.drawCircle(right - r / 2, top + r / 2, r, dotEdge)
                dot.color = dotColor
                canvas.drawCircle(right - r / 2, top + r / 2, r, dot)
            }
        }
        highlight?.takeIf { highlightStrength > 0f }?.let { b ->
            val left = t.screenX(b.left) - pad
            val top = t.screenY(b.top) - pad
            val right = t.screenX(b.right) + pad
            val bottom = t.screenY(b.bottom) + pad
            fill.color = colors.selection
            fill.alpha = (HIGHLIGHT_FILL * highlightStrength).toInt()
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, fill)
            ring.color = colors.selection
            ring.pathEffect = null
            ring.alpha = (255 * highlightStrength).toInt()
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, ring)
            ring.alpha = 255
        }
        selectionPaint.color = colors.selection
        loopPaint.color = colors.selection
        val ox = offsetX * t.pixelsPerUnit
        val oy = offsetY * t.pixelsPerUnit
        // One frame round the whole selection (Phase 13), not one per stroke.
        selection.reduceOrNull(Bounds::union)?.let { b ->
            canvas.drawRoundRect(t.screenX(b.left) - pad + ox, t.screenY(b.top) - pad + oy, t.screenX(b.right) + pad + ox, t.screenY(b.bottom) + pad + oy, corner, corner, selectionPaint)
        }
        // A picture on its own has a corner handle to resize it (Phase 20), and shows its new size while dragged.
        handle?.let { h ->
            val b = resized ?: h
            if (resized != null) canvas.drawRect(t.screenX(b.left), t.screenY(b.top), t.screenX(b.right), t.screenY(b.bottom), selectionPaint)
            dot.color = colors.selection
            canvas.drawCircle(t.screenX(b.right) + ox, t.screenY(b.bottom) + oy, HANDLE_RADIUS * density, dot)
        }
        if (loop.size > 1) {
            path.reset()
            path.moveTo(t.screenX(loop[0].first), t.screenY(loop[0].second))
            for (i in 1 until loop.size) path.lineTo(t.screenX(loop[i].first), t.screenY(loop[i].second))
            if (loopClosed) path.close()
            canvas.drawPath(path, loopPaint)
        }
        if (eraserRadius > 0f) {
            val r = eraserRadius * t.pixelsPerUnit
            eraserFill.color = colors.selection
            eraserFill.alpha = ERASER_FILL
            canvas.drawCircle(t.screenX(eraserX), t.screenY(eraserY), r, eraserFill)
            eraserEdge.color = colors.selection
            canvas.drawCircle(t.screenX(eraserX), t.screenY(eraserY), r, eraserEdge)
        }
    }

    /** The picture that can be resized (its bounds), or null; and its size while the handle is dragged. */
    fun setHandle(value: Bounds?, preview: Bounds? = null) {
        if (value == handle && preview == resized) return
        handle = value
        resized = preview
        invalidate()
    }

    private var handle: Bounds? = null
    private var resized: Bounds? = null

    companion object {
        private const val PADDING = 8f
        private const val CORNER = 12f
        private const val DOT_RADIUS = 6f
        private const val HIGHLIGHT_FILL = 70
        private const val ERASER_FILL = 40

        /** The resize handle, in dp. */
        const val HANDLE_RADIUS = 10f
    }
}
