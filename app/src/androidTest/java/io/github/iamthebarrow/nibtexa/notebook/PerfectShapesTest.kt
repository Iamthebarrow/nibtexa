package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Perfect shapes in the app (Phase 23), through Android's real input system: a stylus held still at the end
 * of a stroke with the Draw pen converts it, and one undo brings the hand-drawn stroke back. No other stroke
 * changes. Screenshots are kept.
 */
@RunWith(AndroidJUnit4::class)
class PerfectShapesTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun surface() = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)

    /** Screen pixels for a position in the surface. */
    private fun origin(): Pair<Float, Float> {
        val at = IntArray(2)
        composeRule.runOnUiThread { surface().getLocationOnScreen(at) }
        return at[0].toFloat() to at[1].toFloat()
    }

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val (ox, oy) = origin()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x + ox; this.y = y + oy; pressure = 0.7f; size = 1f }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
        assertTrue("input was refused", instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }

    /** Draws [points] with the real input system, with a real pause at the end if [holdMillis] is above zero. */
    private fun draw(points: List<Pair<Float, Float>>, holdMillis: Long) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, points[0].first, points[0].second)
        for (p in points.drop(1)) {
            inject(MotionEvent.ACTION_MOVE, down, p.first, p.second)
            Thread.sleep(4)
        }
        if (holdMillis > 0) Thread.sleep(holdMillis)
        val last = points.last()
        inject(MotionEvent.ACTION_UP, down, last.first, last.second)
    }

    private fun drawn(strokes: Int) = waitFor { state().page.ink.strokes.size == strokes && state().savedVersion >= state().version }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = (0..30).map { x0 + (x1 - x0) * it / 30f to y0 + (y1 - y0) * it / 30f }

    private fun circle(cx: Float, cy: Float, r: Float) = (0..80).map { i -> val t = 2 * PI * i / 76; cx + (r * cos(t)).toFloat() + (i % 5) * 0.5f to cy + (r * sin(t)).toFloat() }

    private fun box(x: Float, y: Float, w: Float, h: Float) = line(x, y, x + w, y) + line(x + w, y, x + w, y + h).drop(1) + line(x + w, y + h, x, y + h).drop(1) + line(x, y + h, x, y).drop(1)

    private fun arrow(x: Float, y: Float, l: Float) =
        line(x, y, x + l, y) + line(x + l, y, x + l - 100f, y - 50f).drop(1) + line(x + l - 100f, y - 50f, x + l, y).drop(1) + line(x + l, y, x + l - 100f, y + 50f).drop(1)

    private fun triangle(x: Float, y: Float, s: Float) =
        line(x, y, x + s, y).let { it + line(x + s, y, x + s / 2, y - s * 0.8f).drop(1) + line(x + s / 2, y - s * 0.8f, x, y).drop(1) }

    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ready(tool: Tool) {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false); viewModel().setTool(tool); viewModel().setPenBarHidden(true) }
        waitFor { state().tool == tool && state().penBarHidden }
    }

    @Test
    fun holdingTheDrawPenConvertsAndOneUndoBringsTheHandDrawnStrokeBack() {
        ready(Tool.DRAWING_PEN)
        val wobbly = (0..40).map { i -> 300f + i * 15f to 400f + 6f * sin(i / 3.0).toFloat() + i * 0.6f }
        draw(wobbly, holdMillis = 900)
        drawn(1)
        val shape = state().page.ink.strokes.single()
        // Straight now: every point is on the line between its ends, and it's denser than what was drawn.
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue(shape.points.size > wobbly.size)
        val a = shape.points.first()
        val b = shape.points.last()
        for (p in shape.points) {
            val cross = abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / kotlin.math.hypot(b.x - a.x, b.y - a.y)
            assertTrue("$cross", cross < 0.5f)
        }
        assertEquals(300f / density, a.x, 12f)

        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.ink.strokes.single().points.size < shape.points.size }
        val hand = state().page.ink.strokes.single()
        assertEquals(shape.id, hand.id)
        // The stroke as drawn, wobble and all.
        assertTrue(hand.points.any { p -> abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / kotlin.math.hypot(b.x - a.x, b.y - a.y) > 1f })
        composeRule.runOnUiThread { viewModel().redo() }
        waitFor { state().page.ink.strokes.single().points == shape.points }
        composeRule.runOnUiThread { viewModel().undo(); viewModel().undo() }
        waitFor { state().page.ink.strokes.isEmpty() }
    }

    @Test
    fun withoutAHoldOrWithAnotherPenNothingChanges() {
        ready(Tool.DRAWING_PEN)
        val wobbly = (0..40).map { i -> 300f + i * 15f to 400f + 6f * sin(i / 3.0).toFloat() }
        draw(wobbly, holdMillis = 0)
        drawn(1)
        assertTrue(state().page.ink.strokes.single().points.size <= wobbly.size + 1)
        for (tool in listOf(Tool.MATH_PEN, Tool.TEXT_PEN, Tool.SCRATCH_PEN)) {
            val before = state().page.ink.strokes.size
            composeRule.runOnUiThread { viewModel().setTool(tool) }
            waitFor { state().tool == tool }
            draw(wobbly.map { it.first to it.second + 100f * (before + 1) }, holdMillis = 900)
            drawn(before + 1)
            assertTrue(tool.name, state().page.ink.strokes.last().points.size <= wobbly.size + 1)
        }
    }

    @Test
    fun aScribbleHeldStaysAsDrawnAndGoesOn() {
        ready(Tool.DRAWING_PEN)
        val scribble = (0..60).map { i -> 300f + i * 10f to 400f + if (i % 2 == 0) 0f else 120f }
        val more = (1..20).map { i -> 900f + i * 10f to 460f }
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, scribble[0].first, scribble[0].second)
        for (p in scribble.drop(1)) inject(MotionEvent.ACTION_MOVE, down, p.first, p.second)
        Thread.sleep(900)
        // The pen goes on writing the same stroke.
        for (p in more) inject(MotionEvent.ACTION_MOVE, down, p.first, p.second)
        inject(MotionEvent.ACTION_UP, down, more.last().first, more.last().second)
        drawn(1)
        val stroke = state().page.ink.strokes.single()
        val density = composeRule.activity.resources.displayMetrics.density
        assertEquals(1100f / density, stroke.points.last().x, 3f)
        assertEquals(scribble[1].second / density, stroke.points.first { it.y > 400f / density + 5f }.y, 30f)
    }

    @Test
    fun aShapeRunningOffTheSheetStaysAsDrawn() {
        ready(Tool.DRAWING_PEN)
        composeRule.runOnUiThread { viewModel().onView(PageView(0.25f), final = true) }
        waitFor { state().page.view?.let { it.scale == 0.25f && it.left < -100f } == true }
        val view = state().page.view!!
        val density = composeRule.activity.resources.displayMetrics.density
        fun sx(x: Float) = (x - view.left) * density * view.scale
        fun sy(y: Float) = (y - view.top) * density * view.scale
        val sheet = state().sheet
        draw(line(sx(sheet.width - 300f), sy(400f), sx(sheet.width + 200f), sy(400f)), holdMillis = 900)
        drawn(1)
        val stroke = state().page.ink.strokes.single()
        assertTrue(stroke.points.all { sheet.contains(it.x, it.y) })
        assertEquals(sheet.width, stroke.points.last().x, 2f)
    }

    @Test
    fun theSnappingSettingDecidesWhetherALineLevelsAndEveryShapeConverts() {
        ready(Tool.DRAWING_PEN)
        assertTrue(state().snapShapes)
        draw(line(300f, 300f, 900f, 330f), holdMillis = 900)
        drawn(1)
        val snapped = state().page.ink.strokes.last()
        assertEquals(snapped.points.first().y, snapped.points.last().y, 0.01f)

        composeRule.runOnUiThread { viewModel().setSnapShapes(false) }
        waitFor { !state().snapShapes }
        draw(line(300f, 500f, 900f, 530f), holdMillis = 900)
        drawn(2)
        val free = state().page.ink.strokes.last()
        assertTrue(abs(free.points.first().y - free.points.last().y) > 5f)
        composeRule.runOnUiThread { viewModel().setSnapShapes(true) }
        waitFor { state().snapShapes }

        // All of them, through real input, for looking at.
        draw(circle(1500f, 400f, 150f), holdMillis = 900)
        drawn(3)
        draw(box(1300f, 650f, 400f, 200f), holdMillis = 900)
        drawn(4)
        draw(box(1800f, 650f, 220f, 220f), holdMillis = 900)
        drawn(5)
        draw(triangle(300f, 1000f, 400f), holdMillis = 900)
        drawn(6)
        draw(arrow(800f, 950f, 500f), holdMillis = 900)
        drawn(7)
        draw((0..80).map { i -> val t = 2 * PI * i / 76; 1600f + (300 * cos(t)).toFloat() to 1050f + (120 * sin(t)).toFloat() }, holdMillis = 900)
        drawn(8)
        Thread.sleep(600)
        screenshot("perfect-shapes.png")
        // Every one became a dense generated stroke, so none was left as drawn.
        assertTrue(state().page.ink.strokes.all { s -> s.points.zipWithNext().all { (a, b) -> b.elapsedMillis - a.elapsedMillis == 12L } })
    }
}
