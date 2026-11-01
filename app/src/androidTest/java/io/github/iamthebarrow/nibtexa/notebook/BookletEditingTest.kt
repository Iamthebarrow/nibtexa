package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 14: deleting pages, folding the pen bar, messages that go by themselves, regions that go
 * with their ink, the equation editor's room for its keyboard, and the cover page settings.
 */
@RunWith(AndroidJUnit4::class)
class BookletEditingTest {
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

    private fun write(x: Float = 300f, y: Float = 300f) {
        waitFor { state().loaded }
        val before = state().page.ink.strokes.size
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, x to y, x + 100f to y + 60f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == before + 1 }
    }

    private val loop = listOf(0f to 0f, 2000f to 0f, 2000f to 2000f, 0f to 2000f, 0f to 1f)

    /** Ink by the right edge, selected: the selection bar moves left to stay whole on screen (Phase 16, portrait). */
    @Test
    fun theSelectionBarStaysOnScreenBesideTheRightEdge() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false) }
        var width = 0
        composeRule.runOnUiThread { width = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG).width }
        write(x = width - 130f, y = 500f)
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop) } }
        waitFor { state().selection.isNotEmpty() }
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        waitFor {
            val bar = composeRule.onNodeWithTag(SELECTION_BAR_TAG).getUnclippedBoundsInRoot()
            bar.left >= root.left && bar.right <= root.right - 8.dp
        }
    }

    @Test
    fun aPageIsDeletedFromThePageOverviewAfterAsking() {
        waitFor { state().loaded }
        composeRule.onNodeWithText(string(R.string.page_position, 1, 1)).performClick()
        composeRule.onNodeWithText(string(R.string.delete_page)).assertIsNotEnabled() // the last page stays
        composeRule.onNodeWithText(string(R.string.add_page)).performClick()
        waitFor { state().pageCount == 2 && state().pageIndex == 1 }
        write()
        composeRule.runOnUiThread { viewModel().addPage() }
        waitFor { state().pageCount == 3 && state().pageIndex == 2 }
        composeRule.runOnUiThread { viewModel().goToPage(1) }
        waitFor { state().pageIndex == 1 && state().page.ink.strokes.size == 1 }
        val doomed = state().page.id
        val pages = state().pages.map { it.id }

        composeRule.onNodeWithText(string(R.string.page_position, 2, 3)).performClick()
        composeRule.onNodeWithText(string(R.string.delete_page)).performClick()
        composeRule.onNodeWithText(string(R.string.delete_page_title, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        waitFor { state().pageCount == 2 && state().message == string(R.string.page_deleted, 2) }
        assertEquals(pages - doomed, state().pages.map { it.id })
        assertEquals(1, state().pageIndex) // the page after it
        waitFor { state().savedVersion >= state().version }
        val stored = runBlocking { services.freshRepository().loadBooklet(state().bookletId)!! }
        assertEquals(pages - doomed, stored.pages.map { it.id })
        assertTrue(services.database.dao().let { dao -> runBlocking { dao.strokes(doomed).isEmpty() && dao.regions(doomed).isEmpty() } })
    }

    @Test
    fun thePenBarFoldsAwayAndALongPressSwitchesPens() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN) }
        composeRule.onNodeWithContentDescription(string(R.string.pen_bar_hide)).performClick()
        waitFor { state().penBarHidden }
        composeRule.onNodeWithTag(PEN_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).assertIsDisplayed()

        // Each long press moves on to the next tool along the bar, without opening it, and after
        // the last comes the first again.
        composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).performTouchInput { longClick() }
        waitFor { state().tool == Tool.TEXT_PEN }
        composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).performTouchInput { longClick() }
        waitFor { state().tool == Tool.DRAWING_PEN }
        assertEquals(Tool.entries.drop(1) + Tool.MATH_PEN, Tool.entries.map(NotebookViewModel::nextTool))
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN) }
        composeRule.onNodeWithTag(PEN_BAR_TAG).assertDoesNotExist()
        write() // writing still works with the bar folded

        composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).performTouchInput { click() }
        waitFor { !state().penBarHidden }
        composeRule.onNodeWithTag(PEN_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun thePenBarIsDraggedToADockAndTurnsOnTheSideEdges() {
        waitFor { state().loaded }
        assertEquals(PenDock.TOP_CENTER, state().penDock)
        val grip = composeRule.onNodeWithContentDescription(string(R.string.pen_move))
        val wide = composeRule.onNodeWithTag(PEN_BAR_TAG).getUnclippedBoundsInRoot()
        assertTrue("a row along the top: $wide", wide.right - wide.left > wide.bottom - wide.top)

        // Dragged to the middle of the left edge: it lands there, as a column. Aimed at the edge's middle,
        // so it works in portrait, where the screen is taller, as in landscape.
        val area = composeRule.onRoot().getUnclippedBoundsInRoot()
        val down = (area.top + area.bottom) / 2 - (wide.top + wide.bottom) / 2
        grip.performTouchInput { swipe(center, center.copy(x = 20f - wide.left.toPx(), y = center.y + down.toPx()), durationMillis = 600) }
        waitFor { state().penDock == PenDock.LEFT }
        waitFor {
            val tall = composeRule.onNodeWithTag(PEN_BAR_TAG).getUnclippedBoundsInRoot()
            tall.bottom - tall.top > tall.right - tall.left && tall.left < 40.dp
        }
        assertEquals("LEFT", composeRule.activity.getSharedPreferences("notebook", 0).getString("pen_dock", null))

        // Asked for the right edge, it never covers the Results, zoom and page buttons: there as a column
        // above them when it fits, or somewhere with room when it does not (a short landscape screen).
        composeRule.runOnUiThread { viewModel().setPenDock(PenDock.RIGHT) }
        composeRule.waitForIdle()
        waitFor {
            val bar = composeRule.onNodeWithTag(PEN_BAR_TAG).getUnclippedBoundsInRoot()
            listOf(string(R.string.page_position, 1, 1), string(R.string.results), "100%").all { label ->
                val b = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
                bar.right <= b.left || bar.left >= b.right || bar.bottom <= b.top || bar.top >= b.bottom
            }
        }
        composeRule.runOnUiThread { viewModel().setPenDock(PenDock.LEFT) }
        waitFor { composeRule.onNodeWithTag(PEN_BAR_TAG).getUnclippedBoundsInRoot().left < 40.dp }

        // A small drag that ends near where it started stays put.
        composeRule.onNodeWithContentDescription(string(R.string.pen_move)).performTouchInput { swipe(center, center.copy(y = center.y + 20f), durationMillis = 300) }
        composeRule.waitForIdle()
        assertEquals(PenDock.LEFT, state().penDock)

        // Screen readers move it with an action instead.
        val actions = composeRule.onNodeWithContentDescription(string(R.string.pen_move)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val move = actions.single { it.label == string(R.string.pen_move_to, string(R.string.dock_bottom_start)) }
        composeRule.runOnUiThread { move.action() }
        waitFor { state().penDock == PenDock.BOTTOM_START }

        // Folded, it stays at its dock.
        composeRule.onNodeWithContentDescription(string(R.string.pen_bar_hide)).performClick()
        waitFor { runCatching { composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).assertIsDisplayed() }.isSuccess }
        val folded = composeRule.onNodeWithTag(FOLDED_PEN_BAR_TAG).getUnclippedBoundsInRoot()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("bottom left: $folded in $root", folded.left < 40.dp && folded.bottom > root.bottom - 120.dp)
    }

    @Test
    fun messagesGoByThemselves() {
        waitFor { state().loaded }
        // The message's timer runs on the test clock: step it by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { viewModel().confirmAll() } // "Confirmed 0 results" or similar
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag(MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.dismiss)).assertDoesNotExist() // no button to press
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNodeWithTag(MESSAGE_TAG).assertIsDisplayed() // still there after 3 s
        composeRule.mainClock.advanceTimeBy(2_000)
        assertNull(state().message) // gone after about 4 s
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag(MESSAGE_TAG).assertDoesNotExist() // and slid away

        // A tap sends one away sooner.
        composeRule.runOnUiThread { viewModel().confirmAll() }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag(MESSAGE_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertNull(state().message)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun aRegionGoesWhenAllItsInkIsErasedAndUndoBringsItBack() {
        write()
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop); makeRegion(RegionRole.MATH) } }
        waitFor { state().page.regions.singleOrNull()?.status == RecognitionStatus.RECOGNIZED }
        composeRule.runOnUiThread { viewModel().confirm("r1", "x^2+1") }
        waitFor { state().page.region("r1")?.status == RecognitionStatus.CONFIRMED }

        composeRule.runOnUiThread { viewModel().run { onLasso(loop); deleteSelection() } }
        waitFor { state().page.ink.strokes.isEmpty() }
        assertTrue(state().page.regions.isEmpty())
        assertNull(state().editingRegionId)

        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.region("r1")?.confirmedLatex == "x^2+1" }
        assertEquals(1, state().page.ink.strokes.size)
    }

    @Test
    fun theEquationEditorMakesRoomForItsKeyboard() {
        write()
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop); makeRegion(RegionRole.MATH) } }
        waitFor { state().page.regions.singleOrNull()?.status == RecognitionStatus.RECOGNIZED }
        waitFor { runCatching { composeRule.onNodeWithTag(MATH_EDITOR_TAG).assertIsDisplayed() }.isSuccess }
        val closed = composeRule.onNodeWithTag(MATH_EDITOR_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("closed: $closed", closed < 120.dp)

        // Touching the field opens MathLive's keyboard; the editor grows by its height. The page
        // may still be loading MathLive at the first touch, so touch again until it opens.
        fun height() = composeRule.onNodeWithTag(MATH_EDITOR_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val deadline = System.currentTimeMillis() + 20_000
        while (height() <= 200.dp && System.currentTimeMillis() < deadline) {
            // A real tap (through the input pipeline), since the WebView's page handles it itself.
            val bounds = composeRule.onNodeWithTag(MATH_EDITOR_TAG).getUnclippedBoundsInRoot()
            val origin = IntArray(2).also { composeRule.activity.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(it) }
            val density = composeRule.activity.resources.displayMetrics.density
            val x = origin[0] + ((bounds.left + bounds.right) / 2).value * density
            val y = origin[1] + (bounds.top.value + 50f) * density
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap ${x.toInt()} ${y.toInt()}").close()
            runCatching { waitFor(timeoutMs = 2_000) { height() > 200.dp } }
        }
        Thread.sleep(500) // let the keyboard finish sliding in for the picture
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "editor-keyboard.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("open: ${height()}", height() > 200.dp)
    }

    @Test
    fun aRegionsPlaceOnTheExportedPageCanBeChangedAtAnyTime() {
        write()
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop); makeRegion(RegionRole.MATH) } }
        waitFor { state().page.regions.singleOrNull()?.status == RecognitionStatus.RECOGNIZED }
        composeRule.runOnUiThread { viewModel().confirm("r1", "x^2+1") }
        waitFor { state().page.region("r1")?.status == RecognitionStatus.CONFIRMED }
        composeRule.runOnUiThread { viewModel().openRegion("r1") }

        // Confirmed, and still movable: centred by default, then right, then the default again.
        composeRule.onNodeWithText(string(R.string.placement)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.placement_default)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.align_right)).performScrollTo().performClick()
        waitFor { state().page.region("r1")?.align == MathAlign.RIGHT }
        waitFor { state().savedVersion >= state().version }
        assertEquals(MathAlign.RIGHT, runBlocking { services.freshRepository().loadBooklet(state().bookletId)!! }.pages.single().region("r1")!!.align)
        composeRule.onNodeWithText(string(R.string.placement_default)).performScrollTo().performClick()
        waitFor { state().page.region("r1")?.align == null }
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.region("r1")?.align == MathAlign.RIGHT }
    }

    @Test
    fun theCoverDialogSetsTheBookletsAuthorAndEquationAlignment() {
        waitFor { state().loaded }
        // In the ⋮ menu since Export asks for a format (Phase 18).
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.cover_layout)).performClick()
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.author_booklet))).performTextInput("Grace Hopper")
        composeRule.onNodeWithText(string(R.string.align_right)).performClick()
        composeRule.onNodeWithText(string(R.string.ok)).performClick()

        waitFor { state().bookletAuthor == "Grace Hopper" && state().mathAlign == MathAlign.RIGHT }
        val id = state().bookletId
        waitFor { runBlocking { services.freshRepository().info(id) }?.mathAlign == MathAlign.RIGHT }
        val info = runBlocking { services.freshRepository().info(id) }!!
        assertEquals("Grace Hopper", info.author)
        assertTrue("dates: $info", info.created != null && info.updated != null)
    }
}
