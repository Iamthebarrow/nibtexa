package io.github.iamthebarrow.nibtexa.core.model.notebook

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A path to draw, in a diagram's units: stroked, or filled if [filled]. */
data class DiagramStroke(val points: List<DiagramPoint>, val filled: Boolean = false, val dashed: Boolean = false)

/**
 * A diagram worked out for drawing (Phase 31): every shape, line, arrowhead and symbol as plain paths. The page, PDFs and LaTeX all draw
 * this, so they come out the same. Labels are drawn by each of them, as how they typeset differs.
 */
class DiagramPlotted(val diagram: Diagram, val strokes: List<DiagramStroke>)

/** Where the parts of a diagram are. */
object DiagramGeometry {
    /** Stroke width in a diagram's units. About 0.8 pt in print. */
    const val LINE_WIDTH = 1.8f
    const val DASH = 7f
    const val ARROW_LENGTH = 11f
    private const val ARROW_HALF_WIDTH = 4.5f
    private const val STEP = 3f
    private const val SYMBOL_LENGTH = 44f

    fun plot(diagram: Diagram): DiagramPlotted = DiagramPlotted(
        diagram,
        diagram.shapes.flatMap(::shapeStrokes) + diagram.lines.flatMap { lineStrokes(diagram, it) },
    )

    // ---- Shapes ------------------------------------------------------------------------------

    /** The edge all the way round, closed, which lines stop at. */
    fun outline(shape: DiagramShape): List<DiagramPoint> {
        val x = shape.x
        val y = shape.y
        val w = shape.width / 2
        val h = shape.height / 2
        fun round(a: Float, b: Float) = (0..ROUND_STEPS).map { i ->
            val t = 2 * PI * i / ROUND_STEPS
            DiagramPoint(x + a * cos(t).toFloat(), y + b * sin(t).toFloat())
        }
        return when (shape.type) {
            DiagramShapeType.BOX -> listOf(DiagramPoint(x - w, y - h), DiagramPoint(x + w, y - h), DiagramPoint(x + w, y + h), DiagramPoint(x - w, y + h), DiagramPoint(x - w, y - h))
            DiagramShapeType.CIRCLE, DiagramShapeType.CROSS, DiagramShapeType.PLUS, DiagramShapeType.ELLIPSE, DiagramShapeType.DOT -> round(w, h)
            DiagramShapeType.TRIANGLE, DiagramShapeType.OP_AMP -> when (shape.facing) {
                DiagramFacing.RIGHT -> listOf(DiagramPoint(x - w, y - h), DiagramPoint(x + w, y), DiagramPoint(x - w, y + h), DiagramPoint(x - w, y - h))
                DiagramFacing.LEFT -> listOf(DiagramPoint(x + w, y - h), DiagramPoint(x - w, y), DiagramPoint(x + w, y + h), DiagramPoint(x + w, y - h))
                DiagramFacing.UP -> listOf(DiagramPoint(x - w, y + h), DiagramPoint(x, y - h), DiagramPoint(x + w, y + h), DiagramPoint(x - w, y + h))
                DiagramFacing.DOWN -> listOf(DiagramPoint(x - w, y - h), DiagramPoint(x, y + h), DiagramPoint(x + w, y - h), DiagramPoint(x - w, y - h))
            }
            // A line comes in at the top.
            DiagramShapeType.GROUND -> listOf(DiagramPoint(x - w, y - h), DiagramPoint(x + w, y - h), DiagramPoint(x, y + h), DiagramPoint(x - w, y - h))
        }
    }

