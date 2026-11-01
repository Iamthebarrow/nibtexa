package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** What a stroke became. */
enum class DiagramMade { SHAPE, MARK, LINE, CURVE, WRITING, DOT, NOTHING }

/** The diagram after a stroke, and what the stroke became. [tapped] is the label a tap landed on, which is left as it is. */
class DiagramStep(val diagram: Diagram, val made: DiagramMade, val tapped: Int? = null)

/**
 * Turns strokes into the parts of a diagram (Phase 31), as each is finished. In order, a stroke is:
 * - a tap: a dot, on a line or by itself;
 * - more of the writing just done;
 * - in a small circle, the x or + that makes it a summing point; in any other shape, its label;
 * - beside a summing point, its sign;
 * - a small V on a line: an arrowhead;
 * - a closed shape, a straight or right-angled line (with any arrowhead), a zigzag (a resistor) or an arc;
 * - if none of these and small, writing; if larger, a curve, steadied.
 *
 * Lines end on the shapes they reach, and a shape drawn later takes the free ends it reaches. New shapes line up with others nearby and
 * take their size if it is close. All plain geometry, using [ShapeRecognizer] for what it already knows, with limits as constants below.
 * They are tuned on generated strokes, so real handwriting may ask for changes.
 */
object DiagramBuilder {
    private const val TAP = 9f
    private const val WRITING_MAX = 90f
    private const val WRITING_GAP = 30f
    private const val MIN_SHAPE = 24f
    private const val MIN_LINE = 34f
    private const val SMALL_CIRCLE = 80f
    private const val SIGN_MAX = 30f
    private const val SIGN_REACH = 34f
    private const val HEAD_MAX = 60f
    private const val HEAD_REACH = 16f
    private const val END_SHARE_REACH = 26f
    private const val ATTACH_REACH = 22f
    private const val JOIN_REACH = 13f
    private const val ALIGN_REACH = 14f
    private const val SIZE_MATCH = 0.15f
    private const val SNAP_DEGREES = 13.0
    private const val SHAPE_TOLERANCE = 1.5

    /** The size given to a dot, and a sign's place from its summing point. */
    private const val DOT = 6f

    /** How far from a line an arc or an elbow's fit may stray, as a share of the stroke's size. */
    private const val MAX_ARC_ERROR = 0.022f
    private const val MAX_ELBOW_ERROR = 0.05f
    private const val MIN_ARC_DEGREES = 35.0

    /** [stroke], in the diagram's units, added to [diagram]. [words] is for what reads its writing. */
    fun add(diagram: Diagram, stroke: List<DiagramPoint>, words: Boolean = false): DiagramStep {
        val box = boundsOf(stroke) ?: return DiagramStep(diagram, DiagramMade.NOTHING)
        val size = hypot(box.right - box.left, box.bottom - box.top)
        val centre = DiagramPoint(box.centerX, box.centerY)

        continuing(diagram, box, size)?.let { return DiagramStep(extendWriting(diagram, it, stroke), DiagramMade.WRITING) }
        if (size < TAP) return tap(diagram, centre)

        val holder = holderOf(diagram, stroke)
        if (holder != null) {
            if (isSummable(holder) && isStraight(stroke)) {
                val diagonal = abs(((angleOf(stroke.first(), stroke.last()) * 180 / PI + 360) % 90) - 45) < 25
                val type = when {
                    holder.type != DiagramShapeType.CIRCLE -> holder.type
                    diagonal -> DiagramShapeType.CROSS
                    else -> DiagramShapeType.PLUS
                }
                return DiagramStep(diagram.copy(shapes = diagram.shapes.map { if (it.id == holder.id) it.copy(type = type) else it }), DiagramMade.MARK)
            }
            return DiagramStep(newWriting(diagram, stroke, holder, words), DiagramMade.WRITING)
        }
        sign(diagram, stroke, box, size)?.let { return DiagramStep(it, DiagramMade.MARK) }
        arrowhead(diagram, stroke, size)?.let { return DiagramStep(it, DiagramMade.MARK) }
        tick(diagram, stroke, size)?.let { return DiagramStep(it, DiagramMade.LINE) }
        return recognize(diagram, stroke, box, size, words)
    }

