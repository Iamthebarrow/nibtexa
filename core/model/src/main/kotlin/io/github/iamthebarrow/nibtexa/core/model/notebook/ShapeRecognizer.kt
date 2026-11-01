package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind { LINE, ARROW, CIRCLE, ELLIPSE, TRIANGLE, RECTANGLE, SQUARE }

/** A shape recognized from a hand-drawn stroke: its [points] replace the stroke's. */
class PerfectShape(val kind: ShapeKind, val points: List<InkPoint>, val corners: List<Pair<Float, Float>> = emptyList())

/**
 * Turns a hand-drawn stroke into a perfect shape (Phase 23), using plain geometry. Each candidate shape
 * is fitted to the stroke, and its error measured both ways: how far the stroke strays from the shape,
 * and how far the shape strays from the stroke. So half a circle isn't a circle, and a scribble fits
 * nothing. The closest candidate within [MAX_ERROR] of the stroke's size wins; otherwise the stroke is
 * left as it is.
 *
 * With snapping, a line, arrow, rectangle or ellipse nearly at a multiple of [SNAP_STEP_DEGREES] is turned
 * to it, which keeps horizontal and vertical straight.
 */
object ShapeRecognizer {
    private const val SAMPLES = 128

    /** Strokes smaller than this, in page units, stay as they are: too little to tell what they are. */
    private const val MIN_SIZE = 16.0

    /** How far a shape may be from the stroke, as a share of the stroke's diagonal. */
    private const val MAX_ERROR = 0.035
    private const val MAX_OVAL_ERROR = 0.024

    /** The ends of a closed shape are at most this share of its diagonal apart. */
    private const val CLOSED_GAP = 0.2

    /** A closed shape's path is at least this many times its diagonal: a retraced line isn't one. */
    private const val MIN_CLOSED_PATH = 1.7

    /** A closed shape is at least this thick, as a share of its diagonal. */
    private const val MIN_THICKNESS = 0.12


    private const val SQUARE_RATIO = 1.15
    private const val CIRCLE_RATIO = 1.15
    const val SNAP_STEP_DEGREES = 15.0
    private const val SNAP_WITHIN_DEGREES = 5.0

    // Generated strokes are dense and unhurried, so the ink library's smoothing keeps their corners.
    private const val STEP_UNITS = 4.0
    private const val MAX_POINTS = 600
    private const val MILLIS_PER_POINT = 12L

    /**
     * The shape [stroke] looks like, or null if it isn't clearly any of them. A [tolerance] above 1 accepts a rougher stroke (Phase 31:
     * in a diagram, where everything drawn is meant to be a shape).
     */
    fun recognize(stroke: List<InkPoint>, snap: Boolean = true, tolerance: Double = 1.0): PerfectShape? {
        val raw = stroke.map { P(it.x.toDouble(), it.y.toDouble()) }.distinctBy { it.x to it.y }
        if (raw.size < 3) return null
        val diagonal = hypot(raw.maxOf { it.x } - raw.minOf { it.x }, raw.maxOf { it.y } - raw.minOf { it.y })
        if (diagonal < MIN_SIZE) return null
        val samples = resample(raw)
        val path = pathLength(raw)
        val gap = samples.first().distance(samples.last())
        val closed = gap < CLOSED_GAP * diagonal && path > MIN_CLOSED_PATH * diagonal

        val best = if (closed) closedShape(samples, diagonal, snap, tolerance) else openShape(samples, diagonal, path, snap, tolerance)
        return best?.let { c -> PerfectShape(c.kind, points(c.outline), c.outline.map { it.x.toFloat() to it.y.toFloat() }) }
    }

    // ---- Open shapes -------------------------------------------------------------------------

