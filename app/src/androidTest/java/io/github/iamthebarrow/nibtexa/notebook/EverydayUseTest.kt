package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 29: a saved clipboard stays out of the way until asked for, and each menu holds only what belongs there. Real taps and
 * finger touches where it matters, with screenshots of each screen to look at.
 */
@RunWith(AndroidJUnit4::class)
class EverydayUseTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun inject(events: List<MotionEvent>) = composeRule.runOnUiThread {
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        events.forEach { view.dispatchTouchEvent(it); it.recycle() }
    }

    private fun tool(tool: Tool) {
        composeRule.runOnUiThread { viewModel().setTool(tool) }
        composeRule.waitForIdle()
    }

    private fun tap(x: Float, y: Float) {
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(x to y, x + 1f to y)))
        composeRule.waitForIdle()
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(400) // the bar's animation
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun barShown() = composeRule.onAllNodesWithText(string(R.string.paste)).fetchSemanticsNodes().isNotEmpty()

    /** A stroke, copied, then nothing selected and the lasso in hand: as a user returning to the app with a saved clipboard. */
    private fun withASavedClip() {
        waitFor { state().loaded }
        tool(Tool.DRAWING_PEN)
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 300f to 300f, 500f to 340f))
        waitFor { state().page.ink.strokes.size == 1 }
        tool(Tool.LASSO)
        composeRule.runOnUiThread {
            viewModel().onLasso(listOf(0f to 0f, 3000f to 0f, 3000f to 3000f, 0f to 3000f, 0f to 0f))
            viewModel().copySelection()
        }
        waitFor { state().clips.isNotEmpty() }
        tool(Tool.LASSO)
    }

    @Test
    fun pasteStaysAwayUntilAnEmptyPartOfThePageIsTapped() {
        withASavedClip()
        assertFalse("nothing asked for it yet", barShown())
        shot("everyday-select-nothing-asked.png")

        tap(900f, 900f)
        waitFor { barShown() }
        shot("everyday-paste-offered.png")

        // The next tap takes it away.
        tap(900f, 900f)
        waitFor { !barShown() }
        assertNull(state().pasteOffer)

        // So does another tool, and it doesn't come back with the lasso.
        tap(900f, 900f)
        waitFor { barShown() }
        tool(Tool.MATH_PEN)
        waitFor { !barShown() }
        tool(Tool.LASSO)
        assertFalse(barShown())

        // A tap only clears a selection: it doesn't offer anything as well.
        composeRule.runOnUiThread { viewModel().onLasso(listOf(0f to 0f, 3000f to 0f, 3000f to 3000f, 0f to 3000f, 0f to 0f)) }
        waitFor { state().selection.isNotEmpty() }
        tap(900f, 900f)
        waitFor { state().selection.isEmpty() }
        assertNull(state().pasteOffer)
    }

    @Test
    fun aPasteGoesWhereThePageWasTapped() {
        withASavedClip()
        tap(900f, 900f)
        waitFor { barShown() }
        val offer = assertNotNull_(state().pasteOffer)
        assertEquals(900f / density, offer.x, 2f)
        composeRule.onNodeWithText(string(R.string.paste)).performClick()
        waitFor { state().page.ink.strokes.size == 2 }
        val pasted = state().page.ink.strokes.last().points
        assertEquals(offer.x, pasted.minOf { it.x }, 2f)
        assertEquals(offer.y, pasted.minOf { it.y }, 2f)
        assertNull(state().pasteOffer)
        // What was pasted is selected, so the bar stays for it.
        assertTrue(state().selection.isNotEmpty())
    }

    @Test
    fun theOfferBelongsToItsPage() {
        withASavedClip()
        tap(900f, 900f)
        waitFor { barShown() }
        composeRule.runOnUiThread { viewModel().addPage() }
        waitFor { state().pageCount == 2 }
        waitFor { !barShown() }
    }

    @Test
    fun theBookletMenuHoldsOnlyTheBookletsThings() {
        waitFor { state().loaded }
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        listOf(R.string.insert_picture, R.string.clipboard, R.string.cover_layout, R.string.page_layout, R.string.sync_open, R.string.clear_page, R.string.settings).forEach {
            composeRule.onNodeWithText(string(it)).assertIsDisplayed()
        }
        listOf(
            R.string.author_name_menu, R.string.model_import, R.string.auto_regions, R.string.snap_shapes, R.string.clipboard_system,
            R.string.licences_title, R.string.corpus_capture, R.string.text_samples,
        ).forEach { assertTrue(string(it), composeRule.onAllNodesWithText(string(it)).fetchSemanticsNodes().isEmpty()) }
        shot("everyday-booklet-menu.png")

        composeRule.onNodeWithText(string(R.string.settings)).performClick()
        composeRule.onNodeWithTag(SETTINGS_TAG).assertIsDisplayed()
        listOf(R.string.author_name_menu, R.string.model_import, R.string.auto_regions, R.string.snap_shapes, R.string.clipboard_system, R.string.licences_title).forEach {
            composeRule.onNodeWithText(string(it)).assertIsDisplayed()
        }
        shot("everyday-settings.png")

        // The switches work from here.
        val snap = state().snapShapes
        composeRule.onNodeWithText(string(R.string.snap_shapes)).performClick()
        waitFor { state().snapShapes != snap }
        composeRule.onNodeWithText(string(R.string.snap_shapes)).performClick()
        waitFor { state().snapShapes == snap }

        // The app's author name is changed in one place.
        composeRule.onNodeWithText(string(R.string.author_name_menu)).performClick()
        composeRule.onNodeWithText(string(R.string.author_app_title)).assertIsDisplayed()
    }

    private fun <T : Any> assertNotNull_(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
