package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.ColumnMode
import io.github.iamthebarrow.nibtexa.core.model.notebook.DocumentStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.export.BookletPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 30 in the app: a page written in two columns reads and exports as two columns, the choice in the menu overrides it, and
 * the document settings are kept. Each result is kept as a picture to look at.
 */
@RunWith(AndroidJUnit4::class)
class DocumentLayoutTest {
    private val services = TestServicesRule(startInLibrary = true, openAtFitWidth = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(800)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "layout-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private var order = 0
    private val words = "A system's transfer function is the ratio of the Laplace transforms of its output and input, with every initial condition at zero. "

    private fun text(id: String, value: String, left: Float, top: Float, right: Float, bottom: Float, theorem: TheoremKind? = null) =
        Region(id, RegionRole.TEXT, emptySet(), Bounds(left, top, right, bottom), ++order, RecognitionStatus.CONFIRMED, text = value, typed = true, newParagraph = true, theorem = theorem)

    private fun heading(id: String, value: String, level: Int, left: Float, top: Float, right: Float) =
        Region(id, RegionRole.HEADING, emptySet(), Bounds(left, top, right, top + 40f), ++order, RecognitionStatus.CONFIRMED, text = value, headingLevel = level, typed = true, newParagraph = true)

    private fun math(id: String, latex: String, left: Float, top: Float, right: Float, bottom: Float) =
        Region(id, RegionRole.MATH, emptySet(), Bounds(left, top, right, bottom), ++order, RecognitionStatus.CONFIRMED, confirmedLatex = latex, typed = true, inline = false)

    /** As the user laid out their test page: columns that touch, either side of a wide equation. */
    private fun columnsPage() = Page(
        "import",
        regions = listOf(
            text("abs", words, 40f, 0f, 1240f, 70f, TheoremKind.ABSTRACT),
            heading("h1", "Transfer functions", 1, 40f, 80f, 640f),
            text("sys", words, 40f, 130f, 640f, 230f),
            text("pole", "A pole of \$H(s)\$ is a root of its denominator.", 640f, 135f, 1240f, 225f),
            math("eq", "H(s)=\\frac{\\omega_n^2}{s^2+2\\zeta\\omega_n s+\\omega_n^2}", 400f, 240f, 880f, 320f),
            heading("h2", "What the poles tell us", 2, 40f, 330f, 640f),
            text("proof", words, 640f, 330f, 1240f, 430f),
            text("one", "1. a single pole at \$s=0\$ is an integrator;", 40f, 380f, 640f, 430f),
            text("two", "2. a pair at \$\\pm j\\omega\$ oscillates.", 40f, 440f, 640f, 490f),
            text("three", "3. any in the right half-plane is unstable.", 40f, 500f, 640f, 550f),
            math("zeta", "\\zeta = 0.5", 640f, 445f, 1240f, 495f),
            text("see", "See the article for more.", 640f, 510f, 1240f, 550f),
        ),
        nextRegionNumber = 20,
    )

    private val style = DocumentStyle(titleOnFirstPage = true, numberedSections = true, runningHeader = true)

    private fun seed(): String = runBlocking {
        services.freshRepository().importBooklet("Signals and Systems: Week 3", null, listOf(columnsPage())).id
    }

    private fun open(id: String) {
        composeRule.runOnUiThread { viewModel().openBooklet(id); viewModel().setPenBarHidden(true) }
        waitFor { state().loaded && state().bookletId == id && state().page.regions.isNotEmpty() }
    }

    @Test
    fun aPageWrittenInColumnsReadsInColumnsUntilToldOtherwise() {
        val id = seed()
        waitFor { state().libraryLoaded }
        open(id)
        Thread.sleep(2000) // the equations on the page
        shot("page")
        composeRule.runOnUiThread { viewModel().setReadingMode(true) }
        // Two parts in columns, either side of the equation.
        waitFor { composeRule.onAllNodesWithTag(READING_COLUMNS_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        composeRule.onNodeWithText("See the article for more.").assertIsDisplayed()
        // The inline maths in both columns is drawn, in the line; capturing it runs on the test clock, which waiting advances.
        waitFor(60_000) { composeRule.onAllNodesWithTag(INLINE_MATH_TAG, useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
        Thread.sleep(1500) // the equations
        shot("reading")
        composeRule.runOnUiThread { viewModel().setReadingMode(false) }
        waitFor { !state().readingMode }

        // The menu says otherwise: one column.
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.page_layout)).performClick()
        composeRule.onNodeWithText(string(R.string.columns_auto)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.columns_one)).performClick()
        waitFor { state().page.columns == ColumnMode.ONE }
        composeRule.onNodeWithText(string(R.string.close)).performClick()
        composeRule.runOnUiThread { viewModel().setReadingMode(true) }
        // The list only shows what fits, so it looks for what is at the top.
        waitFor { runCatching { composeRule.onNodeWithText("Transfer functions").assertExists() }.isSuccess }
        composeRule.onNodeWithTag(READING_COLUMNS_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.runOnUiThread { viewModel().setReadingMode(false) }
        waitFor { !state().readingMode }

        // It is kept with the page.
        val stored = waitForStored(id) { it.pages.single().columns == ColumnMode.ONE }
        assertEquals(ColumnMode.ONE, stored.pages.single().columns)
    }

    private fun waitForStored(id: String, condition: (Booklet) -> Boolean): Booklet {
        var booklet: Booklet? = null
        waitFor { booklet = runBlocking { services.freshRepository().loadBooklet(id) }; booklet?.let(condition) == true }
        return booklet!!
    }

    @Test
    fun theDocumentSettingsAreChosenAndKept() {
        val id = seed()
        waitFor { state().libraryLoaded }
        open(id)
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.cover_layout)).performClick()
        composeRule.onNodeWithText(string(R.string.style_title_on_page)).performClick()
        composeRule.onNodeWithText(string(R.string.style_numbered)).performClick()
        composeRule.onNodeWithText(string(R.string.style_header)).performClick()
        shot("settings")
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().documentStyle == style }
        assertEquals(style, waitForStored(id) { it.info.style == style }.info.style)
        // The reading view numbers the sections.
        composeRule.runOnUiThread { viewModel().setReadingMode(true) }
        waitFor { runCatching { composeRule.onNodeWithText("1  Transfer functions", substring = true).assertExists() }.isSuccess }
    }

