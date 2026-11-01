package io.github.iamthebarrow.nibtexa.notebook

import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import io.github.iamthebarrow.nibtexa.ink.TestPointer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Zoom and pan (Phase 17): pinching zooms about the fingers, ink written while zoomed lands where
 * it was written on the page, one finger moves the page when fingers do not draw, a second finger
 * turns a stroke just begun into a zoom, and the view is stored without counting as an edit.
 */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    private val services = TestServicesRule(openAtFitWidth = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun send(events: List<MotionEvent>) = composeRule.runOnUiThread {
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        events.forEach { view.dispatchTouchEvent(it); it.recycle() }
    }

    /** Two fingers about (cx, cy), [from] px apart, spreading to [to] px apart. */
    private fun pinch(cx: Float, cy: Float, from: Float, to: Float): List<MotionEvent> {
        val e = TestMotionEvents()
        fun pair(d: Float) = listOf(TestPointer(0, TOOL_TYPE_FINGER, cx - d / 2, cy), TestPointer(1, TOOL_TYPE_FINGER, cx + d / 2, cy))
        return buildList {
            add(e.event(MotionEvent.ACTION_DOWN, pair(from).take(1)))
            add(e.event(MotionEvent.ACTION_POINTER_DOWN, pair(from), actionIndex = 1))
            for (i in 1..5) add(e.event(MotionEvent.ACTION_MOVE, pair(from + (to - from) * i / 5)))
            add(e.event(MotionEvent.ACTION_POINTER_UP, pair(to), actionIndex = 1))
            add(e.event(MotionEvent.ACTION_UP, pair(to).take(1)))
        }
    }


    /** The page area in dp. */
    private fun area(): Pair<Float, Float> {
        var size = 0 to 0
        composeRule.runOnUiThread { composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG).let { size = it.width to it.height } }
        return size.first / density to size.second / density
    }

    /** The page opens at Fit width (Phase 19); its view once the surface has reported it. */
    private fun opened(): PageView {
        waitFor { state().loaded && state().page.view != null }
        return state().page.view!!
    }

    @Test
    fun aPageOpensWithItsSheetAcrossTheScreen() {
        val view = opened()
        val (width, height) = area()
        assertEquals(PageView.fitWidth(state().sheet, width, height).scale, view.scale, 0.01f)
        // The whole sheet on the desk, kept as a screenshot.
        composeRule.runOnUiThread { viewModel().setPaper(io.github.iamthebarrow.nibtexa.core.model.notebook.Paper.RULED); viewModel().onView(PageView.wholePage(state().sheet, width, height), final = true) }
        send(TestMotionEvents().line(TOOL_TYPE_STYLUS, 300f to 300f, 700f to 360f))
        composeRule.waitForIdle()
        Thread.sleep(500)
        val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(composeRule.activity.getExternalFilesDir("screenshots"), "sheet-whole-page.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pinchingZoomsFreelyAndIsStoredWithoutCountingAsAnEdit() {
        val start = opened().scale
        val updated = state().library.firstOrNull { it.id == state().bookletId }?.updatedAt
        send(pinch(400f, 400f, from = 200f, to = 400f))
        waitFor { (state().page.view?.scale ?: 0f) > start * 1.9f }
        assertEquals(start * 2f, state().page.view!!.scale, 0.05f)
        // Stored once the pinch ends.
        waitFor { runBlocking { services.freshRepository().loadBooklet(state().bookletId) }!!.pages[state().pageIndex].view?.scale?.let { it > start * 1.9f } == true }
        assertEquals(updated, state().library.firstOrNull { it.id == state().bookletId }?.updatedAt)
    }

    @Test
    fun inkWrittenWhileZoomedLandsWhereItWasWritten() {
        opened()
        composeRule.runOnUiThread { viewModel().onView(PageView(2f, 100f, 50f), final = true) }
        waitFor { state().page.view == PageView(2f, 100f, 50f) }
        send(TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to 200f, 400f to 300f))
        waitFor { state().page.ink.strokes.size == 1 }
        val first = state().page.ink.strokes.single().points.first()
        assertEquals(100f + 200f / density / 2f, first.x, 0.5f)
        assertEquals(50f + 200f / density / 2f, first.y, 0.5f)
    }

    @Test
    fun oneFingerMovesThePageWhenFingersDoNotDraw() {
        opened()
        composeRule.runOnUiThread { viewModel().setFingerDraws(false); viewModel().onView(PageView(2f, 300f, 300f), final = true) }
        waitFor { state().page.view == PageView(2f, 300f, 300f) }
        send(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(400f to 400f, 450f to 450f, 500f to 500f)))
        // Moved by the finger's 100 px, at 200%.
        waitFor { state().page.view?.left?.let { kotlin.math.abs(it - (300f - 100f / density / 2f)) < 0.5f } == true }
        assertEquals(0, state().page.ink.strokes.size)
    }

    @Test
    fun theMoveToolMovesThePageWithTheStylus() {
        opened()
        composeRule.runOnUiThread { viewModel().setTool(Tool.MOVE); viewModel().onView(PageView(2f, 300f, 300f), final = true) }
        waitFor { state().page.view == PageView(2f, 300f, 300f) }
        send(TestMotionEvents().path(TOOL_TYPE_STYLUS, listOf(400f to 400f, 450f to 450f, 500f to 500f)))
        waitFor { state().page.view?.top?.let { kotlin.math.abs(it - (300f - 100f / density / 2f)) < 0.5f } == true }
        assertEquals(0, state().page.ink.strokes.size)
    }

    @Test
    fun aSecondFingerTurnsAStrokeIntoAZoom() {
        val start = opened().scale
        composeRule.runOnUiThread { viewModel().setFingerDraws(true) }
        waitFor { state().fingerDraws }
        send(pinch(400f, 400f, from = 300f, to = 150f))
        waitFor { (state().page.view?.scale ?: 1f) < start * 0.6f }
        assertEquals(0, state().page.ink.strokes.size)
    }

    @Test
    fun zoomedOutTheSheetSitsInTheMiddle() {
        opened()
        composeRule.runOnUiThread { viewModel().onView(PageView(0.25f, 500f, 500f), final = true) }
        val (width, _) = area()
        waitFor { state().page.view?.let { kotlin.math.abs(it.left - (state().sheet.width - width / 0.25f) / 2) < 1f } == true }
    }

    @Test
    fun theZoomButtonOffers100PercentFitWidthAndWholePage() {
        opened()
        composeRule.runOnUiThread { viewModel().onView(PageView(2f, 40f, 40f), final = true) }
        composeRule.onNodeWithText("200%").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.zoom_actual)).performClick()
        waitFor { state().page.view?.scale == 1f }
        val (width, height) = area()
        composeRule.onNodeWithText("100%").performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.zoom_whole)).performClick()
        waitFor { state().page.view?.let { kotlin.math.abs(it.scale - PageView.wholePage(state().sheet, width, height).scale) < 0.01f } == true }
        assertTrue(state().page.view!!.scale < 1f)
    }
}