    private fun shapeStrokes(shape: DiagramShape): List<DiagramStroke> {
        val x = shape.x
        val y = shape.y
        val w = shape.width / 2
        val h = shape.height / 2
        return when (shape.type) {
            DiagramShapeType.DOT -> listOf(DiagramStroke(outline(shape), filled = true))
            DiagramShapeType.CROSS -> {
                // Corner to corner of the circle, so that it reads clearly as a summing point.
                val a = w * DIAGONAL
                val b = h * DIAGONAL
                listOf(
                    DiagramStroke(outline(shape)),
                    DiagramStroke(listOf(DiagramPoint(x - a, y - b), DiagramPoint(x + a, y + b))),
                    DiagramStroke(listOf(DiagramPoint(x - a, y + b), DiagramPoint(x + a, y - b))),
                )
            }
            DiagramShapeType.PLUS -> listOf(
                DiagramStroke(outline(shape)),
                DiagramStroke(listOf(DiagramPoint(x - w, y), DiagramPoint(x + w, y))),
                DiagramStroke(listOf(DiagramPoint(x, y - h), DiagramPoint(x, y + h))),
            )
            DiagramShapeType.GROUND -> listOf(
                DiagramStroke(listOf(DiagramPoint(x, y - h), DiagramPoint(x, y - h / 3))),
                DiagramStroke(listOf(DiagramPoint(x - w, y - h / 3), DiagramPoint(x + w, y - h / 3))),
                DiagramStroke(listOf(DiagramPoint(x - w * 0.62f, y + h / 3), DiagramPoint(x + w * 0.62f, y + h / 3))),
                DiagramStroke(listOf(DiagramPoint(x - w * 0.25f, y + h), DiagramPoint(x + w * 0.25f, y + h))),
            )
            DiagramShapeType.OP_AMP -> listOf(DiagramStroke(outline(shape))) + opAmpSigns(shape)
            else -> listOf(DiagramStroke(outline(shape)))
        }
    }

    /** The − and + inside an op-amp, by its two inputs. */
    private fun opAmpSigns(shape: DiagramShape): List<DiagramStroke> {
        val s = minOf(shape.width, shape.height) * 0.09f
        // Along the back, a little in; across, a quarter of the way either side of the middle.
        val (backX, backY, acrossX, acrossY) = when (shape.facing) {
            DiagramFacing.RIGHT -> listOf(-shape.width * 0.32f, 0f, 0f, shape.height * 0.25f)
            DiagramFacing.LEFT -> listOf(shape.width * 0.32f, 0f, 0f, shape.height * 0.25f)
            DiagramFacing.UP -> listOf(0f, shape.height * 0.32f, shape.width * 0.25f, 0f)
            DiagramFacing.DOWN -> listOf(0f, -shape.height * 0.32f, shape.width * 0.25f, 0f)
        }
        val minus = DiagramPoint(shape.x + backX - acrossX, shape.y + backY - acrossY)
        val plus = DiagramPoint(shape.x + backX + acrossX, shape.y + backY + acrossY)
        return listOf(
            DiagramStroke(listOf(DiagramPoint(minus.x - s, minus.y), DiagramPoint(minus.x + s, minus.y))),
            DiagramStroke(listOf(DiagramPoint(plus.x - s, plus.y), DiagramPoint(plus.x + s, plus.y))),
            DiagramStroke(listOf(DiagramPoint(plus.x, plus.y - s), DiagramPoint(plus.x, plus.y + s))),
        )
    }

    /** The place is on or inside [shape], or within [reach] of its box. */
    fun contains(shape: DiagramShape, x: Float, y: Float, reach: Float = 0f): Boolean =
        abs(x - shape.x) <= shape.width / 2 + reach && abs(y - shape.y) <= shape.height / 2 + reach && (reach > 0f || inside(outline(shape), x, y))

