package io.github.iamthebarrow.nibtexa.ink

import android.os.Build
import android.view.MotionEvent
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.capture.CaptureEvent
import io.github.iamthebarrow.nibtexa.core.model.capture.PointerSample
import io.github.iamthebarrow.nibtexa.core.model.capture.StrokeCapture
import kotlin.math.PI

/**
 * Translates Android [MotionEvent]s into [StrokeCapture] calls.
 *
 * Positions are converted from screen pixels to page units through the page's [PageTransform]
 * (its zoom and position, Phase 17). Pressure,
 * tilt and orientation are recorded only for stylus and eraser tools, because other
 * tools report placeholder values rather than measurements.
 */
internal object MotionEventSamples {

    fun dispatch(event: MotionEvent, capture: StrokeCapture, t: PageTransform): List<CaptureEvent> =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                capture.onDown(current(event, event.actionIndex, t))

            MotionEvent.ACTION_MOVE -> (0 until event.pointerCount).flatMap { index ->
                capture.onMove(event.getPointerId(index), batched(event, index, t))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                capture.onUp(current(event, event.actionIndex, t), canceled = isCanceled(event))

            MotionEvent.ACTION_CANCEL -> capture.onCancel()
            else -> emptyList()
        }

    fun toolType(event: MotionEvent, index: Int): ToolType = when (event.getToolType(index)) {
        MotionEvent.TOOL_TYPE_STYLUS -> ToolType.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> ToolType.ERASER
        MotionEvent.TOOL_TYPE_FINGER -> ToolType.FINGER
        MotionEvent.TOOL_TYPE_MOUSE -> ToolType.MOUSE
        else -> ToolType.UNKNOWN // Includes TOOL_TYPE_PALM (API 33+), which must never ink.
    }

    /** API 33+ flags pointers the system has decided were accidental, such as a palm. */
    private fun isCanceled(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0

    private fun current(event: MotionEvent, index: Int, t: PageTransform): PointerSample {
        val tool = toolType(event, index)
        val measured = tool == ToolType.STYLUS || tool == ToolType.ERASER
        return PointerSample(
            pointerId = event.getPointerId(index),
            tool = tool,
            x = t.pageX(event.getX(index)),
            y = t.pageY(event.getY(index)),
            eventTimeMillis = event.eventTime,
            pressure = if (measured) event.getPressure(index).coerceIn(0f, 1f) else null,
            tiltRadians = if (measured) normalizeTilt(event.getAxisValue(MotionEvent.AXIS_TILT, index)) else null,
            orientationRadians = if (measured) normalizeOrientation(event.getOrientation(index)) else null,
        )
    }

    private fun batched(event: MotionEvent, index: Int, t: PageTransform): List<PointerSample> {
        val tool = toolType(event, index)
        val measured = tool == ToolType.STYLUS || tool == ToolType.ERASER
        val pointerId = event.getPointerId(index)
        val historical = (0 until event.historySize).map { h ->
            PointerSample(
                pointerId = pointerId,
                tool = tool,
                x = t.pageX(event.getHistoricalX(index, h)),
                y = t.pageY(event.getHistoricalY(index, h)),
                eventTimeMillis = event.getHistoricalEventTime(h),
                pressure = if (measured) event.getHistoricalPressure(index, h).coerceIn(0f, 1f) else null,
                tiltRadians = if (measured) {
                    normalizeTilt(event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, h))
                } else {
                    null
                },
                orientationRadians = if (measured) normalizeOrientation(event.getHistoricalOrientation(index, h)) else null,
            )
        }
        return historical + current(event, index, t)
    }

    private fun normalizeTilt(radians: Float): Float = radians.coerceIn(0f, (PI / 2).toFloat())

    /** Android reports orientation in `-PI..PI`; the model uses `0 <= o < 2 * PI`. */
    private fun normalizeOrientation(radians: Float): Float {
        val twoPi = (2 * PI).toFloat()
        val wrapped = if (radians < 0f) radians + twoPi else radians
        return if (wrapped >= twoPi) 0f else wrapped
    }
}