    private fun pdf(booklet: Booklet, name: String): Int {
        val result = runBlocking { withContext(Dispatchers.Main) { BookletPdf(composeRule.activity).render(booklet) } } as BookletPdf.Result.Written
        val file = File(composeRule.activity.getExternalFilesDir("screenshots"), "layout-$name.pdf").apply { writeBytes(result.bytes) }
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            for (i in 0 until r.pageCount) {
                val bitmap = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                r.openPage(i).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                File(composeRule.activity.getExternalFilesDir("screenshots"), "layout-$name-${i + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            return r.pageCount
        }
    }

    @Test
    fun thePdfIsInColumnsWithItsTitleAndHeader() {
        waitFor { state().libraryLoaded }
        val page = columnsPage().copy(id = "p1")
        val booklet = Booklet("b1", "Signals and Systems: Week 3", listOf(page), BookletInfo(author = "A. Writer", style = style))
        assertEquals("one page: the title block replaces the cover", 1, pdf(booklet, "pdf"))
    }

    /** Columns too long for a page: each runs on to the next, and what comes after them starts below the longer. */
    @Test
    fun longColumnsRunOnToTheNextPage() {
        waitFor { state().libraryLoaded }
        var y = 0f
        val regions = buildList {
            for (i in 0 until 30) {
                add(text("l$i", "Left $i. $words$words", 40f, y, 640f, y + 100f))
                add(text("r$i", "Right $i. $words", 640f, y + 5f, 1240f, y + 95f))
                y += 110f
            }
            add(text("after", "After the columns, across the whole page. $words$words", 40f, y, 1240f, y + 60f))
        }
        val booklet = Booklet("b1", "Long", listOf(Page("p1", regions = regions, nextRegionNumber = 99, columns = ColumnMode.TWO)), BookletInfo(style = style))
        assertTrue(io.github.iamthebarrow.nibtexa.core.export.Flow.of(booklet).single().contains(io.github.iamthebarrow.nibtexa.core.export.FlowBlock.ColumnsStart))
        val pages = pdf(booklet, "long")
        assertTrue("$pages pages", pages >= 2)
    }

    @Test
    fun aPdfWithoutTheSettingsIsAsItWas() {
        waitFor { state().libraryLoaded }
        val booklet = Booklet("b1", "Plain", listOf(columnsPage().copy(id = "p1", columns = ColumnMode.ONE)), BookletInfo(author = "A. Writer"))
        // The cover page, the contents, then the notes.
        assertTrue(pdf(booklet, "plain") >= 3)
    }
}
