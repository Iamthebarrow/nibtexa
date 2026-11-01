package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.RecognitionStatus
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 29: a booklet as a user finds it, pages opening at Fit width and the pen bar open at the top. The writing at the top of
 * the page starts clear of the bar. Each screen is kept as a screenshot to look at, in the app's current theme.
 */
@RunWith(AndroidJUnit4::class)
class WalkthroughTest {
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
        Thread.sleep(500)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val wide = image.width > image.height
        File(composeRule.activity.getExternalFilesDir("screenshots"), "walk-${if (wide) "landscape" else "portrait"}-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun booklet(): String = runBlocking {
        val words = "Poles in the left half-plane: stable."
        val at = words.indexOf("stable")
        val page = Page(
            "import",
            regions = listOf(
                Region("r1", RegionRole.HEADING, emptySet(), Bounds(40f, 10f, 700f, 50f), 1, RecognitionStatus.CONFIRMED, text = "Heading at the very top", headingLevel = 1, typed = true),
                Region(
                    "r2", RegionRole.TEXT, emptySet(), Bounds(40f, 100f, 700f, 140f), 2, RecognitionStatus.CONFIRMED, text = words, typed = true,
                    marks = listOf(TextMark(at, at + 6, MarkStyle.Color(TextColor.BLUE))),
                ),
            ),
            nextRegionNumber = 3,
        )
        services.freshRepository().importBooklet("Walkthrough", null, listOf(page)).id
    }

    @Test
    fun aBookletFromOpeningItToItsMenus() {
        val id = booklet()
        waitFor { state().libraryLoaded }
        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        shot("library-menu")
        composeRule.onNodeWithText(string(R.string.settings)).performClick()
        shot("settings")
        composeRule.onNodeWithContentDescription(string(R.string.close)).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            viewModel().openBooklet(id)
            viewModel().setPenBarHidden(false)
            viewModel().setPenDock(PenDock.TOP_CENTER)
        }
        waitFor { state().loaded && state().bookletId == id && state().page.view != null }
        Thread.sleep(700) // the bar settles at its dock
        shot("booklet-open")

        // The heading is below the pen bar without moving anything.
        val bar = composeRule.onNodeWithTag(PEN_BAR_TAG).getUnclippedBoundsInRoot()
        val density = composeRule.activity.resources.displayMetrics.density
        val surface = IntArray(2)
        composeRule.runOnUiThread { composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG).getLocationInWindow(surface) }
        val view = state().page.view!!
        val heading = surface[1] / density + (10f - view.top) * view.scale
        assertTrue("heading at $heading, bar to ${bar.bottom}", heading.dp >= bar.bottom)

        composeRule.onNodeWithContentDescription(string(R.string.more)).performClick()
        shot("booklet-menu")
        composeRule.onNodeWithText(string(R.string.clipboard)).performClick()
        shot("clipboard")
        composeRule.runOnUiThread { viewModel().setClipboardOpen(false) }

        composeRule.runOnUiThread { viewModel().setReadingMode(true) }
        shot("reading")
    }
}
