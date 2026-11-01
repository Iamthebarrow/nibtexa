package io.github.iamthebarrow.nibtexa.core.maths

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A range on an axis. */
data class Range(val min: Double, val max: Double) {
    val span: Double get() = max - min
}

/** One unbroken piece of a curve: x and y values at the same positions. */
class CurvePiece(val xs: DoubleArray, val ys: DoubleArray)

/**
 * Sampling a function into a curve to draw (Phase 24): evenly spaced points across the x range, in unbroken pieces.
 * A curve breaks where it is undefined or infinite, and across a pole or jump, so `1/x` and `\tan x` aren't joined by
 * a false vertical line. Points beyond the y range are kept only beside one inside it, so a line runs cleanly off the
 * edge of the graph, without wasting points.
 */
object Plot {
    /** The most points a curve is sampled at. */
    const val MAX_POINTS = 1000

    /** A step this share of the y range or larger is checked for being a real jump. */
    private const val JUMP_SHARE = 0.02
    private const val BISECTIONS = 24

    fun sample(f: (Double) -> Double, x: Range, y: Range, points: Int = MAX_POINTS): List<CurvePiece> {
        val count = points.coerceIn(2, MAX_POINTS)
        val xs = DoubleArray(count) { x.min + x.span * it / (count - 1) }
        val ys = DoubleArray(count) { f(xs[it]) }
        val pieces = mutableListOf<CurvePiece>()
        val pieceX = ArrayList<Double>()
        val pieceY = ArrayList<Double>()
        fun end() {
            if (pieceX.size >= 2 || (pieceX.size == 1 && pieceY[0] in y.min..y.max)) {
                pieces += withinReach(CurvePiece(pieceX.toDoubleArray(), pieceY.toDoubleArray()), y)
            }
            pieceX.clear()
            pieceY.clear()
        }
        for (i in 0 until count) {
            val value = ys[i]
            if (!value.isFinite()) {
                end()
                continue
            }
            if (i > 0 && ys[i - 1].isFinite() && isBreak(f, xs[i - 1], ys[i - 1], xs[i], value, y)) end()
            val near = value in y.min..y.max ||
                (i > 0 && ys[i - 1].isFinite() && ys[i - 1] in y.min..y.max) ||
                (i < count - 1 && ys[i + 1].isFinite() && ys[i + 1] in y.min..y.max)
            if (near) {
                pieceX += xs[i]
                pieceY += value
            } else {
                end()
            }
        }
        end()
        return pieces
    }

    /**
     * A point near a pole can be huge, which graphics and TeX handle badly. Those beyond one more y range are brought to
     * that distance along the line from their neighbour, so what shows on the graph is exactly the same.
     */
    private fun withinReach(piece: CurvePiece, y: Range): CurvePiece {
        val low = y.min - y.span
        val high = y.max + y.span
        val xs = piece.xs
        val ys = piece.ys
        for (i in ys.indices) {
            val value = ys[i]
            if (value in low..high) continue
            val target = if (value > high) high else low
            val near = listOf(i - 1, i + 1).firstOrNull { ys.getOrNull(it)?.let { n -> n in low..high } == true }
            if (near != null) xs[i] = xs[near] + (xs[i] - xs[near]) * (target - ys[near]) / (value - ys[near])
            ys[i] = target
        }
        return piece
    }

    /**
     * Whether the curve really breaks between two points far apart in y. Narrowing in on the larger half: a steep but
     * continuous curve's step shrinks away, while a pole's grows and a jump's stays.
     */
    private fun isBreak(f: (Double) -> Double, x0: Double, y0: Double, x1: Double, y1: Double, y: Range): Boolean {
        if (abs(y1 - y0) < JUMP_SHARE * y.span) return false
        var a = x0
        var b = x1
        var fa = y0
        var fb = y1
        val first = abs(y1 - y0)
        repeat(BISECTIONS) {
            val mid = (a + b) / 2
            val fm = f(mid)
            if (!fm.isFinite()) return true
            if (abs(fm - fa) >= abs(fb - fm)) {
                b = mid
                fb = fm
            } else {
                a = mid
                fa = fm
            }
        }
        return abs(fb - fa) > first * 0.5
    }

    /**
     * A sensible y range for [f] across [x]: the middle of its values with a margin, so a pole's huge values don't
     * squash the rest of the curve flat. A flat curve gets a range round its value, and one with no values at all the
     * [fallback].
     */
    fun autoRange(f: (Double) -> Double, x: Range, fallback: Range = Range(-10.0, 10.0), points: Int = 400): Range {
        val values = (0 until points).map { f(x.min + x.span * it / (points - 1)) }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) return fallback
        val low = values[(values.size * 0.02).toInt()]
        val high = values[((values.size - 1) * 0.98).toInt()]
        val span = high - low
        if (span <= 1e-12 * maxOf(1.0, abs(low))) {
            val margin = maxOf(1.0, abs(low) * 0.5)
            return Range(low - margin, low + margin)
        }
        return Range(low - span * 0.08, high + span * 0.08)
    }

    /** Multiples of a round step (1, 2 or 5 times a power of ten) that give 5 to 10 ticks across [range]. */
    fun ticks(range: Range): List<Double> {
        val step = tickStep(range.span)
        val first = ceil(range.min / step - 1e-9)
        val last = floor(range.max / step + 1e-9)
        val count = (last - first).toInt().coerceAtMost(40)
        return (0..count).map { i ->
            val value = (first + i) * step
            if (abs(value) < step * 1e-6) 0.0 else value
        }
    }

    /** The round step for ticks across a range of width [span]. */
    fun tickStep(span: Double): Double {
        require(span > 0) { "A range needs a width" }
        val magnitude = 10.0.pow(floor(log10(span / MAX_TICKS)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { span / it <= MAX_TICKS }
    }

    /** A tick's value as short text, with as few decimals as its step needs: `0.25`, `1000`, `2e-05`. */
    fun label(value: Double, step: Double): String {
        if (value == 0.0) return "0"
        val size = abs(value)
        if (size >= 1e5 || size < 1e-3) {
            val exponent = floor(log10(size)).toInt()
            val mantissa = value / 10.0.pow(exponent)
            return "${trimmed(mantissa, 2)}e$exponent"
        }
        val decimals = (-floor(log10(step) + 1e-9)).toInt().coerceIn(0, 6)
        return trimmed(value, decimals)
    }

    private fun trimmed(value: Double, decimals: Int): String {
        val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }

    private const val MAX_TICKS = 10
}
