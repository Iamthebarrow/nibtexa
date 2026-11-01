package io.github.iamthebarrow.nibtexa.notebook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.model.notebook.Bounds
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageView
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.Sheet
import io.github.iamthebarrow.nibtexa.core.model.notebook.SheetSize
import io.github.iamthebarrow.nibtexa.export.AnnotatedPdf
import io.github.iamthebarrow.nibtexa.ink.InkSurfaceView
import io.github.iamthebarrow.nibtexa.ink.TestMotionEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 20 in the app: pictures are inserted, selected, resized and exported; ink is written on the
 * sheet only; Markdown and PDFs import; the annotated PDF has the booklet's pages at its paper size.
 */
@RunWith(AndroidJUnit4::class)
class PicturesAndPdfTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun viewModel() = ViewModelProvider(composeRule.activity)[NotebookViewModel::class.java]
    private fun state() = viewModel().state.value
    private val density get() = composeRule.activity.resources.displayMetrics.density

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) = composeRule.waitUntil(timeoutMs) {
        composeRule.waitForIdle()
        runCatching(condition).getOrDefault(false)
    }

    private fun png(width: Int = 300, height: Int = 200): ByteArray {
        val bitmap = createBitmap(width, height).apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun file(name: String, bytes: ByteArray) = File(composeRule.activity.cacheDir, name).apply { writeBytes(bytes) }

    private fun insertPicture(): String {
        composeRule.runOnUiThread { viewModel().insertPicture(Uri.fromFile(file("photo.png", png()))) }
        waitFor { state().page.regions.any { it.picture != null } }
        return state().page.regions.single { it.picture != null }.id
    }

    @Test
    fun aPictureIsPlacedSelectedResizedAndExported() {
        waitFor { state().loaded }
        val id = insertPicture()
        val picture = state().page.region(id)!!
        assertEquals(RegionRole.FIGURE, picture.role)
        assertNotNull(services.pictures.file(picture.picture!!))
        // Its own shape, and selected with the lasso so it can be moved or resized at once.
        assertEquals(1.5f, (picture.bounds.right - picture.bounds.left) / (picture.bounds.bottom - picture.bounds.top), 0.01f)
        assertEquals(Tool.LASSO, state().tool)
        assertEquals(setOf(id), state().selectedTyped)
        assertEquals(id, viewModel().selectedPicture(state())?.id)

        val b = picture.bounds
        composeRule.runOnUiThread { viewModel().resizePicture(Bounds(b.left, b.top, b.left + 600f, b.top + 400f)) }
        waitFor { state().page.region(id)!!.bounds.right == b.left + 600f }

        composeRule.runOnUiThread { viewModel().exportLatex(services.exportZipUri) }
        waitFor { !state().exporting }
        val dir = services.unpackExport()
        val asset = File(dir, "assets").listFiles().orEmpty().single()
        assertTrue(asset.name, asset.name.endsWith("-$id.png"))
        assertTrue(File(dir, "booklets").walk().first { it.isFile && it.extension == "tex" }.readText().contains("\\includegraphics"))

        composeRule.runOnUiThread { viewModel().exportMarkdown(services.exportZipUri) }
        waitFor { !state().exporting }
        val md = services.unpackExport()
        assertTrue(File(md, "main.md").readText().contains("](images/"))
        assertEquals(1, File(md, "images").listFiles().orEmpty().size)
    }

    @Test
    fun inkIsWrittenOnTheSheetOnly() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setTool(Tool.MATH_PEN); viewModel().onView(PageView(0.25f), final = true) }
        // The surface centres the sheet and reports the view it shows.
        waitFor { state().page.view?.let { it.scale == 0.25f && it.left < -100f } == true }
        // Zoomed out, the sheet sits in the middle with desk on both sides.
        val view = state().page.view!!
        fun screenX(x: Float) = (x - view.left) * density * view.scale
        fun screenY(y: Float) = (y - view.top) * density * view.scale
        val surface = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        fun send(events: List<android.view.MotionEvent>) = composeRule.runOnUiThread { events.forEach { surface.dispatchTouchEvent(it); it.recycle() } }

        // Starting on the desk writes nothing.
        send(TestMotionEvents().line(TOOL_TYPE_STYLUS, screenX(-60f) to screenY(400f), screenX(200f) to screenY(400f)))
        composeRule.waitForIdle()
        assertEquals(0, state().page.ink.strokes.size)

        // Running off the sheet ends at its edge.
        val sheet = state().sheet
        send(TestMotionEvents().line(TOOL_TYPE_STYLUS, screenX(sheet.width - 200f) to screenY(500f), screenX(sheet.width + 200f) to screenY(500f), points = 9))
        waitFor { state().page.ink.strokes.size == 1 }
        val points = state().page.ink.strokes.single().points
        assertTrue(points.all { sheet.contains(it.x, it.y) })
        assertEquals(sheet.width, points.last().x, 1f)
    }

    @Test
    fun ruledLinesAreEightMillimetresApart() {
        waitFor { state().loaded }
        composeRule.runOnUiThread { viewModel().setPaper(io.github.iamthebarrow.nibtexa.core.model.notebook.Paper.RULED) }
        waitFor { state().page.paper == io.github.iamthebarrow.nibtexa.core.model.notebook.Paper.RULED }
        Thread.sleep(1000)
        screenshot("ruled-page.png")
        // At 100%, a line every 8 mm of the sheet: count the line rows in a column of the screenshot.
        val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val surface = composeRule.activity.window.decorView.findViewWithTag<InkSurfaceView>(InkSurfaceView.TAG)
        val origin = IntArray(2).also { surface.getLocationOnScreen(it) }
        val x = origin[0] + (40 * density).toInt()
        val paper = shot.getPixel(x, origin[1] + (10 * density).toInt())
        val rows = (origin[1] until minOf(shot.height, origin[1] + surface.height)).filter { y -> shot.getPixel(x, y) != paper }
        val lines = rows.filterIndexed { i, y -> i == 0 || y - rows[i - 1] > 2 }
        val gaps = lines.zipWithNext { a, b -> (b - a) / density }
        assertTrue(gaps.toString(), gaps.size >= 3)
        // 8 mm is 50.4 page units, shown at 100%.
        gaps.take(3).forEach { assertEquals(50.4f, it, 1.5f) }
    }

    @Test
    fun aMarkdownProjectImportsWithItsPicturesButNotWebOnes() {
        waitFor { state().loaded }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("notes/main.md"))
            z.write("---\ntitle: \"Markdown notes\"\n---\n\n# Poles\n\nA **pole** with \$s\$.\n\n![Board](images/board.png)\n\n![Web](https://example.org/x.png)\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("notes/images/board.png"))
            z.write(png(400, 400))
            z.closeEntry()
        }
        composeRule.runOnUiThread { viewModel().importFile(Uri.fromFile(file("notes.zip", zip.toByteArray())), ImportFormat.MARKDOWN_PROJECT) }
        waitFor { state().bookletTitle == "Markdown notes" && state().page.regions.isNotEmpty() }
        val regions = state().page.regionsInReadingOrder
        assertEquals(RegionRole.HEADING, regions.first().role)
        val picture = regions.single { it.picture != null }
        assertEquals("Board", picture.caption)
        assertNotNull(services.pictures.file(picture.picture!!))
        assertTrue(state().importReport!!.warnings.any { "not downloaded" in it })
    }

    @Test
    fun aPdfBecomesPagesToWriteOnAtItsSize() {
        waitFor { state().loaded }
        val pdf = PdfDocument()
        repeat(2) { i ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(960, 540, i + 1).create())
            page.canvas.drawRect(100f, 100f, 400f, 300f, Paint().apply { color = Color.BLUE })
            pdf.finishPage(page)
        }
        val bytes = ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()
        composeRule.runOnUiThread { viewModel().importFile(Uri.fromFile(file("slides.pdf", bytes)), ImportFormat.PDF_PAGES) }
        waitFor { state().bookletTitle == "slides" && state().loaded }
        assertEquals(2, state().pageCount)
        assertEquals(SheetSize.CUSTOM, state().sheet.size)
        assertEquals(Sheet.ofPoints(960f, 540f), state().sheet)
        val background = state().page.background
        assertNotNull(background)
        assertNotNull(services.pictures.bitmap(background!!))
        insertPicture()
        composeRule.runOnUiThread { viewModel().onView(PageView(0.5f), final = true) }
        Thread.sleep(1500)
        screenshot("pdf-page.png")

        // The annotated PDF is the pages as they look, at the PDF's size.
        val out = File(composeRule.activity.cacheDir, "annotated.pdf")
        composeRule.runOnUiThread { viewModel().exportAnnotatedPdf(Uri.fromFile(out), { AnnotatedPdf(composeRule.activity, services.pictures).render(it) }) }
        waitFor(30_000) { !state().exporting && out.length() > 0 }
        PdfRenderer(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertEquals(2, r.pageCount)
            r.openPage(0).use { assertEquals(960, it.width); assertEquals(540, it.height) }
        }
        // The library's thumbnails take each booklet's shape.
        composeRule.runOnUiThread { viewModel().closeBooklet() }
        waitFor { state().screen == Screen.LIBRARY && state().libraryLoaded }
        Thread.sleep(1500)
        screenshot("library-shapes.png")
    }

    private fun screenshot(name: String) {
        val shot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir("screenshots"), name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
