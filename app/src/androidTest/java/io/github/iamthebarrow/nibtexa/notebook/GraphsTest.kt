package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.model.notebook.Graph
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.export.AnnotatedPdf
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Graphs in the app (Phase 25), through Android's real input system: an equation goes to a graph, its view moves
 * only while its controls are open, it follows corrections, and it reaches every export. Screenshots are kept.
 */
@RunWith(AndroidJUnit4::class)
class GraphsTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun surface() = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)

    private fun origin(): Pair<Float, Float> {
        val at = IntArray(2)
        composeRule.runOnUiThread { surface().getLocationOnScreen(at) }
        return at[0].toFloat() to at[1].toFloat()
    }

    private fun density() = composeRule.activity.resources.displayMetrics.density

    private fun inject(action: Int, down: Long, tool: Int, vararg pointers: Pair<Float, Float>) {
        val (ox, oy) = origin()
        val properties = Array(pointers.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coords = Array(pointers.size) { i -> MotionEvent.PointerCoords().apply { x = pointers[i].first + ox; y = pointers[i].second + oy; pressure = 0.7f; size = 1f } }
        val source = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        assertTrue("input was refused", instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }

    /** One pointer of [tool] from [a] to [b], in surface pixels. */
    private fun drag(tool: Int, a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, tool, a)
        for (i in 1..20) {
            inject(MotionEvent.ACTION_MOVE, down, tool, a.first + (b.first - a.first) * i / 20 to a.second + (b.second - a.second) * i / 20)
            Thread.sleep(4)
        }
        inject(MotionEvent.ACTION_UP, down, tool, b)
    }

    /** Two fingers round ([x], [y]), from [from] to [to] pixels apart. */
    private fun pinch(x: Float, y: Float, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        fun at(s: Float) = arrayOf(x - s / 2 to y, x + s / 2 to y)
        val finger = MotionEvent.TOOL_TYPE_FINGER
        inject(MotionEvent.ACTION_DOWN, down, finger, at(from)[0])
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, finger, *at(from))
        for (i in 1..20) {
            inject(MotionEvent.ACTION_MOVE, down, finger, *at(from + (to - from) * i / 20))
            Thread.sleep(4)
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down, finger, *at(to))
        inject(MotionEvent.ACTION_UP, down, finger, at(to)[0])
    }

    private fun screenshot(name: String) {
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A written equation near the top left, at the page's 100%, confirmed as [latex]. Returns its region's id. */
    private fun equation(latex: String, y: Float = 200f): String {
        val before = state().page.regions.map { it.id }.toSet()
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN) }
        waitFor { state().tool == Tool.MATH_PEN }
        val count = state().page.ink.strokes.size
        drag(MotionEvent.TOOL_TYPE_STYLUS, 200f to y, 500f to y)
        waitFor { state().page.ink.strokes.size == count + 1 }
        val stroke = state().page.ink.strokes.last().id
        composeRule.runOnUiThread {
            viewModel().setTool(Tool.LASSO)
            val b = state().page.ink.strokes.last().points
            val (l, t) = b.minOf { it.x } - 5f to b.minOf { it.y } - 5f
            val (r, m) = b.maxOf { it.x } + 5f to b.maxOf { it.y } + 5f
            viewModel().onLasso(listOf(l to t, r to t, r to m, l to m, l to t))
            viewModel().makeRegion(io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole.MATH)
        }
        waitFor { state().page.regions.any { it.id !in before && stroke in it.strokeIds && it.status != io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus.RECOGNIZING } }
        val id = state().page.regions.first { it.id !in before }.id
        composeRule.runOnUiThread { viewModel().confirm(id, latex) }
        waitFor { state().page.region(id)?.confirmedLatex == latex }
        return id
    }

    private fun ready() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false); viewModel().setPenBarHidden(true) }
        waitFor { state().penBarHidden }
    }

    /** Graphs the equation with the real button; returns the graph's region. */
    private fun graphWithButton(equationId: String): Region {
        composeRule.runOnUiThread { viewModel().openRegion(equationId) }
        composeRule.onNodeWithTag(GRAPH_EQUATION_TAG).performScrollTo().performClick()
        waitFor { state().page.regions.any { it.graph != null } && state().page.region(state().editingRegionId ?: "")?.graph != null }
        return state().page.region(state().editingRegionId!!)!!
    }

    private fun graphRegion(id: String) = state().page.region(id)!!
    private fun graphOf(id: String): Graph = graphRegion(id).graph!!

    /** Surface pixels for the middle of the graph's plot. */
    private fun middle(region: Region): Pair<Float, Float> {
        val view = state().page.view ?: io.github.iamthebarrow.nibtexa.core.model.notebook.PageView()
        val d = density()
        return ((region.bounds.centerX - view.left) * view.scale * d) to ((region.bounds.centerY - view.top) * view.scale * d)
    }

    private fun saved() = waitFor { state().savedVersion >= state().version }

    @Test
    fun anEquationGoesToAGraphThatMovesAndZoomsWhileOpenWithoutWriting() {
        ready()
        val id = equation("y=\\sin x")
        val region = graphWithButton(id)
        composeRule.onNodeWithTag(GRAPH_EDITOR_TAG).assertIsDisplayed()
        assertEquals("y=\\sin x", region.graph!!.curves.single().latex)
        assertEquals(id, region.graph!!.curves.single().source)
        // Fitted to the curve, not the default.
        assertTrue(region.graph!!.yMax < 5)
        screenshot("graph-open.png")

        val strokes = state().page.ink.strokes.size
        val pageView = state().page.view
        val (x, y) = middle(region)
        val start = graphOf(region.id)
        drag(MotionEvent.TOOL_TYPE_FINGER, x to y, x + 200f to y)
        waitFor { graphOf(region.id) != start }
        val dragged = graphOf(region.id)
        // Dragged right: lower values come into view, and the width stays.
        assertTrue(dragged.xMin < start.xMin)
        assertEquals(start.xMax - start.xMin, dragged.xMax - dragged.xMin, 1e-6)

        pinch(x, y, 100f, 300f)
        waitFor { graphOf(region.id) != dragged }
        val pinched = graphOf(region.id)
        assertEquals((dragged.xMax - dragged.xMin) / 3, pinched.xMax - pinched.xMin, (dragged.xMax - dragged.xMin) * 0.1)
        screenshot("graph-zoomed.png")

        // Undo takes each gesture back; the graph's touch wrote nothing and moved nothing else.
        assertEquals(strokes, state().page.ink.strokes.size)
        assertEquals(pageView, state().page.view)
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { graphOf(region.id) == dragged }

        // A stylus is the same while it's open.
        drag(MotionEvent.TOOL_TYPE_STYLUS, x to y, x to y + 150f)
        waitFor { graphOf(region.id) != dragged }
        assertEquals(strokes, state().page.ink.strokes.size)
    }

    @Test
    fun aClosedGraphIsPartOfThePage() {
        ready()
        val region = graphWithButton(equation("y=x"))
        val shown = graphOf(region.id)
        composeRule.runOnUiThread { viewModel().setResultsOpen(false); viewModel().setTool(Tool.MATH_PEN) }
        waitFor { !state().resultsOpen && state().tool == Tool.MATH_PEN }
        val strokes = state().page.ink.strokes.size
        val (x, y) = middle(region)
        // The pen writes on it.
        drag(MotionEvent.TOOL_TYPE_STYLUS, x - 50f to y, x + 50f to y)
        waitFor { state().page.ink.strokes.size == strokes + 1 }
        assertEquals(shown, graphOf(region.id))
        // Two fingers zoom the page, not the graph.
        val scale = (state().page.view ?: io.github.iamthebarrow.nibtexa.core.model.notebook.PageView()).scale
        pinch(x, y, 100f, 300f)
        waitFor { state().page.view?.scale != scale }
        assertEquals(shown, graphOf(region.id))
    }

    @Test
    fun correctionsFollowAndADeletedEquationLeavesItsCurve() {
        ready()
        val id = equation("y=x")
        val region = graphWithButton(id)
        composeRule.runOnUiThread { viewModel().confirm(id, "y=x^2") }
        waitFor { graphOf(region.id).curves.single().latex == "y=x^2" }
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { graphOf(region.id).curves.single().latex == "y=x" }
        composeRule.runOnUiThread { viewModel().deleteRegion(id) }
        waitFor { state().page.region(id) == null }
        assertEquals("y=x", graphOf(region.id).curves.single().latex)
    }

    @Test
    fun whatCantBeGraphedSaysWhy() {
        ready()
        val id = equation("x^2+y^2=1")
        composeRule.runOnUiThread { viewModel().openRegion(id) }
        composeRule.onNodeWithTag(GRAPH_EQUATION_TAG).performScrollTo().performClick()
        waitFor { state().message != null }
        assertTrue(state().message!!, state().message!!.contains("function"))
        assertFalse(state().page.regions.any { it.graph != null })
    }

    @Test
    fun constantsAndAChosenAxisDraw() {
        ready()
        val second = equation("y=a+b", y = 800f)
        val region = graphWithButton(equation("y=kx"))
        val needs = composeRule.activity.getString(io.github.iamthebarrow.nibtexa.R.string.graph_needs_values, "k")
        composeRule.onNodeWithText(needs).assertIsDisplayed()
        composeRule.runOnUiThread { viewModel().setGraphConstant(region.id, "k", 2.0) }
        waitFor { graphOf(region.id).constants["k"] == 2.0 }
        composeRule.onNodeWithText(needs).assertDoesNotExist()

        val ambiguous = graphWithButton(second)
        val choose = composeRule.activity.getString(io.github.iamthebarrow.nibtexa.R.string.graph_choose_axis)
        composeRule.onNodeWithText(choose).assertIsDisplayed()
        composeRule.onNodeWithText("b", useUnmergedTree = true).performScrollTo().performClick()
        waitFor { graphOf(ambiguous.id).curves.single().input == "b" }
    }

    @Test
    fun aSecondCurveAndEveryExport() {
        ready()
        val first = equation("y=\\sin x")
        equation("y=\\frac{1}{x}", y = 800f)
        val region = graphWithButton(first)
        composeRule.onNodeWithTag(GRAPH_ADD_TAG).performScrollTo().performClick()
        composeRule.onNodeWithText("y = 1/x", useUnmergedTree = true).performClick()
        waitFor { graphOf(region.id).curves.size == 2 }
        assertNotEquals(graphOf(region.id).curves[0].color, graphOf(region.id).curves[1].color)
        composeRule.runOnUiThread { viewModel().setCaption(region.id, "Two curves") }
        composeRule.runOnUiThread { viewModel().updateGraph(region.id) { it.copy(title = "A graph", xLabel = "Time") } }
        saved()
        screenshot("graph-two-curves.png")

        // Kept: read back from storage as it was.
        val stored = runBlocking { services.freshRepository().loadBooklet(state().bookletId) }!!.pages.single().region(region.id)!!.graph
        assertEquals(graphOf(region.id), stored)

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { !state().exporting }
        val project = services.unpackExport()
        assertTrue(File(project, "main.tex").readText().contains("pgfplots"))
        val tex = File(project, "booklets").walk().first { it.isFile && it.extension == "tex" }.readText()
        assertTrue(tex.contains("\\addplot["))
        assertTrue(tex.contains("title={A graph}"))
        assertNull(File(project, "assets").listFiles()?.firstOrNull { it.name.contains(region.id) })
        // Kept for compiling by hand.
        project.copyRecursively(File(composeRule.activity.getExternalFilesDir("screenshots"), "graph-latex"), overwrite = true)

        composeRule.runOnUiThread { viewModel().exportMarkdown(services.exportZipUri) }
        waitFor { !state().exporting }
        val md = services.unpackExport()
        val image = File(md, "images").listFiles().orEmpty().single { it.name.contains(region.id) }
        assertTrue(File(md, "main.md").readText().contains(image.name))
        image.copyTo(File(composeRule.activity.getExternalFilesDir("screenshots"), "graph-markdown.png"), overwrite = true)

        val booklet = runBlocking { services.freshRepository().loadBooklet(state().bookletId) }!!
        val result = runBlocking { withContext(Dispatchers.Main) { BookletPdf(composeRule.activity).render(booklet) } } as BookletPdf.Result.Written
        val pdf = File(composeRule.activity.getExternalFilesDir("screenshots"), "graph-booklet.pdf").apply { writeBytes(result.bytes) }
        assertTrue("the graph is in the PDF", drawnOn(pdf, "graph-booklet-pdf.png"))

        val annotated = File(composeRule.activity.getExternalFilesDir("screenshots"), "graph-annotated.pdf")
        composeRule.runOnUiThread { viewModel().exportAnnotatedPdf(Uri.fromFile(annotated), { AnnotatedPdf(composeRule.activity, services.pictures).render(it) }) }
        waitFor(30_000) { !state().exporting && annotated.length() > 0 }
        assertTrue(drawnOn(annotated, "graph-annotated-pdf.png"))

        // And in the page overview's thumbnail.
        composeRule.runOnUiThread { viewModel().setResultsOpen(false) }
        waitFor { !state().resultsOpen }
        composeRule.onNodeWithText(composeRule.activity.getString(io.github.iamthebarrow.nibtexa.R.string.page_position, 1, 1), useUnmergedTree = true).performClick()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(io.github.iamthebarrow.nibtexa.R.string.page_thumbnail, 1)).assertIsDisplayed()
        composeRule.waitForIdle()
        screenshot("graph-pages.png")
    }

    @Test
    fun workingOutAGraphIsQuickOnTheDevice() {
        val curves = listOf("y=\\sin x", "y=\\frac{1}{x}", "y=e^{-x^2}\\cos(3x)", "y=\\tan x").mapIndexed { i, l -> io.github.iamthebarrow.nibtexa.core.model.notebook.GraphCurve(l, color = i) }
        val graph = Graph(curves)
        repeat(20) { io.github.iamthebarrow.nibtexa.core.export.GraphPlot.of(graph) }
        val start = System.nanoTime()
        repeat(50) { io.github.iamthebarrow.nibtexa.core.export.GraphPlot.of(graph) }
        val each = (System.nanoTime() - start) / 50 / 1_000_000.0
        android.util.Log.i("GraphsTest", "Four curves at 1000 points: $each ms")
        assertTrue("$each ms", each < 16)
    }

    /** Renders the PDF's pages at twice their size, keeps the last, and says whether a curve's colour is on any of them. */
    private fun drawnOn(pdf: File, name: String): Boolean = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
        (0 until r.pageCount).map { i ->
            r.openPage(i).use { page ->
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val found = pixels.any { Color.red(it) < 40 && Color.green(it) in 90..140 && Color.blue(it) > 160 }
                if (found) File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                found
            }
        }.any { it }
    }
}
