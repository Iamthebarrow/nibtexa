package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.export.ExportResult
import io.github.iamthebarrow.nibtexa.core.export.LatexExporter
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Diagram
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramGeometry
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramLineType
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramPart
import io.github.iamthebarrow.nibtexa.core.model.notebook.DiagramShapeType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Diagrams in the app (Phase 31), through Android's real input system with a little wobble in every stroke: each stroke of the Diagram pen
 * becomes a clean part as soon as it ends, with no ink left behind; writing is read and typeset; the lasso moves parts and the lines follow;
 * and the whole thing survives a restart and comes out in the exports. Screenshots are kept.
 */
@RunWith(AndroidJUnit4::class)
class DiagramsTest {
    private val services = TestServicesRule(margins = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = try {
        composeRule.waitUntil(timeoutMs) {
            composeRule.waitForIdle()
            runCatching(condition).getOrDefault(false)
        }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("On the page: " + state().page.regions.map { r -> r.bounds.toString() + " " + r.diagram?.let(io.github.iamthebarrow.nibtexa.core.model.serialization.DiagramJson::encode) }, e)
    }

    private fun surface() = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)

    private fun origin(): Pair<Float, Float> {
        val at = IntArray(2)
        composeRule.runOnUiThread { surface().getLocationOnScreen(at) }
        return at[0].toFloat() to at[1].toFloat()
    }

    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val (ox, oy) = origin()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x + ox; this.y = y + oy; pressure = 0.7f; size = 1f }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0)
        assertTrue("input was refused", instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }

    /** Page units to screen pixels at the view the test keeps: 100%, from the page's top left. The strokes are moved inside the margin. */
    private fun draw(points: List<Pair<Float, Float>>, inset: Float = INSET) {
        val view = state().page.view ?: io.github.iamthebarrow.nibtexa.core.model.notebook.PageView()
        val screen = points.map { (x, y) -> (x + inset - view.left) * view.scale * density to (y - view.top) * view.scale * density }
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, screen[0].first, screen[0].second)
        for (p in screen.drop(1)) {
            inject(MotionEvent.ACTION_MOVE, down, p.first, p.second)
            Thread.sleep(3)
        }
        inject(MotionEvent.ACTION_UP, down, screen.last().first, screen.last().second)
        composeRule.waitForIdle()
    }

    private fun wobble(points: List<Pair<Float, Float>>, amount: Float = 1.2f) = points.mapIndexed { i, (x, y) ->
        val t = i / 12f
        x + amount * sin(t) to y + amount * cos(1.3f * t)
    }

    private fun through(vararg corners: Pair<Float, Float>): List<Pair<Float, Float>> = corners.toList().zipWithNext().flatMap { (a, b) ->
        val n = (kotlin.math.hypot(b.first - a.first, b.second - a.second) / 6f).toInt().coerceAtLeast(3)
        (0 until n).map { a.first + (b.first - a.first) * it / n to a.second + (b.second - a.second) * it / n }
    } + corners.last()

    private fun box(l: Float, t: Float, r: Float, b: Float) = draw(wobble(through(l to t, r to t, r to b, l to b, l to t)))

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = draw(wobble(through(x1 to y1, x2 to y2), 0.8f))

    private fun circle(x: Float, y: Float, r: Float) = draw(wobble((0..48).map { i -> (2 * PI * i / 48).let { x + r * cos(it).toFloat() to y + r * sin(it).toFloat() } }, 0.5f))

    private fun open() {
        waitFor { state().loaded && surface() != null }
        composeRule.runOnUiThread {
            viewModel().onView(io.github.iamthebarrow.nibtexa.core.model.notebook.PageView(), true)
            viewModel().setTool(Tool.DIAGRAM_PEN)
        }
        composeRule.waitForIdle()
    }

    private fun diagramRegion(): Region = state().page.regions.single { it.diagram != null }
    private fun diagram(): Diagram = diagramRegion().diagram!!

    private fun screenshot(name: String) {
        // Maths capture waits on the test clock.
        repeat(30) {
            composeRule.mainClock.advanceTimeBy(100)
            composeRule.waitForIdle()
            Thread.sleep(100)
        }
        val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(composeRule.activity.getExternalFilesDir(null), "diagrams").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    /** A feedback loop drawn stroke by stroke, as a person would. */
    private fun drawFeedbackLoop() {
        circle(150f, 200f, 22f)
        line(140f, 190f, 160f, 210f)
        line(160f, 190f, 140f, 210f)
        box(240f, 165f, 360f, 235f)
        box(440f, 168f, 560f, 238f)
        line(40f, 202f, 124f, 200f)
        line(175f, 200f, 236f, 202f)
        line(364f, 200f, 436f, 200f)
        line(564f, 200f, 680f, 200f)
        // The output's take-off, down, across and up into the summing point.
        draw(wobble(through(620f to 203f, 620f to 320f, 150f to 320f, 150f to 226f), 0.8f))
    }

    @Test
    fun aFeedbackLoopDrawnByHandComesOutCleanWithNoInk() {
        open()
        drawFeedbackLoop()
        waitFor { diagram().lines.size == 5 }
        val d = diagram()
        assertTrue("no ink is left on the page", state().page.ink.strokes.isEmpty())
        assertEquals("the summing point, two blocks and a take-off", 4, d.shapes.size)
        assertEquals(DiagramShapeType.CROSS, d.shapes[0].type)
        assertEquals(1, d.shapes.count { it.type == DiagramShapeType.DOT })
        val (block1, block2) = d.shapes.filter { it.type == DiagramShapeType.BOX }
        assertEquals("the blocks line up", block1.y, block2.y)
        assertEquals("and are the same size", block1.height, block2.height)
        assertEquals(d.shapes[0].y, block1.y)
        // Every part of every line is exactly level or upright.
        for (line in d.lines) for ((a, b) in line.points.zipWithNext()) assertTrue("square: $a $b", abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f)
        val feedback = d.lines.last()
        assertEquals(4, feedback.points.size)
        assertEquals(d.shapes[0].id, feedback.to)
        assertEquals(1, d.shapes.count { it.id == feedback.from && it.type == DiagramShapeType.DOT })
        screenshot("feedback-loop")
    }

    @Test
    fun writingInABlockIsReadAndTypeset() {
        services.script = { _, _ -> "\\frac{1}{s+2}" }
        open()
        box(240f, 165f, 400f, 245f)
        draw(wobble(through(300f to 190f, 300f to 225f), 0.3f))
        draw(wobble(through(315f to 190f, 330f to 225f, 345f to 190f), 0.3f))
        waitFor { diagram().labels.singleOrNull()?.latex != null }
        val label = diagram().labels.single()
        assertEquals("\\frac{1}{s+2}", label.latex)
        assertEquals("two strokes, one label", 2, label.ink.size)
        assertEquals(diagram().shapes.single().id, label.shape)
        assertEquals(diagram().shapes.single().x, label.x)
        screenshot("label-typeset")
        // Back to the handwriting, which is kept.
        composeRule.runOnUiThread { viewModel().setDiagramLabel(diagramRegion().id, label.id, null, null) }
        waitFor { diagram().labels.single().handwritten }
        assertFalse(diagram().labels.single().unread)
    }

    @Test
    fun writingThatCantBeReadStaysAsHandwritingInTheFigure() {
        services.script = { _, _ -> throw io.github.iamthebarrow.nibtexa.core.recognition.RecognitionException("Can't read this") }
        open()
        box(240f, 165f, 400f, 245f)
        draw(wobble(through(300f to 190f, 330f to 225f), 0.3f))
        waitFor { diagram().labels.singleOrNull()?.unread == false }
        assertTrue(diagram().labels.single().handwritten)
        assertTrue(diagram().labels.single().ink.isNotEmpty())
    }

    @Test
    fun undoTakesBackOneStrokeAtATimeAndRedoRestoresIt() {
        open()
        box(240f, 165f, 360f, 235f)
        line(364f, 200f, 480f, 200f)
        waitFor { diagram().lines.size == 1 }
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { diagram().lines.isEmpty() }
        assertEquals(1, diagram().shapes.size)
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.regions.isEmpty() }
        composeRule.runOnUiThread { viewModel().redo() }
        composeRule.runOnUiThread { viewModel().redo() }
        waitFor { state().page.regions.singleOrNull()?.diagram?.lines?.size == 1 }
    }

    @Test
    fun theLassoChoosesAndMovesAPartAndTheLinesFollow() {
        open()
        box(240f, 165f, 360f, 235f)
        box(500f, 165f, 620f, 235f)
        line(364f, 200f, 496f, 200f)
        waitFor { diagram().lines.size == 1 }
        val region = diagramRegion()
        val second = diagram().shapes[1]
        composeRule.runOnUiThread { viewModel().setTool(Tool.LASSO) }
        composeRule.waitForIdle()
        val scale = (region.bounds.right - region.bounds.left) / diagram().width
        val x = region.bounds.left + second.x * scale
        val y = region.bounds.top + second.y * scale
        // A tap opens its controls, with the part chosen.
        draw(listOf(x to y, x to y), inset = 0f)
        waitFor { state().diagramPart == DiagramPart.Shape(second.id) }
        composeRule.onNodeWithTag(DIAGRAM_EDITOR_TAG).assertIsDisplayed()
        screenshot("part-chosen")
        // A drag moves it, and its line follows.
        draw(through(x to y, x + 70f to y + 120f), inset = 0f)
        waitFor { abs((diagram().shapes[1].y - diagram().shapes[0].y) - 120f / scale) < 2f }
        val d = diagram()
        val path = DiagramGeometry.path(d, d.lines.single())
        assertEquals("the line still ends on the block's edge", d.shapes[1].x - d.shapes[1].width / 2, path.last().x, 12f)
        screenshot("part-moved")
        // Deleting the block lets the line go, which stays where it was.
        composeRule.onNodeWithTag(DIAGRAM_DELETE_TAG).performClick()
        waitFor { diagram().shapes.size == 1 }
        assertNull(diagram().lines.single().to)
    }

    @Test
    fun theEraserRemovesPartsAndAnEmptyDiagramGoes() {
        open()
        box(240f, 165f, 360f, 235f)
        line(364f, 200f, 480f, 200f)
        waitFor { diagram().lines.size == 1 }
        composeRule.runOnUiThread { viewModel().setTool(Tool.ERASER) }
        composeRule.waitForIdle()
        val b = diagramRegion().bounds
        draw(through(b.right - 30f to b.top + 2f, b.right - 30f to b.bottom - 2f), inset = 0f)
        waitFor { diagram().lines.isEmpty() }
        assertEquals(1, diagram().shapes.size)
        val after = diagramRegion().bounds
        draw(through(after.left - 5f to after.centerY, after.right + 5f to after.centerY), inset = 0f)
        waitFor { state().page.regions.none { it.diagram != null } }
    }

    @Test
    fun aDiagramKeepsInsideTheMarginAndAFarStrokeStartsAnother() {
        open()
        box(240f, 165f, 360f, 235f)
        box(240f, 450f, 360f, 520f)
        waitFor { state().page.regions.count { it.diagram != null } == 2 }
        val area = viewModel().writingArea(state().page)
        for (r in state().page.regions) assertTrue("${r.bounds} in $area", r.bounds.left >= area.left - 0.5f && r.bounds.top >= area.top - 0.5f && r.bounds.right <= area.right + 0.5f)
    }

    @Test
    fun itSurvivesARestartAndComesOutInTheExports() {
        services.script = { _, _ -> "G(s)" }
        open()
        drawFeedbackLoop()
        box(240f, 420f, 360f, 490f)
        draw(wobble(through(280f to 440f, 300f to 470f), 0.3f))
        waitFor { diagram().labels.singleOrNull()?.latex == "G(s)" }
        val before = diagramRegion()
        // As a new process would read it.
        waitFor(30_000) { runBlocking { services.freshRepository().loadBooklet(state().bookletId) }!!.pages.single().regions.singleOrNull { it.diagram != null } == before }

        val booklet = Booklet("b-test", "Diagrams", listOf(state().page.copy(id = "p1")))
        val result = LatexExporter.export(booklet) { _, _ -> error("A diagram has no file of its own") } as ExportResult.Success
        val tex = result.files.first { it.path.endsWith(".tex") && it.path.contains("pages") }.content
        assertTrue(tex.contains("\\begin{tikzpicture}"))
        assertTrue(tex.contains("G(s)"))

        val written = runBlocking { withContext(Dispatchers.Main) { BookletPdf(composeRule.activity).render(booklet) } }
        assertNotNull(written as? BookletPdf.Result.Written)
        val dir = File(composeRule.activity.getExternalFilesDir(null), "diagrams").apply { mkdirs() }
        File(dir, "diagram.pdf").writeBytes((written as BookletPdf.Result.Written).bytes)
    }

    @Test
    fun aLineBetweenTwoDiagramsMakesThemOne() {
        open()
        box(240f, 165f, 360f, 235f)
        box(700f, 165f, 820f, 235f)
        waitFor { state().page.regions.count { it.diagram != null } == 2 }
        line(364f, 200f, 696f, 200f)
        waitFor { state().page.regions.count { it.diagram != null } == 1 }
        val d = diagram()
        assertEquals(2, d.shapes.size)
        val l = d.lines.single()
        assertEquals(setOf(d.shapes[0].id, d.shapes[1].id), setOf(l.from, l.to))
        // One undo takes the line away and parts them again.
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.regions.count { it.diagram != null } == 2 }
    }

    @Test
    fun theDiagramPenIsOnThePenBar() {
        composeRule.onNodeWithText(composeRule.activity.getString(io.github.iamthebarrow.nibtexa.R.string.tool_diagram_pen)).performClick()
        waitFor { state().tool == Tool.DIAGRAM_PEN }
        screenshot("pen-bar")
    }

    @Test
    fun aRoughGraphAndASignalFlowGraphOnOnePage() {
        open()
        // A rough graph: axes with arrowheads, and a steadied curve.
        draw(wobble(through(100f to 420f, 100f to 180f, 90f to 198f, 100f to 180f, 110f to 198f), 0.4f))
        draw(wobble(through(100f to 420f, 440f to 420f, 422f to 410f, 440f to 420f, 422f to 430f), 0.4f))
        val curve = (0..120).map { i -> 100f + i * 2.6f to 420f - 180f * (1f - kotlin.math.exp(-i / 22f) * cos(i / 11f)) }
        draw(wobble(curve, 1.5f))
        waitFor { diagram().lines.size == 3 }
        assertTrue(diagram().lines[0].arrowEnd)
        assertTrue(diagram().lines[1].arrowEnd)
        assertEquals(DiagramLineType.CURVE, diagram().lines[2].type)
        // Far to the right: nodes, a branch with an arrow in its middle, and an arc back.
        draw(listOf(720f to 350f, 721f to 350f))
        draw(listOf(1020f to 350f, 1021f to 350f))
        line(724f, 350f, 1016f, 350f)
        draw(wobble(through(862f to 340f, 875f to 350f, 862f to 360f), 0.2f))
        draw(wobble((0..48).map { i -> (PI + PI * i / 48).let { 870f + 150f * cos(it).toFloat() to 350f + 100f * sin(it).toFloat() } }, 0.5f))
        waitFor { state().page.regions.size == 2 && state().page.regions.last().diagram!!.lines.size == 2 }
        val flow = state().page.regions.last().diagram!!
        assertEquals(2, flow.shapes.count { it.type == DiagramShapeType.DOT })
        assertEquals(1, flow.lines[0].arrows.size)
        assertEquals(DiagramLineType.ARC, flow.lines[1].type)
        screenshot("graph-and-flow")
    }

    private companion object {
        /** The tests draw from here across the page; a normal margin is 125 units. */
        const val INSET = 100f
    }
}
