package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.writingArea
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TYPED_TEXT_HEIGHT
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 30: nothing goes outside the page's margin, which is chosen for a new booklet and can be a page's own, and the words in a box
 * that is made narrower wrap instead of being cut off. Real strokes and drags through the surface. The results are kept as pictures.
 */
@RunWith(AndroidJUnit4::class)
class PageMarginsTest {
    private val services = TestServicesRule(startInLibrary = true, margins = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private fun string(id: Int) = composeRule.activity.getString(id)
    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun area() = viewModel().writingArea(state().page)

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun inject(events: List<MotionEvent>) = composeRule.runOnUiThread {
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        events.forEach { view.dispatchTouchEvent(it); it.recycle() }
    }

    private fun tool(tool: Tool) {
        composeRule.runOnUiThread { viewModel().setTool(tool) }
        composeRule.waitForIdle()
    }

    /** A stylus stroke between two page points; the page opens at 100% from its corner here. */
    private fun stroke(fromX: Float, fromY: Float, toX: Float, toY: Float) =
        inject(TestMotionEvents().line(TOOL_TYPE_STYLUS, fromX * density to fromY * density, toX * density to toY * density))

    private fun shot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(800)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "margins-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun blank() {
        waitFor { state().libraryLoaded }
        composeRule.runOnUiThread { viewModel().createBooklet("Margins", Paper.BLANK); }
        waitFor { state().loaded && state().bookletTitle == "Margins" }
        composeRule.runOnUiThread { viewModel().setPenBarHidden(true) }
        composeRule.waitForIdle()
    }

    private val longWords = "A system's transfer function is the ratio of the Laplace transforms of its output and input. ".repeat(4)

    private fun words(id: String, value: String, b: Bounds, order: Int = 1) =
        Region(id, RegionRole.TEXT, emptySet(), b, order, RecognitionStatus.CONFIRMED, text = value, typed = true, newParagraph = true)

    /** Regions on the page in the open booklet, as an import or an older booklet left them. */
    private fun placed(vararg regions: Region) {
        val id = runBlocking { services.freshRepository().importBooklet("Placed", null, listOf(Page("import", regions = regions.toList(), nextRegionNumber = 100))).id }
        waitFor { state().libraryLoaded }
        composeRule.runOnUiThread { viewModel().openBooklet(id) }
        waitFor { state().loaded && state().bookletTitle == "Placed" && state().page.regions.isNotEmpty() }
    }