    /** After a shape was dragged: lined up with another nearby, as a new one is. */
    fun aligned(diagram: Diagram, part: DiagramPart): Diagram {
        val shape = (part as? DiagramPart.Shape)?.let { diagram.shape(it.id) } ?: return diagram
        val others = diagram.shapes.filter { it.id != shape.id && it.type != DiagramShapeType.DOT }
        val dx = others.minByOrNull { abs(it.x - shape.x) }?.let { it.x - shape.x }?.takeIf { abs(it) <= ALIGN_REACH } ?: 0f
        val dy = others.minByOrNull { abs(it.y - shape.y) }?.let { it.y - shape.y }?.takeIf { abs(it) <= ALIGN_REACH } ?: 0f
        return if (dx == 0f && dy == 0f) diagram else diagram.moved(part, dx, dy)
    }

    // ---- Taps and writing --------------------------------------------------------------------

    private fun tap(diagram: Diagram, at: DiagramPoint): DiagramStep {
        (diagram.partAt(at.x, at.y) as? DiagramPart.Label)?.let { return DiagramStep(diagram, DiagramMade.NOTHING, tapped = it.id) }
        val line = diagram.lines.map { it to DiagramGeometry.distance(diagram, it, at) }.filter { it.second <= JOIN_REACH }.minByOrNull { it.second }?.first
        if (line != null) return DiagramStep(withDotOn(diagram, line, at).first, DiagramMade.DOT)
        if (diagram.shapes.any { DiagramGeometry.contains(it, at.x, at.y) }) return DiagramStep(diagram, DiagramMade.NOTHING)
        return DiagramStep(diagram.copy(shapes = diagram.shapes + DiagramShape(diagram.nextId, DiagramShapeType.DOT, at.x, at.y, DOT * 2, DOT * 2), nextId = diagram.nextId + 1), DiagramMade.DOT)
    }

    /** The unread writing this stroke goes on from, if it is small and close. */
    private fun continuing(diagram: Diagram, box: Bounds, size: Float): DiagramLabel? {
        if (size > WRITING_MAX) return null
        return diagram.labels.filter { it.unread && it.handwritten }.firstOrNull { label ->
            val ink = boundsOf(label.ink.flatten()) ?: return@firstOrNull false
            gap(ink, box) <= WRITING_GAP
        }
    }

    private fun extendWriting(diagram: Diagram, label: DiagramLabel, stroke: List<DiagramPoint>): Diagram {
        val ink = label.ink + listOf(stroke)
        val box = boundsOf(ink.flatten())!!
        val held = diagram.shape(label.shape)
        return diagram.copy(labels = diagram.labels.map { if (it.id == label.id) it.copy(ink = ink, x = held?.x ?: box.centerX, y = held?.y ?: box.centerY) else it })
    }

    private fun newWriting(diagram: Diagram, stroke: List<DiagramPoint>, holder: DiagramShape?, words: Boolean): Diagram {
        val box = boundsOf(stroke)!!
        val label = DiagramLabel(diagram.nextId, holder?.x ?: box.centerX, holder?.y ?: box.centerY, shape = holder?.id, ink = listOf(stroke), unread = true, words = words)
        return diagram.copy(labels = diagram.labels + label, nextId = diagram.nextId + 1)
    }

    private fun isSummable(shape: DiagramShape) = shape.width <= SMALL_CIRCLE &&
        (shape.type == DiagramShapeType.CIRCLE || shape.type == DiagramShapeType.CROSS || shape.type == DiagramShapeType.PLUS)

    /** The smallest shape the stroke is written inside. */
    private fun holderOf(diagram: Diagram, stroke: List<DiagramPoint>): DiagramShape? = diagram.shapes
        .filter { it.type != DiagramShapeType.DOT && it.type != DiagramShapeType.GROUND && stroke.count { p -> DiagramGeometry.contains(it, p.x, p.y) } >= stroke.size * 0.9f }
        .minByOrNull { it.width * it.height }

