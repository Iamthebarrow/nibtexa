package io.github.iamthebarrow.nibtexa.core.model.notebook

import kotlin.math.abs
import kotlin.math.hypot

/** A place in a diagram, in its own units. */
data class DiagramPoint(val x: Float, val y: Float) {
    operator fun plus(other: DiagramPoint) = DiagramPoint(x + other.x, y + other.y)
    operator fun minus(other: DiagramPoint) = DiagramPoint(x - other.x, y - other.y)
    fun distance(other: DiagramPoint) = hypot(x - other.x, y - other.y)
}

/** What a shape is drawn as. [CROSS] and [PLUS] are summing points; [DOT] is a node or a place where lines join. */
enum class DiagramShapeType { BOX, CIRCLE, CROSS, PLUS, ELLIPSE, TRIANGLE, DOT, GROUND, OP_AMP }

/** Which way a triangle or op-amp points. */
enum class DiagramFacing { RIGHT, LEFT, UP, DOWN }

/**
 * A shape in a diagram, about its centre ([x], [y]), [width] x [height].
 *
 * @property onLine a [DiagramShapeType.DOT] that sits on this line, [along] of the way, and stays there as the line moves.
 */
data class DiagramShape(
    val id: Int,
    val type: DiagramShapeType,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val facing: DiagramFacing = DiagramFacing.RIGHT,
    val onLine: Int? = null,
    val along: Float = 0f,
)

/** How a line runs through its points: from one to the next, as the arc through all three, or as a smooth curve through them. */
enum class DiagramLineType { STRAIGHT, ARC, CURVE }

/** A circuit symbol set in the middle of a line. */
enum class DiagramSymbol { NONE, RESISTOR, CAPACITOR, INDUCTOR, VOLTAGE_SOURCE, CURRENT_SOURCE, DIODE, SWITCH }

/** An arrowhead part way along a line, [at] of the way, pointing to the line's end unless [reversed]. */
data class DiagramArrow(val at: Float, val reversed: Boolean = false)

/**
 * A line in a diagram. A [from] or [to] shape holds that end: the end point is an anchor that moves with the shape, and the line is
 * drawn only up to the shape's edge.
 */
data class DiagramLine(
    val id: Int,
    val points: List<DiagramPoint>,
    val type: DiagramLineType = DiagramLineType.STRAIGHT,
    val from: Int? = null,
    val to: Int? = null,
    val arrowStart: Boolean = false,
    val arrowEnd: Boolean = false,
    val arrows: List<DiagramArrow> = emptyList(),
    val symbol: DiagramSymbol = DiagramSymbol.NONE,
    val dashed: Boolean = false,
)

/**
 * Words or maths in a diagram, centred on ([x], [y]). [ink] is the handwriting it was read from, which is shown while it has no
 * [latex] or [text], and which it can go back to. A [shape] holds the label, which moves with it.
 */
data class DiagramLabel(
    val id: Int,
    val x: Float,
    val y: Float,
    val latex: String? = null,
    val text: String? = null,
    val shape: Int? = null,
    val ink: List<List<DiagramPoint>> = emptyList(),
    /** Waiting to be read. Off once it has been, or if the user prefers the handwriting. */
    val unread: Boolean = false,
    /** Which reader reads it, chosen when it was written. */
    val words: Boolean = false,
) {
    val handwritten: Boolean get() = latex == null && text == null
}

/** A part of a diagram that can be moved, deleted or changed. */
sealed interface DiagramPart {
    data class Shape(val id: Int) : DiagramPart
    data class Line(val id: Int) : DiagramPart
    data class Label(val id: Int) : DiagramPart
}

/**
 * A clean figure (Phase 31): shapes, lines and labels, not ink, so that it stays straight at any size and comes out as vector lines in
 * every export. Places are in the diagram's own units, from its top left, and it is drawn scaled to fill its region: [width] x [height]
 * units is the size it was drawn at.
 */
