package io.github.iamthebarrow.nibtexa.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import io.github.iamthebarrow.nibtexa.core.export.GraphPlotted
import io.github.iamthebarrow.nibtexa.core.export.GraphText
import io.github.iamthebarrow.nibtexa.core.maths.Plot
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphColors

/** A graph's colours for where it is shown: the page, in either theme, or an export. */
data class GraphStyle(val ink: Int, val grid: Int, val paper: Int) {
    companion object {
        fun of(colors: InkColors) = GraphStyle(colors.text, colors.rule, colors.paper)
    }
}

/**
 * Draws a graph (Phase 25) in page units as a boxed plot: grid, ticks and labels, axis names, a title and a legend. It only
 * draws lines and text, so it is sharp at any zoom and vector in a PDF, and the page, reading view and exports look the same.
 */
object GraphRenderer {
    private const val LEFT = 58f
    private const val RIGHT = 12f
    private const val TOP = 12f
    private const val TITLE = 26f
    private const val BOTTOM = 42f
    private const val TICK = 5f
    private const val LABEL_SIZE = 11f
    private const val NAME_SIZE = 13f
    private const val TITLE_SIZE = 15f
    private const val LEGEND_ROW = 18f
    private const val LEGEND_SAMPLE = 22f
    private const val LEGEND_PAD = 6f

    /** Curves show faintly through the legend, rather than disappearing behind it. */
    private const val LEGEND_ALPHA = 190

    /** Where the curves go in [bounds]. A touch inside the graph maps through this. */
    fun plotArea(graph: Graph, bounds: Bounds): RectF {
        val top = bounds.top + TOP + if (graph.title != null) TITLE else 0f
        val left = bounds.left + LEFT
        // Never inside out, however small the graph is made.
        return RectF(left, top, maxOf(left + 1f, bounds.right - RIGHT), maxOf(top + 1f, bounds.bottom - BOTTOM))
    }

    fun draw(canvas: Canvas, plot: GraphPlotted, bounds: Bounds, style: GraphStyle) {
        val graph = plot.graph
        val area = plotArea(graph, bounds)
        val x = { value: Double -> area.left + ((value - graph.xMin) / (graph.xMax - graph.xMin)).toFloat() * area.width() }
        val y = { value: Double -> area.bottom - ((value - graph.yMin) / (graph.yMax - graph.yMin)).toFloat() * area.height() }

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = 0.8f; color = style.grid }
        for (t in plot.xTicks) canvas.drawLine(x(t), area.top, x(t), area.bottom, line)
        for (t in plot.yTicks) canvas.drawLine(area.left, y(t), area.right, y(t), line)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.ink; textSize = LABEL_SIZE }
        line.color = style.ink
        line.strokeWidth = 1f
        text.textAlign = Paint.Align.CENTER
        for (t in plot.xTicks) {
            canvas.drawLine(x(t), area.bottom, x(t), area.bottom - TICK, line)
            canvas.drawText(Plot.label(t, plot.xStep), x(t), area.bottom + LABEL_SIZE + 4f, text)
        }
        text.textAlign = Paint.Align.RIGHT
        for (t in plot.yTicks) {
            canvas.drawLine(area.left, y(t), area.left + TICK, y(t), line)
            canvas.drawText(Plot.label(t, plot.yStep), area.left - 5f, y(t) + LABEL_SIZE / 3, text)
        }

        canvas.withClip(area) {
            val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
            for (shown in plot.shown) {
                curve.color = rgb(shown.curve.color)
                val path = Path()
                for (piece in shown.pieces) {
                    path.moveTo(x(piece.xs[0]), y(piece.ys[0]))
                    for (i in 1 until piece.xs.size) path.lineTo(x(piece.xs[i]), y(piece.ys[i]))
                    // A lone point still shows.
                    if (piece.xs.size == 1) path.lineTo(x(piece.xs[0]) + 0.5f, y(piece.ys[0]))
                }
                drawPath(path, curve)
            }
        }
        canvas.drawRect(area, line)

        text.textAlign = Paint.Align.CENTER
        text.textSize = NAME_SIZE
        canvas.drawText(graph.xLabel ?: GraphText.plain(plot.xName), area.centerX(), area.bottom + LABEL_SIZE + NAME_SIZE + 9f, text)
        canvas.withRotation(-90f, bounds.left + NAME_SIZE, area.centerY()) {
            drawText(graph.yLabel ?: GraphText.plain(plot.yName), bounds.left + NAME_SIZE, area.centerY(), text)
        }
        graph.title?.let {
            text.textSize = TITLE_SIZE
            text.isFakeBoldText = true
            canvas.drawText(it, area.centerX(), bounds.top + TITLE_SIZE + 4f, text)
            text.isFakeBoldText = false
        }
        legend(canvas, plot, area, style, text, line)
    }

    private fun legend(canvas: Canvas, plot: GraphPlotted, area: RectF, style: GraphStyle, text: Paint, line: Paint) {
        val shown = plot.shown
        if (shown.isEmpty()) return
        text.textSize = LABEL_SIZE
        text.textAlign = Paint.Align.LEFT
        val labels = shown.map { it.label }
        val width = LEGEND_SAMPLE + 3 * LEGEND_PAD + labels.maxOf { text.measureText(it) }
        val height = LEGEND_ROW * shown.size + LEGEND_PAD
        val box = RectF(maxOf(area.left, area.right - width - LEGEND_PAD), area.top + LEGEND_PAD, area.right - LEGEND_PAD, area.top + LEGEND_PAD + height)
        val fill = Paint().apply { color = style.paper; alpha = LEGEND_ALPHA; this.style = Paint.Style.FILL }
        canvas.drawRect(box, fill)
        line.strokeWidth = 0.8f
        canvas.drawRect(box, line)
        val sample = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = 1.6f }
        canvas.withClip(box) {
            shown.forEachIndexed { i, curve ->
                val rowY = box.top + LEGEND_PAD / 2 + LEGEND_ROW * (i + 0.5f)
                sample.color = rgb(curve.curve.color)
                drawLine(box.left + LEGEND_PAD, rowY, box.left + LEGEND_PAD + LEGEND_SAMPLE, rowY, sample)
                drawText(labels[i], box.left + 2 * LEGEND_PAD + LEGEND_SAMPLE, rowY + LABEL_SIZE / 3, text)
            }
        }
    }

    /** A curve's colour for drawing. */
    fun rgb(index: Int): Int = 0xFF000000.toInt() or GraphColors.RGB[index.mod(GraphColors.RGB.size)]
}
