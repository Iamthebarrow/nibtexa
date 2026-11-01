package io.github.iamthebarrow.nibtexa.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.canvas.CanvasViewModel
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.corpus.CorpusCaptureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real canvas with synthetic MotionEvents. This verifies input handling and
 * the model on the emulator; it says nothing about real digitizer behavior (see the
 * hardware matrix in docs/development/TESTING.md).
 */
@RunWith(AndroidJUnit4::class)
class InkSurfaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<CorpusCaptureActivity>()

    private fun surface(): InkSurfaceView =
        composeRule.activity.window.decorView.findViewWithTag(InkSurfaceView.TAG)

    private fun strokes(): List<Stroke> {
        composeRule.waitForIdle()
        return ViewModelProvider(composeRule.activity)[CanvasViewModel::class.java].state.value.document.strokes
    }

    private fun inject(events: List<MotionEvent>) {
        composeRule.runOnUiThread {
            val view = surface()
            events.forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test
    fun stylusLineBecomesStrokeWithMeasuredChannels() {
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 100f, 400f to 300f))

        val stroke = strokes().single()
        assertEquals(ToolType.STYLUS, stroke.tool)
        assertEquals(6, stroke.points.size)
        assertTrue(stroke.points.all { it.pressure == 0.6f && it.tiltRadians != null })
        assertEquals((0 until 6).map { it * 8L }, stroke.points.map { it.elapsedMillis })
        val density = composeRule.activity.resources.displayMetrics.density
        assertEquals(100f / density, stroke.points.first().x, 0.01f)
    }

    @Test
    fun fingerDoesNotInkByDefault() {
        inject(TestMotionEvents().line(TOOL_TYPE_FINGER, 100f to 100f, 400f to 300f))

        assertTrue(strokes().isEmpty())
    }

    @Test
    fun fingerInksAfterEnablingFingerDrawing() {
        composeRule.onNodeWithText(string(R.string.finger_draws)).performClick()

        inject(TestMotionEvents().line(TOOL_TYPE_FINGER, 100f to 100f, 400f to 300f))

        val stroke = strokes().single()
        assertEquals(ToolType.FINGER, stroke.tool)
        assertTrue("Finger strokes must not claim pressure", stroke.points.all { it.pressure == null })
    }

    @Test
    fun gestureCancelDropsStroke() {
        val e = TestMotionEvents()
        val p = TestPointer(0, TOOL_TYPE_STYLUS, 100f, 100f)
        inject(
            listOf(
                e.event(MotionEvent.ACTION_DOWN, listOf(p)),
                e.event(MotionEvent.ACTION_MOVE, listOf(p.copy(x = 150f))),
                e.event(MotionEvent.ACTION_CANCEL, listOf(p.copy(x = 160f))),
            ),
        )

        assertTrue(strokes().isEmpty())
    }

    @Test
    fun stylusTakesOverFromFingerAndPalmIsIgnored() {
        composeRule.onNodeWithText(string(R.string.finger_draws)).performClick()
        val e = TestMotionEvents()
        val finger = TestPointer(0, TOOL_TYPE_FINGER, 500f, 500f)
        val pen = TestPointer(1, TOOL_TYPE_STYLUS, 100f, 100f)

        inject(
            listOf(
                e.event(MotionEvent.ACTION_DOWN, listOf(finger)),
                e.event(MotionEvent.ACTION_MOVE, listOf(finger.copy(x = 520f))),
                e.event(MotionEvent.ACTION_POINTER_DOWN, listOf(finger.copy(x = 520f), pen), actionIndex = 1),
                e.event(MotionEvent.ACTION_MOVE, listOf(finger.copy(x = 540f), pen.copy(x = 200f))),
                e.event(MotionEvent.ACTION_MOVE, listOf(finger.copy(x = 560f), pen.copy(x = 300f))),
                e.event(MotionEvent.ACTION_POINTER_UP, listOf(finger.copy(x = 560f), pen.copy(x = 320f)), actionIndex = 1),
                e.event(MotionEvent.ACTION_UP, listOf(finger.copy(x = 560f))),
            ),
        )

        val stroke = strokes().single()
        assertEquals(ToolType.STYLUS, stroke.tool)
        val density = composeRule.activity.resources.displayMetrics.density
        assertEquals(listOf(100f, 200f, 300f, 320f).map { it / density }, stroke.points.map { it.x })
    }

    @Test
    fun undoRemovesLastStrokeAndRedoRestoresIt() {
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 100f, 400f to 300f))
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 400f, 400f to 500f))
        assertEquals(2, strokes().size)

        composeRule.onNodeWithText(string(R.string.undo)).performClick()
        assertEquals(1, strokes().size)

        composeRule.onNodeWithText(string(R.string.redo)).performClick()
        assertEquals(2, strokes().size)
    }

    @Test
    fun strokesSurviveActivityRecreation() {
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 100f, 400f to 300f))
        val before = strokes()

        composeRule.activityRule.scenario.recreate()

        assertEquals(before, strokes())
    }

    // Regression: finished strokes were drawn in dp at pixel scale (half size on a 2x display),
    // because CanvasStrokeRenderer's matrix argument does not transform the canvas.
    @Test
    fun finishedStrokesAreDrawnWhereTheyWereWritten() {
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to 100f, 400f to 300f))
        assertEquals(1, strokes().size)

        val surface = surface()
        val finished = (0 until surface.childCount).map(surface::getChildAt).single { it is FinishedStrokesView }
        val bitmap = Bitmap.createBitmap(finished.width, finished.height, Bitmap.Config.ARGB_8888)
        composeRule.runOnUiThread {
            bitmap.eraseColor(Color.WHITE)
            finished.draw(Canvas(bitmap))
        }
        fun dark(x: Int, y: Int) = (-2..2).any { dx -> (-2..2).any { dy -> Color.red(bitmap.getPixel(x + dx, y + dy)) < 128 } }

        assertTrue("ink at the midpoint of the written line", dark(250, 200))
        assertTrue("no ink where a half-scale drawing would put it", !dark(125, 100))
    }

    // Regression: recreating right after a stroke used to crash in libhwui during androidx.ink's
    // front-buffer handoff (about 1 run in 7 on the API 37 AVD). Repeated to make it likely to show.
    @Test
    fun recreatingRightAfterWritingDoesNotCrash() {
        repeat(5) { i ->
            val y = 100f + i * 60f
            inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 100f to y, 400f to y + 40f))
            composeRule.activityRule.scenario.recreate()
        }
        assertEquals(5, strokes().size)
    }
}
