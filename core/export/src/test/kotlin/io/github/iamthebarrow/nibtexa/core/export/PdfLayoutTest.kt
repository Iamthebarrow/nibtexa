package io.github.iamthebarrow.nibtexa.core.export

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.notebook.Booklet
import io.github.iamthebarrow.nibtexa.core.model.notebook.Page
import io.github.iamthebarrow.nibtexa.core.model.notebook.PageEditor
import io.github.iamthebarrow.nibtexa.core.model.notebook.RegionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {

    private fun line(x: Float, y: Float, pen: Pen, w: Float = 10f, h: Float = 10f) =
        CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + w, y + h, 8)), pen)

    /** Heading, an equation, a captioned 320 x 100 dp figure, scratch ink, and a paragraph, made out of order. */
    private fun page(): Page {
        val e = PageEditor(Page("p-1"))
        e.addStroke(line(0f, 0f, Pen.TEXT))
        e.addStroke(line(0f, 100f, Pen.MATH))
        e.addStroke(line(0f, 200f, Pen.DRAWING, w = 320f, h = 100f))
        e.addStroke(line(0f, 400f, Pen.SCRATCH))
        e.addStroke(line(0f, 500f, Pen.TEXT))
        e.confirm(e.createRegion(setOf(5), RegionRole.TEXT)!!, " Last words. ")
        e.confirm(e.createRegion(setOf(1), RegionRole.HEADING)!!, "Week 1")
        e.setHeadingLevel("r2", 2)
        e.confirm(e.createRegion(setOf(2), RegionRole.MATH)!!, "x^2+1")
        e.setCaption(e.createRegion(setOf(3), RegionRole.FIGURE)!!, "Plant")
        e.createRegion(setOf(4), RegionRole.EXCLUDED)
        e.orderByPosition()
        return e.page
    }

    @Test
    fun blocksFollowTheReadingOrderAfterTheCover() {
        val blocks = (PdfLayout.blocks(Booklet("b-1", "Signals", listOf(page()))) as PdfLayout.Result.Blocks).blocks

        assertEquals(
            listOf(
                PdfBlock.CoverPage(Cover("Signals", "", emptyList())),
                PdfBlock.Heading(2, "Week 1"),
                PdfBlock.Math("x^2+1"),
                PdfBlock.Figure("p-1", "r4", 144f, 45f, "Plant"), // 320 dp = 2 in = 144 pt
                PdfBlock.Paragraph("Last words."),
            ),
            blocks,
        )
    }

    @Test
    fun pagesFollowEachOtherAndWideFiguresFit() {
        val wide = PageEditor(Page("p-2")).apply {
            addStroke(line(0f, 0f, Pen.DRAWING, w = 2000f, h = 200f))
            createRegion(setOf(1), RegionRole.FIGURE)
        }.page
        val blocks = (PdfLayout.blocks(Booklet("b-1", "T", listOf(page(), wide))) as PdfLayout.Result.Blocks).blocks
        val figure = blocks.last() as PdfBlock.Figure
        assertEquals("p-2", figure.pageId)
        assertEquals(324f, figure.width) // the 4.5 in cap, as in the LaTeX export
        assertEquals(32.4f, figure.height, 0.01f)
    }

    @Test
    fun aBookletThatCannotExportAsLatexDoesNotExportAsPdf() {
        val e = PageEditor(Page("p-1"))
        e.addStroke(line(0f, 0f, Pen.MATH))
        e.createRegion(setOf(1), RegionRole.MATH) // never confirmed
        val result = PdfLayout.blocks(Booklet("b-1", "T", listOf(e.page)))
        assertEquals(PdfLayout.Result.Invalid(listOf(ExportProblem.NotConfirmed("p-1", "r1"))), result)
    }

    @Test
    fun blocksThatDoNotFitStartTheNextPage() {
        val pages = PdfPaginator(pageHeight = 100f)
        assertEquals(listOf(Placement(0, 0f, 0f, 60f)), pages.place(Span(0f, 60f)))
        assertEquals(listOf(Placement(0, 60f, 60f, 100f)), pages.place(Span(60f, 100f))) // exactly fills the page
        assertEquals(listOf(Placement(1, 0f, 100f, 110f)), pages.place(Span(100f, 110f)))
        assertEquals(listOf(Placement(2, 0f, 0f, 95f)), pages.place(Span(0f, 95f))) // a new rendering starts at 0 again
        assertEquals(3, pages.pageCount)
    }

    @Test
    fun spaceIsDroppedAtThePageTopAndKeepMovesToANewPage() {
        val pages = PdfPaginator(pageHeight = 100f)
        pages.space(10f) // at the top: dropped
        assertEquals(listOf(Placement(0, 0f, 0f, 20f)), pages.place(Span(0f, 20f)))
        pages.space(10f)
        assertEquals(listOf(Placement(0, 30f, 0f, 20f)), pages.place(Span(0f, 20f)))
        pages.space(80f) // clipped to the page: the next line starts a new page
        assertEquals(listOf(Placement(1, 0f, 0f, 5f)), pages.place(Span(0f, 5f)))
        pages.keep(90f) // 5 + 90 fits
        assertEquals(1, pages.page)
        pages.keep(96f) // does not: a new page
        assertEquals(listOf(Placement(2, 0f, 0f, 5f)), pages.place(Span(0f, 5f)))
    }

    @Test
    fun aBlockTallerThanAPageIsSlicedFromAFreshPage() {
        val pages = PdfPaginator(pageHeight = 100f)
        pages.place(Span(0f, 30f))
        assertEquals(
            listOf(Placement(1, 0f, 30f, 130f), Placement(2, 0f, 130f, 230f), Placement(3, 0f, 230f, 280f)),
            pages.place(Span(30f, 280f)),
        )
        // The next block continues after the last slice.
        assertEquals(listOf(Placement(3, 50f, 280f, 300f)), pages.place(Span(280f, 300f)))
        assertTrue(pages.place(Span(0f, 0f)).single().page == 3)
    }
}
