package io.github.iamthebarrow.nibtexa.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import kotlin.math.ceil

/**
 * The page: its sheet of paper (Phase 19), with a faint edge on the desk, and its background
 * blank, ruled or grid. Display only; never exported. The sheet and its lines are in page units,
 * so they zoom and move with the ink ([PageTransform], Phase 17). An imported PDF page (Phase 20)
 * fills the sheet instead of the lines.
 */
internal class PaperView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var paper = Paper.BLANK
    private var sheet = Sheet()
    private var backdrop: Bitmap? = null
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var transform = PageTransform(density)
    private val line = Paint().apply {
        color = InkColors.LIGHT.rule
        strokeWidth = density
    }
    /** Where writing goes (Phase 30), shown by a faint line; null on a page with no margin. */
    private var area: Bounds? = null
    private val marginLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = InkColors.LIGHT.attention
        alpha = MARGIN_ALPHA
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val fill = Paint().apply { color = InkColors.LIGHT.paper }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = InkColors.LIGHT.edge
        strokeWidth = density
    }

    fun setPaper(value: Paper) {
        if (value == paper) return
        paper = value
        invalidate()
    }

    fun setArea(value: Bounds?) {
        if (value == area) return
        area = value
        invalidate()
    }

    fun setSheet(value: Sheet) {
        if (value == sheet) return
        sheet = value
        invalidate()
    }

    /** The picture filling the sheet (Phase 20: an imported PDF page), or null for plain paper. */
    fun setBackdrop(value: Bitmap?) {
        if (value === backdrop) return
        backdrop = value
        invalidate()
    }

    fun setTransform(value: PageTransform) {
        transform = value
        invalidate()
    }

    /** The sheet's colours (light or dark paper, Phase 13). */
    fun setColors(value: InkColors) {
        line.color = value.rule
        fill.color = value.paper
        edge.color = value.edge
        marginLine.color = value.attention
        marginLine.alpha = MARGIN_ALPHA
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = transform
        val left = t.screenX(0f)
        val top = t.screenY(0f)
        val right = t.screenX(sheet.width)
        val bottom = t.screenY(sheet.height)
        canvas.drawRect(left, top, right, bottom, fill)
        backdrop?.let { canvas.drawBitmap(it, null, RectF(left, top, right, bottom), backdropPaint) }
        canvas.drawRect(left, top, right, bottom, edge)
        if (paper != Paper.BLANK && backdrop == null) drawLines(canvas, left, top, right, bottom)
        area?.let { a ->
            // Only the sides that have a margin: ruled paper has none at the top and bottom.
            val l = t.screenX(a.left)
            val r = t.screenX(a.right)
            val at = t.screenY(a.top)
            val ab = t.screenY(a.bottom)
            canvas.drawLine(l, at, l, ab, marginLine)
            canvas.drawLine(r, at, r, ab, marginLine)
            if (a.top > 0f) canvas.drawLine(l, at, r, at, marginLine)
            if (a.bottom < sheet.height) canvas.drawLine(l, ab, r, ab, marginLine)
        }
    }

    private fun drawLines(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val t = transform
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        // The first line on screen, then one every [Paper.spacing] page units, as far as the sheet goes.
        val step = paper.spacing
        var y = ceil(maxOf(t.pageY(0f), 0f) / step).coerceAtLeast(1f) * step
        while (y < sheet.height && t.screenY(y) < height) {
            val sy = t.screenY(y)
            canvas.drawLine(left, sy, right, sy, line)
            y += step
        }
        if (paper == Paper.GRID) {
            var x = ceil(maxOf(t.pageX(0f), 0f) / step).coerceAtLeast(1f) * step
            while (x < sheet.width && t.screenX(x) < width) {
                val sx = t.screenX(x)
                canvas.drawLine(sx, top, sx, bottom, line)
                x += step
            }
        }
        canvas.restore()
    }

    private companion object {
        const val MARGIN_ALPHA = 110
    }
}
