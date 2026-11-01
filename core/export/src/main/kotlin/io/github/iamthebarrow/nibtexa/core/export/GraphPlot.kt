package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.maths.AngleUnit
import io.github.iamthebarrow.nibtexa.core.maths.CurvePiece
import io.github.iamthebarrow.nibtexa.core.maths.Equation
import io.github.iamthebarrow.nibtexa.core.maths.MathsException
import io.github.iamthebarrow.nibtexa.core.maths.MathsProblem
import io.github.iamthebarrow.nibtexa.core.maths.Plot
import io.github.iamthebarrow.nibtexa.core.maths.Range
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve
import kotlin.math.ceil
import kotlin.math.floor

/** Why a curve isn't drawn (Phase 25). */
sealed interface CurveProblem {
    /** The engine can't read it, and says why. */
    data class CantRead(val problem: MathsProblem) : CurveProblem

    /** These letters need values. */
    data class NeedsValues(val names: List<String>) : CurveProblem
}

/** One curve worked out: what can be drawn of it, or why it can't be. */
class PlottedCurve(
    val index: Int,
    val curve: GraphCurve,
    val equation: Equation?,
    val pieces: List<CurvePiece>,
    val problem: CurveProblem?,
) {
    val label: String get() = GraphText.plain(curve.latex)
}

/** A graph worked out for drawing, with its ticks. Everything that draws a graph uses this, so they all agree. */
class GraphPlotted(val graph: Graph, val curves: List<PlottedCurve>) {
    /** The curves shown: not hidden, and readable. */
    val shown: List<PlottedCurve> get() = curves.filter { !it.curve.hidden && it.problem == null }
    val xTicks: List<Double> = Plot.ticks(Range(graph.xMin, graph.xMax))
    val yTicks: List<Double> = Plot.ticks(Range(graph.yMin, graph.yMax))
    val xStep: Double = Plot.tickStep(graph.xMax - graph.xMin)
    val yStep: Double = Plot.tickStep(graph.yMax - graph.yMin)

    /** The axis names from the first curve shown, as the equation writes them; the user's own labels replace them. */
    val xName: String get() = shown.firstOrNull()?.equation?.input ?: "x"
    val yName: String get() = shown.firstOrNull()?.equation?.output ?: "y"
}

/** Works out graphs: from what's stored to curves and ticks. Pure, so screen, PDF and LaTeX share it. */
object GraphPlot {
    /** Points per curve in LaTeX: enough to look smooth in print, and few enough for TeX's memory. */
    const val TEX_POINTS = 300

    /** Reads a curve's equation, as far as that goes: the equation, or why it can't be read. */
    fun read(curve: GraphCurve): Result<Equation> = try {
        Result.success(Equation.parse(curve.latex, curve.input))
    } catch (e: MathsException) {
        Result.failure(e)
    }

    fun of(graph: Graph, points: Int = Plot.MAX_POINTS): GraphPlotted {
        val x = Range(graph.xMin, graph.xMax)
        val y = Range(graph.yMin, graph.yMax)
        return GraphPlotted(
            graph,
            graph.curves.mapIndexed { i, curve ->
                val read = read(curve)
                val equation = read.getOrNull()
                val problem = when {
                    equation == null -> CurveProblem.CantRead((read.exceptionOrNull() as MathsException).problem)
                    equation.missing(graph.constants).isNotEmpty() -> CurveProblem.NeedsValues(equation.missing(graph.constants))
                    else -> null
                }
                val pieces = if (problem != null || curve.hidden) emptyList() else Plot.sample(equation!!.function(graph.constants, angle(graph)), x, y, points)
                PlottedCurve(i, curve, equation, pieces, problem)
            },
        )
    }

    private fun angle(graph: Graph) = if (graph.degrees) AngleUnit.DEGREES else AngleUnit.RADIANS

