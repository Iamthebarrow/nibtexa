package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.test.onNodeWithContentDescription
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.recognition.LanguagePackMissingException
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 11 on the real notebook screen, with scripted recognizers: ink written with the text,
 * math and drawing pens becomes regions by itself, text and math are recognized without any
 * lasso, Confirm all accepts them, and the export has a paragraph, display math and a figure in
 * reading order. The exported project is copied to the app's `phase11-export/` folder so it can
 * be pulled and compiled with TeX Live (docs/development/TESTING.md).
 */
@RunWith(AndroidJUnit4::class)
class AutoRegionsFlowTest {
    private val services = TestServicesRule(script = { _, _ -> "x^2+1" }, autoRegions = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun inject(events: List<MotionEvent>) = composeRule.runOnUiThread {
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        events.forEach { view.dispatchTouchEvent(it); it.recycle() }
    }

    /** Waits until [condition] holds; a condition that throws (a region not there yet) counts as false. */
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun pen(tool: Tool) = composeRule.runOnUiThread { viewModel().setTool(tool) }

    private fun stroke(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val before = state().page.ink.strokes.size
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, from, to))
        waitFor { state().page.ink.strokes.size == before + 1 }
    }

    /**
     * Two strokes side by side at [y] px, injected in one batch. Waiting between them could pass
     * the 1.5 s idle time on a loaded emulator and group the first stroke alone (then regroup).
     */
    private fun pair(y: Float, x: Float = 200f) {
        val before = state().page.ink.strokes.size
        // One TestMotionEvents per stroke, as elsewhere: each stroke gets its own down time.
        val first = TestMotionEvents().line(TOOL_TYPE_STYLUS, x to y, x + 40f to y + 60f)
        inject(first + TestMotionEvents().line(TOOL_TYPE_STYLUS, x + 50f to y, x + 90f to y + 60f))
        waitFor { state().page.ink.strokes.size == before + 2 }
    }

    /** One line of "text": two letter strokes side by side, at [y] px. */
    private fun textLine(y: Float) = pair(y)

    private fun region(role: RegionRole) = state().page.regions.single { it.role == role }

    @Test
    fun textMathAndADrawingWithoutTheLassoExportInReadingOrder() {
        waitFor { state().loaded }
        pen(Tool.TEXT_PEN)
        textLine(200f)
        pen(Tool.MATH_PEN)
        pair(400f)
        pen(Tool.DRAWING_PEN)
        stroke(200f to 600f, 400f to 750f)

        // Three regions appear by themselves once the pen rests, with the pens' roles.
        waitFor(15_000) { state().page.regions.size == 3 }
        assertEquals(listOf(RegionRole.TEXT, RegionRole.MATH, RegionRole.FIGURE), state().page.regionsInReadingOrder.map { it.role })
        assertTrue(state().page.regions.all { it.auto })
        assertEquals(setOf(1L, 2L), region(RegionRole.TEXT).strokeIds)

        // Text and math are recognized automatically, one after the other; nothing is typed.
        waitFor(15_000) { region(RegionRole.TEXT).status == RecognitionStatus.RECOGNIZED && region(RegionRole.MATH).status == RecognitionStatus.RECOGNIZED }
        assertEquals("the transfer function", region(RegionRole.TEXT).recognizedText)
        assertEquals("x^2+1", region(RegionRole.MATH).recognizedLatex)
        // The result kept is for the whole line (an earlier, partial request is possible on a slow emulator).
        val (textStrokes, context) = services.textRequests.last()
        assertEquals(2, textStrokes.size)
        assertEquals(2, services.requests.last().size)
        assertTrue(context.writingWidth!! > 0f && context.writingHeight!! > 0f)
        // Recognition never touches the ink.
        assertEquals(5, state().page.ink.strokes.size)

        // The results panel opens from its badge button (Phase 13).
        composeRule.onNodeWithText(composeRule.activity.resources.getQuantityString(R.plurals.to_confirm, 2, 2)).performClick()
        composeRule.onNodeWithText(string(R.string.confirm_all)).performClick()
        waitFor { state().page.regions.count { it.status == RecognitionStatus.CONFIRMED } == 2 }
        composeRule.runOnUiThread { viewModel().setCaption(region(RegionRole.FIGURE).id, "A sketch") }

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { state().message?.startsWith("LaTeX project saved") == true }
        val dir = services.unpackExport()
        val pageTex = File(dir, "booklets/${state().bookletId}/pages/${state().page.id}.tex").readText()
        val text = pageTex.indexOf("the transfer function")
        val math = pageTex.indexOf("\\[\n\\nibfit{x^2+1}\n\\]")
        val figure = pageTex.indexOf("\\includegraphics")
        assertTrue(pageTex, text >= 0 && math > text && figure > math)
        assertTrue(pageTex.contains("A sketch"))
        val figureId = region(RegionRole.FIGURE).id
        assertTrue(pageTex.contains("assets/${state().page.id}-$figureId.png"))
        assertTrue(File(dir, "assets/${state().page.id}-$figureId.png").isFile)

        // Kept for compiling on the host: adb pull this folder, then run pdflatex (docs/development/TESTING.md).
        val keep = File(composeRule.activity.getExternalFilesDir(null), "phase11-export")
        keep.deleteRecursively()
        dir.copyRecursively(keep)
    }

    @Test
    fun oneUndoTakesBackTheLastStrokeAndItsGroupWithoutRegrouping() {
        waitFor { state().loaded }
        pen(Tool.TEXT_PEN)
        textLine(200f)
        waitFor(15_000) { state().page.regions.size == 1 }

        composeRule.onNodeWithContentDescription(string(R.string.undo)).performClick()
        waitFor { state().page.ink.strokes.size == 1 }
        assertTrue(state().page.regions.isEmpty())
        // Undone ink is not grouped again behind the user's back.
        Thread.sleep(2_500) // longer than the 1.5 s idle wait
        composeRule.waitForIdle()
        assertTrue(state().page.regions.isEmpty())
    }

    @Test
    fun theLassoMovesDeletesAndPastesAutoGroupedInk() {
        waitFor { state().loaded }
        pen(Tool.TEXT_PEN)
        textLine(200f)
        waitFor(15_000) { region(RegionRole.TEXT).status == RecognitionStatus.RECOGNIZED }
        val id = region(RegionRole.TEXT).id
        val top = region(RegionRole.TEXT).bounds.top

        // Move: the region keeps its strokes and its bounds follow.
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(listOf(0f to 0f, 400f to 0f, 400f to 300f, 0f to 300f, 0f to 1f)) } }
        waitFor { state().selection.size == 2 }
        composeRule.runOnUiThread { viewModel().moveSelection(0f, 100f) }
        waitFor { state().page.region(id)?.bounds?.top == top + 100f }
        assertEquals(setOf(1L, 2L), state().page.region(id)!!.strokeIds)

        // Delete: removing all of a region's strokes removes the automatic region.
        composeRule.runOnUiThread { viewModel().run { copySelection(); deleteSelection() } }
        waitFor { state().page.region(id) == null }
        assertTrue(state().page.regions.isEmpty())

        // Paste: the region comes with its copied ink, under new ids (Phase 26).
        composeRule.runOnUiThread { viewModel().paste() }
        waitFor(15_000) { state().page.regions.size == 1 }
        val pasted = state().page.regions.single()
        assertTrue(pasted.auto)
        assertEquals(2, pasted.strokeIds.size)
        assertTrue(pasted.strokeIds.none { it in setOf(1L, 2L) })
    }

    @Test
    fun aMissingLanguagePackSaysWhatToDoAndTheDownloadRetries() {
        services.pack.downloaded = false
        services.textScript = { _, _, _ -> if (services.pack.downloaded) "pole" else throw LanguagePackMissingException("en-US") }
        waitFor { state().loaded }
        pen(Tool.TEXT_PEN)
        textLine(200f)
        waitFor(15_000) { state().page.regions.singleOrNull()?.status == RecognitionStatus.FAILED }
        assertEquals(string(R.string.handwriting_missing), state().message)
        assertEquals(false, state().handwritingReady)

        composeRule.runOnUiThread { viewModel().downloadHandwriting(allowMobileData = true) }
        waitFor(15_000) { region(RegionRole.TEXT).status == RecognitionStatus.RECOGNIZED }
        assertEquals(1, services.pack.downloads)
        assertEquals("pole", region(RegionRole.TEXT).recognizedText)
        assertNotEquals(false, state().handwritingReady)
    }
}