    /** A short level stroke by a summing point is a minus; a short upright one across a minus makes it a plus. */
    private fun sign(diagram: Diagram, stroke: List<DiagramPoint>, box: Bounds, size: Float): Diagram? {
        if (size > SIGN_MAX || !isStraight(stroke)) return null
        val centre = DiagramPoint(box.centerX, box.centerY)
        val slope = abs(angleOf(stroke.first(), stroke.last()) * 180 / PI) % 180
        val level = slope < 25 || slope > 155
        val upright = slope in 65.0..115.0
        val minus = diagram.labels.firstOrNull { it.latex == "-" && hypot(it.x - centre.x, it.y - centre.y) <= 12f }
        if (minus != null && upright) return diagram.copy(labels = diagram.labels.map { if (it.id == minus.id) it.copy(latex = "+") else it })
        if (!level) return null
        val summing = diagram.shapes.firstOrNull { isSummable(it) && hypot(it.x - centre.x, it.y - centre.y) <= it.width / 2 + SIGN_REACH } ?: return null
        val label = DiagramLabel(diagram.nextId, centre.x, centre.y, latex = "-", shape = summing.id)
        return diagram.copy(labels = diagram.labels + label, nextId = diagram.nextId + 1)
    }

    /** A small V or chevron with its point on a line, which sets an arrowhead there, the way it points. */
    private fun arrowhead(diagram: Diagram, stroke: List<DiagramPoint>, size: Float): Diagram? {
        if (size > HEAD_MAX) return null
        val corners = simplify(stroke, size * 0.15f)
        if (corners.size != 3) return null
        val (a, tip, c) = corners
        val spread = abs(turnBetween(angleOf(tip, a), angleOf(tip, c))) * 180 / PI
        if (spread !in 20.0..130.0) return null
        val line = diagram.lines.map { it to DiagramGeometry.distance(diagram, it, tip) }.filter { it.second <= HEAD_REACH }.minByOrNull { it.second }?.first ?: return null
        val path = DiagramGeometry.path(diagram, line)
        val total = DiagramGeometry.length(path)
        if (total <= 0f) return null
        val share = DiagramGeometry.shareAlong(path, tip)
        val back = DiagramGeometry.pointAlong(path, (share - 0.02f).coerceAtLeast(0f))
        val ahead = DiagramGeometry.pointAlong(path, (share + 0.02f).coerceAtMost(1f))
        val pointing = DiagramPoint(tip.x - (a.x + c.x) / 2, tip.y - (a.y + c.y) / 2)
        val forward = pointing.x * (ahead.x - back.x) + pointing.y * (ahead.y - back.y) >= 0f
        val changed = when {
            forward && (1 - share) * total <= END_SHARE_REACH -> line.copy(arrowEnd = true)
            !forward && share * total <= END_SHARE_REACH -> line.copy(arrowStart = true)
            else -> line.copy(arrows = line.arrows + DiagramArrow(share, reversed = !forward))
        }
        return diagram.copy(lines = diagram.lines.map { if (it.id == line.id) changed else it })
    }

    /** A short straight stroke across a line: a tick on an axis, kept as a short line. */
    private fun tick(diagram: Diagram, stroke: List<DiagramPoint>, size: Float): Diagram? {
        if (size >= MIN_LINE || !isStraight(stroke)) return null
        val a = stroke.first()
        val b = stroke.last()
        val mid = DiagramPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
        if (diagram.lines.none { DiagramGeometry.distance(diagram, it, mid) <= size / 2 + 2f }) return null
        val (from, to) = snappedEnds(a, b)
        return diagram.copy(lines = diagram.lines + DiagramLine(diagram.nextId, listOf(from, to)), nextId = diagram.nextId + 1)
    }

    // ---- Shapes and lines --------------------------------------------------------------------

