package io.github.iamthebarrow.nibtexa.notebook

import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
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

/**
 * The library home screen (Phase 12): the app opens on it, a new booklet is created with its
 * paper and opened, Back returns, and rename, duplicate, delete and the page strip work through
 * the real screen.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {
    private val services = TestServicesRule(startInLibrary = true)
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

    private fun card(title: String) = composeRule.onNode(hasContentDescription(title, substring = true) and hasContentDescription("page", substring = true))

    private fun newBooklet(title: String, paper: Paper) {
        composeRule.onAllNodesWithText(string(R.string.new_booklet_button)).onFirst().performClick() // an empty library shows it twice
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.booklet_title))).performTextInput(title) // the dialog, not the search field
        composeRule.onNodeWithText(composeRule.activity.getString(if (paper == Paper.GRID) R.string.paper_grid else R.string.paper_ruled)).performClick()
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
    }

    @Test
    fun theAppOpensOnTheLibraryAndANewBookletOpensWithItsPaper() {
        waitFor { state().libraryLoaded }
        assertEquals(Screen.LIBRARY, state().screen)
        composeRule.onNodeWithText(string(R.string.library_empty)).assertIsDisplayed()
        assertNull(composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG))

        newBooklet("Signals", Paper.GRID)
        waitFor { state().screen == Screen.BOOKLET && state().loaded }
        assertEquals("Signals", state().bookletTitle)
        assertEquals(Paper.GRID, state().page.paper)
        composeRule.onNodeWithTag(PEN_BAR_TAG).assertIsDisplayed()

        // Write, then go back: the card shows the booklet, edited just now.
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().line(TOOL_TYPE_STYLUS, 200f to 200f, 300f to 260f).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == 1 }
        Espresso.pressBack()
        waitFor { state().screen == Screen.LIBRARY && state().library.size == 1 }
        waitFor { state().thumbnails[state().library.single().id]?.ink?.strokes?.size == 1 }
        card("Signals").assertIsDisplayed()

        // Tapping the card opens it as it was left.
        card("Signals").performClick()
        waitFor { state().screen == Screen.BOOKLET }
        assertEquals(1, state().page.ink.strokes.size)
    }

    @Test
    fun renameDuplicateAndDeleteFromTheCardMenu() {
        waitFor { state().libraryLoaded }
        newBooklet("Week 1", Paper.RULED)
        waitFor { state().screen == Screen.BOOKLET }
        composeRule.onNodeWithContentDescription(string(R.string.back_to_library)).performClick()
        waitFor { state().screen == Screen.LIBRARY && state().library.size == 1 }

        card("Week 1").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.rename_booklet)).performClick()
        composeRule.onNode(hasSetTextAction() and hasText(string(R.string.booklet_title))).performTextReplacement("Week 1 notes")
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().library.singleOrNull()?.title == "Week 1 notes" }

        card("Week 1 notes").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.duplicate)).performClick()
        waitFor { state().library.map { it.title }.toSet() == setOf("Week 1 notes", string(R.string.booklet_copy_title, "Week 1 notes")) }

        val original = state().library.single { it.title == "Week 1 notes" }.id
        card(string(R.string.booklet_copy_title, "Week 1 notes")).assertIsDisplayed()
        composeRule.onAllNodesWithText("Week 1 notes", substring = true).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        card("Week 1 notes,").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        // It goes to the Trash (Phase 21), so it can come back and nothing needs asking.
        waitFor { state().library.size == 1 && state().trash.size == 1 }
        assertEquals(string(R.string.booklet_copy_title, "Week 1 notes"), state().library.single().title)
        assertEquals(original, state().trash.single().id)
    }

    @Test
    fun thePageStripAddsAndSwitchesPages() {
        waitFor { state().libraryLoaded }
        newBooklet("Pages", Paper.RULED)
        waitFor { state().screen == Screen.BOOKLET && state().loaded }

        composeRule.onNodeWithText(string(R.string.page_position, 1, 1)).performClick() // opens the page overview (Phase 13)
        composeRule.onNodeWithText(string(R.string.add_page)).performClick()
        waitFor { state().pageCount == 2 && state().pageIndex == 1 }
        assertEquals(2, state().pages.size)

        composeRule.onNodeWithText(string(R.string.page_position, 2, 2)).performClick()
        composeRule.onNode(hasContentDescription(string(R.string.page_thumbnail, 1))).performClick()
        waitFor { state().pageIndex == 0 }
        composeRule.onNodeWithText(string(R.string.page_position, 1, 2)).assertIsDisplayed()
    }

    @Test
    fun searchFiltersTheLibraryAndTheCoverColourCanChange() {
        waitFor { state().libraryLoaded }
        newBooklet("Signals", Paper.RULED)
        waitFor { state().screen == Screen.BOOKLET }
        composeRule.onNodeWithContentDescription(string(R.string.back_to_library)).performClick()
        newBooklet("Control", Paper.GRID)
        waitFor { state().screen == Screen.BOOKLET }
        composeRule.onNodeWithContentDescription(string(R.string.back_to_library)).performClick()
        waitFor { state().library.size == 2 }

        composeRule.onNodeWithTag(SEARCH_TAG).performTextInput("sig")
        waitFor { state().searchResults?.map { it.title } == listOf("Signals") }
        card("Signals").assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("Control", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        composeRule.onNodeWithContentDescription(string(R.string.clear_search)).performClick()
        waitFor { state().searchResults == null }

        val before = state().library.single { it.title == "Signals" }.cover
        card("Signals").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.cover_colour)).performClick()
        val next = (before + 1) % 6
        composeRule.onNode(hasContentDescription(string(R.string.cover_swatch, next + 1))).performClick()
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().library.single { it.title == "Signals" }.cover == next }
    }

    @Test
    fun developmentToolsAreInNoMenu() {
        waitFor { state().libraryLoaded }
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        composeRule.onNodeWithText(string(R.string.settings)).performClick()
        composeRule.onNodeWithText(string(R.string.licences_title)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(string(R.string.corpus_capture)).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText(string(R.string.text_samples)).fetchSemanticsNodes().isEmpty())
    }
}
