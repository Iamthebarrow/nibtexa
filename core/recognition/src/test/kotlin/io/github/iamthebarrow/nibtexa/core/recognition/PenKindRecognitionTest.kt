package io.github.iamthebarrow.nibtexa.core.recognition

import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** A pen's kind only changes how ink looks (Phase 22): the image the math model sees comes from the points alone. */
class PenKindRecognitionTest {
    @Test
    fun theKindDoesNotChangeTheImageRecognitionSees() {
        fun strokes(kind: PenKind) = listOf(
            Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(40f, 30f, 8), InkPoint(80f, 5f, 16)), Pen.MATH, kind),
            Stroke(2, ToolType.STYLUS, 20, listOf(InkPoint(10f, 40f, 0), InkPoint(70f, 40f, 8)), Pen.MATH, kind),
        )
        val gel = StrokeRenderer.render(strokes(PenKind.GEL))
        for (kind in PenKind.entries) {
            assertArrayEquals(gel.pixels, StrokeRenderer.render(strokes(kind)).pixels)
        }
    }
}
