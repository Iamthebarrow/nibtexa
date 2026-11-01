package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.MotionEvent.BUTTON_STYLUS_PRIMARY
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSettings
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import io.github.iamthebarrow.nibtexa.ink.TestPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Phase 35: the eraser works as the pen moves, takes only what it passes over unless asked otherwise, and shows its size. */
@RunWith(AndroidJUnit4::class)
class EraserDeviceTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private fun strokes() = state().page.ink.strokes
    private fun surface() = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)

    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(15_000) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun inject(vararg events: MotionEvent) {
        composeRule.runOnUiThread { events.forEach { surface().dispatchTouchEvent(it); it.recycle() } }
        composeRule.waitForIdle()
    }

    private fun tool(tool: Tool) {
        composeRule.runOnUiThread { viewModel().setTool(tool) }
        composeRule.waitForIdle()
    }

    /** The pen writes a line across (300, 400) to (900, 400). */
    private fun line() {
        waitFor { state().loaded }
        tool(Tool.DRAWING_PEN)
        val before = strokes().size
        TestMotionEvents().line(TOOL_TYPE_STYLUS, 300f to 400f, 900f to 400f).let { inject(*it.toTypedArray()) }
        waitFor { strokes().size == before + 1 }
    }

    @Test
    fun theInkGoesAsThePenMovesAndOnePassIsOneUndo() {
        line()
        tool(Tool.ERASER)
        val events = TestMotionEvents(stepMillis = 40)
        val stylus = { x: Float, y: Float -> listOf(TestPointer(0, TOOL_TYPE_STYLUS, x, y)) }

        inject(events.event(MotionEvent.ACTION_DOWN, stylus(450f, 350f)))
        inject(events.event(MotionEvent.ACTION_MOVE, stylus(450f, 450f)))
        // Still down, and the first cut is made.
        waitFor { strokes().size == 2 }
        assertTrue("the ring shows while erasing", InkSurfaceView.eraserRingOf(surface()) > 0f)

        inject(events.event(MotionEvent.ACTION_MOVE, stylus(750f, 450f)))
        inject(events.event(MotionEvent.ACTION_MOVE, stylus(750f, 350f)))
        waitFor { strokes().size == 3 }
        inject(events.event(MotionEvent.ACTION_UP, stylus(750f, 350f)))
        assertEquals("the ring goes with the pen", 0f, InkSurfaceView.eraserRingOf(surface()), 0f)

        composeRule.onNodeWithContentDescription(string(R.string.undo)).performClick()
        waitFor { strokes().size == 1 }
        composeRule.onNodeWithContentDescription(string(R.string.redo)).performClick()
        waitFor { strokes().size == 3 }
        Thread.sleep(300)
        shot("eraser-precise.png")
    }

    @Test
    fun strokeModeTakesTheWholeStroke() {
        line()
        composeRule.runOnUiThread { viewModel().setEraser(EraserSettings(mode = EraserMode.STROKE)) }
        tool(Tool.ERASER)
        TestMotionEvents().line(TOOL_TYPE_STYLUS, 600f to 350f, 600f to 450f).let { inject(*it.toTypedArray()) }
        waitFor { strokes().isEmpty() }
    }

    @Test
    fun aWiderEraserTakesMoreOfTheStroke() {
        line()
        tool(Tool.ERASER)
        fun left() = strokes().sumOf { s -> s.points.zipWithNext().sumOf { (a, b) -> (b.x - a.x).toDouble() } }
        val whole = left()

        TestMotionEvents().path(TOOL_TYPE_STYLUS, listOf(400f to 400f, 401f to 400f)).let { inject(*it.toTypedArray()) }
        waitFor { strokes().size == 2 }
        val small = whole - left()

        composeRule.runOnUiThread { viewModel().setEraser(EraserSettings(size = EraserSize.EXTRA_LARGE)) }
        composeRule.waitForIdle()
        TestMotionEvents().path(TOOL_TYPE_STYLUS, listOf(700f to 400f, 701f to 400f)).let { inject(*it.toTypedArray()) }
        waitFor { strokes().size == 3 }
        val large = whole - left() - small
        assertTrue("small took $small, large took $large", large > small * 3)
        assertEquals(EraserSize.EXTRA_LARGE.radius, surface().eraserRadius, 0f)
    }

    @Test
    fun aHeldPenButtonUsesTheSameEraser() {
        line()
        TestMotionEvents(buttonState = BUTTON_STYLUS_PRIMARY).line(TOOL_TYPE_STYLUS, 600f to 350f, 600f to 450f).let { inject(*it.toTypedArray()) }
        waitFor { strokes().size == 2 }
        assertEquals(Tool.DRAWING_PEN, state().tool)
    }

    @Test
    fun aSecondFingerMakesItAZoomAndNothingIsErased() {
        line()
        val before = strokes()
        tool(Tool.ERASER)
        val events = TestMotionEvents(stepMillis = 40)
        val first = TestPointer(0, TOOL_TYPE_FINGER, 600f, 400f)
        val second = TestPointer(1, TOOL_TYPE_FINGER, 800f, 600f)
        inject(events.event(MotionEvent.ACTION_DOWN, listOf(first)))
        waitFor { strokes().size == 2 }
        inject(events.event(MotionEvent.ACTION_POINTER_DOWN, listOf(first, second), actionIndex = 1))
        waitFor { strokes() == before }
        inject(events.event(MotionEvent.ACTION_POINTER_UP, listOf(first, second), actionIndex = 1), events.event(MotionEvent.ACTION_UP, listOf(first)))
        assertFalse("nothing to undo but the line itself", state().canRedo)
        assertEquals(before, strokes())
    }

    @Test
    fun androidTakingTheTouchBackErasesNothing() {
        line()
        val before = strokes()
        tool(Tool.ERASER)
        val events = TestMotionEvents(stepMillis = 40)
        val pen = listOf(TestPointer(0, TOOL_TYPE_STYLUS, 600f, 400f))
        inject(events.event(MotionEvent.ACTION_DOWN, pen))
        waitFor { strokes().size == 2 }
        inject(events.event(MotionEvent.ACTION_CANCEL, pen))
        waitFor { strokes() == before }
    }

    @Test
    fun whatIsLeftIsReadAgain() {
        waitFor { state().loaded }
        services.textScript = { strokes, _, _ -> if (strokes.size == 1) "first" else "second" }
        composeRule.runOnUiThread { viewModel().setAutoRegions(true) }
        tool(Tool.TEXT_PEN)
        TestMotionEvents().line(TOOL_TYPE_STYLUS, 300f to 400f, 900f to 400f).let { inject(*it.toTypedArray()) }
        waitFor { state().page.regions.singleOrNull()?.recognizedText == "first" }

        tool(Tool.ERASER)
        TestMotionEvents().line(TOOL_TYPE_STYLUS, 600f to 350f, 600f to 450f).let { inject(*it.toTypedArray()) }
        waitFor { state().page.regions.singleOrNull()?.recognizedText == "second" }
        val region = state().page.regions.single()
        assertEquals(RecognitionStatus.RECOGNIZED, region.status)
        assertFalse(state().page.isStale(region))
        assertEquals(2, state().page.membersOf(region).size)
    }

    @Test
    fun aHoveringPenShowsTheRingWithTheEraserInHand() {
        line()
        fun hover(action: Int) = MotionEvent.obtain(
            0L, 0L, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { toolType = TOOL_TYPE_STYLUS }),
            arrayOf(MotionEvent.PointerCoords().apply { x = 500f; y = 500f }),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_STYLUS, 0,
        )
        fun send(action: Int) = composeRule.runOnUiThread { surface().onHoverEvent(hover(action)) }

        send(MotionEvent.ACTION_HOVER_MOVE)
        assertEquals("not with a pen in hand", 0f, InkSurfaceView.eraserRingOf(surface()), 0f)
        tool(Tool.ERASER)
        send(MotionEvent.ACTION_HOVER_MOVE)
        assertEquals(EraserSize.MEDIUM.radius, InkSurfaceView.eraserRingOf(surface()), 0f)
        send(MotionEvent.ACTION_HOVER_EXIT)
        assertEquals(0f, InkSurfaceView.eraserRingOf(surface()), 0f)
    }

    @Test
    fun theMenuSetsItAndItIsKept() {
        waitFor { state().loaded }
        tool(Tool.ERASER)
        assertEquals(EraserSettings(), state().eraser)
        composeRule.onNodeWithText(string(R.string.eraser_precise)).performClick()
        composeRule.onNodeWithTag(ERASER_MENU_TAG).assertIsDisplayed()
        Thread.sleep(300)
        shot("eraser-menu.png")
        composeRule.onNode(hasTestTag(ERASER_MENU_TAG)).assertExists()
        composeRule.onNodeWithText(string(R.string.eraser_stroke)).performClick()
        waitFor { state().eraser.mode == EraserMode.STROKE }
        composeRule.onNodeWithText(string(R.string.eraser_stroke)).performClick()
        composeRule.onNodeWithText(string(R.string.eraser_large)).performClick()
        waitFor { state().eraser.size == EraserSize.LARGE }
        assertEquals(EraserMode.STROKE, state().eraser.mode)
        val prefs = composeRule.activity.getSharedPreferences("notebook", android.content.Context.MODE_PRIVATE)
        assertEquals("STROKE", prefs.getString("eraser_mode", null))
        assertEquals("LARGE", prefs.getString("eraser_size", null))
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
