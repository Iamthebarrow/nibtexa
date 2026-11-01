package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
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
import java.io.File

/**
 * Phase 15 in the app: text and heading buttons that keep the confirmed words, headings by level
 * from Make region, and scratch notes recognized quietly and found by library search.
 */
@RunWith(AndroidJUnit4::class)
class StructureAndSearchTest {
    private val services = TestServicesRule(autoRegions = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun write(x: Float, y: Float) {
        val before = state().page.ink.strokes.size
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, x to y, x + 120f to y + 40f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == before + 1 }
    }

    @Test
    fun theTextAndHeadingButtonsKeepTheConfirmedWords() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setTool(Tool.TEXT_PEN) }
        write(300f, 300f)
        waitFor(timeoutMs = 15_000) { state().page.regions.singleOrNull()?.status == RecognitionStatus.RECOGNIZED }
        val id = state().page.regions.single().id
        composeRule.runOnUiThread { viewModel().confirm(id, "Results") }
        waitFor { state().page.region(id)?.status == RecognitionStatus.CONFIRMED }
        composeRule.runOnUiThread { viewModel().openRegion(id) }

        composeRule.onNodeWithContentDescription(string(R.string.kind_heading, 2)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.role == RegionRole.HEADING }
        val heading = state().page.region(id)!!
        assertEquals(2, heading.headingLevel)
        assertEquals(RecognitionStatus.CONFIRMED, heading.status)
        assertEquals("Results", heading.text)

        composeRule.onNodeWithContentDescription(string(R.string.kind_text_description)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.role == RegionRole.TEXT }
        assertEquals(RecognitionStatus.CONFIRMED, state().page.region(id)!!.status)
    }

    @Test
    fun makeRegionOffersHeadingsByLevel() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false); viewModel().setTool(Tool.TEXT_PEN) }
        write(300f, 300f)
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(listOf(0f to 0f, 2000f to 0f, 2000f to 2000f, 0f to 2000f)) } }
        waitFor { state().selection.isNotEmpty() }
        composeRule.onNodeWithText(string(R.string.make_region)).performClick()
        composeRule.onNodeWithText(string(R.string.kind_heading, 3)).performClick()
        waitFor { state().page.regions.singleOrNull()?.role == RegionRole.HEADING }
        assertEquals(3, state().page.regions.single().headingLevel)
    }

    @Test
    fun theReadingViewPlacesEachRegionAsTheExportDoes() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        listOf(200f, 400f, 600f).forEach { write(300f, it) }
        // A loop round each stroke, in page units.
        val loops = state().page.ink.strokes.map { s ->
            val b = io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds.of(s)
            listOf(b.left - 10f to b.top - 10f, b.right + 10f to b.top - 10f, b.right + 10f to b.bottom + 10f, b.left - 10f to b.bottom + 10f)
        }
        composeRule.runOnUiThread {
            viewModel().run {
                setTool(Tool.LASSO)
                onLasso(loops[0]); makeRegion(RegionRole.TEXT)
                onLasso(loops[1]); makeRegion(RegionRole.TEXT)
                onLasso(loops[2]); makeRegion(RegionRole.MATH)
            }
        }
        waitFor { state().page.regions.size == 3 && state().page.regions.all { it.status == RecognitionStatus.RECOGNIZED } }
        composeRule.runOnUiThread {
            viewModel().run {
                confirm("r1", "On the right"); setAlign("r1", MathAlign.RIGHT)
                confirm("r2", "Centred"); setAlign("r2", MathAlign.CENTER)
                confirm("r3", "x^2+1"); setAlign("r3", MathAlign.RIGHT)
                setResultsOpen(false); setReadingMode(true)
            }
        }
        waitFor { runCatching { composeRule.onNodeWithText("On the right").assertIsDisplayed() }.isSuccess }
        Thread.sleep(1500) // MathLive lays the equation out
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "reading-placement.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun scratchIsRecognizedQuietlyAndFoundBySearch() {
        waitFor { state().loaded }
        services.textScript = { _, _, _ -> "check the sign" }
        composeRule.runOnUiThread { viewModel().setTool(Tool.SCRATCH_PEN) }
        write(300f, 300f)

        waitFor(timeoutMs = 15_000) { state().page.regions.singleOrNull()?.recognizedText == "check the sign" }
        val scratch = state().page.regions.single()
        assertEquals(RegionRole.EXCLUDED, scratch.role)
        // Nothing to confirm: the badge still just says Results.
        composeRule.onNodeWithText(string(R.string.results)).assertIsDisplayed()
        waitFor { state().savedVersion >= state().version }
        assertEquals(0, state().library.firstOrNull { it.id == state().bookletId }?.pending ?: 0)

        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().screen == Screen.LIBRARY }
        composeRule.onNodeWithTag(SEARCH_TAG).performTextInput("SIGN")
        val shown = string(R.string.search_scratch, "“check the sign”")
        waitFor { runCatching { composeRule.onNodeWithText(shown).assertIsDisplayed() }.isSuccess }
        assertTrue(state().searchSnippets.values.single().scratch)
    }
}
