package io.github.iamthebarrow.nibtexa.core.model.notebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a page is shown (Phases 17 and 19): zooming about a point, limits, and the view kept round the sheet. */
class PageViewTest {

    @Test
    fun zoomingAboutAPointKeepsThatPointStill() {
        val view = PageView(1f, 100f, 50f)
        val zoomed = view.zoomedAbout(2f, 200f, 100f)
        assertEquals(2f, zoomed.scale)
        // The page point under (200, 100) was (300, 150); it still is.
        assertEquals(300f, zoomed.left + 200f / zoomed.scale, 0.001f)
        assertEquals(150f, zoomed.top + 100f / zoomed.scale, 0.001f)
    }

    @Test
    fun zoomStaysInRange() {
        assertEquals(PageView.MAX_SCALE, PageView(10f).clamped().scale)
        assertEquals(PageView.MIN_SCALE, PageView(1f).zoomedAbout(0.01f, 0f, 0f).scale)
    }

    private val a4 = Sheet()

    @Test
    fun aSheetSmallerThanTheScreenSitsInTheMiddle() {
        // At 25% an A4 sheet (1323 x 1871) is about 331 x 468 dp on an 800 x 1200 screen.
        val view = PageView(0.25f, 500f, -900f).within(a4, 800f, 1200f)
        assertEquals((a4.width - 800f / 0.25f) / 2, view.left, 0.01f)
        assertEquals((a4.height - 1200f / 0.25f) / 2, view.top, 0.01f)
    }

    @Test
    fun aLargerSheetMovesOnlyAsFarAsItsEdges() {
        val view = PageView(2f, -500f, 5000f).within(a4, 800f, 1200f)
        assertEquals(-PageView.SHEET_MARGIN, view.left, 0.01f)
        assertEquals(a4.height + PageView.SHEET_MARGIN + PageView.PEN_BAR_ROOM / 2f - 1200f / 2f, view.top, 0.01f)
    }

    /** Up and down only: at the sheet's width, nothing moves sideways. */
    @Test
    fun theTopOfThePageCanComeOutFromUnderThePenBar() {
        val fitted = PageView.fitWidth(a4, 800f, 600f)
        val pulled = fitted.copy(left = -500f, top = -5000f).within(a4, 800f, 600f)
        assertEquals(-PageView.SHEET_MARGIN - PageView.PEN_BAR_ROOM / fitted.scale, pulled.top, 0.01f)
        assertEquals(-PageView.SHEET_MARGIN, fitted.left, 0.01f)
        assertEquals(fitted.left, pulled.left, 0.01f)
    }

    @Test
    fun fitWidthAndWholePage() {
        // On a screen shorter than the fitted sheet, it starts at the sheet's top, clear of the pen bar.
        val width = PageView.fitWidth(a4, 800f, 600f)
        assertEquals(800f / (a4.width + 2 * PageView.SHEET_MARGIN), width.scale, 0.0001f)
        assertEquals(-PageView.SHEET_MARGIN - PageView.PEN_BAR_ROOM / width.scale, width.top, 0.01f)
        // The whole sheet: limited by the height on a wide screen.
        val whole = PageView.wholePage(a4, 1600f, 1000f)
        assertEquals((1000f - 2 * PageView.PEN_BAR_ROOM) / (a4.height + 2 * PageView.SHEET_MARGIN), whole.scale, 0.0001f)
        // It sits in the middle, so its top is clear of the pen bar.
        assertTrue(-whole.top * whole.scale >= PageView.PEN_BAR_ROOM - 0.01f)
    }

    /** A sheet that only just fits the screen would otherwise be stuck in the middle, under the pen bar. */
    @Test
    fun aSheetThatOnlyJustFitsStillStartsClearOfThePenBar() {
        val scale = 800f / (a4.width + 2 * PageView.SHEET_MARGIN)
        val height = (a4.height + 2 * PageView.SHEET_MARGIN) * scale + 10f
        val view = PageView.fitWidth(a4, 800f, height)
        assertEquals(-PageView.SHEET_MARGIN - PageView.PEN_BAR_ROOM / scale, view.top, 0.01f)
    }

    @Test
    fun sheetsAreTheirPaperSizeIn160thsOfAnInch() {
        assertEquals(1322.8f, a4.width, 0.1f)
        assertEquals(595.3f, a4.widthPoints, 0.1f)
        assertEquals(Sheet(SheetSize.LETTER, landscape = true).width, 1760f, 0.01f)
    }

    @Test
    fun theViewIsNotPartOfTheUndoHistory() {
        val editor = PageEditor(Page("p"))
        editor.setView(PageView(2f, 10f, 10f))
        assertEquals(PageView(2f, 10f, 10f), editor.page.view)
        assertEquals(false, editor.canUndo)
    }
}
