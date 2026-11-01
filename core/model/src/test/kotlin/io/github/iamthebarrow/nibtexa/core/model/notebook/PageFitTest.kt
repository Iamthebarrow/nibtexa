package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFitTest {
    private val sheet = Sheet()
    private val area = sheet.writingArea(Paper.BLANK, PageMargin.NORMAL)

    private fun inside(b: Bounds, of: Bounds = area, slack: Float = 0.01f) =
        b.left >= of.left - slack && b.top >= of.top - slack && b.right <= of.right + slack && b.bottom <= of.bottom + slack

    @Test
    fun blankPaperHasAMarginAllRoundAndRuledAtTheSidesOnly() {
        val margin = PageMargin.NORMAL.inches * Sheet.UNITS_PER_INCH
        assertEquals(Bounds(margin, margin, sheet.width - margin, sheet.height - margin), sheet.writingArea(Paper.BLANK, PageMargin.NORMAL))
        assertEquals(Bounds(margin, margin, sheet.width - margin, sheet.height - margin), sheet.writingArea(Paper.GRID, PageMargin.NORMAL))
        assertEquals(Bounds(margin, 0f, sheet.width - margin, sheet.height), sheet.writingArea(Paper.RULED, PageMargin.NORMAL))
    }

    @Test
    fun aPagesOwnMarginWinsOverTheBookletsAndNullUsesIt() {
        val style = DocumentStyle(margin = PageMargin.WIDE)
        assertEquals(PageMargin.WIDE, style.marginOf(Page("a")))
        assertEquals(PageMargin.CHEAT_SHEET, style.marginOf(Page("a", margin = PageMargin.CHEAT_SHEET)))
    }

    @Test
    fun aCheatSheetHasVeryLittleMarginAndAWideOneMore() {
        val cheat = sheet.writingArea(Paper.BLANK, PageMargin.CHEAT_SHEET)
        val wide = sheet.writingArea(Paper.BLANK, PageMargin.WIDE)
        assertEquals(40f, cheat.left, 0.01f)
        assertTrue(cheat.right - cheat.left > area.right - area.left)
        assertTrue(wide.right - wide.left < area.right - area.left)
    }

    @Test
    fun aPageOverAPdfCanBeWrittenOnAnywhere() {
        assertEquals(Bounds(0f, 0f, sheet.width, sheet.height), sheet.writingArea(Paper.BLANK, PageMargin.WIDE, background = true))
    }

    @Test
    fun aMarginTooBigForASmallSheetLeavesRoom() {
        val tiny = Sheet(SheetSize.CUSTOM, customWidthInches = 2f, customHeightInches = 2f)
        val left = tiny.writingArea(Paper.BLANK, PageMargin.WIDE)
        assertEquals(tiny.width / 2, left.right - left.left, 0.01f)
    }

    @Test
    fun aStrokeEndsAtTheMarginAndOneStartingInItWritesNothing() {
        val middle = area.centerY
        val crossing = listOf(InkPoint(area.right - 10f, middle, 0), InkPoint(area.right + 90f, middle, 8), InkPoint(area.right + 200f, middle, 16))
        val kept = area.clip(crossing)
        assertEquals(2, kept.size)
        assertEquals(area.right, kept.last().x, 0.01f)
        assertTrue(area.clip(listOf(InkPoint(area.left - 5f, middle, 0), InkPoint(area.left + 50f, middle, 8))).isEmpty())
    }

    @Test
    fun aMoveStopsAtTheMargin() {
        val box = Bounds(200f, 200f, 300f, 300f)
        assertEquals(area.left - 200f to area.top - 200f, PageFit.moveWithin(box, area, -500f, -500f))
        assertEquals(50f to 60f, PageFit.moveWithin(box, area, 50f, 60f))
        assertEquals(area.right - 300f to 0f, PageFit.moveWithin(box, area, 5000f, 0f))
    }

    @Test
    fun somethingAlreadyInTheMarginComesBackWithAnyMove() {
        val box = Bounds(10f, 10f, 60f, 60f)
        assertEquals(area.left - 10f to area.top - 10f, PageFit.moveWithin(box, area, 1f, 1f))
    }

    private fun text(words: String, b: Bounds) = Region("r1", RegionRole.TEXT, emptySet(), b, 1, RecognitionStatus.CONFIRMED, text = words, typed = true)

    @Test
    fun wordsTooWideWrapToANarrowerBoxThatGrowsDown() {
        val words = "word ".repeat(200)
        val tooWide = text(words, Bounds(0f, 200f, 3000f, 240f))
        val box = PageFit.typedBox(tooWide, area)
        assertTrue(inside(box))
        assertEquals(area.right - area.left, box.right - box.left, 0.01f)
        // Nothing is cut off: it is as tall as the words need at that width.
        assertEquals(PageFit.ESTIMATE.height(tooWide, area.right - area.left), box.bottom - box.top, 0.01f)
        assertTrue(box.bottom - box.top > 40f)
    }

    @Test
    fun wordsThatFitAreOnlyMoved() {
        val box = PageFit.typedBox(text("short", Bounds(10f, 200f, 310f, 260f)), area)
        assertEquals(300f, box.right - box.left, 0.01f)
        assertEquals(60f, box.bottom - box.top, 0.01f)
        assertEquals(area.left, box.left, 0.01f)
    }

    @Test
    fun aPictureGetsSmallerWithItsShape() {
        val region = Region("r1", RegionRole.FIGURE, emptySet(), Bounds(0f, 0f, 4000f, 2000f), 1, typed = true, picture = "p.png")
        val box = PageFit.typedBox(region, area)
        assertTrue(inside(box))
        assertEquals(2f, (box.right - box.left) / (box.bottom - box.top), 0.01f)
    }

    private fun stroke(x: Float, y: Float, x2: Float, y2: Float) = CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x2, y2, 8)), Pen.DRAWING)

    private fun typed(id: String, left: Float, top: Float, right: Float, bottom: Float, words: String = "Some words ".repeat(6)) =
        Region(id, RegionRole.TEXT, emptySet(), Bounds(left, top, right, bottom), id.drop(1).toInt(), RecognitionStatus.CONFIRMED, text = words, typed = true)

    private fun overlap(a: Bounds, b: Bounds) = minOf(a.right, b.right) > maxOf(a.left, b.left) + 0.5f && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top) + 0.5f

    /** Two columns running over the right margin, an equation across them, and more below: an imported page, as the user found it. */
    @Test
    fun columnsOverTheMarginStayColumnsInsideIt() {
        val wide = Bounds(125f, 0f, 725f, 1000f)
        val regions = listOf(
            typed("r1", 118f, 100f, 400f, 150f),
            typed("r2", 402f, 100f, 840f, 150f),
            typed("r3", 118f, 170f, 400f, 220f),
            typed("r4", 402f, 170f, 840f, 220f),
            Region("r5", RegionRole.MATH, emptySet(), Bounds(300f, 240f, 600f, 290f), 5, RecognitionStatus.CONFIRMED, confirmedLatex = "x", typed = true),
            typed("r6", 118f, 300f, 400f, 340f),
        )
        val e = PageEditor(Page("p", regions = regions))
        assertTrue(e.fitTo(wide))
        val after = regions.map { e.page.region(it.id)!!.bounds }
        after.forEach { assertTrue("$it", inside(it, wide)) }
        // Side by side still, not on top of each other.
        assertTrue(after[0].right <= after[1].left + 0.5f)
        assertTrue(after[2].right <= after[3].left + 0.5f)
        assertEquals(after[0].top, after[1].top, 0.01f)
        for (a in after.indices) for (b in after.indices) if (a < b) assertFalse("${after[a]} and ${after[b]}", overlap(after[a], after[b]))
        // Words that wrapped pushed down what is below them.
        assertTrue(after[5].top > after[4].bottom - 0.5f)
        assertFalse("settled", e.fitTo(wide))
    }

    @Test
    fun onlyTheColumnThatGrewPushesWhatIsBelowIt() {
        val long = "Some words ".repeat(40)
        val regions = listOf(typed("r1", 150f, 200f, 400f, 230f, long), typed("r2", 420f, 200f, 700f, 600f, "Short"), typed("r3", 150f, 240f, 400f, 280f, "Under"))
        val e = PageEditor(Page("p", regions = regions))
        assertTrue(e.fitTo(area, PageFit.ESTIMATE))
        val first = e.page.region("r1")!!.bounds
        assertTrue("the words have their room", first.bottom - first.top > 30f)
        assertTrue("under them, pushed down", e.page.region("r3")!!.bounds.top >= first.bottom)
        assertEquals("the other column stays", 200f, e.page.region("r2")!!.bounds.top, 0.01f)
    }

    @Test
    fun whatAlreadyFitsIsLeftAlone() {
        val e = PageEditor(Page("p", regions = listOf(typed("r1", 150f, 200f, 400f, 300f, "Short"), typed("r2", 420f, 200f, 700f, 300f, "Short"))))
        assertFalse(e.fitTo(area))
        assertFalse(PageFit.typedSpills(e.page, area, PageFit.ESTIMATE))
    }

    @Test
    fun typedContentOverTheMarginOrTallerThanItsBoxIsFound() {
        assertTrue(PageFit.typedSpills(Page("p", regions = listOf(typed("r1", 150f, 100f, area.right + 40f, 300f, "Short"))), area, PageFit.ESTIMATE))
        assertTrue(PageFit.typedSpills(Page("p", regions = listOf(typed("r1", 150f, 100f, 300f, 110f, "Some words ".repeat(30)))), area, PageFit.ESTIMATE))
    }

    @Test
    fun handwritingBesideTypedColumnsScalesWithThemAndKeepsItsShape() {
        val e = PageEditor(Page("p", regions = listOf(typed("r1", 150f, 100f, area.right + 300f, 160f, "Short"))))
        e.addStroke(stroke(150f, 300f, 450f, 400f))
        assertTrue(e.fitTo(area))
        val b = Bounds.of(e.page.ink.strokes.single())
        assertEquals((400f - 300f) / (450f - 150f), (b.bottom - b.top) / (b.right - b.left), 0.01f)
        assertTrue(inside(b))
    }

    @Test
    fun fittingAPageBringsEverythingInAndIsOneUndo() {
        val e = PageEditor(Page("p"))
        e.addStroke(stroke(5f, 5f, 80f, 60f))
        e.addStroke(stroke(100f, 400f, 5000f, 500f)) // far too wide
        e.addPicture("p.png", Bounds(0f, 0f, 4000f, 2000f))
        assertTrue(e.fitTo(area))
        e.page.ink.strokes.forEach { assertTrue("$it", inside(Bounds.of(it))) }
        e.page.regions.forEach { assertTrue("$it", inside(it.bounds)) }
        // Nothing more to do.
        assertFalse(e.fitTo(area))
        e.undo()
        assertEquals(5f, e.page.ink.strokes.first().points.first().x, 0.01f)
    }

    @Test
    fun aRegionsInkMovesTogetherSoItKeepsItsShape() {
        val e = PageEditor(Page("p"))
        e.addStroke(stroke(10f, 300f, 50f, 340f))
        e.addStroke(stroke(60f, 310f, 100f, 350f))
        e.createRegion(setOf(1L, 2L), RegionRole.FIGURE)
        e.fitTo(area)
        val (a, b) = e.page.ink.strokes
        assertEquals(50f, b.points.first().x - a.points.first().x, 0.01f)
        assertEquals(area.left, a.points.first().x, 0.01f)
    }
}
