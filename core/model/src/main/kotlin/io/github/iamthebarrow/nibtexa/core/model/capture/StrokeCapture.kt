package io.github.iamthebarrow.nibtexa.core.model.capture

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.ToolType

/** One platform-independent pointer sample, already converted to canvas units. */
data class PointerSample(
    val pointerId: Int,
    val tool: ToolType,
    val x: Float,
    val y: Float,
    val eventTimeMillis: Long,
    val pressure: Float? = null,
    val tiltRadians: Float? = null,
    val orientationRadians: Float? = null,
)

/** Which pointers are allowed to ink. */
data class InputPolicy(
    /** When false, finger contacts never ink; they are reserved for navigation. */
    val fingerDraws: Boolean = false,
)

/** What the renderer should do in response to input. */
sealed interface CaptureEvent {
    val pointerId: Int

    data class Started(override val pointerId: Int, val tool: ToolType) : CaptureEvent
    data class Extended(override val pointerId: Int) : CaptureEvent
    data class Finished(override val pointerId: Int, val stroke: CapturedStroke) : CaptureEvent
    data class Canceled(override val pointerId: Int) : CaptureEvent
}

/**
 * Turns a pointer stream into strokes, deciding which contact (if any) inks.
 *
 * Rules:
 * - Stylus and mouse always ink. Fingers ink only if [InputPolicy.fingerDraws].
 *   Eraser and unknown tools never ink (erasing arrives in Phase 6).
 * - Only one stroke is in progress at a time. While a stylus or mouse stroke is
 *   active, every other contact is ignored, which covers a resting palm.
 * - A stylus or mouse touching down during a finger stroke cancels the finger stroke,
 *   because the finger stroke was most likely the hand landing first.
 * - A second finger during a finger stroke cancels it (a multi-finger gesture), and no
 *   finger inks again until every finger has lifted.
 * - A pointer lifted with the platform's "canceled" flag (for example, an OS palm
 *   rejection) and a whole-gesture cancel both drop the active stroke.
 *
 * Not thread-safe; call it from the thread that delivers input.
 */
class StrokeCapture(var policy: InputPolicy = InputPolicy()) {

    private val downPointers = mutableMapOf<Int, ToolType>()
    private var active: ActiveStroke? = null
    private var fingerGestureLock = false

    val hasActiveStroke: Boolean get() = active != null

    /** The points of the stroke being written (Phase 23), for looking at it before it is finished. */
    val activeStroke: CapturedStroke? get() = active?.toStroke()

    /**
     * Ends the stroke being written here, with the pointer still down (Phase 23: a shape converted on a
     * hold), and returns it. Later samples from that pointer are ignored, until it lifts.
     */
    fun takeActive(): CapturedStroke? = active?.toStroke()?.also { active = null }

    fun onDown(sample: PointerSample): List<CaptureEvent> {
        downPointers[sample.pointerId] = sample.tool
        if (!canInk(sample.tool)) return emptyList()

        val current = active
        val events = mutableListOf<CaptureEvent>()
        when {
            current == null -> {
                if (sample.tool == ToolType.FINGER && fingerGestureLock) return emptyList()
                if (sample.tool == ToolType.FINGER && downPointers.values.count { it == ToolType.FINGER } > 1) {
                    fingerGestureLock = true
                    return emptyList()
                }
            }
            current.tool == ToolType.FINGER && sample.tool != ToolType.FINGER -> {
                events += cancelActive()
            }
            current.tool == ToolType.FINGER && sample.tool == ToolType.FINGER -> {
                fingerGestureLock = true
                return cancelActive()
            }
            else -> return emptyList()
        }
        active = ActiveStroke(sample)
        events += CaptureEvent.Started(sample.pointerId, sample.tool)
        return events
    }

    /** [samples] are the batched (historical plus current) samples for one pointer, oldest first. */
    fun onMove(pointerId: Int, samples: List<PointerSample>): List<CaptureEvent> {
        val current = active ?: return emptyList()
        if (current.pointerId != pointerId) return emptyList()
        val added = samples.count { current.append(it) }
        return if (added > 0) listOf(CaptureEvent.Extended(pointerId)) else emptyList()
    }

    fun onUp(sample: PointerSample, canceled: Boolean = false): List<CaptureEvent> {
        downPointers.remove(sample.pointerId)
        if (downPointers.isEmpty()) fingerGestureLock = false
        val current = active ?: return emptyList()
        if (current.pointerId != sample.pointerId) return emptyList()
        if (canceled) return cancelActive()
        current.append(sample)
        active = null
        return listOf(CaptureEvent.Finished(sample.pointerId, current.toStroke()))
    }

    /** The whole gesture was canceled by the system. */
    fun onCancel(): List<CaptureEvent> {
        downPointers.clear()
        fingerGestureLock = false
        return if (active != null) cancelActive() else emptyList()
    }

    private fun canInk(tool: ToolType): Boolean = when (tool) {
        ToolType.STYLUS, ToolType.MOUSE -> true
        ToolType.FINGER -> policy.fingerDraws
        ToolType.ERASER, ToolType.UNKNOWN -> false
    }

    private fun cancelActive(): List<CaptureEvent> {
        val current = active ?: return emptyList()
        active = null
        return listOf(CaptureEvent.Canceled(current.pointerId))
    }

    private class ActiveStroke(first: PointerSample) {
        val pointerId = first.pointerId
        val tool = first.tool
        private val startTime = first.eventTimeMillis
        private val points = mutableListOf(first.toPoint())

        /** Returns false for samples that add nothing: out-of-order or exact duplicates. */
        fun append(sample: PointerSample): Boolean {
            val point = sample.toPoint()
            val last = points.last()
            if (point.elapsedMillis < last.elapsedMillis) return false
            if (point.x == last.x && point.y == last.y && point.elapsedMillis == last.elapsedMillis) return false
            points += point
            return true
        }

        fun toStroke() = CapturedStroke(tool, startTime, points.toList())

        private fun PointerSample.toPoint() = InkPoint(
            x = x,
            y = y,
            elapsedMillis = eventTimeMillis - startTime,
            pressure = pressure,
            tiltRadians = tiltRadians,
            orientationRadians = orientationRadians,
        )
    }
}
