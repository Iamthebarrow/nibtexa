package io.github.iamthebarrow.nibtexa.notebook

import android.view.MotionEvent
import android.view.MotionEvent.BUTTON_STYLUS_PRIMARY
import android.view.MotionEvent.BUTTON_STYLUS_SECONDARY
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtonAction
import io.github.iamthebarrow.nibtexa.core.model.capture.PenButtons
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSettings
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Phase 34: a pen button held as the pen touches the page stands in for the tool, for that touch. The emulator has no pen
 * with buttons, so these are built events carrying the button state Android reports; a real pen is a separate check.
 */
@RunWith(AndroidJUnit4::class)
class PenButtonsDeviceTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(condition: () -> Boolean) = composeRule.waitUntil(15_000) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun inject(events: List<MotionEvent>) {
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            events.forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        composeRule.waitForIdle()
    }

    private fun touch(buttons: Int, from: Pair<Float, Float>, to: Pair<Float, Float>) =
        inject(TestMotionEvents(buttonState = buttons).line(TOOL_TYPE_STYLUS, from, to))

    private fun strokes() = state().page.ink.strokes

    /** The pen in hand, and one stroke on the page across (300, 300) to (500, 340). */
    private fun oneStroke() {
        waitFor { state().loaded }
        composeRule.runOnUiThread {
            viewModel().setTool(Tool.TEXT_PEN)
            viewModel().setEraser(EraserSettings(mode = EraserMode.STROKE))
        }
        composeRule.waitForIdle()
        touch(0, 300f to 300f, 500f to 340f)
        waitFor { strokes().size == 1 }
    }

    @Test
    fun theMainButtonErasesAndTheToolIsBack() {
        oneStroke()
        touch(BUTTON_STYLUS_PRIMARY, 400f to 280f, 400f to 360f)
        waitFor { strokes().isEmpty() }
        assertEquals(Tool.TEXT_PEN, state().tool)
        touch(0, 300f to 500f, 500f to 540f)
        waitFor { strokes().size == 1 }
        assertEquals(Pen.TEXT, strokes().single().pen)
    }

    @Test
    fun theSecondButtonSelectsThenADragMovesItAndATapBringsThePenBack() {
        oneStroke()
        inject(TestMotionEvents(buttonState = BUTTON_STYLUS_SECONDARY).path(TOOL_TYPE_STYLUS, listOf(250f to 250f, 550f to 250f, 550f to 400f, 250f to 400f, 250f to 250f)))
        waitFor { state().selection.size == 1 }
        assertEquals(Tool.TEXT_PEN, state().tool)
        val before = strokes().single().points.first().y
        // The button is let go by now: with something selected, the pen moves it instead of writing.
        touch(0, 400f to 320f, 400f to 520f)
        waitFor { strokes().single().points.first().y != before }
        assertEquals(1, strokes().size)
        inject(TestMotionEvents().path(TOOL_TYPE_STYLUS, listOf(900f to 900f, 901f to 900f)))
        waitFor { state().selection.isEmpty() }
        touch(0, 300f to 800f, 500f to 840f)
        waitFor { strokes().size == 2 }
    }

    @Test
    fun aButtonCanMoveThePageOrWriteScratchOrDoNothing() {
        oneStroke()
        composeRule.runOnUiThread { viewModel().setPenButtons(PenButtons(PenButtonAction.MOVE, PenButtonAction.SCRATCH)) }
        composeRule.waitForIdle()

        val view = state().page.view
        touch(BUTTON_STYLUS_PRIMARY, 500f to 500f, 300f to 300f)
        assertEquals("moving the page writes nothing", 1, strokes().size)
        waitFor { state().page.view != view }
        assertNotEquals(view, state().page.view)

        touch(BUTTON_STYLUS_SECONDARY, 300f to 500f, 500f to 540f)
        waitFor { strokes().size == 2 }
        assertEquals(Pen.SCRATCH, strokes().last().pen)
        assertEquals("the tool stays", Tool.TEXT_PEN, state().tool)

        composeRule.runOnUiThread { viewModel().setPenButtons(PenButtons(PenButtonAction.NOTHING, PenButtonAction.NOTHING)) }
        composeRule.waitForIdle()
        touch(BUTTON_STYLUS_PRIMARY, 300f to 700f, 500f to 740f)
        waitFor { strokes().size == 3 }
        assertEquals(Pen.TEXT, strokes().last().pen)
    }

    @Test
    fun aButtonWorksWhateverTheTool() {
        oneStroke()
        composeRule.runOnUiThread { viewModel().setTool(Tool.MOVE) }
        composeRule.waitForIdle()
        touch(BUTTON_STYLUS_PRIMARY, 400f to 280f, 400f to 360f)
        waitFor { strokes().isEmpty() }
    }

    @Test
    fun theChoiceIsMadeInSettingsAndKept() {
        waitFor { state().loaded }
        assertEquals(PenButtons(), state().penButtons)
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.settings)).performClick()
        composeRule.onNodeWithText(string(R.string.pen_button_main)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.pen_button_about)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.pen_button_scratch)).performClick()
        waitFor { state().penButtons.main == PenButtonAction.SCRATCH }
        assertEquals(PenButtonAction.LASSO, state().penButtons.second)
        val prefs = composeRule.activity.getSharedPreferences("notebook", android.content.Context.MODE_PRIVATE)
        assertEquals(PenButtonAction.SCRATCH.name, prefs.getString("pen_button_main", null))
    }
}
