package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shapes from strokes (Phase 23), on generated strokes: hand-drawn-like wobble, drawn at many sizes and
 * angles, with an accuracy target; and things that are not shapes, which must be left alone.
 */
class ShapeRecognizerTest {
    private val sizes = listOf(40f, 100f, 300f, 800f)
    private val angles = listOf(0.0, 12.0, 37.0, 90.0, 158.0)
    private val wobbles = listOf(0f, 0.008f, 0.016f)

    /** A slow-changing wander, as a hand makes, of up to [amount] of the shape's size. */
    private fun wobbly(points: List<Pair<Float, Float>>, amount: Float, size: Float, seed: Int): List<InkPoint> {
        val random = Random(seed)
        val phases = List(4) { random.nextDouble(0.0, 2 * PI) }
        val a = amount * size
        return points.mapIndexed { i, (x, y) ->
            val t = i / 40.0
            val dx = a * (sin(t * 1.3 + phases[0]) + 0.5 * sin(t * 3.1 + phases[1])) / 1.5
            val dy = a * (sin(t * 1.1 + phases[2]) + 0.5 * sin(t * 2.7 + phases[3])) / 1.5
            InkPoint(x + dx.toFloat(), y + dy.toFloat(), i * 8L)
        }
    }

    private fun rotated(points: List<Pair<Float, Float>>, degrees: Double): List<Pair<Float, Float>> {
        val r = degrees * PI / 180
        return points.map { (x, y) -> (x * cos(r) - y * sin(r)).toFloat() + 2000f to (x * sin(r) + y * cos(r)).toFloat() + 2000f }
    }