    /**
     * The vertical range fitted to the curves shown across the graph's horizontal range, out to round ticks. The graph
     * as it is, if nothing can be drawn.
     */
    fun fitted(graph: Graph): Graph {
        val x = Range(graph.xMin, graph.xMax)
        val ranges = graph.curves.filter { !it.hidden }.mapNotNull { curve ->
            val equation = read(curve).getOrNull()?.takeIf { it.missing(graph.constants).isEmpty() } ?: return@mapNotNull null
            val f = equation.function(graph.constants, angle(graph))
            // A curve with no values across the range has nothing to fit.
            if ((0..50).none { f(x.min + x.span * it / 50).isFinite() }) null else Plot.autoRange(f, x)
        }
        if (ranges.isEmpty()) return graph
        val low = ranges.minOf { it.min }
        val high = ranges.maxOf { it.max }
        val step = Plot.tickStep(high - low)
        return graph.withRange(graph.xMin, graph.xMax, floor(low / step) * step, ceil(high / step) * step)
    }
}

/** Equations as plain words, for a graph's legend, drawn as text on the screen and in the PDF. */
object GraphText {
    private val SYMBOLS = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "hbar" to "ħ", "ell" to "ℓ", "cdot" to "·", "times" to "×", "div" to "÷", "ast" to "*",
    )
    private val DROPPED = setOf("left", "right", "displaystyle", "textstyle", "scriptstyle", "mathrm", "mathit", "mathbf", "mathsf", "mathtt", "boldsymbol", "bm", "text", "textrm", "textit", "mbox", "operatorname", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "limits", "nolimits")
    private val FRACTIONS = setOf("frac", "dfrac", "tfrac", "cfrac")
    private val SUPERSCRIPTS = mapOf('0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴", '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹")
    private const val MAX_LENGTH = 60
    private const val MAX_DEPTH = 40

    /** [latex] as short plain text: `y=\frac{1}{x}` reads `y = 1/x`. Too long, it ends in an ellipsis. */
    fun plain(latex: String): String {
        val text = Reader(latex).read(0).replace(Regex("\\s+"), " ").replace(Regex("\\s*=\\s*"), " = ").trim()
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text
    }

    private class Reader(private val s: String) {
        private var i = 0

        /** Up to the end, or the `}` that closes what called it. */
        fun read(depth: Int): String = buildString {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '}' -> return@buildString
                    c == '{' -> append(group(depth))
                    c == '\\' -> append(command(depth))
                    c == '^' -> {
                        i++
                        append(power(argument(depth)))
                    }
                    c == '_' -> {
                        i++
                        append("_").append(argument(depth))
                    }
                    c == '$' -> i++
                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }

        private fun group(depth: Int): String {
            i++
            val inner = if (depth < MAX_DEPTH) read(depth + 1) else ""
            if (s.getOrNull(i) == '}') i++
            return inner
        }

        /** The next braces, or the next single character or command. */
        private fun argument(depth: Int): String {
            while (s.getOrNull(i)?.isWhitespace() == true) i++
            return when (s.getOrNull(i)) {
                null -> ""
                '{' -> group(depth)
                '\\' -> command(depth)
                else -> s[i++].toString()
            }
        }

        private fun power(p: String): String = if (p.isNotEmpty() && p.all { it in SUPERSCRIPTS }) p.map(SUPERSCRIPTS::getValue).joinToString("") else if (p.length == 1) "^$p" else "^($p)"

        /** Brackets only where the reading needs them: `1/x`, but `(x+1)/2`. */
        private fun bracketed(s: String): String = if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '.' }) s else "($s)"

        private fun command(depth: Int): String {
            i++
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            if (i == start) {
                // `\,`, `\[`, `\{` and so on: a bracket stays, the rest only affect the look.
                val c = s.getOrNull(i++)
                return if (c == '{' || c == '}' || c == '|') c.toString() else ""
            }
            val name = s.substring(start, i)
            return when {
                name in FRACTIONS -> {
                    val top = argument(depth)
                    val bottom = argument(depth)
                    "${bracketed(top)}/${bracketed(bottom)}"
                }
                name == "sqrt" -> {
                    val index = if (s.getOrNull(i) == '[') {
                        val end = s.indexOf(']', i).takeIf { it >= 0 } ?: s.lastIndex
                        s.substring(i + 1, end).also { i = end + 1 }
                    } else {
                        ""
                    }
                    val inner = argument(depth)
                    if (index.isEmpty()) "√${bracketed(inner)}" else "${bracketed(inner)}^(1/$index)"
                }
                name in SYMBOLS -> SYMBOLS.getValue(name)
                name in DROPPED -> ""
                name == "quad" || name == "qquad" -> " "
                // Functions, and anything else the engine knows, by their own names.
                else -> "$name "
            }
        }
    }
}