    private fun recognize(diagram: Diagram, stroke: List<DiagramPoint>, box: Bounds, size: Float, words: Boolean): DiagramStep {
        val ink = stroke.mapIndexed { i, p -> InkPoint(p.x, p.y, i * 8L) }
        val shape = ShapeRecognizer.recognize(ink, snap = true, tolerance = SHAPE_TOLERANCE)
        when (shape?.kind) {
            ShapeKind.CIRCLE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE, ShapeKind.RECTANGLE, ShapeKind.SQUARE -> {
                if (minOf(box.right - box.left, box.bottom - box.top) < MIN_SHAPE) return DiagramStep(newWriting(diagram, stroke, null, words), DiagramMade.WRITING)
                if (shape.kind == ShapeKind.CIRCLE) selfLoop(diagram, box)?.let { return DiagramStep(it, DiagramMade.CURVE) }
                return DiagramStep(withShape(diagram, shape, box), DiagramMade.SHAPE)
            }
            ShapeKind.LINE -> if (size >= MIN_LINE) {
                // A small head on a long line still passes for straight, so it is looked for first.
                elbow(diagram, stroke, size)?.let { return DiagramStep(it, DiagramMade.LINE) }
                return DiagramStep(withLine(diagram, stroke.first(), stroke.last()), DiagramMade.LINE)
            }
            ShapeKind.ARROW -> if (size >= MIN_LINE) {
                val c = shape.corners
                return DiagramStep(withLine(diagram, DiagramPoint(c[0].first, c[0].second), DiagramPoint(c[1].first, c[1].second), arrowEnd = true), DiagramMade.LINE)
            }
            null -> Unit
        }
        if (size >= MIN_LINE) {
            elbow(diagram, stroke, size)?.let { return DiagramStep(it, DiagramMade.LINE) }
            zigzag(diagram, stroke)?.let { return DiagramStep(it, DiagramMade.LINE) }
        }
        if (size > WRITING_MAX * 0.7f) arc(diagram, stroke, size)?.let { return DiagramStep(it, DiagramMade.CURVE) }
        if (size <= WRITING_MAX) return DiagramStep(newWriting(diagram, stroke, null, words), DiagramMade.WRITING)
        return DiagramStep(withCurve(diagram, stroke), DiagramMade.CURVE)
    }

    private fun withShape(diagram: Diagram, shape: PerfectShape, box: Bounds): Diagram {
        var width = box.right - box.left
        var height = box.bottom - box.top
        val type = when (shape.kind) {
            ShapeKind.CIRCLE -> {
                width = (width + height) / 2
                height = width
                DiagramShapeType.CIRCLE
            }
            ShapeKind.ELLIPSE -> DiagramShapeType.ELLIPSE
            ShapeKind.TRIANGLE -> DiagramShapeType.TRIANGLE
            else -> DiagramShapeType.BOX
        }
        // Blocks of about the same size are made the same size, and line up with those nearby.
        diagram.shapes.firstOrNull { it.type == type && abs(it.width - width) <= SIZE_MATCH * width && abs(it.height - height) <= SIZE_MATCH * height }?.let {
            width = it.width
            height = it.height
        }
        var x = box.centerX
        var y = box.centerY
        diagram.shapes.filter { it.type != DiagramShapeType.DOT }.minByOrNull { abs(it.y - y) }?.let { if (abs(it.y - y) <= ALIGN_REACH) y = it.y }
        diagram.shapes.filter { it.type != DiagramShapeType.DOT }.minByOrNull { abs(it.x - x) }?.let { if (abs(it.x - x) <= ALIGN_REACH) x = it.x }
        val facing = if (type == DiagramShapeType.TRIANGLE) facingOf(shape.corners, box) else DiagramFacing.RIGHT
        val made = DiagramShape(diagram.nextId, type, x, y, width, height, facing)
        val next = diagram.copy(shapes = diagram.shapes + made, nextId = diagram.nextId + 1)
        // Free ends of lines that reach it now end on it.
        return next.copy(lines = next.lines.map { line ->
            val start = line.from == null && reaches(made, line.points.first())
            val end = line.to == null && reaches(made, line.points.last())
            if (start || end) anchored(next, line.copy(from = if (start) made.id else line.from, to = if (end) made.id else line.to)) else line
        })
    }

    private fun facingOf(corners: List<Pair<Float, Float>>, box: Bounds): DiagramFacing {
        val w = (box.right - box.left) / 2
        val h = (box.bottom - box.top) / 2
        val c = corners.take(3).map { DiagramPoint(it.first, it.second) }
        return DiagramFacing.entries.minBy { facing ->
            val model = DiagramGeometry.outline(DiagramShape(0, DiagramShapeType.TRIANGLE, box.centerX, box.centerY, w * 2, h * 2, facing)).take(3)
            c.sumOf { p -> model.minOf { it.distance(p) }.toDouble() }
        }
    }

