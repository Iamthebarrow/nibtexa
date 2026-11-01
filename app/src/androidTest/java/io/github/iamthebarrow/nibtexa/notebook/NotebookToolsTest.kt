package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_ERASER
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.EraserSettings
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Phase 6 tools on the real notebook screen: pens, roles, eraser, lasso actions, pages. */
@RunWith(AndroidJUnit4::class)
class NotebookToolsTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun inject(events: List<MotionEvent>) = composeRule.runOnUiThread {
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        events.forEach { view.dispatchTouchEvent(it); it.recycle() }
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        condition()
    }

    /** Selects a tool and waits until the canvas has it, so the next touches use it. */
    private fun tool(tool: Tool) {
        composeRule.runOnUiThread { viewModel().setTool(tool) }
        composeRule.waitForIdle()
    }

    private fun write(x: Float, y: Float) = inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, x to y, x + 100f to y + 60f))

    /** A finger lasso around the pixel rectangle. */
    private fun lasso(left: Float, top: Float, right: Float, bottom: Float) {
        tool(Tool.LASSO)
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(left to top, right to top, right to bottom, left to bottom, left to top + 5f)))
    }

    /**
     * Since Phase 11 a text region is recognized when it is made, and the box opens with the
     * result; the user corrects it (here: replaces it) and confirms.
     */
    @Test
    fun pensSetTheRoleAndTextIsRecognizedCorrectedAndConfirmed() {
        waitFor { state().loaded }
        tool(Tool.TEXT_PEN)
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 1 }
        assertEquals(Pen.TEXT, state().page.ink.strokes.single().pen)

        lasso(150f, 150f, 400f, 320f)
        waitFor { state().selection.size == 1 }
        composeRule.onNodeWithText(string(R.string.convert)).performClick()

        waitFor { state().page.region("r1")?.status == RecognitionStatus.RECOGNIZED }
        assertEquals(RegionRole.TEXT, state().page.region("r1")!!.role)
        assertTrue(services.requests.isEmpty()) // text is never sent to the math recognizer
        assertEquals(1, services.textRequests.size)
        composeRule.onNodeWithTag(TEXT_FIELD_TAG).assertTextContains("the transfer function")
        composeRule.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement("Gain & phase: 50%")
        composeRule.onNodeWithText(string(R.string.confirm)).performClick()

        waitFor { state().page.region("r1")?.status == RecognitionStatus.CONFIRMED }
        assertEquals("Gain & phase: 50%", state().page.region("r1")!!.text)
    }

    @Test
    fun drawingsBecomeCaptionedFiguresAndExportAsImages() {
        waitFor { state().loaded }
        tool(Tool.DRAWING_PEN)
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 1 }
        lasso(150f, 150f, 400f, 320f)
        waitFor { state().selection.size == 1 }
        composeRule.onNodeWithText(string(R.string.convert)).performClick()

        waitFor { state().page.region("r1")?.role == RegionRole.FIGURE }
        composeRule.onNodeWithTag(CAPTION_FIELD_TAG).performTextInput("Direct form")
        composeRule.onNodeWithText(string(R.string.save_caption)).performClick()
        waitFor { state().page.region("r1")?.caption == "Direct form" }

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { state().message?.startsWith("LaTeX project saved") == true }
        val dir = services.unpackExport()
        val png = File(dir, "assets/${state().page.id}-r1.png").readBytes()
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF }) // PNG signature
        val tex = File(dir, "booklets/${state().bookletId}/pages/${state().page.id}.tex").readText()
        assertTrue(tex, tex.contains("\\includegraphics") && tex.contains("\\par\\small Direct form"))
    }

    @Test
    fun eraserRemovesTouchedStrokesAndUndoBringsThemBack() {
        waitFor { state().loaded }
        write(200f, 200f)
        write(200f, 500f)
        waitFor { state().page.ink.strokes.size == 2 }
        composeRule.runOnUiThread { viewModel().setEraser(EraserSettings(mode = EraserMode.STROKE)) }

        // The stylus's eraser end erases even with a pen selected.
        inject(TestMotionEvents().path(TOOL_TYPE_ERASER, listOf(150f to 230f, 350f to 230f)))
        waitFor { state().page.ink.strokes.size == 1 }
        assertEquals(2L, state().page.ink.strokes.single().id)

        tool(Tool.ERASER)
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(150f to 530f, 350f to 530f)))
        waitFor { state().page.ink.strokes.isEmpty() }

        composeRule.onNodeWithContentDescription(string(R.string.undo)).performClick()
        composeRule.onNodeWithContentDescription(string(R.string.undo)).performClick()
        waitFor { state().page.ink.strokes.size == 2 }
    }

    @Test
    fun draggingTheSelectionMovesItAndUndoMovesItBack() {
        waitFor { state().loaded }
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 1 }
        val before = state().page.ink.strokes.single().points.first()
        lasso(150f, 150f, 400f, 320f)
        waitFor { state().selection.size == 1 }

        // Drag from inside the selection by (+300, +200) px.
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(250f to 230f, 400f to 330f, 550f to 430f)))
        waitFor { state().page.ink.strokes.single().points.first().x != before.x }
        val after = state().page.ink.strokes.single().points.first()
        assertEquals(before.x + 300f / density, after.x, 0.5f)
        assertEquals(before.y + 200f / density, after.y, 0.5f)
        assertEquals(1L, state().page.ink.strokes.single().id)

        composeRule.onNodeWithContentDescription(string(R.string.undo)).performClick()
        waitFor { state().page.ink.strokes.single().points.first().x == before.x }
    }

    @Test
    fun copyPasteAndDeleteTheSelection() {
        waitFor { state().loaded }
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 1 }
        lasso(150f, 150f, 400f, 320f)
        waitFor { state().selection.size == 1 }

        composeRule.onNodeWithText(string(R.string.copy)).performClick()
        composeRule.onNodeWithText(string(R.string.paste)).performClick()
        waitFor { state().page.ink.strokes.size == 2 }
        assertEquals(setOf(2L), state().selection) // the pasted copy is selected

        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        waitFor { state().page.ink.strokes.size == 1 }
        assertEquals(1L, state().page.ink.strokes.single().id)
    }

    @Test
    fun readingOrderPreviewAndReadingMode() {
        waitFor { state().loaded }
        tool(Tool.TEXT_PEN)
        write(200f, 600f)
        tool(Tool.MATH_PEN)
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 2 }
        composeRule.runOnUiThread {
            val vm = viewModel()
            vm.onLasso(listOf(0f to 250f, 2000f to 250f, 2000f to 2000f, 0f to 2000f)) // the text stroke, low on the page
            vm.makeRegion(RegionRole.HEADING)
            vm.confirm("r1", "Results")
            vm.onLasso(listOf(0f to 0f, 2000f to 0f, 2000f to 200f, 0f to 200f)) // the math stroke
            vm.makeRegion(RegionRole.MATH)
        }
        waitFor { state().page.region("r2")?.status == RecognitionStatus.RECOGNIZED }
        composeRule.runOnUiThread { viewModel().confirm("r2", "x^2+1") }
        waitFor { state().page.region("r2")?.status == RecognitionStatus.CONFIRMED }

        // By position from the start (Phase 14): the math, higher up, reads first.
        waitFor { state().page.regionsInReadingOrder.map { it.id } == listOf("r2", "r1") }
        composeRule.onNodeWithText(string(R.string.order_by_position)).performScrollTo().assertIsNotEnabled()
        composeRule.runOnUiThread { viewModel().moveRegion("r2", +1) }
        waitFor { state().page.regionsInReadingOrder.map { it.id } == listOf("r1", "r2") && state().page.manualOrder }
        composeRule.onNodeWithText(string(R.string.order_by_position)).performScrollTo().assertIsEnabled().performClick()
        waitFor { state().page.regionsInReadingOrder.map { it.id } == listOf("r2", "r1") && !state().page.manualOrder }

        composeRule.onNodeWithText(string(R.string.show_latex)).performScrollTo().performClick()
        waitFor { state().preview != null }
        val preview = state().preview!!
        assertTrue(preview, preview.indexOf("x^2+1") < preview.indexOf("\\section{Results}"))

        composeRule.onNodeWithContentDescription(string(R.string.reading_mode)).performClick()
        composeRule.onNodeWithTag(READING_TAG).assertExists()
        composeRule.onNodeWithText("Results").assertExists()
    }

    @Test
    fun pagesAndBookletsPersist() {
        waitFor { state().loaded }
        write(200f, 200f)
        waitFor { state().page.ink.strokes.size == 1 }
        val firstPage = state().page.id

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.page_position, 1, 1)).performClick() // the page overview (Phase 13)
        composeRule.onNodeWithText(string(R.string.add_page)).performClick()
        waitFor { state().pageCount == 2 && state().pageIndex == 1 }
        assertTrue(state().page.ink.strokes.isEmpty())
        write(300f, 300f)
        write(300f, 500f)
        composeRule.runOnUiThread { viewModel().setPaper(Paper.RULED) }
        waitFor { state().page.ink.strokes.size == 2 && state().savedVersion == state().version }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.page_position, 2, 2)).performClick()
        composeRule.onNode(hasContentDescription(composeRule.activity.getString(R.string.page_thumbnail, 1))).performClick()
        waitFor { state().page.id == firstPage }
        assertEquals(1, state().page.ink.strokes.size)

        val stored = runBlocking { services.freshRepository().loadBooklet(state().bookletId)!! }
        assertEquals(listOf(1, 2), stored.pages.map { it.ink.strokes.size })
        assertEquals(Paper.RULED, stored.pages[1].paper)

        composeRule.runOnUiThread { viewModel().createBooklet("Control") }
        waitFor { state().bookletTitle == "Control" }
        assertEquals(1, state().pageCount)
        assertFalse(state().page.id == firstPage)
        assertEquals(listOf(1, 2), state().library.map { it.pageCount }) // newest first (Phase 12)
    }
}
