package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphColors
import java.util.Locale

/**
 * A graph as `pgfplots` (Phase 25). TeX never evaluates an equation: it plots points worked out here, so the PDF it makes
 * matches the app, and no equation runs as TeX. Labels are escaped; a legend or axis name is written as maths only if it
 * passes the same checks as an equation in the document, and as escaped words otherwise.
 */
internal object GraphLatex {
    /** What would break out of `$…$` or a pgfplots option, or isn't needed in a name or an equation that was read. */
    private val BREAKS_OUT = Regex("""[$%#&~]|\\[\[\]()]""")

    fun axis(graph: Graph, widthInches: Float, heightInches: Float): String = buildString {
        val plot = GraphPlot.of(graph, GraphPlot.TEX_POINTS)
        appendLine("\\begin{tikzpicture}")
        appendLine("\\begin{axis}[")
        appendLine("  width=${decimal(widthInches)}in, height=${decimal(heightInches)}in,")
        appendLine("  xmin=${number(graph.xMin)}, xmax=${number(graph.xMax)}, ymin=${number(graph.yMin)}, ymax=${number(graph.yMax)},")
        appendLine("  xtick={${plot.xTicks.joinToString(",", transform = ::number)}}, ytick={${plot.yTicks.joinToString(",", transform = ::number)}},")
        appendLine("  scaled ticks=false, grid=major, legend cell align=left, legend pos=north east,")
        appendLine("  legend style={fill opacity=0.75, draw opacity=1, text opacity=1},")
        appendLine("  xlabel={${graph.xLabel?.let(LatexExporter::escapeText) ?: maths(plot.xName)}},")
        appendLine("  ylabel={${graph.yLabel?.let(LatexExporter::escapeText) ?: maths(plot.yName)}},")
        graph.title?.let { appendLine("  title={${LatexExporter.escapeText(it)}},") }
        appendLine("]")
        for (curve in plot.shown) {
            val style = "${colour(curve.curve.color)}, thick, no markers"
            if (curve.pieces.isEmpty()) {
                // Off the graph here, but still in the legend, as on the screen.
                appendLine("\\addlegendimage{$style}")
            }
            curve.pieces.forEachIndexed { i, piece ->
                val points = piece.xs.indices.joinToString(" ") { "(${number(piece.xs[it])},${number(piece.ys[it])})" }
                appendLine("\\addplot[$style${if (i > 0) ", forget plot" else ""}] coordinates {$points};")
            }
            appendLine("\\addlegendentry{${maths(curve.curve.latex)}}")
        }
        appendLine("\\end{axis}")
        appendLine("\\end{tikzpicture}")
    }

    /** [latex] as maths if it's safe there, or as words. */
    private fun maths(latex: String): String {
        val inner = latex.trim().removeSurrounding("\\[", "\\]").removeSurrounding("$").removeSurrounding("$").trim()
        return if (LatexExporter.unsafe(inner) == null && !BREAKS_OUT.containsMatchIn(inner)) "\$$inner\$" else LatexExporter.escapeText(GraphText.plain(latex))
    }

    private fun colour(index: Int): String {
        val rgb = GraphColors.RGB[index.mod(GraphColors.RGB.size)]
        return "color={rgb,255:red,${rgb shr 16 and 0xFF};green,${rgb shr 8 and 0xFF};blue,${rgb and 0xFF}}"
    }

    private fun decimal(value: Float) = "%.2f".format(Locale.ROOT, value)

    /** Fixed across devices, in a form TeX reads, and short: no trailing zeros. */
    private fun number(value: Double): String {
        val text = "%.7g".format(Locale.ROOT, if (value == 0.0) 0.0 else value)
        if ('e' in text) return text
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }
}
