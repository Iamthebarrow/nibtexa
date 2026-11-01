package io.github.iamthebarrow.nibtexa.ink

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** A pointer at one instant, in view pixels. */
data class TestPointer(
    val id: Int,
    val toolType: Int,
    val x: Float,
    val y: Float,
    val pressure: Float = 0.6f,
    val tilt: Float = 0.3f,
)

/**
 * Builds deterministic synthetic [MotionEvent]s for injecting into a view, standing in
 * for a real stylus on the emulator. Every event shares one down time; event times
 * advance by [stepMillis] per sample.
 */
class TestMotionEvents(private val stepMillis: Long = 8, private val buttonState: Int = 0) {
    private val downTime = SystemClock.uptimeMillis()
    private var time = downTime

    fun event(action: Int, pointers: List<TestPointer>, actionIndex: Int = 0, flags: Int = 0): MotionEvent {
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        return MotionEvent.obtain(
            downTime, time, masked, pointers.size,
            pointers.map(::properties).toTypedArray(),
            pointers.map(::coords).toTypedArray(),
            0, buttonState, 1f, 1f, 0, 0, source(pointers), flags,
        ).also { time += stepMillis }
    }

    /** A move whose first [history] entries become batched historical samples. */
    fun move(history: List<List<TestPointer>>, current: List<TestPointer>): MotionEvent {
        val event = MotionEvent.obtain(
            downTime, time, MotionEvent.ACTION_MOVE, current.size,
            current.map(::properties).toTypedArray(),
            history.firstOrNull()?.map(::coords)?.toTypedArray() ?: current.map(::coords).toTypedArray(),
            0, buttonState, 1f, 1f, 0, 0, source(current), 0,
        )
        time += stepMillis
        for (batch in history.drop(1) + listOf(current)) {
            event.addBatch(time, batch.map(::coords).toTypedArray(), 0)
            time += stepMillis
        }
        return event
    }

    /** DOWN, one batched MOVE of [points] - 2 samples, then UP, for a single pointer. */
    fun line(toolType: Int, from: Pair<Float, Float>, to: Pair<Float, Float>, points: Int = 6, id: Int = 0): List<MotionEvent> {
        require(points >= 3)
        val samples = (0 until points).map { i ->
            val f = i / (points - 1f)
            TestPointer(id, toolType, from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f)
        }
        return listOf(
            event(MotionEvent.ACTION_DOWN, listOf(samples.first())),
            move(samples.subList(1, points - 2).map { listOf(it) }, listOf(samples[points - 2])),
            event(MotionEvent.ACTION_UP, listOf(samples.last())),
        )
    }

    /** DOWN at the first point, one MOVE per middle point, UP at the last, for a single pointer. */
    fun path(toolType: Int, points: List<Pair<Float, Float>>, id: Int = 0): List<MotionEvent> {
        require(points.size >= 2)
        val pointers = points.map { (x, y) -> TestPointer(id, toolType, x, y) }
        return buildList {
            add(event(MotionEvent.ACTION_DOWN, listOf(pointers.first())))
            pointers.subList(1, pointers.size - 1).forEach { add(event(MotionEvent.ACTION_MOVE, listOf(it))) }
            add(event(MotionEvent.ACTION_UP, listOf(pointers.last())))
        }
    }

    private fun properties(p: TestPointer) = MotionEvent.PointerProperties().apply {
        id = p.id
        toolType = p.toolType
    }

    private fun coords(p: TestPointer) = MotionEvent.PointerCoords().apply {
        x = p.x
        y = p.y
        pressure = p.pressure
        size = 0.1f
        setAxisValue(MotionEvent.AXIS_TILT, p.tilt)
    }

    private fun source(pointers: List<TestPointer>): Int =
        if (pointers.any { it.toolType == MotionEvent.TOOL_TYPE_STYLUS }) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
}
