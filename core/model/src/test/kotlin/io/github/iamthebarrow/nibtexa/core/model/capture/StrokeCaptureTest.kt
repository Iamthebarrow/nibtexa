package io.github.iamthebarrow.nibtexa.core.model.capture

import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent.Canceled
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent.Extended
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent.Finished
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent.Started
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeCaptureTest {

    private fun sample(
        id: Int = 0,
        tool: ToolType = ToolType.STYLUS,
        x: Float = 0f,
        y: Float = 0f,
        t: Long = 1000,
        pressure: Float? = 0.5f,
    ) = PointerSample(id, tool, x, y, t, pressure)

    private fun StrokeCapture.drawLine(id: Int = 0, tool: ToolType = ToolType.STYLUS, startT: Long = 1000): List<CaptureEvent> {
        val events = mutableListOf<CaptureEvent>()
        events += onDown(sample(id, tool, 0f, 0f, startT))
        events += onMove(id, listOf(sample(id, tool, 5f, 5f, startT + 8), sample(id, tool, 10f, 10f, startT + 16)))
        events += onUp(sample(id, tool, 15f, 15f, startT + 24))
        return events
    }

    @Test
    fun stylusStrokeIsCapturedWithRelativeTimesAndPressure() {
        val events = StrokeCapture().drawLine()

        assertEquals(listOf(Started(0, ToolType.STYLUS), Extended(0)), events.take(2))
        val stroke = (events.last() as Finished).stroke
        assertEquals(ToolType.STYLUS, stroke.tool)
        assertEquals(1000L, stroke.startTimeMillis)
        assertEquals(listOf(0L, 8L, 16L, 24L), stroke.points.map { it.elapsedMillis })
        assertEquals(listOf(0f, 5f, 10f, 15f), stroke.points.map { it.x })
        assertTrue(stroke.points.all { it.pressure == 0.5f })
    }

    @Test
    fun tapProducesSinglePointStroke() {
        val capture = StrokeCapture()
        capture.onDown(sample(x = 3f, y = 4f))
        val events = capture.onUp(sample(x = 3f, y = 4f))

        assertEquals(1, (events.single() as Finished).stroke.points.size)
    }

    @Test
    fun fingerDoesNotInkByDefault() {
        val events = StrokeCapture().drawLine(tool = ToolType.FINGER)

        assertTrue(events.isEmpty())
    }

    @Test
    fun fingerInksWhenPolicyAllows() {
        val events = StrokeCapture(InputPolicy(fingerDraws = true)).drawLine(tool = ToolType.FINGER)

        assertEquals(ToolType.FINGER, (events.last() as Finished).stroke.tool)
    }

    @Test
    fun mouseInks() {
        val events = StrokeCapture().drawLine(tool = ToolType.MOUSE)

        assertTrue(events.last() is Finished)
    }

    @Test
    fun eraserAndUnknownToolsDoNotInk() {
        assertTrue(StrokeCapture().drawLine(tool = ToolType.ERASER).isEmpty())
        assertTrue(StrokeCapture().drawLine(tool = ToolType.UNKNOWN).isEmpty())
    }

    @Test
    fun palmTouchDuringStylusStrokeIsIgnored() {
        val capture = StrokeCapture(InputPolicy(fingerDraws = true))
        capture.onDown(sample(id = 0))

        assertTrue(capture.onDown(sample(id = 1, tool = ToolType.FINGER, t = 1004)).isEmpty())
        assertTrue(capture.onMove(1, listOf(sample(id = 1, tool = ToolType.FINGER, x = 50f, t = 1008))).isEmpty())
        assertTrue(capture.onUp(sample(id = 1, tool = ToolType.FINGER, t = 1010)).isEmpty())

        val finished = capture.onUp(sample(id = 0, x = 9f, t = 1020)).single() as Finished
        assertEquals(listOf(0f, 9f), finished.stroke.points.map { it.x })
    }

    @Test
    fun stylusDownCancelsFingerStrokeAndStartsItsOwn() {
        val capture = StrokeCapture(InputPolicy(fingerDraws = true))
        capture.onDown(sample(id = 0, tool = ToolType.FINGER))

        val events = capture.onDown(sample(id = 1, tool = ToolType.STYLUS, t = 1010))

        assertEquals(listOf(Canceled(0), Started(1, ToolType.STYLUS)), events)
    }

    @Test
    fun secondFingerCancelsFingerStrokeUntilAllFingersLift() {
        val capture = StrokeCapture(InputPolicy(fingerDraws = true))
        capture.onDown(sample(id = 0, tool = ToolType.FINGER))

        assertEquals(listOf(Canceled(0)), capture.onDown(sample(id = 1, tool = ToolType.FINGER, t = 1005)))

        // One finger lifts; the remaining one must not start inking mid-gesture.
        capture.onUp(sample(id = 0, tool = ToolType.FINGER, t = 1020))
        assertTrue(capture.onDown(sample(id = 2, tool = ToolType.FINGER, t = 1030)).isEmpty())

        // After every finger is up, a new finger stroke works again.
        capture.onUp(sample(id = 1, tool = ToolType.FINGER, t = 1040))
        capture.onUp(sample(id = 2, tool = ToolType.FINGER, t = 1041))
        assertEquals(listOf(Started(3, ToolType.FINGER)), capture.onDown(sample(id = 3, tool = ToolType.FINGER, t = 1050)))
    }

    @Test
    fun canceledPointerUpDropsStroke() {
        val capture = StrokeCapture()
        capture.onDown(sample())
        capture.onMove(0, listOf(sample(x = 5f, t = 1008)))

        assertEquals(listOf(Canceled(0)), capture.onUp(sample(x = 6f, t = 1010), canceled = true))
        assertFalse(capture.hasActiveStroke)
    }

    @Test
    fun gestureCancelDropsStroke() {
        val capture = StrokeCapture()
        capture.onDown(sample())

        assertEquals(listOf(Canceled(0)), capture.onCancel())
        assertTrue(capture.onUp(sample(t = 1100)).isEmpty())
    }

    @Test
    fun secondStylusWhileWritingIsIgnored() {
        val capture = StrokeCapture()
        capture.onDown(sample(id = 0))

        assertTrue(capture.onDown(sample(id = 1, t = 1003)).isEmpty())
        assertTrue(capture.onMove(1, listOf(sample(id = 1, x = 20f, t = 1006))).isEmpty())
    }

    @Test
    fun duplicateAndOutOfOrderSamplesAreDropped() {
        val capture = StrokeCapture()
        capture.onDown(sample(x = 1f, t = 1000))

        val events = capture.onMove(0, listOf(sample(x = 1f, t = 1000), sample(x = 2f, t = 999), sample(x = 3f, t = 1004)))
        val stroke = (capture.onUp(sample(x = 3f, t = 1004)).single() as Finished).stroke

        assertEquals(listOf(Extended(0)), events)
        assertEquals(listOf(1f, 3f), stroke.points.map { it.x })
    }

    @Test
    fun moveWithOnlyDuplicatesEmitsNothing() {
        val capture = StrokeCapture()
        capture.onDown(sample(x = 1f, t = 1000))

        assertTrue(capture.onMove(0, listOf(sample(x = 1f, t = 1000))).isEmpty())
    }

    @Test
    fun policyChangeAppliesToNextStroke() {
        val capture = StrokeCapture()
        assertTrue(capture.onDown(sample(tool = ToolType.FINGER)).isEmpty())
        capture.onUp(sample(tool = ToolType.FINGER))

        capture.policy = InputPolicy(fingerDraws = true)

        assertEquals(listOf(Started(0, ToolType.FINGER)), capture.onDown(sample(tool = ToolType.FINGER, t = 2000)))
    }
}
