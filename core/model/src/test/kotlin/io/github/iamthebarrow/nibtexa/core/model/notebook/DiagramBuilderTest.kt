package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.serialization.DiagramJson
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert
import org.junit.Test

private fun assertEquals(expected: Any?, actual: Any?, message: String? = null) = Assert.assertEquals(message, expected, actual)
private fun assertEquals(expected: Float, actual: Float, delta: Float, message: String? = null) = Assert.assertEquals(message, expected, actual, delta)
private fun assertEquals(expected: Float, actual: Float, message: String? = null) = Assert.assertEquals(message, expected, actual, 0f)
private fun assertTrue(value: Boolean, message: String? = null) = Assert.assertTrue(message, value)
private fun assertFalse(value: Boolean) = Assert.assertFalse(value)
private fun assertNull(value: Any?, message: String? = null) = Assert.assertNull(message, value)
private fun assertNotNull(value: Any?) = Assert.assertNotNull(value)

class DiagramBuilderTest {
    /** A hand's wobble, repeatable: a slow drift and a little shake, both less than the stroke's own shape. */
    private fun wobbly(points: List<DiagramPoint>, amount: Float = 1.6f, seed: Int = 7): List<DiagramPoint> {
        val r = Random(seed)
        val phase = r.nextFloat() * 6f
        return points.mapIndexed { i, p ->
            val t = i / 14f + phase
            DiagramPoint(p.x + amount * sin(t) + (r.nextFloat() - 0.5f) * 0.5f, p.y + amount * cos(1.3f * t) + (r.nextFloat() - 0.5f) * 0.5f)
        }
    }