    /** A circle drawn on a small node, not round it, is a loop from the node back to itself. */
    private fun selfLoop(diagram: Diagram, box: Bounds): Diagram? {
        val r = (box.right - box.left + box.bottom - box.top) / 4
        val c = DiagramPoint(box.centerX, box.centerY)
        val node = diagram.shapes.firstOrNull { it.width <= r && abs(DiagramPoint(it.x, it.y).distance(c) - r) <= ALIGN_REACH } ?: return null
        val start = atan2(node.y - c.y, node.x - c.x)
        val around = (1 until LOOP_POINTS).map { i ->
            val t = start + 2 * PI.toFloat() * i / LOOP_POINTS
            DiagramPoint(c.x + r * kotlin.math.cos(t), c.y + r * kotlin.math.sin(t))
        }
        val at = DiagramPoint(node.x, node.y)
        val line = DiagramLine(diagram.nextId, listOf(at) + around + at, DiagramLineType.CURVE, from = node.id, to = node.id)
        return diagram.copy(lines = diagram.lines + line, nextId = diagram.nextId + 1)
    }

    private const val LOOP_POINTS = 12

    private fun reaches(shape: DiagramShape, at: DiagramPoint) = DiagramGeometry.contains(shape, at.x, at.y, if (shape.type == DiagramShapeType.DOT) JOIN_REACH else ATTACH_REACH)

    private fun attachedTo(diagram: Diagram, at: DiagramPoint): DiagramShape? =
        diagram.shapes.filter { reaches(it, at) }.minByOrNull { hypot(it.x - at.x, it.y - at.y) }

    private fun snappedEnds(a: DiagramPoint, b: DiagramPoint): Pair<DiagramPoint, DiagramPoint> {
        val slope = abs(angleOf(a, b) * 180 / PI)
        return when {
            slope < SNAP_DEGREES || slope > 180 - SNAP_DEGREES -> ((a.y + b.y) / 2).let { a.copy(y = it) to b.copy(y = it) }
            abs(slope - 90) < SNAP_DEGREES -> ((a.x + b.x) / 2).let { a.copy(x = it) to b.copy(x = it) }
            else -> a to b
        }
    }

    /**
     * A new line through [points]. An end that reaches a shape is held by it; one that reaches another line joins it with a dot there; one
     * that reaches a free end meets it.
     */
    private fun withLine(diagram: Diagram, start: DiagramPoint, end: DiagramPoint, arrowEnd: Boolean = false, arrowStart: Boolean = false, symbol: DiagramSymbol = DiagramSymbol.NONE): Diagram {
        val (a, b) = snappedEnds(start, end)
        return placed(diagram, listOf(a, b), DiagramLineType.STRAIGHT, arrowEnd, arrowStart, symbol)
    }

    private fun placed(diagram: Diagram, points: List<DiagramPoint>, type: DiagramLineType, arrowEnd: Boolean = false, arrowStart: Boolean = false, symbol: DiagramSymbol = DiagramSymbol.NONE): Diagram {
        var d = diagram
        val (first, from) = endOf(d, points.first(), type).also { d = it.third }.let { it.first to it.second }
        val (last, to) = endOf(d, points.last(), type).also { d = it.third }.let { it.first to it.second }
        val moved = listOf(first) + points.subList(1, points.lastIndex) + last
        val line = anchored(d, DiagramLine(d.nextId, moved, type, from = from, to = to, arrowEnd = arrowEnd, arrowStart = arrowStart, symbol = symbol))
        return d.copy(lines = d.lines + line, nextId = d.nextId + 1)
    }

    /** Where a line's end goes: the place, the shape that holds it, and the diagram, with any dot made for it. */
    private fun endOf(diagram: Diagram, at: DiagramPoint, type: DiagramLineType): Triple<DiagramPoint, Int?, Diagram> {
        attachedTo(diagram, at)?.let { return Triple(at, it.id, diagram) }
        if (type != DiagramLineType.STRAIGHT) return Triple(at, null, diagram)
        diagram.lines.flatMap { l -> listOfNotNull(l.points.first().takeIf { l.from == null }, l.points.last().takeIf { l.to == null }) }
            .firstOrNull { it.distance(at) <= JOIN_REACH }?.let { return Triple(it, null, diagram) }
        val line = diagram.lines.map { it to DiagramGeometry.distance(diagram, it, at) }.filter { it.second <= JOIN_REACH }.minByOrNull { it.second }?.first
        if (line != null) {
            val (joined, dot) = withDotOn(diagram, line, at)
            return Triple(DiagramPoint(dot.x, dot.y), dot.id, joined)
        }
        return Triple(at, null, diagram)
    }