    private fun inside(polygon: List<DiagramPoint>, x: Float, y: Float): Boolean {
        var odd = false
        for ((a, b) in polygon.zipWithNext()) {
            if ((a.y > y) != (b.y > y) && x < a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)) odd = !odd
        }
        return odd
    }

    // ---- Lines -------------------------------------------------------------------------------

    /** The line as it is drawn, as points: followed through its curve, and stopping at the edges of the shapes that hold it. */
    fun path(diagram: Diagram, line: DiagramLine): List<DiagramPoint> {
        var path = centreLine(line)
        diagram.shape(line.from)?.let { path = fromEdge(path, it) }
        diagram.shape(line.to)?.let { path = fromEdge(path.asReversed(), it).asReversed() }
        return path
    }

    private fun centreLine(line: DiagramLine): List<DiagramPoint> = when (line.type) {
        DiagramLineType.STRAIGHT -> line.points
        DiagramLineType.ARC -> arc(line.points)
        DiagramLineType.CURVE -> smooth(line.points)
    }

    /** The path from where it leaves [shape]. One that never leaves it stays as it is, rather than disappearing. */
    private fun fromEdge(path: List<DiagramPoint>, shape: DiagramShape): List<DiagramPoint> {
        if (!inside(outline(shape), path.first().x, path.first().y) && path.first().distance(DiagramPoint(shape.x, shape.y)) > maxOf(shape.width, shape.height)) return path
        val edge = outline(shape)
        // Where it first crosses the edge going along.
        for ((i, part) in path.zipWithNext().withIndex()) {
            val crossing = edge.zipWithNext().firstNotNullOfOrNull { (a, b) -> crossing(part.first, part.second, a, b) }
            if (crossing != null) return listOf(crossing) + path.drop(i + 1)
        }
        return path
    }

    private fun crossing(a: DiagramPoint, b: DiagramPoint, c: DiagramPoint, d: DiagramPoint): DiagramPoint? {
        val r = b - a
        val s = d - c
        val cross = r.x * s.y - r.y * s.x
        if (abs(cross) < 1e-6f) return null
        val t = ((c.x - a.x) * s.y - (c.y - a.y) * s.x) / cross
        val u = ((c.x - a.x) * r.y - (c.y - a.y) * r.x) / cross
        return if (t in 0f..1f && u in 0f..1f) DiagramPoint(a.x + r.x * t, a.y + r.y * t) else null
    }

    /** The arc from the first point through the second to the last. Nearly in a line, it is a straight one. */
    private fun arc(points: List<DiagramPoint>): List<DiagramPoint> {
        if (points.size != 3) return points
        val (a, b, c) = points
        val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) < 1e-3f) return listOf(a, c)
        val ax = a.x * a.x + a.y * a.y
        val bx = b.x * b.x + b.y * b.y
        val cx = c.x * c.x + c.y * c.y
        val ox = (ax * (b.y - c.y) + bx * (c.y - a.y) + cx * (a.y - b.y)) / d
        val oy = (ax * (c.x - b.x) + bx * (a.x - c.x) + cx * (b.x - a.x)) / d
        val r = hypot(a.x - ox, a.y - oy)
        val start = atan2(a.y - oy, a.x - ox)
        val via = atan2(b.y - oy, b.x - ox)
        val end = atan2(c.y - oy, c.x - ox)
        // Round the way that passes through the middle point.
        val toVia = turn(via - start)
        val toEnd = turn(end - start)
        val sweep = if (toVia <= toEnd) toEnd else toEnd - 2 * PI.toFloat()
        val steps = ceil(abs(sweep) * r / STEP).toInt().coerceIn(8, 400)
        return (0..steps).map { i ->
            val t = start + sweep * i / steps
            DiagramPoint(ox + r * cos(t), oy + r * sin(t))
        }
    }

    private fun turn(angle: Float): Float {
        var a = angle
        while (a < 0f) a += 2 * PI.toFloat()
        while (a >= 2 * PI.toFloat()) a -= 2 * PI.toFloat()
        return a
    }

    /** A Catmull-Rom curve through [points]. */
    private fun smooth(points: List<DiagramPoint>): List<DiagramPoint> {
        if (points.size < 3) return points
        val out = mutableListOf(points.first())
        for (i in 0 until points.lastIndex) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
            val steps = ceil(p1.distance(p2) / STEP).toInt().coerceIn(1, 40)
            for (s in 1..steps) {
                val t = s.toFloat() / steps
                fun at(a: Float, b: Float, c: Float, d: Float) = 0.5f * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t * t * t)
                out += DiagramPoint(at(p0.x, p1.x, p2.x, p3.x), at(p0.y, p1.y, p2.y, p3.y))
            }
        }
        return out
    }

    /** How far the place is from the line as drawn. */
    fun distance(diagram: Diagram, line: DiagramLine, at: DiagramPoint): Float =
        path(diagram, line).zipWithNext { a, b -> toSegment(at, a, b) }.minOrNull() ?: Float.MAX_VALUE

    fun toSegment(p: DiagramPoint, a: DiagramPoint, b: DiagramPoint): Float {
        val d = b - a
        val length = d.x * d.x + d.y * d.y
        if (length == 0f) return p.distance(a)
        val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / length).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * d.x), p.y - (a.y + t * d.y))
    }

    fun length(path: List<DiagramPoint>): Float = path.zipWithNext { a, b -> a.distance(b) }.sum()

    /** The point [share] of the way along [path]. */
    fun pointAlong(path: List<DiagramPoint>, share: Float): DiagramPoint {
        var left = length(path) * share.coerceIn(0f, 1f)
        for ((a, b) in path.zipWithNext()) {
            val part = a.distance(b)
            if (left <= part && part > 0f) return DiagramPoint(a.x + (b.x - a.x) * left / part, a.y + (b.y - a.y) * left / part)
            left -= part
        }
        return path.last()
    }

    /** How far along [path] the point nearest to [at] is, as a share of its length. */
    fun shareAlong(path: List<DiagramPoint>, at: DiagramPoint): Float {
        val total = length(path)
        if (total <= 0f) return 0f
        var travelled = 0f
        var best = 0f
        var nearest = Float.MAX_VALUE
        for ((a, b) in path.zipWithNext()) {
            val d = b - a
            val length = a.distance(b)
            val t = if (length == 0f) 0f else (((at.x - a.x) * d.x + (at.y - a.y) * d.y) / (length * length)).coerceIn(0f, 1f)
            val distance = hypot(at.x - (a.x + t * d.x), at.y - (a.y + t * d.y))
            if (distance < nearest) {
                nearest = distance
                best = travelled + t * length
            }
            travelled += length
        }
        return best / total
    }

    private fun lineStrokes(diagram: Diagram, line: DiagramLine): List<DiagramStroke> {
        val path = path(diagram, line)
        if (path.size < 2) return emptyList()
        val out = mutableListOf<DiagramStroke>()
        val total = length(path)
        if (line.symbol == DiagramSymbol.NONE) {
            out += DiagramStroke(path, dashed = line.dashed)
        } else {
            val half = minOf(SYMBOL_LENGTH / 2, total / 2 * 0.9f)
            val startAt = total / 2 - half
            val endAt = total / 2 + half
            val a = pointAlong(path, startAt / total)
            val b = pointAlong(path, endAt / total)
            out += DiagramStroke(partOf(path, 0f, startAt / total), dashed = line.dashed)
            out += DiagramStroke(partOf(path, endAt / total, 1f), dashed = line.dashed)
            out += symbol(line.symbol, a, b)
        }
        if (line.arrowEnd) out += head(path.last(), direction(path.asReversed()))
        if (line.arrowStart) out += head(path.first(), direction(path))
        for (arrow in line.arrows) {
            val tip = pointAlong(path, arrow.at + ARROW_LENGTH / 2 / total * (if (arrow.reversed) -1 else 1))
            val back = pointAlong(path, arrow.at - ARROW_LENGTH / 2 / total * (if (arrow.reversed) -1 else 1))
            out += head(tip, atan2(back.y - tip.y, back.x - tip.x))
        }
        return out
    }

    /** The direction from the first point of [path] along it, taken over the length of a head so that a curve's arrow lies along it. */
    private fun direction(path: List<DiagramPoint>): Float {
        val first = path.first()
        val next = path.drop(1).firstOrNull { it.distance(first) >= ARROW_LENGTH } ?: path.last()
        return atan2(next.y - first.y, next.x - first.x)
    }

    /** A filled arrowhead with its point at [tip] and its back in the direction [back]. */
    private fun head(tip: DiagramPoint, back: Float): DiagramStroke {
        val cx = tip.x + ARROW_LENGTH * cos(back)
        val cy = tip.y + ARROW_LENGTH * sin(back)
        val ax = -sin(back) * ARROW_HALF_WIDTH
        val ay = cos(back) * ARROW_HALF_WIDTH
        return DiagramStroke(listOf(tip, DiagramPoint(cx + ax, cy + ay), DiagramPoint(cx - ax, cy - ay), tip), filled = true)
    }

    private fun partOf(path: List<DiagramPoint>, from: Float, to: Float): List<DiagramPoint> {
        val total = length(path)
        val out = mutableListOf(pointAlong(path, from))
        var travelled = 0f
        for (p in path.zipWithNext()) {
            travelled += p.first.distance(p.second)
            if (travelled / total > from && travelled / total < to) out += p.second
        }
        out += pointAlong(path, to)
        return out
    }

    // ---- Circuit symbols ---------------------------------------------------------------------

    /** A symbol from [a] to [b], drawn with its own lines, so that it needs no package in LaTeX. */
    private fun symbol(symbol: DiagramSymbol, a: DiagramPoint, b: DiagramPoint): List<DiagramStroke> {
        val length = a.distance(b)
        val ux = (b.x - a.x) / length
        val uy = (b.y - a.y) / length
        // Along the line from a, and out to one side.
        fun at(along: Float, side: Float) = DiagramPoint(a.x + ux * along * length - uy * side, a.y + uy * along * length + ux * side)
        val size = length * 0.28f
        return when (symbol) {
            DiagramSymbol.NONE -> emptyList()
            DiagramSymbol.RESISTOR -> {
                val teeth = 6
                val points = buildList {
                    add(at(0f, 0f))
                    for (i in 0 until teeth) add(at((i + 0.5f) / teeth, if (i % 2 == 0) -size else size))
                    add(at(1f, 0f))
                }
                listOf(DiagramStroke(points))
            }
            DiagramSymbol.CAPACITOR -> listOf(
                DiagramStroke(listOf(at(0f, 0f), at(0.42f, 0f))),
                DiagramStroke(listOf(at(0.42f, -size * 1.5f), at(0.42f, size * 1.5f))),
                DiagramStroke(listOf(at(0.58f, -size * 1.5f), at(0.58f, size * 1.5f))),
                DiagramStroke(listOf(at(0.58f, 0f), at(1f, 0f))),
            )
            DiagramSymbol.INDUCTOR -> {
                val bumps = 4
                val points = (0..bumps * 12).map { i ->
                    val t = i / 12f
                    val inBump = t - t.toInt().coerceAtMost(bumps - 1)
                    at(t / bumps, -size * 1.3f * sin(PI.toFloat() * inBump))
                }
                listOf(DiagramStroke(points))
            }
            DiagramSymbol.VOLTAGE_SOURCE, DiagramSymbol.CURRENT_SOURCE -> {
                val r = length * 0.36f
                val circle = (0..ROUND_STEPS).map { i ->
                    val t = 2 * PI.toFloat() * i / ROUND_STEPS
                    at(0.5f + r / length * cos(t), r * sin(t))
                }
                val leads = listOf(
                    DiagramStroke(listOf(at(0f, 0f), at(0.5f - r / length, 0f))),
                    DiagramStroke(listOf(at(0.5f + r / length, 0f), at(1f, 0f))),
                    DiagramStroke(circle),
                )
                val s = r * 0.32f / length
                val inside = if (symbol == DiagramSymbol.VOLTAGE_SOURCE) {
                    // + towards the start, − towards the end.
                    listOf(
                        DiagramStroke(listOf(at(0.36f - s, 0f), at(0.36f + s, 0f))),
                        DiagramStroke(listOf(at(0.36f, -r * 0.32f), at(0.36f, r * 0.32f))),
                        DiagramStroke(listOf(at(0.64f, -r * 0.32f), at(0.64f, r * 0.32f))),
                    )
                } else {
                    val tip = at(0.72f, 0f)
                    listOf(DiagramStroke(listOf(at(0.3f, 0f), at(0.56f, 0f))), head(tip, atan2(a.y - b.y, a.x - b.x)))
                }
                leads + inside
            }
            DiagramSymbol.DIODE -> listOf(
                DiagramStroke(listOf(at(0f, 0f), at(0.32f, 0f))),
                DiagramStroke(listOf(at(0.32f, -size * 1.3f), at(0.68f, 0f), at(0.32f, size * 1.3f), at(0.32f, -size * 1.3f)), filled = true),
                DiagramStroke(listOf(at(0.68f, -size * 1.3f), at(0.68f, size * 1.3f))),
                DiagramStroke(listOf(at(0.68f, 0f), at(1f, 0f))),
            )
            DiagramSymbol.SWITCH -> listOf(
                DiagramStroke(listOf(at(0f, 0f), at(0.25f, 0f))),
                DiagramStroke(listOf(at(0.25f, 0f), at(0.72f, -size * 1.5f))),
                DiagramStroke(listOf(at(0.75f, 0f), at(1f, 0f))),
            )
        }
    }

    private const val ROUND_STEPS = 72
    private val DIAGONAL = sqrt(0.5f)
}
