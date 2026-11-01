package io.github.iamthebarrow.nibtexa.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.graphics.withSave
import io.github.iamthebarrow.nibtexa.core.export.GraphText
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLabel
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPlotted
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPoint

/** The captures' size, as [TypedRegionsView.MATH_PX_PER_EM]: MathLive's inline maths is 22 CSS pixels per em. */
const val DIAGRAM_MATH_PX_PER_EM = 22f

/** Typeset maths for a label: an ink mask, [width] x [height] in a diagram's units. */
class DiagramMath(val bitmap: Bitmap, val width: Float, val height: Float)

/**
 * Draws a diagram (Phase 31), scaled to fill [bounds] of the page. Only lines and fills, so it is sharp at any zoom and vector in a PDF.
 * Maths labels come from [maths] where they have been typeset, and show as plain text until then.
 */
object DiagramRenderer {
    /** What LaTeX's 10 pt comes to in a diagram's units, for labels on the page to match the export. */
    const val MATH_SIZE = 20f
    private const val TEXT_SIZE = 20f
    private const val INK_WIDTH = 1.4f
    private const val FIT = 0.86f
    private const val MARK_WIDTH = 7f

    fun draw(canvas: Canvas, plot: DiagramPlotted, bounds: Bounds, ink: Int, maths: (String) -> DiagramMath? = { null }, selected: DiagramPart? = null, mark: Int = 0) {
        val diagram = plot.diagram
        val scale = (bounds.right - bounds.left) / diagram.width.coerceAtLeast(1f)
        canvas.withSave {
            translate(bounds.left, bounds.top)
            scale(scale, scale)
            if (selected != null) marked(this, plot, selected, mark)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = DiagramGeometry.LINE_WIDTH
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = ink
            }
            val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(DiagramGeometry.DASH, DiagramGeometry.DASH * 0.7f), 0f) }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = ink
            }
            for (stroke in plot.strokes) {
                val path = path(stroke.points)
                drawPath(path, if (stroke.filled) fill else if (stroke.dashed) dashed else line)
            }
            for (label in diagram.labels) label(this, plot, label, ink, maths)
        }
    }

    private fun path(points: List<DiagramPoint>) = Path().apply {
        points.firstOrNull()?.let { moveTo(it.x, it.y) }
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    private fun label(canvas: Canvas, plot: DiagramPlotted, label: DiagramLabel, ink: Int, maths: (String) -> DiagramMath?) {
        if (label.handwritten) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = INK_WIDTH
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = ink
            }
            label.ink.forEach { canvas.drawPath(path(it), paint) }
            return
        }
        // Held by a shape, it is made smaller if it wouldn't fit inside.
        val shape = plot.diagram.shape(label.shape)?.takeIf { it.width > 2 * TEXT_SIZE && it.height > TEXT_SIZE }
        val roomX = shape?.let { it.width * FIT } ?: Float.MAX_VALUE
        val roomY = shape?.let { it.height * FIT } ?: Float.MAX_VALUE
        val image = label.latex?.let(maths)
        if (image != null) {
            val fit = minOf(1f, roomX / image.width.coerceAtLeast(1f), roomY / image.height.coerceAtLeast(1f))
            val w = image.width * fit
            val h = image.height * fit
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(image.bitmap, null, RectF(label.x - w / 2, label.y - h / 2, label.x + w / 2, label.y + h / 2), paint)
            return
        }
        val words = label.text ?: GraphText.plain(label.latex.orEmpty())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = TEXT_SIZE
            textAlign = Paint.Align.CENTER
        }
        paint.textSize = TEXT_SIZE * minOf(1f, roomX / paint.measureText(words).coerceAtLeast(1f))
        canvas.drawText(words, label.x, label.y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    /** A soft band behind the part chosen in the controls. */
    private fun marked(canvas: Canvas, plot: DiagramPlotted, part: DiagramPart, color: Int) {
        val diagram = plot.diagram
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = MARK_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            alpha = MARK_ALPHA
        }
        when (part) {
            is DiagramPart.Shape -> diagram.shape(part.id)?.let { canvas.drawPath(path(DiagramGeometry.outline(it)), paint) }
            is DiagramPart.Line -> diagram.line(part.id)?.let { canvas.drawPath(path(DiagramGeometry.path(diagram, it)), paint) }
            is DiagramPart.Label -> diagram.label(part.id)?.let { canvas.drawRoundRect(RectF(it.x - MARK_LABEL, it.y - MARK_LABEL / 2, it.x + MARK_LABEL, it.y + MARK_LABEL / 2), 4f, 4f, paint) }
        }
    }

    private const val MARK_ALPHA = 110
    private const val MARK_LABEL = 26f
}