    private fun withDotOn(diagram: Diagram, line: DiagramLine, at: DiagramPoint): Pair<Diagram, DiagramShape> {
        val path = DiagramGeometry.path(diagram, line)
        val share = DiagramGeometry.shareAlong(path, at)
        val place = DiagramGeometry.pointAlong(path, share)
        val dot = DiagramShape(diagram.nextId, DiagramShapeType.DOT, place.x, place.y, DOT * 2, DOT * 2, onLine = line.id, along = share)
        return diagram.copy(shapes = diagram.shapes + dot, nextId = diagram.nextId + 1) to dot
    }

    /**
     * The line's ends set on the shapes holding them. A level or upright part going in takes the shape's middle if it is near it, and
     * then its other end stays level or upright with it. A part at an angle goes to the middle.
     */
    internal fun anchored(diagram: Diagram, line: DiagramLine): DiagramLine {
        val from = diagram.shape(line.from)
        val to = diagram.shape(line.to)
        if (from == null && to == null) return line
        val points = line.points.toMutableList()
        if (line.type != DiagramLineType.STRAIGHT) {
            from?.let { points[0] = DiagramPoint(it.x, it.y) }
            to?.let { points[points.lastIndex] = DiagramPoint(it.x, it.y) }
            return line.copy(points = points)
        }
        fun level(a: DiagramPoint, b: DiagramPoint) = abs(a.y - b.y) < 0.5f
        fun upright(a: DiagramPoint, b: DiagramPoint) = abs(a.x - b.x) < 0.5f
        fun set(end: Int, next: Int, shape: DiagramShape) {
            val p = points[end]
            val n = points[next]
            when {
                level(p, n) -> {
                    val y = if (abs(p.y - shape.y) <= reachIn(shape.height)) shape.y else p.y
                    points[end] = DiagramPoint(shape.x, y)
                    points[next] = n.copy(y = y)
                }
                upright(p, n) -> {
                    val x = if (abs(p.x - shape.x) <= reachIn(shape.width)) shape.x else p.x
                    points[end] = DiagramPoint(x, shape.y)
                    points[next] = n.copy(x = x)
                }
                else -> points[end] = DiagramPoint(shape.x, shape.y)
            }
        }
        from?.let { set(0, 1, it) }
        to?.let { set(points.lastIndex, points.lastIndex - 1, it) }
        // Two shapes at slightly different heights: where the second took the line over, the first keeps holding it, inside its edge.
        if (points.size == 2 && from != null && to != null && level(line.points[0], line.points[1])) {
            val y = points[1].y
            points[0] = points[0].copy(y = y)
        }
        if (points.size == 2 && from != null && to != null && upright(line.points[0], line.points[1])) {
            val x = points[1].x
            points[0] = points[0].copy(x = x)
        }
        return line.copy(points = points)
    }

    /** How far from a shape's middle a line going straight in is pulled to it. */
    private fun reachIn(side: Float) = maxOf(side * 0.3f, JOIN_REACH)

    /** A stroke with square corners, and any arrowhead on its end. */
    private fun elbow(diagram: Diagram, stroke: List<DiagramPoint>, size: Float): Diagram? {
        // The head's corners are small, so they are looked for before the shake is ignored.
        val tip = trailingHead(stroke, simplify(stroke, maxOf(size * 0.02f, 3f)))
        val shaft = if (tip != null) stroke.subList(0, stroke.indexOf(tip) + 1) else stroke
        val corners = simplify(shaft, maxOf(size * 0.06f, 5f))
        // A small head on a long straight line, too small for the shape recognizer to see.
        if (tip != null && corners.size == 2) return withLine(diagram, corners[0], corners[1], arrowEnd = true)
        if (corners.size !in 3..MAX_ELBOW_CORNERS) return null
        val levels = corners.zipWithNext { a, b ->
            val slope = abs(angleOf(a, b) * 180 / PI)
            when {
                slope < ELBOW_DEGREES || slope > 180 - ELBOW_DEGREES -> true
                abs(slope - 90) < ELBOW_DEGREES -> false
                else -> return null
            }
        }
        if (levels.zipWithNext().any { (a, b) -> a == b }) return null
        // Each part lies at the mean of its ends, and each corner is where two parts meet.
        val places = corners.zipWithNext().mapIndexed { i, (a, b) -> if (levels[i]) (a.y + b.y) / 2 else (a.x + b.x) / 2 }
        val square = corners.indices.map { i ->
            when (i) {
                0 -> if (levels[0]) DiagramPoint(corners[0].x, places[0]) else DiagramPoint(places[0], corners[0].y)
                corners.lastIndex -> if (levels[i - 1]) DiagramPoint(corners[i].x, places[i - 1]) else DiagramPoint(places[i - 1], corners[i].y)
                else -> if (levels[i - 1]) DiagramPoint(places[i], places[i - 1]) else DiagramPoint(places[i - 1], places[i])
            }
        }
        val error = shaft.map { p -> square.zipWithNext { a, b -> DiagramGeometry.toSegment(p, a, b) }.min() }.average().toFloat()
        if (error > MAX_ELBOW_ERROR * size) return null
        return placed(diagram, square, DiagramLineType.STRAIGHT, arrowEnd = tip != null)
    }

