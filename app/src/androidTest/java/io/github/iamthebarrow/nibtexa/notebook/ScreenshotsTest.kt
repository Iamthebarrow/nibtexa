package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.Paper
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.ui.theme.NibTeXaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Screenshots of the redesigned screens (Phase 13), light and dark, for review. It asserts
 * nothing beyond the screens appearing; the PNGs go to the app's `screenshots/` folder. Sample
 * booklets are written into the test's own database, never the user's.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotsTest {
    private val services = TestServicesRule(startInLibrary = true)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(600) // let the ink surface and any slide-in settle
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(composeRule.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A handwritten-looking "word": a few joined wavy strokes in a w x h box at (x, y), in dp. */
    private fun PageEditor.word(x: Float, y: Float, w: Float, h: Float, pen: Pen, seed: Int) {
        val parts = 2 + seed % 2
        val partWidth = w / parts
        repeat(parts) { part ->
            val left = x + part * partWidth
            val points = (0..24).map { i ->
                val t = i / 24f
                val wave = sin((t * 9f + seed + part) * 1.3f) * 0.42f + sin(t * 23f + seed) * 0.12f
                InkPoint(left + t * (partWidth - 6f), y + h * (0.5f + wave), i * 8L)
            }
            addStroke(CapturedStroke(ToolType.STYLUS, (seed * 100 + part).toLong(), points, pen))
        }
    }

    private fun PageEditor.line(x: Float, y: Float, words: List<Float>, h: Float, pen: Pen, seed: Int): Set<Long> {
        val first = page.ink.nextStrokeId
        var left = x
        words.forEachIndexed { i, w -> word(left, y, w, h, pen, seed + i); left += w + h * 0.6f }
        return (first until page.ink.nextStrokeId).toSet()
    }

    private fun PageEditor.box(x: Float, y: Float, w: Float, h: Float): Set<Long> {
        val first = page.ink.nextStrokeId
        val pts = listOf(x to y, x + w to y + 2, x + w - 2 to y + h, x + 1 to y + h - 2, x to y)
        addStroke(CapturedStroke(ToolType.STYLUS, 900, pts.mapIndexed { i, (px, py) -> InkPoint(px, py, i * 30L) }, Pen.DRAWING))
        addStroke(CapturedStroke(ToolType.STYLUS, 901, listOf(InkPoint(x - 90, y + h / 2, 0), InkPoint(x - 4, y + h / 2, 60)), Pen.DRAWING))
        addStroke(CapturedStroke(ToolType.STYLUS, 902, listOf(InkPoint(x + w + 4, y + h / 2, 0), InkPoint(x + w + 110, y + h / 2, 60)), Pen.DRAWING))
        addStroke(CapturedStroke(ToolType.STYLUS, 903, listOf(InkPoint(x + w + 70, y + h / 2, 0), InkPoint(x + w + 70, y + h + 50, 40), InkPoint(x - 50, y + h + 50, 90), InkPoint(x - 50, y + h / 2 + 6, 130)), Pen.DRAWING))
        return (first until page.ink.nextStrokeId).toSet()
    }

    /** Six booklets; the first has a page with a heading, text, math waiting and a figure. */
    private fun populate(): String = runBlocking {
        val repository = services.freshRepository()
        val titles = listOf("Probability", "Lab notebook", "Circuit analysis", "Linear algebra", "Control theory", "Signals & Systems")
        var signals = ""
        titles.forEachIndexed { i, title ->
            val booklet = repository.createBooklet(title, if (i % 2 == 0) Paper.RULED else Paper.GRID, cover = (i + 1) % 6)
            val e = PageEditor(booklet.pages.single())
            if (title == "Signals & Systems") {
                val heading = e.line(120f, 90f, listOf(110f, 40f, 170f, 150f), 34f, Pen.TEXT, 3)
                val text = e.line(120f, 170f, listOf(60f, 90f, 70f, 40f, 110f, 80f), 26f, Pen.TEXT, 7) + e.line(120f, 214f, listOf(50f, 120f, 70f, 140f), 26f, Pen.TEXT, 11)
                val math = e.line(140f, 300f, listOf(70f, 30f, 180f), 44f, Pen.MATH, 17)
                val figure = e.box(260f, 430f, 150f, 80f)
                e.line(640f, 470f, listOf(40f, 60f, 70f), 22f, Pen.SCRATCH, 23)
                val h = e.createRegion(heading, RegionRole.HEADING)!!
                e.startRecognition(h)?.let { e.applyResult(it, "Week 3 — Transfer functions") }
                val t = e.createRegion(text, RegionRole.TEXT)!!
                e.startRecognition(t)?.let { e.applyResult(it, "The closed loop has a pair of complex poles, so the step response overshoots, then settles.") }
                val m = e.createRegion(math, RegionRole.MATH)!!
                e.startRecognition(m)?.let { e.applyResult(it, """H(s)=\frac{\omega_n^2}{s^2+2\zeta\omega_n s+\omega_n^2}""") }
                e.setCaption(e.createRegion(figure, RegionRole.FIGURE)!!, "Closed loop with unity feedback")
                e.orderByPosition()
                signals = booklet.id
            } else {
                repeat(5) { row -> e.line(60f, 70f + row * 64f, listOf(80f, 120f, 60f, 100f).take(2 + (row + i) % 3), 26f, if (row == 2) Pen.MATH else Pen.TEXT, i * 5 + row) }
            }
            repository.savePage(booklet.id, 0, e.page)
        }
        signals
    }

    @Test
    fun takeScreenshots() {
        waitFor { state().libraryLoaded }
        val signals = populate()
        composeRule.runOnUiThread { viewModel().closeBooklet() } // reloads the library
        waitFor { state().library.size == 6 && state().thumbnails.size == 6 }
        shot("1-library-light")

        composeRule.runOnUiThread { viewModel().openBooklet(signals) }
        waitFor { state().screen == Screen.BOOKLET && state().loaded }
        shot("2-booklet-light")

        val figure = state().page.regions.single { it.role == RegionRole.FIGURE }.bounds
        composeRule.runOnUiThread {
            viewModel().run {
                setTool(Tool.LASSO)
                onLasso(listOf(figure.left - 120 to figure.top - 20, figure.right + 130 to figure.top - 20, figure.right + 130 to figure.bottom + 70, figure.left - 120 to figure.bottom + 70, figure.left - 120 to figure.top - 19))
                setResultsOpen(true)
            }
        }
        waitFor { state().selection.isNotEmpty() && state().resultsOpen }
        shot("3-booklet-selection-results-light")

        // Dark: the same state, themed dark in the test (the emulator's own setting is untouched).
        composeRule.runOnUiThread {
            composeRule.activity.setContent { NibTeXaTheme(darkTheme = true) { NotebookScreen() } }
        }
        shot("5-booklet-selection-results-dark")
        composeRule.runOnUiThread { viewModel().run { setResultsOpen(false); setTool(Tool.MATH_PEN); closeBooklet() } }
        waitFor { state().screen == Screen.LIBRARY }
        shot("4-library-dark")
    }
}