    /** Evenly spaced points along straight runs through [corners]. */
    private fun polyline(vararg corners: Pair<Float, Float>): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        for ((a, b) in corners.toList().zipWithNext()) {
            val steps = (hypot(b.first - a.first, b.second - a.second) / 3f).toInt().coerceAtLeast(4)
            for (i in 0 until steps) out += a.first + (b.first - a.first) * i / steps to a.second + (b.second - a.second) * i / steps
        }
        out += corners.last()
        return out
    }

    private fun oval(a: Float, b: Float, overlap: Double = 0.03): List<Pair<Float, Float>> =
        (0..200).map { i -> val t = 2 * PI * (i / 200.0) * (1 + overlap); (a * cos(t)).toFloat() to (b * sin(t)).toFloat() }

    private fun line(s: Float) = polyline(-s / 2 to 0f, s / 2 to 0f)
    private fun arrow(s: Float) = polyline(-s / 2 to 0f, s / 2 to 0f, s / 2 - s * 0.25f to -s * 0.15f, s / 2 to 0f, s / 2 - s * 0.25f to s * 0.15f)
    private fun rectangle(s: Float) = polyline(-s / 2 to -s / 4, s / 2 to -s / 4, s / 2 to s / 4, -s / 2 to s / 4, -s / 2 to -s / 4)
    private fun square(s: Float) = polyline(-s / 2 to -s / 2, s / 2 to -s / 2, s / 2 to s / 2, -s / 2 to s / 2, -s / 2 to -s / 2)
    private fun triangle(s: Float) = polyline(-s / 2 to s / 3, s / 2 to s / 3, 0f to -s / 2, -s / 2 to s / 3)

    /** The share of [shape]'s drawings at every size, angle and wobble that come out as [kind]. */
    private fun accuracy(kind: ShapeKind, rotates: Boolean = true, shape: (Float) -> List<Pair<Float, Float>>): Pair<Double, List<String>> {
        var right = 0
        var total = 0
        val wrong = mutableListOf<String>()
        var seed = 1
        for (size in sizes) for (angle in if (rotates) angles else listOf(0.0)) for (wobble in wobbles) {
            total++
            val got = ShapeRecognizer.recognize(wobbly(rotated(shape(size), angle), wobble, size, seed++), snap = false)
            if (got?.kind == kind) right++ else wrong += "size $size angle $angle wobble $wobble -> ${got?.kind}"
        }
        return right.toDouble() / total to wrong
    }

    private fun assertAccurate(kind: ShapeKind, rotates: Boolean = true, shape: (Float) -> List<Pair<Float, Float>>) {
        val (score, wrong) = accuracy(kind, rotates, shape)
        assertTrue("$kind only ${(score * 100).toInt()}%: ${wrong.take(8)}", score >= 0.95)
    }

    @Test fun lines() = assertAccurate(ShapeKind.LINE, shape = ::line)
    @Test fun arrows() = assertAccurate(ShapeKind.ARROW, shape = ::arrow)
    @Test fun circles() = assertAccurate(ShapeKind.CIRCLE, rotates = false) { s -> oval(s / 2, s / 2) }
    @Test fun ellipses() = assertAccurate(ShapeKind.ELLIPSE) { s -> oval(s / 2, s / 4) }
    @Test fun triangles() = assertAccurate(ShapeKind.TRIANGLE, shape = ::triangle)
    @Test fun rectangles() = assertAccurate(ShapeKind.RECTANGLE, shape = ::rectangle)
    @Test fun squares() = assertAccurate(ShapeKind.SQUARE, shape = ::square)

    @Test
    fun whatIsNotAShapeIsLeftAlone() {
        val notShapes = mapOf(
            "scribble" to (0..120).map { i -> (i * 3f + (if (i % 2 == 0) 0f else 40f)) to (if (i % 4 < 2) 0f else 60f) },
            "wave" to (0..200).map { i -> i * 3f to (60 * sin(i / 12.0)).toFloat() },
            "spiral" to (0..300).map { i -> val t = i / 15.0; ((10 + i * 0.5) * cos(t)).toFloat() to ((10 + i * 0.5) * sin(t)).toFloat() },
            "half circle" to (0..100).map { i -> val t = PI * i / 100; (200 * cos(t)).toFloat() to (200 * sin(t)).toFloat() },
            "letter L" to polyline(0f to 0f, 0f to 300f, 180f to 300f),
            "letter S" to (0..160).map { i -> val t = i / 160.0 * 2 * PI; (120 * sin(t)).toFloat() to (i * 2f) },
            "letter N" to polyline(0f to 300f, 0f to 0f, 200f to 300f, 200f to 0f),
            "star" to polyline(0f to -200f, 118f to 162f, -190f to -62f, 190f to -62f, -118f to 162f, 0f to -200f),
            "pentagon" to (0..5).map { i -> val t = 2 * PI * i / 5 - PI / 2; (300 * cos(t)).toFloat() to (300 * sin(t)).toFloat() }.let { polyline(*it.toTypedArray()) },
            "line and back" to polyline(0f to 0f, 400f to 0f, 0f to 8f),
            // A written e: its bar across the middle, then round the top and left to an open end.
            "written e" to (polyline(-100f to 0f, 100f to 0f) + (0..150).map { i -> val t = i / 150.0 * 1.6 * PI; (100 * cos(t)).toFloat() to (-100 * sin(t)).toFloat() }),
        )
        for ((name, points) in notShapes) {
            val got = ShapeRecognizer.recognize(points.mapIndexed { i, (x, y) -> InkPoint(x, y, i * 8L) }, snap = false)
            assertNull("$name became ${got?.kind}", got)
        }
    }

    @Test
    fun tinyOrEmptyStrokesAreLeftAlone() {
        assertNull(ShapeRecognizer.recognize(listOf(InkPoint(0f, 0f, 0))))
        assertNull(ShapeRecognizer.recognize(listOf(InkPoint(0f, 0f, 0), InkPoint(1f, 1f, 8), InkPoint(2f, 1f, 16))))
        assertNull(ShapeRecognizer.recognize(List(50) { InkPoint(5f, 5f, it * 8L) }))
    }

    private fun recognized(points: List<Pair<Float, Float>>, snap: Boolean = true) =
        assertNotNull(ShapeRecognizer.recognize(points.mapIndexed { i, (x, y) -> InkPoint(x, y, i * 8L) }, snap = snap)).let { ShapeRecognizer.recognize(points.mapIndexed { i, (x, y) -> InkPoint(x, y, i * 8L) }, snap = snap)!! }

    private fun degrees(a: InkPoint, b: InkPoint) = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 180 / PI

    @Test
    fun aNearlyLevelLineSnapsWhileSnappingIsOn() {
        val slightly = polyline(100f to 100f, 500f to 112f)
        val on = recognized(slightly)
        assertEquals(ShapeKind.LINE, on.kind)
        assertEquals(0.0, degrees(on.points.first(), on.points.last()), 0.01)
        // The middle stays where it was drawn.
        assertEquals(106f, (on.points.first().y + on.points.last().y) / 2, 0.5f)
        val off = recognized(slightly, snap = false)
        assertEquals(atan2(12.0, 400.0) * 180 / PI, degrees(off.points.first(), off.points.last()), 0.01)
    }

    @Test
    fun anAngledLineSnapsToFifteenDegreeStepsOnlyWhenClose() {
        fun at(angle: Double): List<Pair<Float, Float>> {
            val r = angle * PI / 180
            return polyline(1000f to 1000f, 1000f + 400 * cos(r).toFloat() to 1000f + 400 * sin(r).toFloat())
        }
        val near = recognized(at(47.0))
        assertEquals(45.0, degrees(near.points.first(), near.points.last()), 0.01)
        // 37.5 is as far from a step as can be, so it stays.
        val far = recognized(at(37.5))
        assertEquals(37.5, degrees(far.points.first(), far.points.last()), 0.5)
    }

    @Test
    fun aNearlyLevelRectangleComesOutLevelAndTheShapeIsAccurate() {
        val drawn = rotated(rectangle(400f), 3.0)
        val shape = recognized(drawn)
        assertEquals(ShapeKind.RECTANGLE, shape.kind)
        val xs = shape.points.map { it.x }
        val ys = shape.points.map { it.y }
        assertEquals(400f, xs.max() - xs.min(), 20f)
        assertEquals(200f, ys.max() - ys.min(), 20f)
        // Every point is on the box's edge: level, with no rotation left.
        assertTrue(shape.points.all { p -> abs(p.x - xs.min()) < 0.5f || abs(p.x - xs.max()) < 0.5f || abs(p.y - ys.min()) < 0.5f || abs(p.y - ys.max()) < 0.5f })
    }

    @Test
    fun theOutcomeIsAWholeStrokeThatStartsAndEndsTogetherWhenClosed() {
        val circle = recognized(oval(150f, 150f))
        assertEquals(ShapeKind.CIRCLE, circle.kind)
        assertEquals(circle.points.first().x, circle.points.last().x, 0.01f)
        assertEquals(circle.points.first().y, circle.points.last().y, 0.01f)
        // Time always moves on, and points are dense enough to keep their shape.
        assertTrue(circle.points.zipWithNext().all { (a, b) -> b.elapsedMillis > a.elapsedMillis })
        assertTrue(circle.points.size >= 48)
        val center = circle.points.map { it.x }.average().toFloat() to circle.points.map { it.y }.average().toFloat()
        assertTrue(circle.points.all { abs(hypot(it.x - center.first, it.y - center.second) - 150f) < 8f })
    }

    @Test
    fun oneUndoBringsTheHandDrawnStrokeBackEvenAfterGrouping() {
        val drawn = line(300f).map { (x, y) -> InkPoint(x + 400f, y + 400f, 0) }.mapIndexed { i, p -> p.copy(elapsedMillis = i * 8L) }
        val shape = ShapeRecognizer.recognize(drawn)!!
        val e = PageEditor(Page("p-1"))
        e.addStroke(io.github.iamthebarrow.nibtexa.core.model.CapturedStroke(io.github.iamthebarrow.nibtexa.core.model.ToolType.STYLUS, 0, drawn, io.github.iamthebarrow.nibtexa.core.model.Pen.DRAWING))
        val id = e.page.ink.strokes.single().id
        assertTrue(e.convertStroke(id, shape.points))
        // Grouping runs a moment later, and makes a figure region of it.
        e.autoGroup(AutoRegions.plan(e.page))
        assertEquals(1, e.page.regions.size)
        assertEquals(RegionRole.FIGURE, e.page.regions.single().role)
        assertEquals(shape.points, e.page.ink.strokes.single().points)

        assertTrue(e.undo())
        assertEquals(drawn, e.page.ink.strokes.single().points)
        assertEquals(0, e.page.regions.size)
        assertTrue(e.redo())
        assertEquals(shape.points, e.page.ink.strokes.single().points)
        assertEquals(1, e.page.regions.size)
    }

    @Test
    fun aHoldAtTheEndOfTheStrokeDoesNotChangeTheShape() {
        // A pen held still jitters a little, at the very end, for many samples.
        val held = line(300f) + List(80) { i -> 150f + (i % 3) * 0.4f to 0.5f * (i % 2) }
        assertEquals(ShapeKind.LINE, recognized(held).kind)
        val closed = oval(150f, 150f) + List(80) { i -> 150f + (i % 3) * 0.4f to 0.5f * (i % 2) }
        assertEquals(ShapeKind.CIRCLE, recognized(closed).kind)
    }
}
