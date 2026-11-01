package io.github.iamthebarrow.nibtexa.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iamthebarrow.nibtexa.MainActivity
import io.github.iamthebarrow.nibtexa.core.export.ExportProblem
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.BookletInfo
import io.github.iamthebarrow.nibtexa.core.model.notebook.MarkStyle
import io.github.iamthebarrow.nibtexa.core.model.notebook.MathAlign
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextColor
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextMark
import io.github.iamthebarrow.nibtexa.core.model.notebook.TextSize
import io.github.iamthebarrow.nibtexa.core.model.notebook.TheoremKind
import io.github.iamthebarrow.nibtexa.notebook.TestServicesRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * The on-device PDF export (Phase 12) with the real document page: content renders, long
 * booklets flow onto more pages, unready booklets are refused, and output is byte-stable
 * (Git sync commits it). Since Phase 14 a cover page comes first, without a number, and
 * equations can sit left, centred or right. The last PDF is kept as `pdf-test.pdf` in the app's files to look at.
 */
@RunWith(AndroidJUnit4::class)
class BookletPdfTest {
    private val services = TestServicesRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(services).around(composeRule)

    private fun stroke(x: Float, y: Float, pen: Pen, w: Float = 40f, h: Float = 30f) =
        CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + w / 2, y + h, 8), InkPoint(x + w, y, 16)), pen)

    /** A heading, a paragraph, display math and a captioned drawing. */
    private fun page(id: String, paragraphs: Int = 1): Page {
        val e = PageEditor(Page(id))
        e.addStroke(stroke(0f, 0f, Pen.TEXT))
        e.confirm(e.createRegion(setOf(1), RegionRole.HEADING)!!, "Week 1: transfer functions")
        repeat(paragraphs) { i ->
            e.addStroke(stroke(0f, 100f + i, Pen.TEXT))
            e.confirm(e.createRegion(setOf(e.page.ink.strokes.last().id), RegionRole.TEXT)!!, "Paragraph ${i + 1}. The transfer function has a pole at s = -2, so the step response settles without overshoot. " + "More words follow here to fill a line or two. ".repeat(3))
        }
        e.addStroke(stroke(0f, 3000f, Pen.MATH))
        e.confirm(e.createRegion(setOf(e.page.ink.strokes.last().id), RegionRole.MATH)!!, """H(s)=\frac{\omega_n^2}{s^2+2\zeta\omega_n s+\omega_n^2}""")
        e.addStroke(stroke(0f, 3100f, Pen.DRAWING, w = 320f, h = 160f))
        e.setCaption(e.createRegion(setOf(e.page.ink.strokes.last().id), RegionRole.FIGURE)!!, "Figure 1: the plant")
        return e.page
    }

    private fun render(booklet: Booklet): BookletPdf.Result = runBlocking {
        withContext(Dispatchers.Main) { BookletPdf(composeRule.activity).render(booklet) }
    }

    /** Each page of the PDF rendered at 1 pixel per point. */
    private fun <T> pages(bytes: ByteArray, each: (Bitmap) -> T): List<T> {
        val file = File(composeRule.activity.getExternalFilesDir(null), "pdf-test.pdf").apply { writeBytes(bytes) }
        return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            (0 until r.pageCount).map { i ->
                val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                r.openPage(i).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                each(bitmap).also { bitmap.recycle() }
            }
        }
    }

    /** Dark pixels in rows [top] until [bottom] of the bitmap. */
    private fun dark(bitmap: Bitmap, top: Int = 0, bottom: Int = 842, left: Int = 0, right: Int = 595): Int {
        var n = 0
        for (y in top until bottom) for (x in left until right) if (Color.luminance(bitmap.getPixel(x, y)) < 0.5f) n++
        return n
    }

    /** The leftmost and rightmost dark columns between rows [top] and [bottom]. */
    private fun inkColumns(bitmap: Bitmap, top: Int, bottom: Int): IntRange {
        var first = Int.MAX_VALUE
        var last = -1
        for (y in top until bottom) for (x in 0 until 595) if (Color.luminance(bitmap.getPixel(x, y)) < 0.5f) {
            first = minOf(first, x)
            last = maxOf(last, x)
        }
        return first..last
    }

    /** Dark pixels on each page of the PDF, rendered at 1 pixel per point. */
    private fun inkPerPage(bytes: ByteArray): List<Int> {
        val file = File(composeRule.activity.getExternalFilesDir(null), "pdf-test.pdf").apply { writeBytes(bytes) }
        return PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            (0 until r.pageCount).map { i ->
                val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                r.openPage(i).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                var dark = 0
                for (y in 0 until 842 step 2) for (x in 0 until 595 step 2) if (Color.luminance(bitmap.getPixel(x, y)) < 0.5f) dark++
                bitmap.recycle()
                dark
            }
        }
    }

    @Test
    fun aBookletRendersOnOnePageAfterItsCoverAndTheSameBookletGivesTheSameBytes() {
        composeRule.waitUntil(10_000) { true }
        val booklet = Booklet("b-1", "Signals & Systems", listOf(page("p-1")))
        val first = render(booklet) as BookletPdf.Result.Written
        assertEquals(3, first.pages) // the cover, the contents (it has a heading) and one page of notes
        val ink = inkPerPage(first.bytes)
        assertEquals(3, ink.size)
        assertTrue("a page looks blank: $ink", ink.all { it > 100 } && ink[2] > 500)

        val second = render(booklet) as BookletPdf.Result.Written
        assertArrayEquals("PDF output is not deterministic", first.bytes, second.bytes)
    }

    @Test
    fun aLongBookletFlowsOntoMorePages() {
        val booklet = Booklet("b-2", "Long", listOf(page("p-1", paragraphs = 25), page("p-2", paragraphs = 25)))
        val result = render(booklet) as BookletPdf.Result.Written
        val ink = inkPerPage(result.bytes)
        assertTrue("expected several pages, got ${result.pages}", result.pages >= 5)
        assertEquals(result.pages, ink.size)
        // Every page has content; the last may hold only the final figure, kept with its caption.
        assertTrue("a page is blank: $ink", ink.drop(2).dropLast(1).all { it > 2_000 } && ink.last() > 30)
        // Text is real text, not pictures of it: only the equation and the figure are images.
        val images = Regex("/Subtype\\s*/Image").findAll(String(result.bytes, Charsets.ISO_8859_1)).count()
        assertEquals("images: one per equation and figure", 4, images)
    }

    @Test
    fun theCoverHasTheTitleAuthorAndDatesAndNoNumberAndTheNotesStartAtOne() {
        val info = BookletInfo(author = "Ada Lovelace", created = LocalDate.of(2026, 9, 3), updated = LocalDate.of(2026, 10, 28))
        val result = render(Booklet("b-4", "Signals & Systems", listOf(page("p-1")), info)) as BookletPdf.Result.Written
        assertEquals(3, result.pages)
        // Page number area: the bottom margin (842 - 56 = 786 and below).
        val (cover, _, notes) = pages(result.bytes) { b -> Triple(dark(b, 786, 842), dark(b, 200, 500), dark(b, 0, 200)) }
        assertEquals("the cover has no page number", 0, cover.first)
        assertTrue("the notes page is numbered", notes.first > 0)
        // Title, author and two dates sit a little above the middle of the cover, and nothing at the top.
        assertTrue("cover text: $cover", cover.second > 1_000 && cover.third == 0)

        val bare = render(Booklet("b-5", "Signals & Systems", listOf(page("p-1")))) as BookletPdf.Result.Written
        val bareCover = pages(bare.bytes) { b -> dark(b, 200, 500) }.first()
        assertTrue("the author and dates add text: $bareCover < ${cover.second}", bareCover < cover.second)
    }

    @Test
    fun headingsGetAnUnnumberedTableOfContentsAndTheNotesStartOnANewPage() {
        // Two pages of notes, each opening with its heading.
        val booklet = Booklet("b-7", "Signals", listOf(page("p-1", paragraphs = 25), page("p-2")))
        val result = render(booklet) as BookletPdf.Result.Written
        val (contents, firstNotes) = pages(result.bytes) { b -> Triple(dark(b, 786, 842), dark(b, 56, 160), dark(b, 160, 786)) }.drop(1)
        assertEquals("the contents page has no number", 0, contents.first)
        assertTrue("the contents list its headings at the top: $contents", contents.second > 300)
        assertTrue("and nothing else: $contents", contents.third < contents.second)
        assertTrue("the notes are numbered", firstNotes.first > 0)

        // Without a heading there are no contents.
        val e = PageEditor(Page("p-1"))
        e.addStroke(stroke(0f, 0f, Pen.TEXT))
        e.confirm(e.createRegion(setOf(1), RegionRole.TEXT)!!, "Plain notes.")
        assertEquals(2, (render(Booklet("b-8", "T", listOf(e.page))) as BookletPdf.Result.Written).pages)
    }

    @Test
    fun listsAreSetWithTheirBulletsAndNumbers() {
        val e = PageEditor(Page("p-1"))
        listOf("- poles", "- zeros", "1. sample", "2. window").forEachIndexed { i, text ->
            e.addStroke(stroke(0f, i * 60f, Pen.TEXT))
            e.confirm(e.createRegion(setOf(i + 1L), RegionRole.TEXT)!!, text)
        }
        val result = render(Booklet("b-9", "", listOf(e.page))) as BookletPdf.Result.Written
        // The markers sit in the indent, left of the text, which starts LIST_INDENT (22 pt) in.
        val indent = pages(result.bytes) { b -> dark(b, 56, 200, left = 56, right = 56 + 20) }[1]
        assertTrue("bullets and numbers in the indent: $indent", indent > 20)
    }

    /**
     * Phase 16 in the PDF: formatting, inline maths in a sentence, a theorem, a numbered equation,
     * a footnote at the bottom of its page and a page break. Kept as `pdf-structure.pdf` to look at.
     */
    @Test
    fun formattingInlineMathsFootnotesNumbersAndPageBreaks() {
        val e = PageEditor(Page("p-1"))
        var y = 0f
        fun region(value: String, role: RegionRole = RegionRole.TEXT, x: Float = 0f, w: Float = 100f, sameLine: Boolean = false, marks: List<TextMark> = emptyList()): String {
            if (!sameLine) y += 80f
            e.addStroke(stroke(x, y, if (role == RegionRole.MATH) Pen.MATH else Pen.TEXT, w = w, h = 24f))
            val id = e.createRegion(setOf(e.page.ink.strokes.last().id), role)!!
            e.confirm(id, value, marks)
            return id
        }
        val styled = "Bold, italic, under, mono, Huge, red and a link with a note."
        fun at(word: String, style: MarkStyle) = styled.indexOf(word).let { TextMark(it, it + word.length, style) }
        region(styled, marks = listOf(
            at("Bold", MarkStyle.Bold), at("italic", MarkStyle.Italic), at("under", MarkStyle.Underline), at("mono", MarkStyle.Typewriter),
            at("Huge", MarkStyle.Size(TextSize.HUGEST)), at("red", MarkStyle.Color(TextColor.RED)),
            at("link", MarkStyle.Link("https://example.org")), at("note", MarkStyle.Footnote("A footnote at the bottom of the page.")),
        ))
        region("The root \$s^2+1=0\$ and")
        region("\\frac{a}{b}", RegionRole.MATH, x = 110f, w = 30f, sameLine = true)
        region("sit in the sentence.", x = 150f, sameLine = true)
        e.setTheorem(region("A pole is a root of the denominator."), TheoremKind.DEFINITION)
        e.setNumbered(region("H(s)=\\frac{1}{s+1}", RegionRole.MATH), true)
        e.setPageBreak(region("After the page break."), true)

        val result = render(Booklet("b-10", "", listOf(e.page))) as BookletPdf.Result.Written
        File(composeRule.activity.getExternalFilesDir(null), "pdf-structure.pdf").writeBytes(result.bytes)
        // The cover, the notes, and the page after the break.
        assertEquals(3, result.pages)
        val pdf = String(result.bytes, Charsets.ISO_8859_1)
        // Inline maths is ink on transparency: each comes with a soft mask, itself an image.
        val masks = Regex("/SMask\\s+\\d+").findAll(pdf).count()
        val images = Regex("/Subtype\\s*/Image").findAll(pdf).count() - masks
        assertEquals("one image per inline maths and equation", 3, images)
        assertEquals("the inline maths are masked", 2, masks)
        val (notes, numberColumn) = pages(result.bytes) { b -> dark(b, 700, 786) to dark(b, 56, 786, left = 520, right = 539) }[1]
        assertTrue("the footnote sits at the bottom of the page: $notes", notes > 30)
        assertTrue("the equation number sits on the right margin: $numberColumn", numberColumn > 10)
        assertTrue("the text after the break is on its own page", pages(result.bytes) { b -> dark(b, 56, 120) }[2] > 30)
    }

    @Test
    fun equationsCanSitLeftCentredOrRight() {
        val e = PageEditor(Page("p-1"))
        e.addStroke(stroke(0f, 0f, Pen.MATH))
        e.confirm(e.createRegion(setOf(1), RegionRole.MATH)!!, "x^2+1")
        fun columns(align: MathAlign): IntRange {
            val result = render(Booklet("b-6", "", listOf(e.page), BookletInfo(mathAlign = align))) as BookletPdf.Result.Written
            // Page 2 holds the equation, at the top of the text area; below the cover's (empty) title.
            return pages(result.bytes) { b -> inkColumns(b, 56, 200) }[1]
        }
        val left = columns(MathAlign.LEFT)
        val centre = columns(MathAlign.CENTER)
        val right = columns(MathAlign.RIGHT)
        assertTrue("left: $left", left.first in 56..70)
        assertTrue("right: $right", right.last in 525..539)
        assertTrue("centre: $centre", centre.first > left.first + 50 && centre.last < right.last - 50)
        val middle = (centre.first + centre.last) / 2
        assertTrue("centre: $centre", middle in 287..307)
    }

    @Test
    fun anUnconfirmedRegionStopsThePdfAsItStopsTheLatexExport() {
        val e = PageEditor(Page("p-1"))
        e.addStroke(stroke(0f, 0f, Pen.MATH))
        e.createRegion(setOf(1), RegionRole.MATH)
        assertEquals(BookletPdf.Result.Invalid(listOf(ExportProblem.NotConfirmed("p-1", "r1"))), render(Booklet("b-3", "T", listOf(e.page))))
    }
}
