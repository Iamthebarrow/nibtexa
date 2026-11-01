package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Importing LaTeX in the app (Phase 18): Import and Export ask for the format first; a `.tex`
 * file becomes a booklet of typed regions, opened, with a report of what was left out; typed
 * regions are drawn on the page and move and delete with the lasso. A screenshot is kept.
 */
@RunWith(AndroidJUnit4::class)
class ImportTest {
    private val services = TestServicesRule(startInLibrary = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private val tex = """
        \documentclass{article}
        \title{Imported notes}
        \author{Ada}
        \begin{document}
        \section{Poles}
        A pole is where ${'$'}H(s)${'$'} is \textbf{infinite}.
        \begin{equation}
        H(s)=\frac{1}{s^2+2s+5}
        \end{equation}
        \begin{itemize}
        \item stable
        \item fast
        \end{itemize}
        \unknowncommand{kept}
        \end{document}
    """.trimIndent()

    private fun importFile(): String {
        val file = File(composeRule.activity.cacheDir, "notes.tex").apply { writeText(tex) }
        composeRule.runOnUiThread { viewModel().importFile(Uri.fromFile(file), ImportFormat.LATEX_FILE) }
        waitFor { state().screen == Screen.BOOKLET && state().page.regions.isNotEmpty() }
        return state().bookletId
    }

    @Test
    fun importAndExportAskForTheFormatFirst() {
        waitFor { state().libraryLoaded }
        composeRule.onNodeWithText(string(R.string.import_button)).performClick()
        composeRule.onNodeWithTag(FORMAT_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.format_tex)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.format_latex_zip)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        composeRule.runOnUiThread { viewModel().createBooklet("Exported") }
        waitFor { state().screen == Screen.BOOKLET && state().loaded }
        composeRule.onNodeWithText(string(R.string.export)).performClick()
        composeRule.onNodeWithText(string(R.string.format_pdf)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.format_latex_zip)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).performClick()
    }

    @Test
    fun aTexFileBecomesABookletOfTypedRegions() {
        waitFor { state().libraryLoaded }
        val id = importFile()
        assertEquals("Imported notes", state().bookletTitle)
        val regions = state().page.regionsInReadingOrder
        assertEquals(listOf(RegionRole.HEADING, RegionRole.TEXT, RegionRole.MATH, RegionRole.TEXT, RegionRole.TEXT, RegionRole.TEXT), regions.map { it.role })
        assertTrue(regions.all { it.typed && it.strokeIds.isEmpty() })
        assertEquals(true, regions[2].numbered)
        assertEquals("- stable", regions[3].text)
        // The report says what was left out, then goes.
        composeRule.onNodeWithText(string(R.string.import_dropped)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.ok)).performClick()
        waitFor { state().importReport == null }
        // Stored, with nothing waiting for recognition.
        val stored = kotlinx.coroutines.runBlocking { services.freshRepository().loadBooklet(id) }!!
        assertEquals(regions.map { it.id }, stored.pages.single().regionsInReadingOrder.map { it.id })
        assertEquals(0, state().library.firstOrNull { it.id == id }?.pending ?: 0)

        // MathLive typesets the maths on the page. Its waits run on the test's clock, so the clock moves on too.
        repeat(16) {
            composeRule.mainClock.advanceTimeBy(500)
            Thread.sleep(500)
        }
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), "imported-page.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun typedRegionsMoveAndDeleteWithTheLasso() {
        waitFor { state().libraryLoaded }
        importFile()
        composeRule.runOnUiThread { viewModel().dismissImportReport() }
        val heading = state().page.regionsInReadingOrder.first()
        val b = heading.bounds
        val loop = listOf(b.left - 10f to b.top - 10f, b.right + 10f to b.top - 10f, b.right + 10f to b.bottom + 10f, b.left - 10f to b.bottom + 10f)
        composeRule.runOnUiThread { viewModel().run { setTool(Tool.LASSO); onLasso(loop) } }
        waitFor { state().selectedTyped == setOf(heading.id) }
        composeRule.runOnUiThread { viewModel().moveSelection(50f, 20f) }
        waitFor { state().page.region(heading.id)?.bounds?.left == b.left + 50f }
        composeRule.runOnUiThread { viewModel().undo() }
        waitFor { state().page.region(heading.id)?.bounds == b }

        composeRule.runOnUiThread { viewModel().onLasso(loop) }
        waitFor { state().selectedTyped.isNotEmpty() }
        composeRule.onNodeWithText(string(R.string.delete)).performClick()
        waitFor { state().page.region(heading.id) == null }
    }
}
