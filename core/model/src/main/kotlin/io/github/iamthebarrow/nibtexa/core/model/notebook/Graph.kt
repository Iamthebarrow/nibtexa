package io.github.iamthebarrow.nibtexa.core.model.notebook

import kotlin.math.abs

/**
 * One equation on a graph (Phase 25).
 *
 * @property latex the equation. It follows its source while that exists, and stays as it last was if the source goes.
 * @property source the math region on the same page it came from, which corrections follow; null for an equation
 *   from another page, which is kept as it was added.
 * @property color an index into [GraphColors].
 * @property input the horizontal axis, when the user chose it because the equation didn't make it clear.
 */
data class GraphCurve(
    val latex: String,
    val source: String? = null,
    val color: Int = 0,
    val hidden: Boolean = false,
    val input: String? = null,
)

/**
 * What a graph shows (Phase 25): its equations and view. Only this is stored, never points or a picture, so the graph
 * is drawn fresh at any size and in any export. [constants] are the user's values for letters other than the axis.
 */
data class Graph(
    val curves: List<GraphCurve>,
    val xMin: Double = -DEFAULT_RANGE,
    val xMax: Double = DEFAULT_RANGE,
    val yMin: Double = -DEFAULT_RANGE,
    val yMax: Double = DEFAULT_RANGE,
    val degrees: Boolean = false,
    val constants: Map<String, Double> = emptyMap(),
    val title: String? = null,
    val xLabel: String? = null,
    val yLabel: String? = null,
) {
    /** The next curve's colour: the first not in use. */
    fun nextColor(): Int = GraphColors.RGB.indices.firstOrNull { c -> curves.none { it.color == c } } ?: (curves.size % GraphColors.RGB.size)

    /** The same graph, showing these ranges if they can be shown; unchanged if not. */
    fun withRange(xMin: Double, xMax: Double, yMin: Double, yMax: Double): Graph {
        val usable = listOf(xMin, xMax, yMin, yMax).all { it.isFinite() && abs(it) <= MAX_VALUE } &&
            xMax - xMin in MIN_SPAN..MAX_SPAN && yMax - yMin in MIN_SPAN..MAX_SPAN
        return if (usable) copy(xMin = xMin, xMax = xMax, yMin = yMin, yMax = yMax) else this
    }

    /**
     * After a drag or pinch: [zoom] times larger, with what was at ([fromX], [fromY]) now at ([toX], [toY]). Positions
     * are shares of the plot area from its top-left corner, so this doesn't depend on its size on the screen.
     */
    fun moved(fromX: Double, fromY: Double, toX: Double, toY: Double, zoom: Double): Graph {
        val xSpan = xMax - xMin
        val ySpan = yMax - yMin
        val newX = (xSpan / zoom).coerceIn(MIN_SPAN, MAX_SPAN)
        val newY = (ySpan / zoom).coerceIn(MIN_SPAN, MAX_SPAN)
        val left = xMin + fromX * xSpan - toX * newX
        val top = yMax - fromY * ySpan + toY * newY
        return withRange(left, left + newX, top - newY, top)
    }

    /** A graph as read from a file: within limits, and whole, whatever the file held. */
    fun checked(): Graph {
        val base = copy(
            curves = curves.take(MAX_CURVES).map { it.copy(color = it.color.mod(GraphColors.RGB.size)) },
            constants = constants.filterValues { it.isFinite() },
            xMin = -DEFAULT_RANGE,
            xMax = DEFAULT_RANGE,
            yMin = -DEFAULT_RANGE,
            yMax = DEFAULT_RANGE,
        )
        return base.withRange(xMin, xMax, yMin, yMax)
    }

    companion object {
        const val DEFAULT_RANGE = 10.0
        const val MAX_CURVES = 8
        private const val MIN_SPAN = 1e-6
        private const val MAX_SPAN = 1e9
        private const val MAX_VALUE = 1e12
    }
}

/** Curve colours, clear on paper in both themes and when printed, in the order they're given out. */
object GraphColors {
    val RGB = listOf(0x0072BD, 0xD95319, 0x77AC30, 0x7E2F8E, 0x00A5A5, 0xA2142F, 0xC49A00, 0x5A5A5A)
}
