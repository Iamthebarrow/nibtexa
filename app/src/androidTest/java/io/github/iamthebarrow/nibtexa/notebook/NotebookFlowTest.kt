package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.latex.LatexTokens
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionCanceledException
import io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Phase 5 flow on the real notebook screen, with a scripted recognizer: draw → lasso →
 * Convert → confirm in MathLive → autosave → reload → export. Plus cancel, failure, and a
 * result that arrives after the ink changed. The real model is checked in TexTellerModelTest.
 */
@RunWith(AndroidJUnit4::class)
class NotebookFlowTest {
    private val services = TestServicesRule()
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

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        condition()
    }

    /** Two pen strokes, then a finger lasso around them; returns after the selection is made. */
    private fun writeAndSelect() {
        waitFor { state().loaded }
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to 200f, 300f to 300f))
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 320f to 200f, 400f to 300f))
        waitFor { state().page.ink.strokes.size == 2 }

        composeRule.onNodeWithText(string(R.string.tool_lasso)).performClick()
        // The ink view takes its mode from the next composition; inject only after it.
        composeRule.waitForIdle()
        val loop = listOf(150f to 150f, 500f to 150f, 500f to 400f, 150f to 400f, 150f to 160f)
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, loop))
        waitFor { state().selection == setOf(1L, 2L) }
    }

    private fun awaitSaved() = waitFor { state().let { it.savedVersion == it.version } }

    @Test
    fun convertConfirmReloadAndExport() {
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()

        // The editor opens with the recognized LaTeX; Confirm becomes available once MathLive reports it.
        waitFor { state().page.region("r1")?.status == RecognitionStatus.RECOGNIZED }
        waitFor { runCatching { composeRule.onNodeWithText(string(R.string.confirm)).assertIsEnabled() }.isSuccess }
        composeRule.onNodeWithText(string(R.string.confirm)).performClick()

        waitFor { state().page.region("r1")?.status == RecognitionStatus.CONFIRMED }
        val region = state().page.region("r1")!!
        assertEquals(LatexTokens.normalize("x^2+1"), LatexTokens.normalize(region.confirmedLatex!!))
        assertEquals("x^2+1", region.recognizedLatex)
        assertEquals(setOf(1L, 2L), region.sourceStrokeIds)
        assertEquals(1, services.requests.size)
        assertEquals(2, services.requests.single().size)

        // Autosave, then read back with a repository that has no in-memory state.
        awaitSaved()
        val stored = runBlocking { services.freshRepository().loadOrCreate() }
        val page = stored.pages.single()
        assertEquals(state().page.ink, page.ink)
        assertEquals(state().page.regions, page.regions)

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { state().message?.startsWith("LaTeX project saved") == true }
        val dir = services.unpackExport()
        val main = File(dir, "main.tex").readText()
        val pageTex = File(dir, "booklets/${stored.id}/pages/${page.id}.tex").readText()
        assertTrue(main.contains("\\input{booklets/${stored.id}/pages/${page.id}}"))
        assertTrue(pageTex.contains("% region r1\n\\[\n\\nibfit{${region.confirmedLatex}}\n\\]"))
        assertTrue(File(dir, "manifest.json").readText().contains("\"sourceStrokeIds\""))
    }

    @Test
    fun cancelKeepsTheInkAndMarksTheRegion() {
        val started = CountDownLatch(1)
        services.script = { _, isCanceled ->
            started.countDown()
            while (!isCanceled()) Thread.sleep(20)
            throw RecognitionCanceledException()
        }
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        waitFor { state().recognizingRegionId == "r1" }

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        waitFor { state().recognizingRegionId == null }
        val region = state().page.region("r1")!!
        assertEquals(RecognitionStatus.FAILED, region.status)
        assertEquals("Recognition canceled", region.error)
        assertEquals(2, state().page.ink.strokes.size)
        assertNull(region.recognizedLatex)
    }

    @Test
    fun recognitionErrorLosesNothingAndBlocksExport() {
        services.script = { _, _ -> throw RecognitionException("Model exploded") }
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()

        waitFor { state().page.region("r1")?.status == RecognitionStatus.FAILED }
        assertEquals("Model exploded", state().page.region("r1")!!.error)
        assertEquals(2, state().page.ink.strokes.size)
        composeRule.onNodeWithTag(MESSAGE_TAG).assertExists()

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { state().message?.startsWith("Not exported") == true }
        assertTrue(state().message!!.contains("r1"))
        assertFalse(services.exportZip.exists())
    }

    @Test
    fun aMissingModelSaysHowToLoadIt() {
        services.script = { _, _ -> throw io.github.iamthebarrow.nibtexa.recognition.ModelNotInstalledException(File("/models"), listOf("vocab.json")) }
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()

        waitFor { state().page.region("r1")?.status == RecognitionStatus.FAILED }
        assertEquals(string(R.string.model_not_loaded), state().page.region("r1")!!.error)
        assertEquals(string(R.string.model_not_loaded), state().message)
        assertEquals(2, state().page.ink.strokes.size)
    }

    @Test
    fun resultForInkThatChangedMeanwhileIsDiscarded() {
        val release = CountDownLatch(1)
        services.script = { _, _ ->
            release.await(10, TimeUnit.SECONDS)
            "stale result"
        }
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()
        waitFor { state().recognizingRegionId == "r1" }

        // Add a stroke inside the region while it is being recognized.
        // The pen bar's Math, not the region's role "Math" in the results panel.
        composeRule.onNode(hasText(string(R.string.tool_math_pen)) and hasAnyAncestor(hasTestTag(PEN_BAR_TAG))).performClick()
        composeRule.waitForIdle() // the ink view takes the pen from the next composition
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, 250f to 220f, 280f to 260f))
        waitFor { state().page.ink.strokes.size == 3 }
        release.countDown()

        waitFor { state().recognizingRegionId == null }
        val page = state().page
        val region = page.region("r1")!!
        assertNull(region.recognizedLatex)
        assertTrue(page.isStale(region))
        assertEquals(string(R.string.result_discarded), state().message)
    }

    @Test
    fun recreatingTheActivityKeepsThePageAndTheOpenRegion() {
        writeAndSelect()
        composeRule.onNodeWithText(string(R.string.convert)).performClick()
        waitFor { state().page.region("r1")?.status == RecognitionStatus.RECOGNIZED }

        composeRule.activityRule.scenario.recreate()

        waitFor { state().loaded }
        assertEquals(2, state().page.ink.strokes.size)
        assertEquals("r1", state().editingRegionId)
    }
}
