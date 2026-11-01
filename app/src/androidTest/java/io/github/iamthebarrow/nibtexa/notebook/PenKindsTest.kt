package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.R
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.Region
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.export.AnnotatedPdf
import io.github.iamthebarrow.nibtexa.export.InkFigureRenderer
import io.github.iamthebarrow.nibtexa.ink.InkColors
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
import java.io.File

/**
 * Phase 22 in the app: the pen bar chooses ballpoint, gel or felt per role, each stroke keeps the kind it
 * was written with, and every drawing of it shows the difference. Screenshots of all three are kept.
 */
@RunWith(AndroidJUnit4::class)
class PenKindsTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun string(id: Int) = composeRule.activity.getString(id)
    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun write(y: Float) {
        val before = state().page.ink.strokes.size
        composeRule.runOnUiThread {
            val view = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
            TestMotionEvents().path(TOOL_TYPE_STYLUS, listOf(200f to y, 500f to y + 60f, 800f to y - 40f, 1100f to y + 30f)).forEach { view.dispatchTouchEvent(it); it.recycle() }
        }
        waitFor { state().page.ink.strokes.size == before + 1 }
    }

    private fun choose(kind: Int) {
        composeRule.onNodeWithText(string(kindLabel(state().kindOf(state().tool.pen!!)))).performClick()
        composeRule.onNode(hasText(string(kind)) and hasAnyAncestor(hasTestTag(PEN_KIND_MENU_TAG))).performClick()
    }

    private fun kindLabel(kind: PenKind) = when (kind) {
        PenKind.BALLPOINT -> R.string.pen_kind_ballpoint
        PenKind.GEL -> R.string.pen_kind_gel
        PenKind.FELT -> R.string.pen_kind_felt
    }

    private fun screenshot(name: String) {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** How many dark pixels a bitmap on white has. */
    private fun ink(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.red(it) < 200 || Color.green(it) < 200 || Color.blue(it) < 200 }
    }

    @Test
    fun eachRoleRemembersItsKindAndStrokesKeepThem() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false); viewModel().setTool(Tool.MATH_PEN) }
        // Everything starts as gel, as before.
        assertEquals(PenKind.GEL, state().kindOf(Pen.MATH))
        write(300f)

        choose(R.string.pen_kind_felt)
        waitFor { state().kindOf(Pen.MATH) == PenKind.FELT }
        write(450f)
        composeRule.runOnUiThread { viewModel().setTool(Tool.TEXT_PEN) }
        // Another role keeps its own.
        assertEquals(PenKind.GEL, state().kindOf(Pen.TEXT))
        waitFor { state().tool == Tool.TEXT_PEN }
        choose(R.string.pen_kind_ballpoint)
        waitFor { state().kindOf(Pen.TEXT) == PenKind.BALLPOINT }
        write(600f)
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN) }
        assertEquals(PenKind.FELT, state().kindOf(Pen.MATH))
        waitFor { state().tool == Tool.MATH_PEN }
        write(750f)

        assertEquals(listOf(PenKind.GEL, PenKind.FELT, PenKind.BALLPOINT, PenKind.FELT), state().page.ink.strokes.map { it.kind })
        // On disk, as a new process would read them; and the first stroke hasn't changed since its pen did.
        waitFor { state().savedVersion >= state().version }
        val stored = runBlocking { services.freshRepository().loadBooklet(state().bookletId)!! }.pages.single().ink.strokes
        assertEquals(listOf(PenKind.GEL, PenKind.FELT, PenKind.BALLPOINT, PenKind.FELT), stored.map { it.kind })
        // Remembered: the next view model starts with them.
        val prefs = composeRule.activity.getSharedPreferences("notebook", android.content.Context.MODE_PRIVATE)
        assertEquals("FELT", prefs.getString("pen_kind_MATH", null))
        assertEquals("BALLPOINT", prefs.getString("pen_kind_TEXT", null))
        Thread.sleep(600)
        screenshot("pen-kinds-page.png")
    }

    @Test
    fun theMenuOnlyOpensForPensThatHaveAKind() {
        waitFor { state().loaded }
        // The eraser has its own button there (Phase 35).
        for (tool in listOf(Tool.SCRATCH_PEN, Tool.LASSO, Tool.MOVE)) {
            composeRule.runOnUiThread { viewModel().setTool(tool) }
            waitFor { state().tool == tool }
            composeRule.onNodeWithText(string(R.string.pen_kind_gel)).performClick()
            composeRule.waitForIdle()
            assertTrue(composeRule.onAllNodes(hasTestTag(PEN_KIND_MENU_TAG)).fetchSemanticsNodes().isEmpty())
        }
        composeRule.runOnUiThread { viewModel().setTool(Tool.DRAWING_PEN) }
        waitFor { state().tool == Tool.DRAWING_PEN }
        composeRule.onNodeWithText(string(R.string.pen_kind_gel)).performClick()
        composeRule.onNodeWithTag(PEN_KIND_MENU_TAG).assertIsDisplayed()
        Thread.sleep(400)
        screenshot("pen-kinds-menu.png")
    }

    private fun zigzag(id: Long, y: Float, kind: PenKind) =
        Stroke(id, ToolType.STYLUS, 0, (0..24).map { InkPoint(40f + it * 24f, y + if (it % 2 == 0) 0f else 50f, it * 8L, pressure = 0.6f) }, Pen.DRAWING, kind)

    @Test
    fun figuresAndTheAnnotatedPdfDrawEachKindsLineWeight() {
        waitFor { state().loaded }
        val figures = InkFigureRenderer()
        fun figure(kind: PenKind): Int {
            val stroke = zigzag(1, 40f, kind)
            val region = Region("r1", RegionRole.FIGURE, setOf(1), Bounds.of(stroke), 1)
            val page = Page("p", io.github.iamthebarrow.nibtexa.core.model.InkDocument(listOf(stroke), 2), listOf(region))
            return ink(figures.bitmap(page, region, InkColors.LIGHT, Color.WHITE))
        }
        val (ballpoint, gel, felt) = PenKind.entries.map(::figure)
        assertTrue("ballpoint $ballpoint < gel $gel", ballpoint < gel)
        assertTrue("gel $gel < felt $felt", gel < felt)

        val booklet = Booklet(
            "b-1",
            "Pens",
            listOf(Page("p-1", io.github.iamthebarrow.nibtexa.core.model.InkDocument(PenKind.entries.mapIndexed { i, kind -> zigzag(i + 1L, 100f + i * 140f, kind) }, 4))),
        )
        val bytes = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { AnnotatedPdf(composeRule.activity, services.pictures).render(booklet) } }
        val file = File(composeRule.activity.cacheDir, "kinds.pdf").apply { writeBytes(bytes) }
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { page ->
                val bitmap = createBitmap(page.width * 2, page.height * 2).apply { eraseColor(Color.WHITE) }
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                File(composeRule.activity.getExternalFilesDir("screenshots"), "pen-kinds-pdf.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                fun band(i: Int): Int {
                    val top = ((90f + i * 140f) * 72f / 160f * 2f).toInt()
                    val height = (80f * 72f / 160f * 2f).toInt()
                    return ink(Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height))
                }
                assertTrue("${band(0)} < ${band(1)}", band(0) < band(1))
                assertTrue("${band(1)} < ${band(2)}", band(1) < band(2))
            }
        }
        assertNull(state().restorePrompt)
    }

    @Test
    fun aKindSurvivesABackup() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setAutoRegions(false); viewModel().setTool(Tool.MATH_PEN); viewModel().setPenKind(Pen.MATH, PenKind.BALLPOINT) }
        waitFor { state().kindOf(Pen.MATH) == PenKind.BALLPOINT }
        write(300f)
        waitFor { state().savedVersion >= state().version }
        val file = File(composeRule.activity.cacheDir, "kinds.nibtexa.zip").apply { delete() }
        composeRule.runOnUiThread { viewModel().exportBackup(Uri.fromFile(file)) }
        waitFor { !state().exporting && file.length() > 0 }
        val back = io.github.iamthebarrow.nibtexa.core.export.Backup.check(file).booklets.single().booklet
        assertEquals(PenKind.BALLPOINT, back.pages.single().ink.strokes.single().kind)
    }
}