    @Test
    fun theBarsGetOutOfTheWayWhileTheSelectionIsDragged() {
        placed(words("r90", "Move these words", Bounds(300f, 300f, 600f, 360f)))
        tool(Tool.LASSO)
        composeRule.runOnUiThread { viewModel().onLasso(listOf(200f to 200f, 700f to 200f, 700f to 500f, 200f to 500f, 200f to 200f)) }
        waitFor { composeRule.onAllNodesWithTag(SELECTION_BAR_TAG).fetchSemanticsNodes().isNotEmpty() }
        val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) = MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x * density, y * density, 0).apply { source = android.view.InputDevice.SOURCE_STYLUS }
        inject(listOf(event(MotionEvent.ACTION_DOWN, 450f, 330f), event(MotionEvent.ACTION_MOVE, 450f, 300f), event(MotionEvent.ACTION_MOVE, 450f, 250f)))
        waitFor { state().draggingSelection }
        waitFor { composeRule.onAllNodesWithTag(SELECTION_BAR_TAG).fetchSemanticsNodes().isEmpty() }
        shot("dragging")
        inject(listOf(event(MotionEvent.ACTION_UP, 450f, 250f)))
        waitFor { !state().draggingSelection }
        waitFor { composeRule.onAllNodesWithTag(SELECTION_BAR_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("it moved", 220f, state().page.region("r90")!!.bounds.top, 1f)
        assertNotNull(view)
    }

    @Test
    fun theSelectionBarKeepsClearOfWhatIsSelected() {
        placed(words("r90", "At the very top of the page", Bounds(200f, 130f, 560f, 190f)))
        composeRule.runOnUiThread { viewModel().setPenBarHidden(true) }
        tool(Tool.LASSO)
        composeRule.runOnUiThread { viewModel().onLasso(listOf(0f to 0f, 900f to 0f, 900f to 300f, 0f to 300f, 0f to 0f)) }
        waitFor { composeRule.onAllNodesWithTag(SELECTION_BAR_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        val bar = composeRule.onNodeWithTag(SELECTION_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val surface = IntArray(2).also { composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG).getLocationInWindow(it) }
        val view = state().page.view ?: io.github.iamthebarrow.nibtexa.core.model.notebook.PageView()
        val selected = state().page.region("r90")!!.bounds
        val top = surface[1] + (selected.top - view.top) * view.scale * density
        val bottom = surface[1] + (selected.bottom - view.top) * view.scale * density
        assertTrue("bar ${bar.top} to ${bar.bottom}, selection $top to $bottom", bar.bottom <= top || bar.top >= bottom)
        shot("bar-clear")
    }

    /** As the user's imported page was: columns running over the right margin. */
    @Test
    fun anImportedPageOverTheMarginComesInAndKeepsItsColumnsWhenShown() {
        val sheet = io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet()
        val area = sheet.writingArea(Paper.BLANK, io.github.iamthebarrow.nibtexa.core.model.notebook.PageMargin.NORMAL)
        val long = "A system's transfer function is the ratio of the Laplace transforms of its output and input."
        placed(
            words("r90", long, Bounds(area.left - 10f, 100f, area.left + 420f, 170f), 1),
            words("r91", long, Bounds(area.left + 425f, 100f, area.right + 150f, 170f), 2),
            words("r92", long, Bounds(area.left - 10f, 180f, area.left + 420f, 250f), 3),
            words("r93", long, Bounds(area.left + 425f, 180f, area.right + 150f, 250f), 4),
        )
        waitFor { state().page.regions.all { it.bounds.right <= area.right + 0.5f } }
        val b = (90..93).map { state().page.region("r$it")!!.bounds }
        b.forEach { assertTrue("$it in $area", it.left >= area.left - 0.5f && it.right <= area.right + 0.5f) }
        assertTrue("side by side", b[0].right <= b[1].left + 0.5f && b[2].right <= b[3].left + 0.5f)
        assertTrue("no overlap down the column", b[2].top >= b[0].bottom - 0.5f && b[3].top >= b[1].bottom - 0.5f)
        shot("columns-in")
    }

    @Test
    fun inkStopsAtTheMarginAndAStrokeStartingInItWritesNothing() {
        blank()
        val area = area()
        tool(Tool.DRAWING_PEN)
        stroke(area.left / 2, 400f, area.left + 100f, 400f)
        composeRule.waitForIdle()
        assertTrue(state().page.ink.strokes.isEmpty())
        stroke(area.left + 100f, 500f, area.left / 2, 500f)
        waitFor { state().page.ink.strokes.size == 1 }
        val points = state().page.ink.strokes.single().points
        assertEquals(area.left, points.minOf { it.x }, 1f)
        shot("ink")
    }

    @Test
    fun aMoveStopsAtTheMarginOnEverySide() {
        blank()
        val area = area()
        tool(Tool.DRAWING_PEN)
        stroke(300f, 300f, 400f, 340f)
        waitFor { state().page.ink.strokes.size == 1 }
        tool(Tool.LASSO)
        composeRule.runOnUiThread {
            viewModel().onLasso(listOf(0f to 0f, 3000f to 0f, 3000f to 3000f, 0f to 3000f, 0f to 0f))
            viewModel().moveSelection(-5000f, -5000f)
        }
        waitFor { state().page.ink.strokes.single().points.minOf { it.x } == area.left }
        assertEquals(area.top, state().page.ink.strokes.single().points.minOf { it.y }, 0.5f)
        composeRule.runOnUiThread { viewModel().moveSelection(9000f, 9000f) }
        waitFor { state().page.ink.strokes.single().points.maxOf { it.y } == area.bottom }
        assertEquals(area.right, state().page.ink.strokes.single().points.maxOf { it.x }, 0.5f)
    }

    /** The user's example: a block of words too large for the page gets narrower, and keeps every word. */
    @Test
    fun wordsTooWideWrapInsideTheMarginWithNoneCutOff() {
        val id = runBlocking {
            services.freshRepository().importBooklet("Wide", null, listOf(Page("import", regions = listOf(words("r1", longWords, Bounds(10f, 40f, 3000f, 80f))), nextRegionNumber = 2))).id
        }
        waitFor { state().libraryLoaded }
        composeRule.runOnUiThread { viewModel().openBooklet(id); viewModel().setPenBarHidden(true) }
        waitFor { state().loaded && state().page.regions.isNotEmpty() }
        composeRule.runOnUiThread { viewModel().fitPageToMargins() }
        val area = area()
        waitFor { state().page.region("r1")!!.bounds.right <= area.right }
        val region = state().page.region("r1")!!
        assertEquals(area.left, region.bounds.left, 0.5f)
        assertEquals(area.top, region.bounds.top, 0.5f)
        val width = region.bounds.right - region.bounds.left
        assertEquals(area.right - area.left, width, 0.5f)
        assertEquals(TYPED_TEXT_HEIGHT.height(region, width), region.bounds.bottom - region.bounds.top, 0.5f)
        assertEquals(longWords, region.text)
        shot("words")
    }

    @Test
    fun aTextBoxCanBeShortenedButNotPastItsWords() {
        val id = runBlocking {
            services.freshRepository().importBooklet("Box", null, listOf(Page("import", regions = listOf(words("r1", longWords, Bounds(200f, 200f, 1000f, 500f))), nextRegionNumber = 2))).id
        }
        waitFor { state().libraryLoaded }
        composeRule.runOnUiThread { viewModel().openBooklet(id); viewModel().setPenBarHidden(true) }
        waitFor { state().loaded && state().page.regions.isNotEmpty() }
        tool(Tool.LASSO)
        val region = state().page.region("r1")!!
        composeRule.runOnUiThread { viewModel().onLasso(listOf(0f to 0f, 3000f to 0f, 3000f to 3000f, 0f to 3000f, 0f to 0f)) }
        waitFor { state().selectedTyped == setOf("r1") }
        shot("box-selected")
        // By the corner handle, far up and to the left.
        val b = region.bounds
        inject(TestMotionEvents().path(TOOL_TYPE_FINGER, listOf(b.right * density to b.bottom * density, 700f * density to 300f * density, 500f * density to 210f * density)))
        waitFor { state().page.region("r1")!!.bounds != b }
        val after = state().page.region("r1")!!.bounds
        val width = after.right - after.left
        assertEquals(300f, width, 2f)
        // It is exactly as tall as its words at that width, not as short as the drag asked.
        assertEquals(TYPED_TEXT_HEIGHT.height(region, width), after.bottom - after.top, 1f)
        assertTrue(after.bottom - after.top > 100f)
    }

    @Test
    fun aPageCanHaveItsOwnMarginAndGoBack() {
        blank()
        val booklet = area()
        assertEquals(PageMargin.NORMAL.inches * 160f, booklet.left, 0.5f)
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.page_layout)).performClick()
        composeRule.onNodeWithText(string(R.string.margin_cheat_sheet)).performScrollTo().performClick()
        waitFor { state().page.margin == PageMargin.CHEAT_SHEET }
        assertEquals(40f, area().left, 0.5f)
        shot("page-layout")
        composeRule.onNodeWithText(string(R.string.margin_booklet)).performScrollTo().performClick()
        waitFor { state().page.margin == null }
        assertEquals(booklet, area())
        // Kept with the page.
        composeRule.onNodeWithText(string(R.string.margin_wide)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.close)).performClick()
        val id = state().bookletId
        waitFor { runBlocking { services.freshRepository().loadBooklet(id) }?.pages?.single()?.margin == PageMargin.WIDE }
    }

    @Test
    fun aNewBookletAsksForItsMargin() {
        waitFor { state().libraryLoaded }
        // In the header, and again on an empty library.
        composeRule.onAllNodesWithText(string(R.string.new_booklet_button))[0].performClick()
        composeRule.onNodeWithText(string(R.string.margin_cheat_sheet)).performScrollTo().performClick()
        shot("new-booklet")
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().loaded && state().documentStyle.margin == PageMargin.CHEAT_SHEET }
        val id = state().bookletId
        assertEquals(PageMargin.CHEAT_SHEET, runBlocking { services.freshRepository().loadBooklet(id) }!!.info.style.margin)
    }

    @Test
    fun aWiderMarginBringsEveryPageInside() {
        val id = runBlocking {
            val pages = listOf(
                Page("import", regions = listOf(words("r1", "One", Bounds(150f, 200f, 500f, 260f))), nextRegionNumber = 2),
                Page("import", regions = listOf(words("r1", "Two", Bounds(150f, 200f, 500f, 260f))), nextRegionNumber = 2),
            )
            services.freshRepository().importBooklet("Wide", null, pages).id
        }
        waitFor { state().libraryLoaded }
        composeRule.runOnUiThread { viewModel().openBooklet(id); viewModel().setPenBarHidden(true) }
        waitFor { state().loaded && state().pageCount == 2 }
        composeRule.runOnUiThread { viewModel().setBookletInfo(null, state().mathAlign, style = DocumentStyle(margin = PageMargin.WIDE)) }
        waitFor { state().page.region("r1")!!.bounds.left == 200f }
        waitFor { runBlocking { services.freshRepository().loadBooklet(id) }!!.pages.all { it.region("r1")!!.bounds.left == 200f } }
    }

    private fun pdf(booklet: Booklet, name: String): Int {
        val result = runBlocking { withContext(Dispatchers.Main) { BookletPdf(composeRule.activity).render(booklet) } } as BookletPdf.Result.Written
        val file = File(composeRule.activity.getExternalFilesDir("screenshots"), "margins-$name.pdf").apply { writeBytes(result.bytes) }
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            for (i in 0 until r.pageCount) {
                val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                r.openPage(i).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                // Nothing is drawn outside the margin but the page number.
                File(composeRule.activity.getExternalFilesDir("screenshots"), "margins-$name-${i + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            return r.pageCount
        }
    }

    @Test
    fun theMarginsAreInThePdfAndAPageCanHaveItsOwn() {
        waitFor { state().libraryLoaded }
        val text = listOf(words("r1", longWords + longWords + longWords, Bounds(40f, 0f, 1200f, 300f)))
        val pages = listOf(Page("p1", regions = text, nextRegionNumber = 2), Page("p2", regions = text, nextRegionNumber = 2, margin = PageMargin.WIDE), Page("p3", regions = text, nextRegionNumber = 2))
        val booklet = Booklet("b1", "Cheat sheet", pages, BookletInfo(style = DocumentStyle(titleOnFirstPage = true, margin = PageMargin.CHEAT_SHEET, runningHeader = true)))
        assertEquals("the wide page, and the cheat-sheet pages either side", 3, pdf(booklet, "mixed"))
    }
}
