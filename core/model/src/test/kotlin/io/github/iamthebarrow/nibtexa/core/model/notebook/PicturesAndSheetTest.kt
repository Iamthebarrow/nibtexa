package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.PenKind
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.serialization.InkJson
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 20: pictures on the page, ink only on the sheet, and custom sheet sizes. */
class PicturesAndSheetTest {
    private val sheet = Sheet() // A4, 1323 x 1871 page units

    private fun p(x: Float, y: Float) = InkPoint(x, y, 0)

    @Test
    fun aStrokeStartingOnTheDeskIsNotWritten() {
        assertEquals(emptyList<InkPoint>(), sheet.clip(listOf(p(-10f, 100f), p(50f, 100f))))
        assertEquals(emptyList<InkPoint>(), sheet.clip(listOf(p(100f, sheet.height + 1f))))
    }

    @Test
    fun aStrokeThatLeavesTheSheetEndsOnItsEdge() {
        val clipped = sheet.clip(listOf(p(100f, 100f), p(200f, 100f), p(sheet.width + 100f, 100f), p(200f, 300f)))
        assertEquals(3, clipped.size)
        assertEquals(sheet.width, clipped.last().x, 0.01f)
        assertEquals(100f, clipped.last().y, 0.01f)
        // Across a corner, the edge it reaches first.
        val corner = sheet.clip(listOf(p(10f, 10f), p(-10f, -30f)))
        assertEquals(0f, corner.last().y, 0.01f)
        assertEquals(5f, corner.last().x, 0.01f)
    }

    @Test
    fun aStrokeOnTheSheetIsKeptWhole() {
        val points = listOf(p(0f, 0f), p(100f, 100f), p(sheet.width, sheet.height))
        assertEquals(points, sheet.clip(points))
    }

    @Test
    fun ruledLinesAreEightMillimetresApartAndTheGridFive() {
        // A page unit is 1/160 inch.
        assertEquals(8f, Paper.RULED.spacing / 160f * 25.4f, 0.001f)
        assertEquals(5.08f, Paper.GRID.spacing / 160f * 25.4f, 0.001f)
        assertEquals(32f, Paper.GRID.spacing, 0.001f)
    }

    @Test
    fun aCustomSheetHasThePdfPagesSize() {
        val slide = Sheet.ofPoints(960f, 540f)
        assertEquals(SheetSize.CUSTOM, slide.size)
        assertEquals(960f / 72f * 160f, slide.width, 0.01f)
        assertEquals(540f / 72f * 160f, slide.height, 0.01f)
        assertEquals(960f, slide.widthPoints, 0.01f)
        assertTrue(slide.contains(slide.width - 1f, slide.height - 1f))
        assertFalse(slide.contains(slide.width + 1f, 0f))
    }

    // ---- Pen kinds (Phase 22) ----------------------------------------------------------------

    @Test
    fun aStrokeKeepsItsKindThroughEditingPastingAndJson() {
        val e = PageEditor(Page("p-1"))
        e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(0f, 0f), p(10f, 10f)), Pen.MATH, PenKind.FELT))
        e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(0f, 50f), p(10f, 60f)), Pen.MATH))
        assertEquals(listOf(PenKind.FELT, PenKind.GEL), e.page.ink.strokes.map { it.kind })
        val pasted = e.paste(Clip(e.page.ink.strokes.take(1)), 5f, 5f).strokes
        assertEquals(PenKind.FELT, e.page.ink.strokes.single { it.id in pasted }.kind)
        // Moving keeps it too.
        e.moveStrokes(setOf(e.page.ink.strokes.first().id), 3f, 3f)
        assertEquals(PenKind.FELT, e.page.ink.strokes.first().kind)

        val json = InkJson.encode(e.page.ink)
        assertEquals(e.page.ink, InkJson.decode(json))
        // Gel is left out, so documents written before kinds stay byte-identical, and still read.
        assertEquals(2, Regex("\"kind\"").findAll(json).count())
        val before = InkDocument(listOf(Stroke(1, ToolType.STYLUS, 0, listOf(p(0f, 0f)), Pen.TEXT)), 2)
        assertFalse("kind" in InkJson.encode(before))
        assertEquals(PenKind.GEL, InkJson.decode(InkJson.encode(before)).strokes.single().kind)
    }

    @Test
    fun kindsDontChangeGrouping() {
        // Grouping goes by the pen's role, not its kind, so what is written in one line with two kinds joins up.
        val mixed = PageEditor(Page("p-1")).apply {
            addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(0f, 0f), p(30f, 20f)), Pen.TEXT, PenKind.FELT))
            addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(40f, 0f), p(70f, 20f)), Pen.TEXT, PenKind.BALLPOINT))
        }
        val plain = PageEditor(Page("p-1")).apply {
            addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(0f, 0f), p(30f, 20f)), Pen.TEXT))
            addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(40f, 0f), p(70f, 20f)), Pen.TEXT))
        }
        assertEquals(AutoRegions.plan(plain.page).newGroups.map { it.strokeIds }, AutoRegions.plan(mixed.page).newGroups.map { it.strokeIds })
    }

    @Test
    fun aPictureIsATypedFigureThatExportsAndResizesUndoably() {
        val e = PageEditor(Page("p-1"))
        val id = e.addPicture("abc.png", Bounds(10f, 10f, 210f, 110f))
        val picture = e.page.region(id)!!
        assertEquals(RegionRole.FIGURE, picture.role)
        assertTrue(picture.typed)
        assertTrue(e.page.isExportable(picture))
        assertFalse(e.page.isStale(picture))

        assertTrue(e.setBounds(id, Bounds(10f, 10f, 410f, 210f)))
        assertEquals(410f, e.page.region(id)!!.bounds.right)
        e.undo()
        assertEquals(210f, e.page.region(id)!!.bounds.right)
        e.undo()
        assertEquals(null, e.page.region(id))
    }

    @Test
    fun inkOverAPictureDoesNotJoinIt() {
        val e = PageEditor(Page("p-1"))
        val id = e.addPicture("abc.png", Bounds(0f, 0f, 400f, 400f))
        e.addStroke(CapturedStroke(ToolType.STYLUS, 0, listOf(p(100f, 100f), p(150f, 120f)), Pen.DRAWING))
        assertEquals(emptySet<Long>(), e.page.region(id)!!.strokeIds)
        // Erasing ink elsewhere leaves the picture, which has no ink to lose.
        e.removeStrokes(e.page.ink.strokes.mapTo(HashSet()) { it.id })
        assertTrue(e.page.region(id) != null)
        // A written region's bounds follow its ink; a picture's can't be set that way for it.
        assertFalse(e.setBounds("r99", Bounds(0f, 0f, 1f, 1f)))
    }
}