    private fun openShape(samples: List<P>, diagonal: Double, path: Double, snap: Boolean, tolerance: Double): Candidate? {
        val start = samples.first()
        val end = samples.last()
        val chord = start.distance(end)
        if (chord > 0.5 * diagonal && path < 1.25 * chord) {
            val line = listOf(start, end).let { if (snap) snapLine(it[0], it[1]) else it }
            val candidate = Candidate(ShapeKind.LINE, line, error(samples, line) / diagonal)
            if (candidate.error <= MAX_ERROR * tolerance) return candidate
        }
        return arrow(samples, diagonal, snap)?.takeIf { it.error <= 1.6 * MAX_ERROR * tolerance }
    }

    /**
     * An arrow in one stroke: the shaft, then the head's barbs, in any of the ways people draw them. The
     * long first run is the shaft; the two corners furthest from its tip, either side of it, are the barbs.
     */
    private fun arrow(samples: List<P>, diagonal: Double, snap: Boolean): Candidate? {
        val corners = simplify(samples, 0.05 * diagonal)
        if (corners.size !in 4..6) return null
        val start = corners[0]
        val tip = corners[1]
        val shaft = start.distance(tip)
        val rest = corners.drop(2)
        if (rest.any { it.distance(tip) > 0.7 * shaft }) return null
        val direction = atan2(tip.y - start.y, tip.x - start.x)
        val barbs = rest.filter { it.distance(tip) > 0.12 * shaft }
        val left = barbs.filter { side(start, tip, it) > 0 }.maxByOrNull { it.distance(tip) } ?: return null
        val right = barbs.filter { side(start, tip, it) < 0 }.maxByOrNull { it.distance(tip) } ?: return null
        val head = (left.distance(tip) + right.distance(tip)) / 2
        for (barb in listOf(left, right)) {
            val back = atan2(barb.y - tip.y, barb.x - tip.x)
            val angle = abs(normalize(back - (direction + PI)))
            if (angle < 10.0.radians || angle > 75.0.radians) return null
        }
        val (from, to) = if (snap) snapLine(start, tip).let { it[0] to it[1] } else start to tip
        val aim = atan2(to.y - from.y, to.x - from.x)
        val outline = listOf(from, to, to.toward(aim + PI - ARROW_ANGLE, head), to, to.toward(aim + PI + ARROW_ANGLE, head))
        return Candidate(ShapeKind.ARROW, outline, error(samples, outline) / diagonal)
    }

    private val ARROW_ANGLE = 28.0.radians