    private const val ELBOW_DEGREES = 22.0
    private const val MAX_ELBOW_CORNERS = 7

    /**
     * The corner that is an arrow's tip, if the corners after it make a head round it: barbs back from the tip, on both sides of the line
     * going in. Much larger than the shake, so that a wobble at the end of a line isn't one.
     */
    private fun trailingHead(stroke: List<DiagramPoint>, corners: List<DiagramPoint>): DiagramPoint? {
        for (i in 1..corners.size - 2) {
            val tip = corners[i]
            val rest = corners.drop(i + 1)
            val lead = DiagramGeometry.pointAlong(stroke.subList(0, stroke.indexOf(tip) + 1), 0.9f)
            val shaft = DiagramGeometry.length(stroke.subList(0, stroke.indexOf(tip) + 1))
            if (shaft < MIN_LINE || rest.any { it.distance(tip) > minOf(HEAD_MAX, shaft * 0.5f) }) continue
            val along = DiagramPoint(tip.x - lead.x, tip.y - lead.y)
            val barbs = rest.filter { it.distance(tip) >= MIN_BARB && (it.x - tip.x) * along.x + (it.y - tip.y) * along.y < 0f }
            val sides = barbs.map { along.x * (it.y - tip.y) - along.y * (it.x - tip.x) }
            if (sides.any { it > 0f } && sides.any { it < 0f }) return tip
        }
        return null
    }

    private const val MIN_BARB = 8f

    /** A line of alternating teeth: a resistor on the straight line through its ends. */
    private fun zigzag(diagram: Diagram, stroke: List<DiagramPoint>): Diagram? {
        val a = stroke.first()
        val b = stroke.last()
        val chord = a.distance(b)
        if (chord < MIN_LINE || DiagramGeometry.length(stroke) < 1.5f * chord) return null
        val corners = simplify(stroke, maxOf(chord * 0.05f, 3f)).drop(1).dropLast(1)
        val sides = corners.map { (b.x - a.x) * (it.y - a.y) - (b.y - a.y) * (it.x - a.x) }.map { it / chord }
        val teeth = sides.filter { abs(it) > chord * 0.04f }
        if (teeth.size < MIN_TEETH || teeth.any { abs(it) > chord * 0.6f }) return null
        if (teeth.zipWithNext().any { (x, y) -> x * y > 0 }) return null
        return withLine(diagram, a, b, symbol = DiagramSymbol.RESISTOR)
    }

    private const val MIN_TEETH = 3

