package io.github.iamthebarrow.nibtexa.notebook

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.Backup
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Clip
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import io.github.iamthebarrow.nibtexa.storage.ClipSummary
import io.github.iamthebarrow.nibtexa.storage.NotebookRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The clipboard in the app (Phase 26): copies are kept across pages and booklets and paste with no recognition.
 * Surviving a restart is in [NotebookRestartTest]. Screenshots are kept.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun ready() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setPenBarHidden(true) }
        waitFor { state().penBarHidden }
    }

    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A written equation at height [y] pixels, recognized once and confirmed as [latex]. Returns its region's id. */
    private fun equation(latex: String, y: Float = 200f): String {
        val regions = state().page.regions.map { it.id }.toSet()
        val strokes = state().page.ink.strokes.size
        // The surface takes its tool when the screen next updates: the pen must be in hand before it writes.
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to y, 500f to y + 20f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == strokes + 1 }
        val points = state().page.ink.strokes.last().points
        composeRule.runOnUiThread {
            viewModel().setTool(Tool.LASSO)
            lasso(points.minOf { it.y } - 5f, points.maxOf { it.y } + 5f)
            viewModel().makeRegion(RegionRole.MATH)
        }
        waitFor { state().page.regions.any { it.id !in regions && it.status == RecognitionStatus.RECOGNIZED } }
        val id = state().page.regions.first { it.id !in regions }.id
        composeRule.runOnUiThread { viewModel().confirm(id, latex) }
        waitFor { state().page.region(id)?.confirmedLatex == latex }
        return id
    }

    private fun lasso(top: Float, bottom: Float) = viewModel().onLasso(listOf(0f to top, 3000f to top, 3000f to bottom, 0f to bottom, 0f to top))

    private fun select(regionId: String) {
        val b = state().page.region(regionId)!!.bounds
        composeRule.runOnUiThread {
            viewModel().setTool(Tool.LASSO)
            lasso(b.top - 5f, b.bottom + 5f)
        }
        waitFor { state().selection.isNotEmpty() || state().selectedTyped.isNotEmpty() }
    }

    private fun copy(regionId: String) {
        val count = state().clips.size
        select(regionId)
        composeRule.runOnUiThread { viewModel().copySelection() }
        waitFor { state().clips.size == count + 1 }
    }

    private fun recognitions() = synchronized(services.requests) { services.requests.size }

    private fun onDisk() = runBlocking { services.freshRepository().clips() }

    @Test
    fun aPasteIsOneUndoAndItsResultStaysWithItsHandwriting() {
        ready()
        val id = equation("x^2")
        copy(id)
        val asked = recognitions()
        val strokes = state().page.ink.strokes.size
        composeRule.runOnUiThread { viewModel().paste(state().clips.single().id, handwriting = true) }
        waitFor { state().page.regions.size == 2 }
        assertEquals(strokes + 1, state().page.ink.strokes.size)
        val pasted = state().page.regions.first { it.id != id }
        assertEquals("x^2", pasted.confirmedLatex)
        assertFalse(pasted.typed)
        assertEquals(pasted.strokeIds, state().selection)
        assertEquals(Tool.LASSO, state().tool)
        assertFalse(state().page.isStale(pasted))

        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.regions.size == 1 }
        assertEquals(strokes, state().page.ink.strokes.size)
        composeRule.runOnUiThread { viewModel().redo() }
        waitFor { state().page.regions.size == 2 }
        assertEquals(asked, recognitions())
    }

    @Test
    fun repeatedPastesDoNotStack() {
        ready()
        copy(equation("x"))
        composeRule.runOnUiThread { viewModel().paste() }
        waitFor { state().page.regions.size == 2 }
        composeRule.runOnUiThread { viewModel().paste() }
        waitFor { state().page.regions.size == 3 }
        assertEquals(3, state().page.regions.map { it.bounds.left }.toSet().size)
    }

    @Test
    fun thePanelShowsItTypesetAndPastesOnAnotherPage() {
        ready()
        select(equation("y=\\sin x"))
        composeRule.onNodeWithText(string(R.string.copy)).performClick()
        waitFor { state().clips.size == 1 }
        composeRule.onNodeWithText(string(R.string.clipboard)).assertIsDisplayed()
        composeRule.waitForIdle()
        screenshot("clipboard-selection-bar.png")
        composeRule.onNodeWithText(string(R.string.clipboard)).performClick()
        composeRule.onNodeWithTag(CLIPBOARD_TAG).assertIsDisplayed()
        // Typeset: the equation is now an image, named by its LaTeX.
        composeRule.waitUntil(30_000) { composeRule.onAllNodes(hasContentDescription("y=\\sin x")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        screenshot("clipboard-panel.png")
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        waitFor { !state().clipboardOpen }

        composeRule.runOnUiThread { viewModel().addPage() }
        waitFor { state().pageCount == 2 && state().pageIndex == 1 }
        composeRule.runOnUiThread { viewModel().setClipboardOpen(true) }
        composeRule.onNodeWithTag(clipRowTag(state().clips.single().id)).performClick()
        waitFor { state().page.regions.size == 1 }
        assertFalse(state().clipboardOpen)
        val region = state().page.regions.single()
        assertEquals("y=\\sin x", region.confirmedLatex)
        assertTrue(region.typed)
        assertEquals(setOf(region.id), state().selectedTyped)
        // On another page it goes at the view's corner.
        assertEquals(40f, region.bounds.left, 1f)
        composeRule.waitForIdle()
        screenshot("clipboard-typed-on-page.png")

        // A typed region can be copied too.
        composeRule.onNodeWithText(string(R.string.copy)).performClick()
        waitFor { state().clips.size == 2 }
        assertFalse(state().clips.first().handwritten)
    }

    @Test
    fun pinsSurviveTheLimitAndClearAllEmptiesIt() {
        ready()
        val id = equation("pinned")
        copy(id)
        val pinned = state().clips.single().id
        composeRule.runOnUiThread { viewModel().setClipPinned(pinned, true) }
        waitFor { state().clips.single().pinned }
        select(id)
        repeat(52) { composeRule.runOnUiThread { viewModel().copySelection() } }
        composeRule.waitForIdle()
        assertEquals(51, state().clips.size)
        assertEquals(pinned, state().clips.first().id)
        waitFor(30_000) { onDisk().map { it.id } == state().clips.map { it.id } }

        composeRule.runOnUiThread { viewModel().setClipboardOpen(true) }
        val clear = hasText(string(R.string.clipboard_clear))
        composeRule.onNode(clear and hasAnyAncestor(hasTestTag(CLIPBOARD_TAG))).performClick()
        composeRule.onNodeWithText(string(R.string.clipboard_clear_title)).assertIsDisplayed()
        composeRule.onAllNodes(clear).onLast().performClick()
        waitFor { state().clips.isEmpty() }
        waitFor { onDisk().isEmpty() }
        composeRule.onNodeWithText(string(R.string.clipboard_empty)).assertIsDisplayed()
    }

    @Test
    fun aPictureInAnEntryIsKeptThroughTheCleanUpUntilTheEntryGoes() = runBlocking {
        ready()
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val stored = services.pictures.add(png)
        val repository = services.freshRepository()
        val clip = Clip(regions = listOf(Region("r1", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 20f, 20f), 1, typed = true, picture = stored.file)))
        assertTrue(repository.addClip(ClipSummary.of("c1", 1, false, clip, null), clip))
        assertTrue(stored.file in repository.usedPictures())
        services.pictures.removeUnused(repository.usedPictures())
        assertNotNull(services.pictures.file(stored.file))

        repository.deleteClip("c1")
        assertFalse(stored.file in repository.usedPictures())
        services.pictures.removeUnused(repository.usedPictures())
        assertNull(services.pictures.file(stored.file))
    }

    @Test
    fun whatIsTooLargeIsNotStoredAndWhatFitsReadsBack() = runBlocking {
        ready()
        val repository = services.freshRepository()
        val random = java.util.Random(1)
        val points = List(NotebookRepository.MAX_CLIP_POINTS + 1) { InkPoint(random.nextFloat() * 1000f, random.nextFloat() * 1000f, it.toLong()) }
        // Too many points: refused without running out of memory on the way.
        val big = Clip(listOf(Stroke(1, ToolType.STYLUS, 0, points, Pen.DRAWING)))
        assertFalse(repository.addClip(ClipSummary.of("big", 1, false, big, null), big))
        assertTrue(repository.clips().isEmpty())
        // At the limit, and noise that hardly compresses: the worst that can be stored still reads back whole.
        val fits = Clip(listOf(Stroke(1, ToolType.STYLUS, 0, points.dropLast(1), Pen.DRAWING)))
        assertTrue(repository.addClip(ClipSummary.of("ok", 1, false, fits, null), fits))
        assertEquals(fits, repository.clip("ok"))
    }

    @Test
    fun anEntryGoesToAGraphNewOrOpen() {
        ready()
        copy(equation("y=x"))
        copy(equation("y=x^2", 400f))
        copy(equation("x^2+y^2=1", 600f))
        val byLatex = state().clips.associateBy { it.equations }

        composeRule.runOnUiThread { viewModel().clipToGraph(byLatex.getValue("y=x").id) }
        waitFor { state().page.regions.any { it.graph != null } }
        val graph = state().page.regions.first { it.graph != null }
        assertEquals(listOf("y=x"), graph.graph!!.curves.map { it.latex })
        assertNull(graph.graph!!.curves.single().source)
        assertEquals(graph.id, state().editingRegionId)

        // With the graph open, the next one joins it.
        composeRule.runOnUiThread { viewModel().clipToGraph(byLatex.getValue("y=x^2").id) }
        waitFor { state().page.region(graph.id)!!.graph!!.curves.size == 2 }
        assertEquals(1, state().page.regions.count { it.graph != null })
        val curves = state().page.region(graph.id)!!.graph!!.curves
        assertNotEquals(curves[0].color, curves[1].color)

        // What can't be graphed says why, and changes nothing.
        composeRule.runOnUiThread { viewModel().clipToGraph(byLatex.getValue("x^2+y^2=1").id) }
        waitFor { state().message != null }
        assertTrue(state().message!!, state().message!!.contains("function"))
        assertEquals(2, state().page.region(graph.id)!!.graph!!.curves.size)
    }

    @Test
    fun aCopiedGraphStaysAGraphOnAnotherPage() {
        ready()
        val id = equation("y=x")
        composeRule.runOnUiThread { viewModel().graphEquation(id) }
        waitFor { state().page.regions.any { it.graph != null } }
        val graph = state().page.regions.first { it.graph != null }
        composeRule.runOnUiThread {
            viewModel().setResultsOpen(false)
            viewModel().setTool(Tool.LASSO)
            lasso(0f, 3000f)
            viewModel().copySelection()
        }
        waitFor { state().clips.size == 1 }
        assertTrue(state().clips.single().drawn)
        composeRule.runOnUiThread { viewModel().setClipboardOpen(true) }
        waitFor { composeRule.onAllNodes(hasContentDescription(string(R.string.clipboard_picture))).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitUntil(30_000) { composeRule.onAllNodes(hasContentDescription("y=x")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        // The window shows a frame or so after the content is ready.
        Thread.sleep(500)
        screenshot("clipboard-graph-entry.png")
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        composeRule.runOnUiThread { viewModel().addPage() }
        waitFor { state().pageIndex == 1 }
        composeRule.runOnUiThread { viewModel().paste() }
        waitFor { state().page.regions.size == 2 }
        val copy = state().page.regions.first { it.graph != null }
        val equationCopy = state().page.regions.first { it.role == RegionRole.MATH }
        assertEquals(graph.graph!!.xMax, copy.graph!!.xMax, 0.0)
        // It follows the equation copied with it.
        assertEquals(equationCopy.id, copy.graph!!.curves.single().source)
    }

    @Test
    fun equationsGoToAndroidsClipboardWithTheSettingOn() {
        ready()
        val id = equation("E=mc^2")
        val manager = composeRule.activity.getSystemService(ClipboardManager::class.java)
        fun onClipboard(): String? {
            var text: String? = null
            composeRule.runOnUiThread { text = manager.primaryClip?.getItemAt(0)?.text?.toString() }
            return text
        }
        composeRule.runOnUiThread { manager.setPrimaryClip(android.content.ClipData.newPlainText("test", "before")) }

        // Off, as tests start: what is there stays.
        copy(id)
        assertEquals("before", onClipboard())

        composeRule.runOnUiThread { viewModel().setSystemClipboard(true) }
        copy(id)
        assertEquals("E=mc^2", onClipboard())
        composeRule.runOnUiThread { manager.clearPrimaryClip() }
    }

    @Test
    fun theClipboardIsInAWholeBackupAndComesBack() {
        ready()
        copy(equation("z=1"))
        val pinned = state().clips.single().id
        composeRule.runOnUiThread { viewModel().setClipPinned(pinned, true) }
        waitFor { onDisk().singleOrNull()?.pinned == true }

        val whole = File(composeRule.activity.cacheDir, "with-clips.nibtexa.zip").apply { delete() }
        composeRule.runOnUiThread { viewModel().exportBackup(Uri.fromFile(whole)) }
        waitFor(30_000) { !state().exporting && whole.length() > 0 }
        val checked = Backup.check(whole)
        assertEquals(listOf("z=1"), checked.clips.single().clip.equations)
        assertTrue(checked.clips.single().pinned)

        // Chosen booklets don't carry it.
        val chosen = File(composeRule.activity.cacheDir, "without-clips.nibtexa.zip").apply { delete() }
        composeRule.runOnUiThread { viewModel().exportBackup(Uri.fromFile(chosen), setOf(state().bookletId)) }
        waitFor(30_000) { !state().exporting && chosen.length() > 0 }
        assertTrue(Backup.check(chosen).clips.isEmpty())

        // Restored after the list has been cleared, it is back as it was.
        composeRule.runOnUiThread { viewModel().clearClips() }
        waitFor { onDisk().isEmpty() }
        composeRule.runOnUiThread { viewModel().prepareRestore(Uri.fromFile(whole)) }
        waitFor(30_000) { state().restorePrompt != null }
        composeRule.runOnUiThread { viewModel().confirmRestore(false) }
        waitFor(30_000) { !state().exporting && state().clips.size == 1 }
        assertEquals(pinned, state().clips.single().id)
        assertTrue(state().clips.single().pinned)
        assertEquals(listOf("z=1"), runBlocking { services.freshRepository().clip(pinned) }!!.equations)
        whole.delete()
        chosen.delete()
    }
}