data class Diagram(
    val width: Float = 0f,
    val height: Float = 0f,
    val shapes: List<DiagramShape> = emptyList(),
    val lines: List<DiagramLine> = emptyList(),
    val labels: List<DiagramLabel> = emptyList(),
    val nextId: Int = 1,
) {
    val isEmpty: Boolean get() = shapes.isEmpty() && lines.isEmpty() && labels.isEmpty()

    fun shape(id: Int?) = shapes.firstOrNull { it.id == id }
    fun line(id: Int?) = lines.firstOrNull { it.id == id }
    fun label(id: Int?) = labels.firstOrNull { it.id == id }

    /** The part at this place, or null: labels first, as they sit on the rest, then the smallest shape, then lines. */
    fun partAt(x: Float, y: Float, reach: Float = HIT_REACH): DiagramPart? {
        val at = DiagramPoint(x, y)
        labels.filter { abs(it.x - x) <= LABEL_REACH && abs(it.y - y) <= LABEL_REACH / 2 }.minByOrNull { hypot(it.x - x, it.y - y) }?.let { return DiagramPart.Label(it.id) }
        shapes.filter { DiagramGeometry.contains(it, x, y, reach) }.minByOrNull { it.width * it.height }?.let { return DiagramPart.Shape(it.id) }
        lines.map { it to DiagramGeometry.distance(this, it, at) }.filter { it.second <= reach }.minByOrNull { it.second }?.let { return DiagramPart.Line(it.first.id) }
        return null
    }

    /** With [part] moved by ([dx], [dy]). Lines held by a shape follow it and labels it holds go with it; a right-angled line keeps its right angles. */
    fun moved(part: DiagramPart, dx: Float, dy: Float): Diagram {
        val by = DiagramPoint(dx, dy)
        return when (part) {
            is DiagramPart.Label -> copy(labels = labels.map { if (it.id == part.id) it.copy(x = it.x + dx, y = it.y + dy) else it })
            is DiagramPart.Shape -> {
                val shape = shape(part.id) ?: return this
                // A dot that is dragged comes off the line it was on.
                copy(
                    shapes = shapes.map { if (it.id == part.id) it.copy(x = it.x + dx, y = it.y + dy, onLine = null) else it },
                    lines = lines.map { it.endsMoved(shape.id, by) },
                    labels = labels.map { if (it.shape == part.id) it.copy(x = it.x + dx, y = it.y + dy) else it },
                ).settled()
            }
            is DiagramPart.Line -> {
                val line = line(part.id) ?: return this
                // A line held at both ends stays with its shapes.
                if (line.from != null && line.to != null) return this
                copy(lines = lines.map { l -> if (l.id != part.id) l else l.copy(points = l.points.map { it + by }, from = null, to = null) }).settled()
            }
        }
    }

    /** Without [part], and with what depended on it let go: a line a shape held keeps its place, ending where the shape's edge was. */
    fun without(part: DiagramPart): Diagram = when (part) {
        is DiagramPart.Label -> copy(labels = labels.filter { it.id != part.id })
        is DiagramPart.Line -> copy(
            lines = lines.filter { it.id != part.id },
            // Dots on the line that joined nothing else go too; others stay where they are.
            shapes = shapes.filter { s -> s.onLine != part.id || lines.any { it.id != part.id && (it.from == s.id || it.to == s.id) } }.map { if (it.onLine == part.id) it.copy(onLine = null) else it },
        ).let { d -> d.copy(labels = d.labels.filter { it.shape == null || d.shape(it.shape) != null }) }
        is DiagramPart.Shape -> copy(
            shapes = shapes.filter { it.id != part.id },
            labels = labels.filter { it.shape != part.id },
            lines = lines.map { l ->
                val drawn = DiagramGeometry.path(this, l)
                var points = l.points
                if (l.from == part.id) points = listOf(drawn.first()) + points.drop(1)
                if (l.to == part.id) points = points.dropLast(1) + drawn.last()
                l.copy(points = points, from = l.from.takeIf { it != part.id }, to = l.to.takeIf { it != part.id })
            },
        )
    }

    /** Dots on lines put back on them, and the lines held by those dots brought along, after something moved. */
    internal fun settled(): Diagram {
        var result = this
        for (dot in shapes.filter { it.onLine != null }) {
            val line = result.line(dot.onLine) ?: continue
            val place = DiagramGeometry.pointAlong(DiagramGeometry.path(result, line), dot.along)
            val now = result.shape(dot.id) ?: continue
            val by = place - DiagramPoint(now.x, now.y)
            if (by.x == 0f && by.y == 0f) continue
            result = result.copy(
                shapes = result.shapes.map { if (it.id == dot.id) it.copy(x = place.x, y = place.y) else it },
                lines = result.lines.map { it.endsMoved(dot.id, by) },
            )
        }
        return result
    }

    private fun DiagramLine.endsMoved(shape: Int, by: DiagramPoint): DiagramLine {
        if (from != shape && to != shape) return this
        val start = if (from == shape) by else DiagramPoint(0f, 0f)
        val end = if (to == shape) by else DiagramPoint(0f, 0f)
        val last = points.lastIndex
        val next = when {
            type == DiagramLineType.STRAIGHT && points.size > 2 -> rightAngled(points, start, end)
            // Between the two ends, each point goes as far as how near it is to that end.
            else -> points.mapIndexed { i, p ->
                val share = i.toFloat() / last
                DiagramPoint(p.x + start.x * (1 - share) + end.x * share, p.y + start.y * (1 - share) + end.y * share)
            }
        }
        return copy(points = next)
    }

    /** The ends moved, and the corner next to each kept square with it. */
    private fun rightAngled(points: List<DiagramPoint>, start: DiagramPoint, end: DiagramPoint): List<DiagramPoint> {
        val out = points.toMutableList()
        fun shift(end: Int, next: Int, by: DiagramPoint) {
            if (by.x == 0f && by.y == 0f) return
            val level = abs(points[end].y - points[next].y) <= abs(points[end].x - points[next].x)
            out[end] = out[end] + by
            // A level part keeps its level by moving the corner up or down with the end; an upright part, across.
            out[next] = if (level) out[next].copy(y = out[next].y + by.y) else out[next].copy(x = out[next].x + by.x)
        }
        shift(0, 1, start)
        shift(points.lastIndex, points.lastIndex - 1, end)
        return out
    }

    /**
     * With [other] added, scaled by [scale] and moved by ([dx], [dy]) into this diagram's units, under new ids (a stroke reached both, so they
     * are one figure now).
     */
    fun merged(other: Diagram, scale: Float, dx: Float, dy: Float): Diagram {
        fun id(old: Int) = nextId + old
        fun at(p: DiagramPoint) = DiagramPoint(p.x * scale + dx, p.y * scale + dy)
        return copy(
            shapes = shapes + other.shapes.map {
                it.copy(id = id(it.id), x = it.x * scale + dx, y = it.y * scale + dy, width = it.width * scale, height = it.height * scale, onLine = it.onLine?.let(::id))
            },
            lines = lines + other.lines.map { l -> l.copy(id = id(l.id), points = l.points.map(::at), from = l.from?.let(::id), to = l.to?.let(::id)) },
            labels = labels + other.labels.map { l -> l.copy(id = id(l.id), x = l.x * scale + dx, y = l.y * scale + dy, shape = l.shape?.let(::id), ink = l.ink.map { s -> s.map(::at) }) },
            nextId = nextId + other.nextId,
        )
    }

    /** Every place used, drawn or written; null for nothing. */
    fun extent(): Bounds? {
        var box: Bounds? = null
        fun add(b: Bounds) { box = box?.union(b) ?: b }
        shapes.forEach { add(Bounds(it.x - it.width / 2, it.y - it.height / 2, it.x + it.width / 2, it.y + it.height / 2)) }
        lines.forEach { l -> DiagramGeometry.path(this, l).forEach { add(Bounds(it.x, it.y, it.x, it.y)) } }
        labels.forEach { l ->
            add(Bounds(l.x - LABEL_ROOM, l.y - LABEL_ROOM / 2, l.x + LABEL_ROOM, l.y + LABEL_ROOM / 2))
            l.ink.flatten().forEach { add(Bounds(it.x, it.y, it.x, it.y)) }
        }
        return box
    }

    /** Moved so that its top left is at the padding, with its size updated. Returns it, and how far the top left moved in its own units. */
    fun normalized(): Pair<Diagram, DiagramPoint> {
        val box = extent() ?: return copy(width = 0f, height = 0f) to DiagramPoint(0f, 0f)
        val dx = PADDING - box.left
        val dy = PADDING - box.top
        val by = DiagramPoint(dx, dy)
        val moved = copy(
            width = box.right - box.left + 2 * PADDING,
            height = box.bottom - box.top + 2 * PADDING,
            shapes = shapes.map { it.copy(x = it.x + dx, y = it.y + dy) },
            lines = lines.map { l -> l.copy(points = l.points.map { it + by }) },
            labels = labels.map { l -> l.copy(x = l.x + dx, y = l.y + dy, ink = l.ink.map { s -> s.map { it + by } }) },
        )
        return moved to by
    }

    /** What a stored diagram may hold: finite places, ends that exist, and no more than the limits. */
    fun checked(): Diagram? {
        val places = shapes.flatMap { listOf(it.x, it.y, it.width, it.height, it.along) } + lines.flatMap { l -> l.points.flatMap { listOf(it.x, it.y) } } + labels.flatMap { listOf(it.x, it.y) } + listOf(width, height)
        if (places.any { !it.isFinite() } || width < 0f || height < 0f) return null
        if (shapes.size > MAX_PARTS || lines.size > MAX_PARTS || labels.size > MAX_PARTS || lines.any { it.points.size !in 2..MAX_POINTS }) return null
        val ids = shapes.map { it.id }
        if (ids.distinct().size != ids.size || lines.map { it.id }.distinct().size != lines.size || labels.map { it.id }.distinct().size != labels.size) return null
        val known = ids.toSet()
        return copy(
            lines = lines.map { it.copy(from = it.from?.takeIf(known::contains), to = it.to?.takeIf(known::contains), arrows = it.arrows.map { a -> a.copy(at = a.at.coerceIn(0f, 1f)) }) },
            labels = labels.map { it.copy(shape = it.shape?.takeIf(known::contains)) },
            shapes = shapes.map { s -> s.copy(onLine = s.onLine?.takeIf { id -> lines.any { it.id == id } }, along = s.along.coerceIn(0f, 1f)) },
        )
    }

    companion object {
        /** Round the diagram, in its units, so that nothing touches the edge of its region. */
        const val PADDING = 6f

        private const val HIT_REACH = 12f
        private const val LABEL_REACH = 26f
        private const val LABEL_ROOM = 12f
        private const val MAX_PARTS = 2000
        private const val MAX_POINTS = 2000
    }
}
