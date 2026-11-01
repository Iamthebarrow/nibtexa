package io.github.iamthebarrow.nibtexa.notebook

import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The open booklet's on-demand controls (Phase 13): the selection bar appears only while ink
 * is selected, and the results panel is closed until its badge button or a region opens it.
 */
@RunWith(AndroidJUnit4::class)
class BookletChromeTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun write() {
        waitFor { state().loaded }
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 300f to 300f, 400f to 360f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == 1 }
    }

    private val loop = listOf(0f to 0f, 600f to 0f, 600f to 600f, 0f to 600f, 0f to 1f)

    @Test
    fun theSelectionBarAppearsOnlyWithASelection() {
        write()
        composeRule.onNodeWithTag(PEN_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()

        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop) } }
        waitFor { state().selection.size == 1 }
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.copy)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.convert)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        waitFor { state().page.ink.strokes.isEmpty() }
        // Nothing selected now, but something was not copied either: the bar is gone.
        composeRule.onNodeWithTag(SELECTION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun theResultsPanelOpensFromItsBadgeAndFromARegion() {
        write()
        composeRule.onNodeWithTag(RESULTS_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.results)).assertIsDisplayed() // nothing waiting yet

        // Converting opens the region in the panel; close it again.
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop); makeRegion(RegionRole.MATH) } }
        waitFor { state().page.regions.singleOrNull()?.status == RecognitionStatus.RECOGNIZED }
        composeRule.onNodeWithTag(RESULTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.close_results)).performClick()
        waitFor { !state().resultsOpen }
        composeRule.onNodeWithTag(RESULTS_TAG).assertDoesNotExist()

        // The badge counts the waiting result and opens the panel.
        val badge = composeRule.activity.resources.getQuantityString(R.plurals.to_confirm, 1, 1)
        composeRule.onNodeWithText(badge).performClick()
        waitFor { state().resultsOpen }
        composeRule.onNodeWithTag(RESULTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.confirm_all)).performClick()
        waitFor { state().page.regions.single().status == RecognitionStatus.CONFIRMED }
        composeRule.onNodeWithContentDescription(string(R.string.close_results)).performClick()
        waitFor { !state().resultsOpen }

        // Tapping a region on the page opens it in the panel.
        val b = state().page.regions.single().bounds
        composeRule.runOnUiThread { viewModel().onTap(b.centerX, b.centerY) }
        waitFor { state().resultsOpen && state().editingRegionId == "r1" }
        composeRule.onNodeWithTag(EDITOR_TAG).assertIsDisplayed()
        assertEquals("r1", state().editingRegionId)
        assertTrue(state().page.regions.single().status == RecognitionStatus.CONFIRMED)
    }
}
