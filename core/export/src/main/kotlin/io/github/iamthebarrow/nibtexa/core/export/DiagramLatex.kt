package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPoint
import java.util.Locale

/**
 * A diagram as plain TikZ (Phase 31): the same paths the page draws, so that it needs no library, and circuit symbols don't need
 * `circuitikz`. Labels are set by LaTeX, as maths if they pass the same checks as an equation in the document, and as escaped words
 * otherwise. Handwriting still unread is drawn as it is.
 */
internal object DiagramLatex {
    /** What would break out of `$…$`, or isn't needed in a label. */
    private val BREAKS_OUT = Regex("""[$%#&~]|\\[\[\]()]""")

    private const val FIT = 0.86f
    private const val MIN_ROOM = 40f

    /** The preamble's part: what sets a label inside its shape. */
    val PREAMBLE = listOf(
        "\\usepackage{tikz}",
        "\\newsavebox{\\nibinsidebox}",
        "\\newcommand{\\nibinside}[2]{\\sbox{\\nibinsidebox}{#2}\\ifdim\\wd\\nibinsidebox>#1\\resizebox{#1}{!}{\\usebox{\\nibinsidebox}}\\else\\usebox{\\nibinsidebox}\\fi}",
    )

    fun picture(diagram: Diagram, widthInches: Float): String = buildString {
        val unit = widthInches / diagram.width.coerceAtLeast(1f)
        // The diagram's units run down the page, as the page's do.
        appendLine("\\begin{tikzpicture}[x=${decimal(unit)}in, y=-${decimal(unit)}in, line width=0.8pt, line cap=round, line join=round]")
        for (stroke in DiagramGeometry.plot(diagram).strokes) {
            val command = when {
                stroke.filled -> "\\fill"
                stroke.dashed -> "\\draw[dash pattern=on 4pt off 3pt]"
                else -> "\\draw"
            }
            appendLine("$command ${path(stroke.points)};")
        }
        for (label in diagram.labels) {
            val content = label.latex?.let(::maths) ?: label.text?.let(LatexExporter::escapeText)
            if (content != null) {
                // Inside a shape it is made smaller if it wouldn't fit, as on the page.
                val room = diagram.shape(label.shape)?.takeIf { it.width > MIN_ROOM }?.let { it.width * FIT * unit }
                val fitted = if (room != null) "\\nibinside{${decimal(room)}in}{$content}" else content
                appendLine("\\node at (${number(label.x)},${number(label.y)}) {$fitted};")
            } else {
                label.ink.filter { it.size >= 2 }.forEach { appendLine("\\draw[line width=0.6pt] ${path(it)};") }
            }
        }
        appendLine("\\end{tikzpicture}")
    }

    private fun maths(latex: String): String =
        if (LatexExporter.unsafe(latex) == null && !BREAKS_OUT.containsMatchIn(latex)) "\$\\displaystyle $latex\$" else LatexExporter.escapeText(latex)

    private fun path(points: List<DiagramPoint>) = points.joinToString(" -- ") { "(${number(it.x)},${number(it.y)})" }

    private fun number(value: Float) = "%.1f".format(Locale.ROOT, value)

    private fun decimal(value: Float) = "%.5f".format(Locale.ROOT, value)
}
