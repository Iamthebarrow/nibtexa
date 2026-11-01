package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LassoTest {
    private val square = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)

    private fun stroke(id: Long, vararg xy: Pair<Float, Float>) =
        Stroke(id, ToolType.STYLUS, 0, xy.mapIndexed { i, (x, y) -> InkPoint(x, y, i.toLong()) })

    @Test
    fun containsUsesTheClosedLoop() {
        assertTrue(Lasso.contains(square, 50f, 50f))
        assertFalse(Lasso.contains(square, 150f, 50f))
        assertFalse(Lasso.contains(square, -1f, 50f))
    }

    @Test
    fun concaveLoopsWork() {
        val u = listOf(0f to 0f, 30f to 0f, 30f to 70f, 70f to 70f, 70f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        assertTrue(Lasso.contains(u, 10f, 50f))
        assertFalse(Lasso.contains(u, 50f, 30f)) // inside the notch
    }

    @Test
    fun selectsWholeStrokesByTheShareOfPointsInside() {
        val inside = stroke(1, 10f to 10f, 20f to 20f)
        val half = stroke(2, 50f to 50f, 150f to 50f)
        val mostlyOut = stroke(3, 90f to 90f, 150f to 150f, 160f to 160f)
        val outside = stroke(4, 200f to 200f)

        assertEquals(setOf(1L, 2L), Lasso.select(square, listOf(inside, half, mostlyOut, outside)))
    }

    @Test
    fun degenerateLoopSelectsNothing() {
        assertEquals(emptySet<Long>(), Lasso.select(listOf(0f to 0f, 10f to 10f), listOf(stroke(1, 5f to 5f))))
    }

    @Test
    fun eraserHitsStrokesNearItsPath() {
        val near = stroke(1, 10f to 10f, 20f to 10f)
        val far = stroke(2, 100f to 100f)
        val crossed = stroke(3, 50f to 0f, 50f to 40f)

        assertEquals(setOf(1L, 3L), Lasso.hit(listOf(0f to 14f, 60f to 14f), 5f, listOf(near, far, crossed)))
        assertEquals(setOf(2L), Lasso.hit(listOf(101f to 101f), 3f, listOf(near, far, crossed)))
        assertEquals(emptySet<Long>(), Lasso.hit(emptyList(), 5f, listOf(near)))
    }
}
