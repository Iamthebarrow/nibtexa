package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.export.BookletNavigation
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Finding the way round a long booklet (Phase 28), with real taps: the contents, the search and the library's search all land
 * on the right page, with the region in view at the page's own zoom.
 */
@RunWith(AndroidJUnit4::class)
class BookletNavigationTest {
    private val services = TestServicesRule(startInLibrary = true)
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

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(500)
        val shot = instrumentation.uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun typed(id: String, role: RegionRole, y: Float, text: String, level: Int = 1) =
        Region(id, role, emptySet(), Bounds(40f, y, 500f, y + 40f), 0, RecognitionStatus.CONFIRMED, text = text, headingLevel = level, typed = true)

    /**
     * A booklet of [pages] pages: on each a heading at the top, a sub-heading low on the page, words between, an unconfirmed
     * result and scratch; so that a jump has to move the view.
     */
    private fun longBooklet(pages: Int = 40): String = runBlocking {
        val content = List(pages) { i ->
            val strokes = listOf(Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(40f, 500f, 0), InkPoint(300f, 520f, 8)), Pen.TEXT), Stroke(2, ToolType.STYLUS, 0, listOf(InkPoint(40f, 600f, 0), InkPoint(300f, 620f, 8)), Pen.SCRATCH))
            Page(
                "import",
                InkDocument(strokes, 3),
                listOf(
                    typed("r1", RegionRole.HEADING, 40f, "Chapter ${i + 1}"),
                    typed("r2", RegionRole.TEXT, 200f, "Words on page ${i + 1} about topic${i + 1}."),
                    Region("r3", RegionRole.TEXT, setOf(1), Bounds(40f, 500f, 300f, 520f), 0, RecognitionStatus.RECOGNIZED, setOf(1), recognizedText = "waiting result$i"),
                    Region("r4", RegionRole.EXCLUDED, setOf(2), Bounds(40f, 600f, 300f, 620f), 0, RecognitionStatus.RECOGNIZED, setOf(2), recognizedText = "scratch thought$i", auto = true),
                    typed("r5", RegionRole.HEADING, 1500f, "Section ${i + 1}.1", level = 2),
                ).mapIndexed { n, r -> r.copy(readingOrder = n + 1) },
                nextRegionNumber = 6,
            )
        }
        services.freshRepository().importBooklet("Long notes", null, content).id
    }

    private fun open(id: String, hit: io.github.iamthebarrow.nibtexa.storage.SearchHit? = null) {
        composeRule.runOnUiThread { viewModel().openBooklet(id, hit); viewModel().setPenBarHidden(true) }
        waitFor { state().loaded && state().bookletId == id }
    }

    private fun overview(tab: Int) {
        composeRule.onNodeWithText(string(R.string.page_position, state().pageIndex + 1, state().pageCount), useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(PAGES_TAG).assertIsDisplayed()
        composeRule.onNode(hasText(string(tab)) and hasAnyAncestor(hasTestTag(PAGES_TAG))).performClick()
    }

    /** The region is on the page area, whose size the screen reported: all of it, or its start if it is wider than that. */
    private fun assertInView(id: String) {
        val view = state().page.view ?: PageView()
        val surface = composeRule.activity.window.decorView.findViewWithTag<android.view.View>(io.github.iamthebarrow.nibtexa.ink.InkSurfaceView.TAG)
        val density = composeRule.activity.resources.displayMetrics.density
        val width = surface.width / density / view.scale
        val height = surface.height / density / view.scale
        val b = state().page.region(id)!!.bounds
        assertTrue("$b in $view $width x $height", b.left >= view.left - 1f && (b.right <= view.left + width + 1f || b.right - b.left >= width) && b.top >= view.top - 1f && b.bottom <= view.top + height + 1f)
    }

    @Test
    fun theContentsListEveryHeadingAndJumpInView() {
        val id = longBooklet()
        open(id)
        waitFor { state().pageCount == 40 && state().screen == Screen.BOOKLET }
        overview(R.string.contents_title)
        composeRule.onNodeWithTag(CONTENTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Chapter 1").assertIsDisplayed()
        composeRule.onNodeWithText("Section 1.1").assertIsDisplayed()
        screenshot("navigation-contents.png")

        // Low on page 2, out of view at 100%.
        composeRule.onNodeWithText("Section 2.1").performClick()
        waitFor { state().pageIndex == 1 && state().highlightRegion == "r5" }
        assertEquals("r5", state().highlightRegion)
        composeRule.waitForIdle()
        assertInView("r5")
        screenshot("navigation-jump.png")
        // The mark goes by itself.
        waitFor { state().highlightRegion == null }

        // The order is the exports' order: heading then sub-heading, page by page.
        val contents = BookletNavigation.contents(viewModel().openBookletNow(state()))
        assertEquals((1..40).flatMap { listOf("Chapter $it", "Section $it.1") }, contents.map { it.title })
    }

    @Test
    fun aJumpKeepsThePagesZoomAndSurvivesARecreation() {
        val id = longBooklet(3)
        open(id)
        waitFor { state().pageCount == 3 }
        // Page 2, zoomed in and away from its sub-heading.
        composeRule.runOnUiThread { viewModel().goToPage(1) }
        waitFor { state().pageIndex == 1 }
        composeRule.runOnUiThread { viewModel().onView(PageView(2f, 0f, 0f), final = true) }
        waitFor { state().page.view?.scale == 2f }
        composeRule.runOnUiThread { viewModel().goToPage(0) }
        waitFor { state().pageIndex == 0 }

        composeRule.runOnUiThread { viewModel().goToRegion(1, "r5") }
        waitFor { state().pageIndex == 1 && state().page.view?.top != 0f }
        assertEquals(2f, state().page.view!!.scale, 0.001f)
        composeRule.waitForIdle()
        assertInView("r5")

        // What's already in view doesn't move.
        val shown = state().page.view
        composeRule.runOnUiThread { viewModel().goToRegion(1, "r5") }
        composeRule.waitForIdle()
        assertEquals(shown, state().page.view)

        // Kept for the next time the page opens.
        val pageId = state().page.id
        waitFor { runBlocking { services.freshRepository().loadBooklet(id) }!!.pages.first { it.id == pageId }.view?.top == shown!!.top }
    }

    @Test
    fun searchFindsThroughThePagesAndMarksWhatIsNotConfirmed() {
        val id = longBooklet()
        open(id)
        waitFor { state().pageCount == 40 }
        overview(R.string.find_title)
        val field = composeRule.onNodeWithTag(FIND_FIELD_TAG)
        composeRule.onNodeWithText(string(R.string.find_prompt)).assertIsDisplayed()

        // Any case, through the pages.
        field.performTextInput("TOPIC37")
        waitFor { composeRule.onAllNodes(hasText("topic37", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        screenshot("navigation-search.png")

        // Unconfirmed and scratch say so.
        field.performTextReplacement("result5")
        waitFor { composeRule.onAllNodes(hasText("waiting result5")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(string(R.string.find_page, 6) + " · " + string(R.string.find_unconfirmed)).assertIsDisplayed()
        field.performTextReplacement("thought5")
        waitFor { composeRule.onAllNodes(hasText("scratch thought5")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(string(R.string.find_page, 6) + " · " + string(R.string.find_scratch)).assertIsDisplayed()
        screenshot("navigation-search-marked.png")

        // Nothing found says what can be found. Special characters are only characters.
        field.performTextReplacement(".*")
        waitFor { composeRule.onAllNodes(hasText(string(R.string.find_empty))).fetchSemanticsNodes().isNotEmpty() }

        // A wide search says it stopped.
        field.performTextReplacement("Words")
        waitFor { composeRule.onAllNodes(hasText("Words on page 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Tapping a result goes there, and marks it: scratch too, which has no mark of its own.
        field.performTextReplacement("thought7")
        waitFor { composeRule.onAllNodes(hasText("scratch thought7")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("scratch thought7").performClick()
        waitFor { state().pageIndex == 7 && state().highlightRegion == "r4" }
        composeRule.waitForIdle()
        assertInView("r4")
        screenshot("navigation-search-jump.png")
    }

    @Test
    fun aHeadingsTabWithNoneSaysSo() {
        val id = runBlocking { services.freshRepository().createBooklet("Empty").id }
        open(id)
        waitFor { state().pageCount == 1 && state().bookletId == id }
        overview(R.string.contents_title)
        composeRule.onNodeWithText(string(R.string.contents_empty)).assertIsDisplayed()
        // The pages tab is still there, with its buttons.
        composeRule.onNode(hasText(string(R.string.pages_title)) and hasAnyAncestor(hasTestTag(PAGES_TAG))).performClick()
        composeRule.onNodeWithText(string(R.string.add_page)).assertIsDisplayed()
    }

    @Test
    fun searchSeesEditsNotSavedYet() {
        val id = longBooklet(2)
        open(id)
        waitFor { state().pageCount == 2 }
        val booklet = viewModel().openBookletNow(state())
        assertEquals(2, booklet.pages.size)
        composeRule.runOnUiThread { viewModel().setCaption("r1", "ignored") ; viewModel().confirm("r2", "A freshly changed sentence") }
        waitFor { state().page.region("r2")?.text == "A freshly changed sentence" }
        val found = BookletNavigation.search(viewModel().openBookletNow(state()).pages, "freshly")
        assertEquals(listOf(0 to "r2"), found.map { it.pageIndex to it.regionId })
    }

    @Test
    fun aLibrarySearchResultOpensOnTheMatchingPage() {
        val id = longBooklet(12)
        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().library.size == 1 }
        composeRule.runOnUiThread { viewModel().setSearch("topic9") }
        waitFor { state().searchResults?.size == 1 && state().searchSnippets[id] != null }
        val hit = state().searchSnippets.getValue(id)
        assertEquals("r2", hit.regionId)
        composeRule.onNodeWithText("Long notes").performClick()
        waitFor { state().screen == Screen.BOOKLET && state().bookletId == id && state().pageIndex == 8 }
        assertEquals("r2", state().highlightRegion)
        assertNotEquals(null, state().page.region("r2"))
        composeRule.waitForIdle()
        assertInView("r2")
        screenshot("navigation-library-result.png")

        // A match on the title alone opens at the start, marking nothing.
        composeRule.runOnUiThread { viewModel().closeBooklet(); viewModel().setSearch("Long notes") }
        waitFor { state().screen == Screen.LIBRARY && state().searchResults?.size == 1 && state().searchSnippets[id] == null }
        assertNull(state().searchSnippets[id])
    }

    @Test
    fun aLongBookletStaysResponsive() {
        val id = longBooklet(200)
        open(id)
        waitFor(30_000) { state().pageCount == 200 }
        val booklet = viewModel().openBookletNow(state())
        repeat(2) { BookletNavigation.contents(booklet); BookletNavigation.search(booklet.pages, "absent") }
        var start = System.nanoTime()
        assertEquals(400, BookletNavigation.contents(booklet).size)
        val contents = (System.nanoTime() - start) / 1_000_000
        start = System.nanoTime()
        assertTrue(BookletNavigation.search(booklet.pages, "absent").isEmpty())
        val search = (System.nanoTime() - start) / 1_000_000
        android.util.Log.i("BookletNavigationTest", "200 pages: contents $contents ms, search $search ms")
        assertTrue("$contents ms", contents < 2000)
        assertTrue("$search ms", search < 500)
        overview(R.string.contents_title)
        composeRule.onNodeWithText("Chapter 1").assertIsDisplayed()
    }
}
