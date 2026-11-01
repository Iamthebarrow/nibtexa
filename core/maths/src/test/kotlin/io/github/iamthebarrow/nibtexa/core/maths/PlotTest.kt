package io.github.iamthebarrow.nibtexa.core.maths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/** Curves, ranges and ticks (Phase 24). */
class PlotTest {
    private val wide = Range(-10.0, 10.0)
    private fun f(latex: String) = Equation.parse(latex).function()

    private fun assertNoFalseLines(pieces: List<CurvePiece>, y: Range) {
        for (p in pieces) for (i in 1 until p.ys.size) {
            assertTrue("a line of ${p.ys[i] - p.ys[i - 1]} joins up at x=${p.xs[i]}", abs(p.ys[i] - p.ys[i - 1]) < 2 * y.span)
        }
    }

    @Test
    fun aSmoothCurveIsOnePiece() {
        val pieces = Plot.sample(f("y=\\sin x"), wide, Range(-2.0, 2.0))
        assertEquals(1, pieces.size)
        assertEquals(Plot.MAX_POINTS, pieces.single().xs.size)
        assertEquals(-10.0, pieces.single().xs.first(), 1e-9)
        assertEquals(10.0, pieces.single().xs.last(), 1e-9)
    }

    @Test
    fun aPoleBreaksTheCurveWithNoFalseLine() {
        val y = Range(-10.0, 10.0)
        val pieces = Plot.sample(f("y=\\frac{1}{x}"), wide, y)
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].xs.all { it < 0 })
        assertTrue(pieces[1].xs.all { it > 0 })
        assertNoFalseLines(pieces, y)
    }

    @Test
    fun hugeValuesAreBroughtWithinReachOnTheSameLine() {
        val y = Range(-1.0, 1.0)
        val pieces = Plot.sample(f("y=x^3"), wide, y)
        val piece = pieces.single()
        assertTrue(piece.ys.all { it in -3.0..3.0 })
        // Each stays on the line between the samples either side of the edge, so it still leaves at the same place.
        assertTrue(piece.xs.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun aPoleExactlyOnASampleBreaksToo() {
        val pieces = Plot.sample(f("y=\\frac{1}{x}"), Range(-1.0, 1.0), Range(-5.0, 5.0), points = 201)
        assertEquals(2, pieces.size)
    }

    @Test
    fun tangentBreaksAtEveryPole() {
        val y = Range(-5.0, 5.0)
        val pieces = Plot.sample(::tan, wide, y)
        // Six poles between -10 and 10 make seven branches.
        assertEquals(7, pieces.size)
        assertNoFalseLines(pieces, y)
    }

    @Test
    fun aJumpBreaks() {
        val pieces = Plot.sample(f("y=\\lfloor x\\rfloor"), Range(-2.5, 2.5), Range(-4.0, 4.0))
        assertEquals(6, pieces.size)
    }

    @Test
    fun aSteepButContinuousCurveStaysWhole() {
        val pieces = Plot.sample(f("y=x^3"), wide, Range(-1.0, 1.0))
        assertEquals(1, pieces.size)
        val logistic = Plot.sample(f("y=\\frac{1}{1+e^{-50x}}"), Range(-1.0, 1.0), Range(-0.1, 1.1))
        assertEquals(1, logistic.size)
    }

    @Test
    fun whereItIsUndefinedThereIsNoCurve() {
        val pieces = Plot.sample(f("y=\\sqrt{x}"), wide, Range(0.0, 4.0))
        assertEquals(1, pieces.size)
        assertTrue(pieces.single().xs.all { it >= 0 })
        assertEquals(emptyList<CurvePiece>(), Plot.sample(f("y=\\sqrt{-x^2-1}"), wide, wide))
    }

    @Test
    fun pointsOffTheGraphAreKeptOnlyBesideOneOnIt() {
        val y = Range(-1.0, 1.0)
        val piece = Plot.sample(f("y=x^2"), wide, y).single()
        val outside = piece.ys.count { it !in y.min..y.max }
        assertEquals(2, outside)
        assertTrue(piece.xs.size < 150)
        // A curve wholly off the graph has nothing to draw.
        assertEquals(emptyList<CurvePiece>(), Plot.sample(f("y=x^2+100"), wide, y))
        // A wave going off the top and coming back is pieces, each running off cleanly.
        assertEquals(4, Plot.sample(f("y=3\\sin x"), wide, Range(-10.0, 1.0)).size)
    }

    @Test
    fun autoRangeFitsTheCurveWithoutAPoleSquashingIt() {
        val line = Plot.autoRange(f("y=2x+1"), Range(0.0, 10.0))
        assertTrue(line.min in -1.5..1.0 && line.max in 20.0..23.0)
        val pole = Plot.autoRange(f("y=\\frac{1}{x}"), wide)
        assertTrue("$pole", pole.span < 40)
        val flat = Plot.autoRange(f("y=5"), wide)
        assertEquals(5.0, (flat.min + flat.max) / 2, 1e-9)
        assertTrue(flat.span > 0)
        assertEquals(Range(-3.0, 3.0), Plot.autoRange(f("y=\\sqrt{-x^2-1}"), wide, Range(-3.0, 3.0)))
    }

    @Test
    fun ticksAreRoundAndAFewAcrossAnyRange() {
        for (span in listOf(1e-6, 0.003, 0.1, 1.0, 3.0, 7.0, 20.0, 45.0, 100.0, 12345.0, 1e9)) {
            for (start in listOf(0.0, -span / 2, span * 3.3)) {
                val ticks = Plot.ticks(Range(start, start + span))
                assertTrue("$span from $start: ${ticks.size}", ticks.size in 4..11)
                assertTrue(ticks.all { it >= start - span * 1e-6 && it <= start + span * (1 + 1e-6) })
                val step = ticks[1] - ticks[0]
                val leading = step / 10.0.pow10(step)
                assertTrue("$step", listOf(1.0, 2.0, 5.0).any { abs(leading - it) < 1e-6 })
            }
        }
        assertEquals(listOf(-10.0, -8.0, -6.0, -4.0, -2.0, 0.0, 2.0, 4.0, 6.0, 8.0, 10.0), Plot.ticks(wide))
        // Zero is exactly zero, not a tiny leftover.
        assertTrue(0.0 in Plot.ticks(Range(-0.3, 0.3)))
    }

    private fun Double.pow10(step: Double) = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(step) + 1e-9))

    @Test
    fun labelsAreShort() {
        assertEquals("0", Plot.label(0.0, 2.0))
        assertEquals("2", Plot.label(2.0, 2.0))
        assertEquals("-10", Plot.label(-10.0, 2.0))
        assertEquals("0.25", Plot.label(0.25, 0.05))
        assertEquals("1.5", Plot.label(1.5, 0.5))
        assertEquals("1000", Plot.label(1000.0, 200.0))
        assertEquals("2e6", Plot.label(2e6, 1e6))
        assertEquals("5e-5", Plot.label(5e-5, 1e-5))
    }
}
