package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
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
 * Phase 16 in the app: the format bar formats the selected words and Confirm keeps them; theorem
 * chips and the layout switches set the region; the reading view sets it all as the exports do.
 */
@RunWith(AndroidJUnit4::class)
class FormattingTest {
    private val services = TestServicesRule()
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
        check(runCatching { waitFor { state().page.ink.strokes.size == before + 1 } }.isSuccess) { "no stroke at $y: ${state().page.ink.strokes.size} strokes, tool ${state().tool}" }
    }

    /** One text region per stroke, by lasso round each stroke, recognized. */
    private fun regions(vararg ys: Float, role: RegionRole = RegionRole.TEXT, roles: List<RegionRole> = ys.map { role }): List<String> {
        composeRule.runOnUiThread { viewModel().setTool(Tool.TEXT_PEN) }
        ys.forEach { write(300f, it) }
        val loops = state().page.ink.strokes.takeLast(ys.size).map { s ->
            val b = Bounds.of(s)
            listOf(b.left - 10f to b.top - 10f, b.right + 10f to b.top - 10f, b.right + 10f to b.bottom + 10f, b.left - 10f to b.bottom + 10f)
        }
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); loops.forEachIndexed { i, loop -> onLasso(loop); makeRegion(roles[i]) } } }
        // Recognition can queue behind earlier tests in a full run.
        val recognized = runCatching { waitFor(60_000) { state().page.regions.size >= ys.size && state().page.regions.all { it.status == RecognitionStatus.RECOGNIZED } } }
        check(recognized.isSuccess) { "not recognized: ${state().page.regions.map { "${it.id} ${it.role} ${it.status} ${it.strokeIds} ${it.sourceStrokeIds} ${it.error}" }}; running ${state().recognizingRegionId}, ${state().page.ink.strokes.size} strokes, message ${state().message}" }
        return state().page.regionsInReadingOrder.takeLast(ys.size).map { it.id }
    }

    /**
     * A recognizer that answers at once can finish inside `launch`, before its job is noted as the running one. Nothing may
     * then start beside the next recognition, or a region is left recognizing for ever.
     */
    @Test
    fun manyQuickRecognitionsAllFinish() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        val ids = regions(*FloatArray(16) { 100f + it * 80f })
        assertEquals(16, ids.size)
    }

    @Test
    fun theFormatBarFormatsTheSelectionAndConfirmKeepsIt() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        val id = regions(300f).single()
        composeRule.runOnUiThread { viewModel().openRegion(id) }
        val words = state().page.region(id)!!.recognizedText!!
        composeRule.onNodeWithTag(TEXT_FIELD_TAG).performScrollTo().performTextInputSelection(TextRange(0, 3))
        composeRule.onNodeWithContentDescription(string(R.string.format_bold)).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription(string(R.string.format_underline)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.confirm)).performScrollTo().performClick()

        waitFor { state().page.region(id)?.status == RecognitionStatus.CONFIRMED }
        val region = state().page.region(id)!!
        assertEquals(words.trim(), region.text)
        assertEquals(listOf(TextMark(0, 3, MarkStyle.Bold), TextMark(0, 3, MarkStyle.Underline)).toSet(), region.marks.toSet())
    }

    @Test
    fun theoremChipsAndLayoutSwitchesSetTheRegion() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        val id = regions(300f).single()
        composeRule.runOnUiThread { viewModel().confirm(id, "A pole is a root."); viewModel().openRegion(id) }
        composeRule.onNodeWithText(string(R.string.theorem_definition)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.theorem == TheoremKind.DEFINITION }
        composeRule.onNodeWithText(string(R.string.page_break)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.pageBreak == true }
        composeRule.onNodeWithText(string(R.string.new_paragraph)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.newParagraph == true }
        // The confirmation stays through all of it.
        assertEquals(RecognitionStatus.CONFIRMED, state().page.region(id)!!.status)
        composeRule.onNodeWithText(string(R.string.theorem_definition)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.theorem == null }
    }

    @Test
    fun aMathRegionCanBeNumberedAndPutInTheText() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        val id = regions(300f, role = RegionRole.MATH).single()
        composeRule.runOnUiThread { viewModel().openRegion(id) }
        composeRule.onNodeWithText(string(R.string.number_equation)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.numbered == true }
        composeRule.onNodeWithText(string(R.string.math_inline)).performScrollTo().performClick()
        waitFor { state().page.region(id)?.inline == true }
    }

    /** The reading view: formatting, inline maths, a theorem, a list, a numbered equation and a footnote. Kept as a screenshot. */
    @Test
    fun theReadingViewSetsTheStructure() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        val ids = regions(150f, 270f, 390f, 510f, 630f, 750f, roles = List(5) { RegionRole.TEXT } + RegionRole.MATH)
        val (styled, inline, definition, item, sub) = ids
        val eq = ids.last()
        composeRule.runOnUiThread {
            viewModel().run {
                confirm(styled, "Bold words and a note.", listOf(TextMark(0, 4, MarkStyle.Bold), TextMark(17, 21, MarkStyle.Footnote("Read at the bottom."))))
                confirm(inline, "The root \$s^2+1=0\$ sits in the line.")
                confirm(definition, "A pole is a root of the denominator.")
                setTheorem(definition, TheoremKind.DEFINITION)
                confirm(item, "- poles")
                confirm(sub, "- left half-plane")
                setListLevel(sub, 1)
                confirm(eq, "H(s)=\\frac{1}{s+1}")
                setNumbered(eq, true)
                setResultsOpen(false)
                setReadingMode(true)
            }
        }
        waitFor { runCatching { composeRule.onNodeWithText("Definition 1. A pole is a root of the denominator.").assertIsDisplayed() }.isSuccess }
        assertTrue(runCatching { composeRule.onNodeWithText("1. Read at the bottom.").assertIsDisplayed() }.isSuccess)
        // The inline maths is drawn once MathLive has typeset it.
        waitFor(30_000) { composeRule.onAllNodesWithTag(INLINE_MATH_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1500) // and the equation
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "reading-structure.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