    /** The arc that best fits the stroke, if it fits closely and turns enough to be one. */
    private fun arc(diagram: Diagram, stroke: List<DiagramPoint>, size: Float): Diagram? {
        val n = stroke.size
        if (n < 8) return null
        val mx = stroke.map { it.x }.average()
        val my = stroke.map { it.y }.average()
        var suu = 0.0
        var svv = 0.0
        var suv = 0.0
        var suuu = 0.0
        var svvv = 0.0
        var suvv = 0.0
        var svuu = 0.0
        for (p in stroke) {
            val u = p.x - mx
            val v = p.y - my
            suu += u * u
            svv += v * v
            suv += u * v
            suuu += u * u * u
            svvv += v * v * v
            suvv += u * v * v
            svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-6) return null
        val uc = (0.5 * (suuu + suvv) * svv - 0.5 * (svvv + svuu) * suv) / det
        val vc = (0.5 * (svvv + svuu) * suu - 0.5 * (suuu + suvv) * suv) / det
        val cx = (uc + mx).toFloat()
        val cy = (vc + my).toFloat()
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n).toFloat()
        val error = stroke.map { abs(hypot(it.x - cx, it.y - cy) - r) }.average()
        if (error > MAX_ARC_ERROR * size) return null
        val start = stroke.first()
        val end = stroke.last()
        val middle = DiagramGeometry.pointAlong(stroke, 0.5f)
        val swept = abs(turnBetween(atan2(middle.y - cy, middle.x - cx).toDouble(), atan2(start.y - cy, start.x - cx).toDouble())) + abs(turnBetween(atan2(end.y - cy, end.x - cx).toDouble(), atan2(middle.y - cy, middle.x - cx).toDouble()))
        if (swept * 180 / PI < MIN_ARC_DEGREES) return null
        // Both ends on one shape can't make an arc through three points: it is a loop.
        val from = attachedTo(diagram, start)
        val to = attachedTo(diagram, end)
        if (from != null && from.id == to?.id) return withCurve(diagram, stroke)
        return placed(diagram, listOf(start, middle, end), DiagramLineType.ARC)
    }

    private fun withCurve(diagram: Diagram, stroke: List<DiagramPoint>): Diagram = placed(diagram, steadied(stroke), DiagramLineType.CURVE)

    /** The hand's shake taken out: evened by distance, averaged along, and reduced to the points it needs. The ends stay. */
    internal fun steadied(stroke: List<DiagramPoint>): List<DiagramPoint> {
        val total = DiagramGeometry.length(stroke)
        if (total <= 0f) return listOf(stroke.first(), stroke.last())
        val count = (total / STEADY_STEP).toInt().coerceIn(4, MAX_CURVE_POINTS)
        val even = (0..count).map { DiagramGeometry.pointAlong(stroke, it.toFloat() / count) }
        val averaged = even.indices.map { i ->
            val w = minOf(STEADY_WINDOW, i, even.lastIndex - i)
            val part = even.subList(i - w, i + w + 1)
            DiagramPoint(part.map { it.x }.average().toFloat(), part.map { it.y }.average().toFloat())
        }
        return simplify(averaged, STEADY_TOLERANCE).let { if (it.size > 2) it else listOf(averaged.first(), averaged[averaged.size / 2], averaged.last()) }
    }

    private const val STEADY_STEP = 4f
    private const val STEADY_WINDOW = 4
    private const val STEADY_TOLERANCE = 1f
    private const val MAX_CURVE_POINTS = 800

    // ---- Geometry ----------------------------------------------------------------------------

    private fun boundsOf(points: List<DiagramPoint>): Bounds? {
        if (points.isEmpty()) return null
        return Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun gap(a: Bounds, b: Bounds): Float = hypot(maxOf(0f, a.left - b.right, b.left - a.right), maxOf(0f, a.top - b.bottom, b.top - a.bottom))

    private fun angleOf(a: DiagramPoint, b: DiagramPoint): Double = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())

    private fun turnBetween(a: Double, b: Double): Double {
        var d = a - b
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return d
    }

    private fun isStraight(stroke: List<DiagramPoint>): Boolean {
        val a = stroke.first()
        val b = stroke.last()
        val chord = a.distance(b)
        if (chord < 3f) return false
        return DiagramGeometry.length(stroke) <= 1.25f * chord && stroke.all { DiagramGeometry.toSegment(it, a, b) <= maxOf(chord * 0.2f, 2.5f) }
    }

    /** The corners: Ramer-Douglas-Peucker, keeping points further than [tolerance] from the chord. */
    private fun simplify(points: List<DiagramPoint>, tolerance: Float): List<DiagramPoint> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var furthest = -1
        var distance = 0f
        for (i in 1 until points.lastIndex) {
            val d = DiagramGeometry.toSegment(points[i], first, last)
            if (d > distance) {
                distance = d
                furthest = i
            }
        }
        if (distance <= tolerance) return listOf(first, last)
        return simplify(points.subList(0, furthest + 1), tolerance).dropLast(1) + simplify(points.subList(furthest, points.size), tolerance)
    }
}