    /** Which side of the line from [a] to [b] the point is on. */
    private fun side(a: P, b: P, point: P) = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)

    // ---- Closed shapes -----------------------------------------------------------------------

    private fun closedShape(samples: List<P>, diagonal: Double, snap: Boolean, tolerance: Double): Candidate? {
        // A line drawn out and back isn't a closed shape, however closely a sliver would fit it.
        val box = rectangle(samples, snap = false).outline
        if (minOf(box[0].distance(box[1]), box[1].distance(box[2])) < MIN_THICKNESS * diagonal) return null
        val candidates = listOfNotNull(oval(samples, snap), triangle(samples), rectangle(samples, snap))
            .map { it.copy(error = error(samples, it.outline) / diagonal) }
        val closest = candidates.minByOrNull { it.error } ?: return null
        // An oval has no corners to tell it from a many-sided shape, so it must fit more closely.
        val allowed = if (closest.kind == ShapeKind.CIRCLE || closest.kind == ShapeKind.ELLIPSE) MAX_OVAL_ERROR else MAX_ERROR
        return closest.takeIf { it.error <= allowed * tolerance }
    }

    /** A circle or ellipse along the stroke's main axes. */
    private fun oval(samples: List<P>, snap: Boolean): Candidate {
        val cx = samples.map { it.x }.average()
        val cy = samples.map { it.y }.average()
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (p in samples) {
            xx += (p.x - cx) * (p.x - cx)
            yy += (p.y - cy) * (p.y - cy)
            xy += (p.x - cx) * (p.y - cy)
        }
        val angle = 0.5 * atan2(2 * xy, xx - yy)
        // The size is measured along the axes as drawn; snapping then turns the finished shape.
        val along = samples.map { (it.x - cx) * cos(angle) + (it.y - cy) * sin(angle) }
        val across = samples.map { -(it.x - cx) * sin(angle) + (it.y - cy) * cos(angle) }
        val a = (along.max() - along.min()) / 2
        val b = (across.max() - across.min()) / 2
        // The middle of the extents, which a slower part of the stroke doesn't pull aside as it does the mean.
        val midAlong = (along.max() + along.min()) / 2
        val midAcross = (across.max() + across.min()) / 2
        val center = P(cx + midAlong * cos(angle) - midAcross * sin(angle), cy + midAlong * sin(angle) + midAcross * cos(angle))
        val isCircle = maxOf(a, b) / minOf(a, b).coerceAtLeast(1e-6) < CIRCLE_RATIO
        val outline = if (isCircle) {
            val r = (a + b) / 2
            ellipse(center, r, r, 0.0)
        } else {
            ellipse(center, a, b, if (snap) snapAngle(angle) else angle)
        }
        return Candidate(if (isCircle) ShapeKind.CIRCLE else ShapeKind.ELLIPSE, outline, 0.0)
    }

    private fun ellipse(center: P, a: Double, b: Double, angle: Double): List<P> {
        val perimeter = PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))
        val count = (perimeter / STEP_UNITS).toInt().coerceIn(48, MAX_POINTS)
        return (0..count).map { i ->
            val t = 2 * PI * i / count
            val x = a * cos(t)
            val y = b * sin(t)
            P(center.x + x * cos(angle) - y * sin(angle), center.y + x * sin(angle) + y * cos(angle))
        }
    }

    /** The largest triangle in the stroke: its corners are where the stroke goes furthest out. */
    private fun triangle(samples: List<P>): Candidate {
        val first = samples.maxBy { it.distance(samples[0]) }
        val second = samples.maxBy { it.distance(first) }
        val third = samples.maxBy { abs(side(first, second, it)) }
        return Candidate(ShapeKind.TRIANGLE, listOf(first, second, third, first), 0.0)
    }

    /** The tightest box round the stroke, at the angle it was drawn. */
    private fun rectangle(samples: List<P>, snap: Boolean): Candidate {
        var bestAngle = 0.0
        var bestArea = Double.MAX_VALUE
        for (degrees in 0 until 90) {
            val angle = degrees.toDouble().radians
            val along = samples.map { it.x * cos(angle) + it.y * sin(angle) }
            val across = samples.map { -it.x * sin(angle) + it.y * cos(angle) }
            val area = (along.max() - along.min()) * (across.max() - across.min())
            if (area < bestArea) {
                bestArea = area
                bestAngle = angle
            }
        }
        // The size and middle are measured as drawn; snapping then turns the finished box about its middle.
        val along = samples.map { it.x * cos(bestAngle) + it.y * sin(bestAngle) }
        val across = samples.map { -it.x * sin(bestAngle) + it.y * cos(bestAngle) }
        val midA = (along.min() + along.max()) / 2
        val midB = (across.min() + across.max()) / 2
        val center = P(midA * cos(bestAngle) - midB * sin(bestAngle), midA * sin(bestAngle) + midB * cos(bestAngle))
        var width = along.max() - along.min()
        var height = across.max() - across.min()
        val square = maxOf(width, height) / minOf(width, height).coerceAtLeast(1e-6) < SQUARE_RATIO
        if (square) {
            width = (width + height) / 2
            height = width
        }
        val angle = if (snap) snapAngle(bestAngle) else bestAngle
        fun corner(a: Double, b: Double) = P(center.x + a * cos(angle) - b * sin(angle), center.y + a * sin(angle) + b * cos(angle))
        val c = corner(-width / 2, -height / 2)
        return Candidate(
            if (square) ShapeKind.SQUARE else ShapeKind.RECTANGLE,
            listOf(c, corner(width / 2, -height / 2), corner(width / 2, height / 2), corner(-width / 2, height / 2), c),
            0.0,
        )
    }

    // ---- Snapping ----------------------------------------------------------------------------

    private fun snapLine(start: P, end: P): List<P> {
        val angle = atan2(end.y - start.y, end.x - start.x)
        val snapped = snapAngle(angle)
        if (snapped == angle) return listOf(start, end)
        val mid = P((start.x + end.x) / 2, (start.y + end.y) / 2)
        val half = start.distance(end) / 2
        return listOf(mid.toward(snapped + PI, half), mid.toward(snapped, half))
    }

    /** [angle] turned to the nearest step, if it is close; otherwise as it is. */
    private fun snapAngle(angle: Double): Double {
        val step = SNAP_STEP_DEGREES.radians
        val nearest = round(angle / step) * step
        return if (abs(angle - nearest) <= SNAP_WITHIN_DEGREES.radians) nearest else angle
    }

    // ---- Geometry ----------------------------------------------------------------------------

    private data class P(val x: Double, val y: Double) {
        fun distance(other: P) = hypot(x - other.x, y - other.y)
        fun toward(angle: Double, length: Double) = P(x + length * cos(angle), y + length * sin(angle))
    }

    private data class Candidate(val kind: ShapeKind, val outline: List<P>, val error: Double)

    private val Double.radians get() = this * PI / 180

    private fun normalize(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2 * PI
        while (a < -PI) a += 2 * PI
        return a
    }

    private fun pathLength(points: List<P>) = points.zipWithNext { a, b -> a.distance(b) }.sum()

    /** [SAMPLES] points evenly along the path, so how fast it was drawn doesn't count. */
    private fun resample(points: List<P>): List<P> {
        val total = pathLength(points)
        val step = total / (SAMPLES - 1)
        val out = mutableListOf(points.first())
        var travelled = 0.0
        var next = step
        for ((a, b) in points.zipWithNext()) {
            val length = a.distance(b)
            while (length > 0 && next <= travelled + length && out.size < SAMPLES - 1) {
                val f = (next - travelled) / length
                out += P(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
                next += step
            }
            travelled += length
        }
        out += points.last()
        return out
    }

    /** The stroke's corners: Ramer-Douglas-Peucker, keeping points further than [tolerance] from the chord. */
    private fun simplify(points: List<P>, tolerance: Double): List<P> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var furthest = -1
        var distance = 0.0
        for (i in 1 until points.lastIndex) {
            val d = segmentDistance(points[i], first, last)
            if (d > distance) {
                distance = d
                furthest = i
            }
        }
        if (distance <= tolerance) return listOf(first, last)
        return simplify(points.subList(0, furthest + 1), tolerance).dropLast(1) + simplify(points.subList(furthest, points.size), tolerance)
    }

    private fun segmentDistance(p: P, a: P, b: P): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0.0) return p.distance(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** How far apart the stroke and the outline are, both ways: the larger of the two mean distances. */
    private fun error(samples: List<P>, outline: List<P>): Double {
        fun toOutline(p: P) = outline.zipWithNext { a, b -> segmentDistance(p, a, b) }.min()
        val strays = samples.map(::toOutline).average()
        val along = spread(outline).map { o -> samples.zipWithNext { a, b -> segmentDistance(o, a, b) }.min() }.average()
        return maxOf(strays, along)
    }

    /** Points evenly along the outline, for measuring it against the stroke. */
    private fun spread(outline: List<P>): List<P> = resample(outline)

    /** The outline as stroke points, with exact corners and dense straight runs. */
    private fun points(outline: List<P>): List<InkPoint> {
        val out = mutableListOf<P>()
        val total = pathLength(outline)
        val step = maxOf(STEP_UNITS, total / MAX_POINTS)
        for ((a, b) in outline.zipWithNext()) {
            val parts = (a.distance(b) / step).toInt().coerceAtLeast(1)
            for (i in 0 until parts) {
                val f = i.toDouble() / parts
                out += P(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
        }
        out += outline.last()
        return out.mapIndexed { i, p -> InkPoint(p.x.toFloat(), p.y.toFloat(), i * MILLIS_PER_POINT) }
    }
}

