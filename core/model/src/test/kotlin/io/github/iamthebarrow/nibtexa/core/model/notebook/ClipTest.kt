package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import io.github.iamthebarrow.nibtexa.core.model.serialization.ClipJson
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copying and pasting a selection (Phase 26): content, ids, order and undo. */
class ClipTest {
    private fun line(x: Float, y: Float, pen: Pen = Pen.MATH, kind: PenKind = PenKind.GEL) =
        CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + 10, y + 10, 8)), pen, kind)

    /** A page with a confirmed equation made of two strokes at (0, 0), and returns its region's id. */
    private fun equation(editor: PageEditor, latex: String = "x^2", y: Float = 0f): String {
        val first = editor.page.ink.nextStrokeId
        editor.addStroke(line(0f, y))
        editor.addStroke(line(20f, y))
        val id = editor.createRegion(setOf(first, first + 1), RegionRole.MATH)!!
        val request = editor.startRecognition(id)!!
        editor.applyResult(request, "wrong")
        editor.confirm(id, latex)
        return id
    }

    private fun clipOfAll(editor: PageEditor) = Clip.of(editor.page, editor.page.ink.strokes.mapTo(HashSet()) { it.id }, editor.page.regions.filter { it.typed }.mapTo(HashSet()) { it.id })

    @Test
    fun aWholeRegionComesWithItsResultAndGetsNewIds() {
        val source = PageEditor(Page("a"))
        val id = equation(source)
        val clip = clipOfAll(source)
        assertEquals(listOf("x^2"), clip.equations)

        val target = PageEditor(Page("b"))
        target.addStroke(line(0f, 500f))
        val pasted = target.paste(clip, 100f, 200f)

        assertEquals(setOf(2L, 3L), pasted.strokes)
        val region = target.page.regions.single()
        assertEquals("r1", region.id)
        assertEquals(setOf(2L, 3L), region.strokeIds)
        assertEquals(RecognitionStatus.CONFIRMED, region.status)
        assertEquals("x^2", region.confirmedLatex)
        assertEquals("wrong", region.recognizedLatex)
        assertEquals(Bounds(100f, 200f, 130f, 210f), region.bounds)
        assertFalse(target.page.isStale(region))
        assertTrue(target.page.isExportable(region))
        assertEquals(id, source.page.regions.single().id)
    }

    @Test
    fun oneUndoTakesItAllBackAndRedoRestoresIt() {
        val source = PageEditor(Page("a"))
        equation(source)
        source.addGraph(Graph(listOf(GraphCurve("y=x"))), Bounds(0f, 100f, 100f, 200f))
        val clip = clipOfAll(source)
        val target = PageEditor(Page("b"))
        target.paste(clip, 0f, 0f)
        assertEquals(2, target.page.regions.size)
        target.undo()
        assertTrue(target.page.ink.strokes.isEmpty())
        assertTrue(target.page.regions.isEmpty())
        target.redo()
        assertEquals(2, target.page.ink.strokes.size)
        assertEquals(2, target.page.regions.size)
        // Ids aren't reused after an undo.
        target.undo()
        target.paste(clip, 0f, 0f)
        assertTrue(target.page.ink.strokes.all { it.id > 2 })
        assertNull(target.page.region("r1"))
    }

    @Test
    fun readingOrderFollowsThePositionsOrTheUsersOrder() {
        val source = PageEditor(Page("a"))
        equation(source, "a", y = 0f)
        equation(source, "b", y = 100f)
        val clip = clipOfAll(source)
        assertEquals(listOf("a", "b"), clip.equations)

        val above = PageEditor(Page("b"))
        above.addStroke(line(0f, 400f, Pen.TEXT))
        above.createRegion(setOf(1), RegionRole.TEXT)
        above.paste(clip, 0f, 0f)
        assertEquals(listOf("a", "b", null), above.page.regionsInReadingOrder.map { it.confirmedLatex })

        // A hand-made order is kept, with the paste after it.
        val manual = PageEditor(Page("c"))
        manual.addStroke(line(0f, 400f, Pen.TEXT))
        manual.createRegion(setOf(1), RegionRole.TEXT)
        manual.addStroke(line(0f, 500f, Pen.TEXT))
        manual.createRegion(setOf(2), RegionRole.TEXT)
        manual.moveInReadingOrder("r2", 0)
        manual.paste(clip, 0f, 0f)
        assertEquals(listOf(null, null, "a", "b"), manual.page.regionsInReadingOrder.map { it.confirmedLatex })
    }

    @Test
    fun partOfARegionOrAStaleOneComesLoose() {
        val source = PageEditor(Page("a"))
        equation(source)
        val part = Clip.of(source.page, setOf(1L), emptySet())
        assertEquals(1, part.strokes.size)
        assertTrue(part.regions.isEmpty())

        val stale = PageEditor(Page("b"))
        val id = equation(stale)
        stale.addStroke(line(5f, 2f))
        assertTrue(stale.page.isStale(stale.page.region(id)!!))
        val clip = clipOfAll(stale)
        assertEquals(3, clip.strokes.size)
        assertTrue(clip.regions.isEmpty())
    }

    @Test
    fun aRecognitionStillRunningIsLeftBehind() {
        val source = PageEditor(Page("a"))
        source.addStroke(line(0f, 0f))
        val id = source.createRegion(setOf(1), RegionRole.MATH)!!
        source.startRecognition(id)
        val region = clipOfAll(source).regions.single()
        assertEquals(RecognitionStatus.NOT_REQUESTED, region.status)
        assertTrue(region.sourceStrokeIds.isEmpty())
    }

    @Test
    fun anUnconfirmedResultStaysAsItIs() {
        val source = PageEditor(Page("a"))
        source.addStroke(line(0f, 0f))
        val id = source.createRegion(setOf(1), RegionRole.MATH)!!
        source.applyResult(source.startRecognition(id)!!, "y")
        val clip = clipOfAll(source)
        assertEquals("", clip.plain)
        assertTrue(clip.equations.isEmpty())
        assertTrue(clip.isDrawn)
        val target = PageEditor(Page("b"))
        target.paste(clip.typedEquations(), 0f, 0f)
        assertEquals(RecognitionStatus.RECOGNIZED, target.page.regions.single().status)
        assertEquals("y", target.page.regions.single().recognizedLatex)
        assertEquals(1, target.page.ink.strokes.size)
    }

    @Test
    fun anEquationPastesTypedOrWithItsHandwriting() {
        val source = PageEditor(Page("a"))
        equation(source)
        source.addStroke(line(0f, 300f, Pen.DRAWING))
        val clip = clipOfAll(source)
        assertTrue(clip.hasHandwrittenEquations)
        assertTrue(clip.isDrawn)

        val typed = PageEditor(Page("b"))
        val pasted = typed.paste(clip.typedEquations(), 50f, 50f)
        val region = typed.page.regions.single()
        assertTrue(region.typed)
        assertTrue(region.strokeIds.isEmpty())
        assertEquals("x^2", region.confirmedLatex)
        assertEquals(setOf(region.id), pasted.typedRegions)
        // The loose drawing keeps its ink, and the equation's is gone.
        assertEquals(1, typed.page.ink.strokes.size)
        assertEquals(Pen.DRAWING, typed.page.ink.strokes.single().pen)
        assertFalse(typed.page.isStale(region))
        assertTrue(typed.page.isExportable(region))
        assertFalse(clip.typedEquations().hasHandwrittenEquations)

        val written = PageEditor(Page("c"))
        written.paste(clip, 50f, 50f)
        assertEquals(3, written.page.ink.strokes.size)
        assertFalse(written.page.regions.single().typed)
    }

    @Test
    fun picturesAndGraphsCome() {
        val source = PageEditor(Page("a"))
        val eq = equation(source, "y=x")
        val graph = source.addGraph(Graph(listOf(GraphCurve("y=x", eq), GraphCurve("y=2x", "r99"))), Bounds(0f, 100f, 100f, 200f))
        val picture = source.addPicture("p.png", Bounds(0f, 300f, 50f, 350f))
        val strokes = source.page.ink.strokes.mapTo(HashSet()) { it.id }
        val clip = Clip.of(source.page, strokes, setOf(graph, picture))
        assertEquals(setOf("p.png"), clip.pictures)

        val target = PageEditor(Page("b"))
        val pasted = target.paste(clip, 0f, 0f)
        assertEquals(2, pasted.typedRegions.size)
        val copy = target.page.regions.first { it.graph != null }.graph!!
        val equation = target.page.regions.first { it.role == RegionRole.MATH }
        // Linked to the equation copied with it; a source that didn't come is dropped.
        assertEquals(equation.id, copy.curves[0].source)
        assertNull(copy.curves[1].source)
        target.confirm(equation.id, "y=3x")
        assertEquals("y=3x", target.page.regions.first { it.graph != null }.graph!!.curves[0].latex)
    }

    @Test
    fun aGraphWithoutItsEquationLosesTheLink() {
        val source = PageEditor(Page("a"))
        val eq = equation(source, "y=x")
        val graph = source.addGraph(Graph(listOf(GraphCurve("y=x", eq))), Bounds(0f, 100f, 100f, 200f))
        val clip = Clip.of(source.page, emptySet(), setOf(graph))
        assertNull(clip.regions.single().graph!!.curves.single().source)
    }

    @Test
    fun plainTextIsConfirmedContentInReadingOrder() {
        val e = PageEditor(Page("a"))
        equation(e, "E=mc^2", y = 200f)
        e.addStroke(line(0f, 0f, Pen.TEXT))
        val text = e.createRegion(setOf(3), RegionRole.TEXT)!!
        e.applyResult(e.startRecognition(text)!!, "mass")
        e.confirm(text, "Energy and mass")
        assertEquals("Energy and mass\nE=mc^2", clipOfAll(e).plain)
        assertEquals(listOf("Energy and mass"), clipOfAll(e).words)
    }

    @Test
    fun theLimitKeepsTheLatestFiftyAndAllPinned() {
        class Item(val time: Long, val pinned: Boolean)
        val pins = (0 until 60).map { Item(it.toLong(), true) }
        val others = (100 until 170).map { Item(it.toLong(), false) }
        val kept = Clipboard.kept(pins + others, Item::pinned, Item::time)
        assertEquals(110, kept.size)
        assertEquals(59L, kept.first().time)
        assertTrue(kept.take(60).all { it.pinned })
        assertEquals(169L, kept[60].time)
        assertEquals(120L, kept.last().time)
    }

    @Test
    fun itGoesInView() {
        val sheet = Sheet()
        val clip = Clip(listOf(io.github.iamthebarrow.nibtexa.core.model.Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(100f, 100f, 0), InkPoint(200f, 150f, 8)), Pen.MATH)))
        val view = PageView()
        // Near the original, moving along with each paste.
        assertEquals(Placement(124f, 124f, 1f), clip.placement(sheet, view, 800f, 600f, samePage = true, repeat = 0))
        assertEquals(Placement(148f, 148f, 1f), clip.placement(sheet, view, 800f, 600f, samePage = true, repeat = 1))
        // Another page: the view's corner.
        assertEquals(Placement(40f, 40f, 1f), clip.placement(sheet, view, 800f, 600f, samePage = false, repeat = 0))
        assertEquals(Placement(64f, 64f, 1f), clip.placement(sheet, view, 800f, 600f, samePage = false, repeat = 1))
        // The original scrolled away: the view's corner too.
        val away = PageView(1f, 300f, 700f)
        assertEquals(Placement(340f, 740f, 1f), clip.placement(sheet, away, 400f, 300f, samePage = true, repeat = 0))
        // At the sheet's far corner it stays on the sheet.
        val corner = PageView(1f, sheet.width - 10f, sheet.height - 10f)
        val placed = clip.placement(sheet, corner, 400f, 300f, samePage = false, repeat = 0)!!
        assertEquals(sheet.width - 100f, placed.left, 0.01f)
        assertEquals(sheet.height - 50f, placed.top, 0.01f)
        assertNull(Clip().placement(sheet, view, 1f, 1f, false, 0))
    }

    @Test
    fun aChosenPlaceWinsAndStaysOnTheSheet() {
        val sheet = Sheet()
        val clip = Clip(listOf(io.github.iamthebarrow.nibtexa.core.model.Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(100f, 50f, 8)), Pen.MATH)))
        val place = clip.placement(sheet, PageView(), 800f, 600f, samePage = true, repeat = 3, at = 300f to 400f)!!
        assertEquals(300f, place.left, 0.01f)
        assertEquals(400f, place.top, 0.01f)
        val past = clip.placement(sheet, PageView(), 800f, 600f, samePage = false, repeat = 0, at = sheet.width + 500f to 400f)!!
        assertTrue(past.left < sheet.width)
    }

    @Test
    fun whatIsTooBigForTheSheetIsMadeSmaller() {
        val small = Sheet(SheetSize.A5)
        val wide = small.width * 2
        val clip = Clip(listOf(io.github.iamthebarrow.nibtexa.core.model.Stroke(1, ToolType.STYLUS, 0, listOf(InkPoint(0f, 0f, 0), InkPoint(wide, 100f, 8)), Pen.MATH)))
        val place = clip.placement(small, PageView(), 800f, 600f, samePage = false, repeat = 0)!!
        assertEquals(0.5f, place.scale, 0.001f)
        val e = PageEditor(Page("a"))
        e.paste(clip, place.left, place.top, place.scale)
        val xs = e.page.ink.strokes.single().points.map { it.x }
        assertTrue(xs.max() <= small.width + 0.01f)
    }

    // ---- Bringing a region into view (Phase 28) --------------------------------------------------

    @Test
    fun aViewShowsARegionAndKeepsItsZoom() {
        val sheet = Sheet()
        val view = PageView(2f, 0f, 0f)
        // In view: nothing moves.
        assertEquals(view, view.showing(Bounds(10f, 60f, 50f, 100f), sheet, 800f, 600f))
        // Where the pen bar can be, it isn't in view.
        assertTrue(view.showing(Bounds(10f, 10f, 50f, 40f), sheet, 800f, 600f).top < 0f)
        // Out of view: in the middle, at the same zoom.
        val low = Bounds(100f, 900f, 200f, 940f)
        val shown = view.showing(low, sheet, 800f, 600f)
        assertEquals(2f, shown.scale, 0f)
        assertEquals(low.centerY - (300f + PageView.PEN_BAR_ROOM / 2f) / 2f, shown.top, 0.01f)
        assertTrue(low.top >= shown.top && low.bottom <= shown.top + 300f)
        // At the sheet's end it stays round the sheet, and the region is still in view.
        val end = Bounds(100f, sheet.height - 20f, 200f, sheet.height)
        val atEnd = view.showing(end, sheet, 800f, 600f)
        assertEquals(sheet.height + PageView.SHEET_MARGIN + PageView.PEN_BAR_ROOM / 2f - 300f, atEnd.top, 0.01f)
        assertTrue(end.bottom <= atEnd.top + 300f)
    }

    @Test
    fun aRegionTooLongToFitShowsItsStart() {
        val sheet = Sheet()
        val view = PageView(2f, 300f, 300f)
        // Wider than the view, which is 400 across here: its first words show, and asking again moves nothing.
        val wide = Bounds(40f, 900f, 500f, 940f)
        val shown = view.showing(wide, sheet, 800f, 600f)
        assertEquals(40f, shown.left, 0.01f)
        assertTrue(wide.top >= shown.top && wide.bottom <= shown.top + 300f)
        assertEquals(shown, shown.showing(wide, sheet, 800f, 600f))
        // Taller than the view likewise, from its top.
        val tall = Bounds(100f, 200f, 200f, 800f)
        assertEquals(200f - PageView.PEN_BAR_ROOM / 2f, view.showing(tall, sheet, 800f, 600f).top, 0.01f)
    }

    @Test
    fun itKeepsEverythingThroughJson() {
        val source = PageEditor(Page("a"))
        equation(source)
        source.addStroke(line(0f, 200f, Pen.DRAWING, PenKind.FELT))
        val graph = source.addGraph(Graph(listOf(GraphCurve("y=x"))), Bounds(0f, 300f, 100f, 400f))
        val picture = source.addPicture("p.png", Bounds(0f, 500f, 100f, 600f))
        val clip = Clip.of(source.page, source.page.ink.strokes.mapTo(HashSet()) { it.id }, setOf(graph, picture))
        assertEquals(clip, ClipJson.decode(ClipJson.encode(clip)))
        assertEquals(Clip(), ClipJson.decode(ClipJson.encode(Clip())))
        assertThrows(InkFormatException::class.java) { ClipJson.decode("{}") }
    }

    @Test
    fun aPreviewShowsItAloneOnItsOwnSheet() {
        val source = PageEditor(Page("a"))
        source.addStroke(line(500f, 900f, Pen.DRAWING))
        val (page, sheet) = clipOfAll(source).preview()
        val point = page.ink.strokes.single().points.first()
        assertEquals(Clipboard.PREVIEW_MARGIN, point.x, 0.01f)
        assertEquals(Clipboard.PREVIEW_MARGIN, point.y, 0.01f)
        assertEquals(10f + 2 * Clipboard.PREVIEW_MARGIN, sheet.width, 0.01f)
        assertEquals(10f + 2 * Clipboard.PREVIEW_MARGIN, sheet.height, 0.01f)
    }
}