    private fun through(vararg corners: Pair<Float, Float>): List<DiagramPoint> {
        val at = corners.map { DiagramPoint(it.first, it.second) }
        return at.zipWithNext().flatMap { (a, b) ->
            val n = (a.distance(b) / 3f).toInt().coerceAtLeast(2)
            (0 until n).map { DiagramPoint(a.x + (b.x - a.x) * it / n, a.y + (b.y - a.y) * it / n) }
        } + at.last()
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = wobbly(through(left to top, right to top, right to bottom, left to bottom, left to top))

    private fun circle(x: Float, y: Float, r: Float, from: Double = 0.0, sweep: Double = 2 * PI, amount: Float = 0.8f): List<DiagramPoint> {
        val n = (abs(sweep) * r / 2.5).toInt().coerceAtLeast(40)
        return wobbly((0..n).map { i -> (from + sweep * i / n).let { DiagramPoint(x + r * cos(it).toFloat(), y + r * sin(it).toFloat()) } }, amount)
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = wobbly(through(x1 to y1, x2 to y2))

    private fun Diagram.draw(stroke: List<DiagramPoint>) = DiagramBuilder.add(this, stroke)

    private fun Diagram.draws(vararg strokes: List<DiagramPoint>) = strokes.fold(this) { d, s -> d.draw(s).diagram }

    @Test
    fun aWobblyBoxIsATrueBox() {
        val step = Diagram().draw(box(100f, 100f, 260f, 180f))
        assertEquals(DiagramMade.SHAPE, step.made)
        val shape = step.diagram.shapes.single()
        assertEquals(DiagramShapeType.BOX, shape.type)
        assertEquals(180f, shape.x, 4f)
        assertEquals(140f, shape.y, 4f)
    }

    @Test
    fun aLineIsStraightAndLevelWhateverTheShake() {
        val line = Diagram().draw(line(100f, 200f, 300f, 208f)).diagram.lines.single()
        assertEquals(2, line.points.size)
        assertEquals(line.points[0].y, line.points[1].y, 0.01f)
    }

    @Test
    fun aLineEndsOnTheShapesItReachesAtTheirMiddles() {
        val d = Diagram().draws(box(100f, 100f, 200f, 170f), box(320f, 104f, 420f, 174f), line(205f, 142f, 315f, 130f))
        assertEquals(d.shapes[0].y, d.shapes[1].y, "the second lines up with the first")
        assertEquals(d.shapes[0].width, d.shapes[1].width, "and takes its size")
        val l = d.lines.single()
        assertEquals(d.shapes[0].id, l.from)
        assertEquals(d.shapes[1].id, l.to)
        val path = DiagramGeometry.path(d, l)
        assertEquals(d.shapes[0].x + d.shapes[0].width / 2, path.first().x, 0.5f)
        assertEquals(d.shapes[1].x - d.shapes[1].width / 2, path.last().x, 0.5f)
        assertEquals(d.shapes[0].y, path.first().y, 0.5f)
        assertEquals(d.shapes[0].y, path.last().y, 0.5f)
    }

    @Test
    fun aShapeDrawnLaterTakesTheFreeEndsItReaches() {
        val d = Diagram().draws(line(50f, 140f, 200f, 140f), box(205f, 100f, 300f, 180f))
        val l = d.lines.single()
        assertEquals(d.shapes.single().id, l.to)
        assertEquals(d.shapes.single().y, DiagramGeometry.path(d, l).last().y, 0.5f)
    }

    @Test
    fun movingABlockBringsItsLinesAndLabel() {
        val start = Diagram().draws(box(100f, 100f, 200f, 170f), box(320f, 100f, 420f, 170f), line(205f, 135f, 315f, 135f), wobbly(through(145f to 125f, 155f to 125f, 150f to 140f), 0.2f))
        val second = start.shapes[1]
        val moved = start.moved(DiagramPart.Shape(second.id), 40f, 0f)
        val path = DiagramGeometry.path(moved, moved.lines.single())
        assertEquals(second.x + 40f - second.width / 2, path.last().x, 0.5f)
        assertEquals(second.y, path.last().y, 0.5f)
        val first = start.shapes[0]
        val movedLabel = start.moved(DiagramPart.Shape(first.id), 0f, 30f)
        assertEquals(start.labels.single().y + 30f, movedLabel.labels.single().y)
    }

    @Test
    fun anXInACircleMakesASummingPointAndSecondStrokeIsAbsorbed() {
        var d = Diagram().draw(circle(200f, 200f, 25f)).diagram
        assertEquals(DiagramShapeType.CIRCLE, d.shapes.single().type)
        val first = d.draw(line(190f, 190f, 210f, 210f))
        assertEquals(DiagramMade.MARK, first.made)
        d = first.diagram
        assertEquals(DiagramShapeType.CROSS, d.shapes.single().type)
        val second = d.draw(line(210f, 190f, 190f, 210f))
        assertEquals(DiagramMade.MARK, second.made)
        assertEquals(DiagramShapeType.CROSS, second.diagram.shapes.single().type)
        assertTrue(second.diagram.lines.isEmpty() && second.diagram.labels.isEmpty(), "nothing else is left over")
    }

    @Test
    fun levelAndUprightStrokesInACircleMakeAPlus() {
        val d = Diagram().draws(circle(200f, 200f, 25f), line(186f, 200f, 214f, 200f), line(200f, 186f, 200f, 214f))
        assertEquals(DiagramShapeType.PLUS, d.shapes.single().type)
    }

    @Test
    fun signsBesideASummingPoint() {
        val d = Diagram().draws(circle(200f, 200f, 25f), line(150f, 170f, 165f, 170f))
        assertEquals("-", d.labels.single().latex)
        assertEquals(d.shapes.single().id, d.labels.single().shape)
        val plus = d.draws(line(157f, 162f, 157f, 178f))
        assertEquals("+", plus.labels.single().latex)
    }

    @Test
    fun writingInABoxIsItsLabelAndKeepsGoing() {
        val d = Diagram().draw(box(100f, 100f, 260f, 180f)).diagram
        val first = d.draw(circle(160f, 140f, 8f, amount = 0.2f))
        assertEquals(DiagramMade.WRITING, first.made)
        val second = first.diagram.draw(line(178f, 130f, 178f, 150f))
        assertEquals(DiagramMade.WRITING, second.made)
        val label = second.diagram.labels.single()
        assertEquals(2, label.ink.size)
        assertEquals(d.shapes.single().id, label.shape)
        assertEquals(d.shapes.single().x, label.x)
        assertTrue(label.unread)
        assertTrue(second.diagram.lines.isEmpty())
    }

    @Test
    fun smallWritingOutsideIsALabel() {
        val s = wobbly(through(100f to 100f, 108f to 120f, 116f to 100f, 124f to 120f, 110f to 110f), 0.3f)
        val step = Diagram().draw(s)
        assertEquals(DiagramMade.WRITING, step.made)
        assertNotNull(step.diagram.labels.singleOrNull())
    }

    @Test
    fun aRightAngledFeedbackLineHasSquareCornersAndEndsOnItsShapes() {
        val d = Diagram().draws(circle(100f, 100f, 20f), box(300f, 70f, 420f, 130f))
        val l = d.draw(wobbly(through(360f to 135f, 360f to 220f, 100f to 220f, 100f to 125f))).diagram.lines.single()
        assertEquals(4, l.points.size)
        for ((a, b) in l.points.zipWithNext()) assertTrue(abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f, "square: $a to $b")
        assertEquals(d.shapes[1].id, l.from)
        assertEquals(d.shapes[0].id, l.to)
        assertEquals(d.shapes[0].x, l.points.last().x, 0.01f)
    }

    @Test
    fun anArrowDrawnInOneStroke() {
        val arrow = wobbly(through(100f to 100f, 260f to 100f, 240f to 88f, 260f to 100f, 240f to 112f), 0.5f)
        val l = Diagram().draw(arrow).diagram.lines.single()
        assertTrue(l.arrowEnd)
        assertEquals(2, l.points.size)
        assertEquals(260f, l.points[1].x, 5f)
    }

    @Test
    fun axesWithArrowheadsFromOneOrigin() {
        val up = Diagram().draw(wobbly(through(200f to 420f, 200f to 180f, 190f to 198f, 200f to 180f, 210f to 198f), 0.4f))
        assertTrue(up.diagram.lines.single().arrowEnd)
        val across = up.diagram.draw(wobbly(through(200f to 420f, 540f to 420f, 522f to 410f, 540f to 420f, 522f to 430f), 0.4f))
        assertEquals(2, across.diagram.lines.size)
        assertTrue(across.diagram.lines[1].arrowEnd)
    }

    @Test
    fun anElbowWithAnArrowOnItsEnd() {
        val s = wobbly(through(100f to 100f, 300f to 100f, 300f to 240f, 290f to 222f, 300f to 240f, 310f to 222f), 0.5f)
        val l = Diagram().draw(s).diagram.lines.single()
        assertTrue(l.arrowEnd)
        assertEquals(3, l.points.size)
    }

    @Test
    fun aSmallVOnALineIsAnArrowheadTheWayItPoints() {
        val start = Diagram().draw(line(100f, 200f, 400f, 200f)).diagram
        val mid = start.draw(through(244f to 190f, 256f to 200f, 244f to 210f))
        assertEquals(DiagramMade.MARK, mid.made)
        val arrow = mid.diagram.lines.single().arrows.single()
        assertEquals(0.52f, arrow.at, 0.04f)
        assertFalse(arrow.reversed)
        val back = start.draw(through(256f to 190f, 244f to 200f, 256f to 210f)).diagram.lines.single().arrows.single()
        assertTrue(back.reversed)
        val end = start.draw(through(388f to 190f, 400f to 200f, 388f to 210f)).diagram.lines.single()
        assertTrue(end.arrowEnd)
        assertTrue(end.arrows.isEmpty())
    }

    @Test
    fun anArcBetweenTwoNodesEndsOnThem() {
        val nodes = Diagram().draws(through(100f to 200f, 102f to 201f), through(300f to 200f, 301f to 201f))
        assertEquals(2, nodes.shapes.count { it.type == DiagramShapeType.DOT })
        val d = nodes.draw(circle(200f, 200f, 100f, from = PI, sweep = PI)).diagram
        val l = d.lines.single()
        assertEquals(DiagramLineType.ARC, l.type)
        assertNotNull(l.from)
        assertNotNull(l.to)
        val path = DiagramGeometry.path(d, l)
        val mid = path[path.size / 2]
        assertEquals(100f, abs(mid.y - 200f), 12f)
    }

    @Test
    fun aDotOnALineStaysOnItAndALineFromItFollows() {
        val d0 = Diagram().draws(box(100f, 100f, 200f, 170f), box(420f, 100f, 520f, 170f), line(205f, 135f, 415f, 135f))
        // A line going down from the first line starts with a dot on it.
        val d = d0.draw(line(310f, 138f, 310f, 280f)).diagram
        val dot = d.shapes.first { it.type == DiagramShapeType.DOT }
        val branch = d.lines.last()
        assertEquals(dot.id, branch.from)
        assertEquals(135f, dot.y, 0.5f)
        assertEquals(dot.x, branch.points.last().x, 0.5f, "the branch is upright")
        val moved = d.moved(DiagramPart.Shape(d.shapes[1].id), 0f, 40f)
        val line = moved.lines.first()
        val movedDot = moved.shapes.first { it.id == dot.id }
        assertTrue(DiagramGeometry.distance(moved, line, DiagramPoint(movedDot.x, movedDot.y)) < 1f, "the dot is still on the line")
        assertEquals(movedDot.y, DiagramGeometry.path(moved, moved.lines.last()).first().y, DiagramGeometry.LINE_WIDTH + movedDot.height, "and the branch starts at it")
    }

    @Test
    fun aZigzagIsAResistor() {
        val teeth = (0..8).map { i -> (100f + i * 22f) to (if (i == 0 || i == 8) 200f else if (i % 2 == 0) 214f else 186f) }
        val l = Diagram().draw(wobbly(through(*teeth.toTypedArray()), 0.4f)).diagram.lines.single()
        assertEquals(DiagramSymbol.RESISTOR, l.symbol)
        assertEquals(2, l.points.size)
        assertEquals(l.points[0].y, l.points[1].y, 0.01f)
    }

    @Test
    fun aLongCurveIsSteadiedAndKeepsItsEnds() {
        // A step response: it rises, overshoots and settles, which no one arc fits.
        val raw = (0..200).map { i -> DiagramPoint(100f + i * 2f, 300f - 120f * (1f - kotlin.math.exp(-i / 40f) * cos(i / 18f))) }
        val shaky = wobbly(raw, 1.2f)
        val step = Diagram().draw(shaky)
        assertEquals(DiagramMade.CURVE, step.made)
        val l = step.diagram.lines.single()
        assertEquals(DiagramLineType.CURVE, l.type)
        val path = DiagramGeometry.path(step.diagram, l)
        assertTrue(shaky.first().distance(path.first()) < 4f)
        assertTrue(shaky.last().distance(path.last()) < 4f)
        fun roughness(p: List<DiagramPoint>): Float {
            val even = (0..100).map { DiagramGeometry.pointAlong(p, it / 100f) }
            return even.windowed(3).map { (a, b, c) -> hypot(a.x - 2 * b.x + c.x, a.y - 2 * b.y + c.y) }.average().toFloat()
        }
        assertTrue(roughness(path) < roughness(shaky), "smoother than drawn: ${roughness(path)} against ${roughness(shaky)}")
    }

    @Test
    fun aTickOnAnAxisStaysALine() {
        val d = Diagram().draw(line(100f, 300f, 400f, 300f)).diagram
        val step = d.draw(line(200f, 292f, 200f, 308f))
        assertEquals(DiagramMade.LINE, step.made)
        assertEquals(2, step.diagram.lines.size)
    }

    @Test
    fun aTapMakesADotAndOneOnALabelFindsIt() {
        val dot = Diagram().draw(listOf(DiagramPoint(100f, 100f), DiagramPoint(101f, 100f)))
        assertEquals(DiagramShapeType.DOT, dot.diagram.shapes.single().type)
        val labelled = Diagram(labels = listOf(DiagramLabel(9, 300f, 300f, latex = "x")), nextId = 10)
        val tap = labelled.draw(listOf(DiagramPoint(300f, 300f), DiagramPoint(301f, 300f)))
        assertEquals(9, tap.tapped)
        assertEquals(labelled, tap.diagram)
    }

    @Test
    fun aCircleOnANodeIsALoopBackToIt() {
        val node = Diagram().draw(through(200f to 200f, 201f to 201f)).diagram
        val d = node.draw(circle(200f, 160f, 40f)).diagram
        val l = d.lines.single()
        assertEquals(l.from, l.to)
        assertEquals(node.shapes.single().id, l.from)
    }

    @Test
    fun deletingAShapeLeavesItsLinesWhereTheyWere() {
        val d = Diagram().draws(box(100f, 100f, 200f, 170f), box(320f, 100f, 420f, 170f), line(205f, 135f, 315f, 135f))
        val without = d.without(DiagramPart.Shape(d.shapes[1].id))
        val l = without.lines.single()
        assertNull(l.to)
        assertEquals(d.shapes[1].x - d.shapes[1].width / 2, l.points.last().x, 0.5f)
    }

    @Test
    fun normalizedStartsAtThePaddingAndKeepsTheDrawing() {
        val d = Diagram().draws(box(100f, 100f, 200f, 170f))
        val (n, by) = d.normalized()
        assertEquals(Diagram.PADDING - 100f, by.x, 3f)
        assertEquals(Diagram.PADDING, n.shapes.single().x - n.shapes.single().width / 2, 0.01f)
        assertEquals(n.shapes.single().width + 2 * Diagram.PADDING, n.width, 0.01f)
    }

    @Test
    fun everySymbolAndKindDrawsAndRoundTripsThroughJson() {
        var d = Diagram().draws(box(100f, 100f, 200f, 170f), line(205f, 135f, 400f, 135f))
        val symbols = DiagramSymbol.entries.mapIndexed { i, s -> DiagramLine(100 + i, listOf(DiagramPoint(0f, 300f + i * 40), DiagramPoint(200f, 300f + i * 40)), symbol = s) }
        val shapes = DiagramShapeType.entries.mapIndexed { i, t -> DiagramFacing.entries.mapIndexed { j, f -> DiagramShape(200 + i * 10 + j, t, 500f + j * 80, 100f + i * 80, 60f, 50f, f) } }.flatten()
        d = d.copy(
            lines = d.lines + symbols,
            shapes = d.shapes + shapes,
            labels = listOf(
                DiagramLabel(300, 10f, 10f, latex = "\\frac{1}{s}", shape = d.shapes[0].id),
                DiagramLabel(301, 20f, 20f, text = "Plant", words = true),
                DiagramLabel(302, 30f, 30f, ink = listOf(listOf(DiagramPoint(1f, 2f), DiagramPoint(3f, 4f))), unread = true),
            ),
            nextId = 400,
        )
        val plotted = DiagramGeometry.plot(d)
        assertTrue(plotted.strokes.all { s -> s.points.all { it.x.isFinite() && it.y.isFinite() } && s.points.size >= 2 })
        assertEquals(d, DiagramJson.decode(DiagramJson.encode(d)))
    }

    @Test
    fun badStoredDiagramsAreRefusedOrCleaned() {
        assertNull(DiagramJson.decode("not json"))
        assertNull(DiagramJson.decode("""{"width":1,"height":1,"nextId":1,"lines":[{"id":1,"points":[1,2,3]}]}"""))
        assertNull(DiagramJson.decode("""{"width":-1,"height":1,"nextId":1}"""))
        val loose = DiagramJson.decode("""{"width":1,"height":1,"nextId":3,"lines":[{"id":1,"points":[1,2,3,4],"from":99}]}""")
        assertNull(loose!!.lines.single().from, "an end held by nothing is let go")
    }
}
