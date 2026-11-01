package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.Stroke
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EraserTest {
    /** A flat line from x = 0 to x = 100 at y = 50, a point every 10 units, and 10 ms apart. */
    private val points = (0..10).map { InkPoint(it * 10f, 50f, it * 10L, pressure = it / 10f, tiltRadians = 0.5f, orientationRadians = 1f) }
    private val stroke = Stroke(1, ToolType.STYLUS, 1000, points, Pen.TEXT)

    private fun across(x: Float) = listOf(x to 0f, x to 100f)

    // ---- What's left of a stroke ----------------------------------------------------------

    @Test
    fun aPathThatMissesLeavesTheStrokeAlone() {
        assertNull(Eraser.pieces(stroke, listOf(0f to 0f, 100f to 0f), 8f))
    }

    @Test
    fun aPathAcrossTheMiddleLeavesTwoPieces() {
        val pieces = checkNotNull(Eraser.pieces(stroke, across(50f), 8f))
        assertEquals(2, pieces.size)
        assertEquals(0f, pieces[0].first().x, 0f)
        assertEquals(100f, pieces[1].last().x, 0f)
        // Each cut falls at the eraser's edge, to about a third of its radius.
        assertTrue("left cut at ${pieces[0].last().x}", pieces[0].last().x in 39f..42f)
        assertTrue("right cut at ${pieces[1].first().x}", pieces[1].first().x in 58f..61f)
    }

    @Test
    fun aPathOverAnEndTrimsIt() {
        val pieces = checkNotNull(Eraser.pieces(stroke, across(100f), 8f))
        assertEquals(1, pieces.size)
        assertEquals(0f, pieces[0].first().x, 0f)
        assertTrue(pieces[0].last().x < 93f)
    }

    @Test
    fun aPathAlongTheWholeStrokeLeavesNothing() {
        assertEquals(emptyList<List<InkPoint>>(), Eraser.pieces(stroke, listOf(0f to 50f, 100f to 50f), 8f))
    }

    @Test
    fun aTapIsAPathOfOnePoint() {
        assertEquals(2, Eraser.pieces(stroke, listOf(50f to 50f), 8f)?.size)
    }

    @Test
    fun aLongSegmentWithNoPointsNearTheEraserIsStillCut() {
        val sparse = stroke.copy(points = listOf(points.first(), points.last()))
        val pieces = checkNotNull(Eraser.pieces(sparse, across(50f), 8f))
        assertEquals(2, pieces.size)
    }

    @Test
    fun aPointMadeAtACutTakesItsValuesFromItsNeighbours() {
        val cut = checkNotNull(Eraser.pieces(stroke, across(50f), 8f))[0].last()
        assertEquals(cut.x / 100f, cut.pressure!!, 0.01f)
        assertEquals(0.5f, cut.tiltRadians!!, 0.001f)
        assertEquals(1f, cut.orientationRadians!!, 0f)
        assertEquals(cut.x.toDouble(), cut.elapsedMillis.toDouble(), 1.0)
    }

    @Test
    fun channelsAPenDoesNotReportStayMissing() {
        val plain = stroke.copy(points = listOf(InkPoint(0f, 50f, 0), InkPoint(100f, 50f, 100)))
        val cut = checkNotNull(Eraser.pieces(plain, across(50f), 8f))[0].last()
        assertNull(cut.pressure)
        assertNull(cut.tiltRadians)
    }

    @Test
    fun aDotIsEitherGoneOrUntouched() {
        val dot = stroke.copy(points = listOf(InkPoint(50f, 50f, 0)))
        assertEquals(emptyList<List<InkPoint>>(), Eraser.pieces(dot, across(50f), 8f))
        assertNull(Eraser.pieces(dot, across(90f), 8f))
    }

    @Test
    fun aCrumbTooSmallToSeeIsNotKept() {
        val pieces = checkNotNull(Eraser.pieces(stroke.copy(points = points.take(2)), across(8.2f), 8f))
        assertTrue(pieces.all { part -> part.zipWithNext().sumOf { (a, b) -> (b.x - a.x).toDouble() } >= 0.5 })
    }

    // ---- The page -------------------------------------------------------------------------

    private fun captured(x: Float, y: Float, pen: Pen = Pen.TEXT) =
        CapturedStroke(ToolType.STYLUS, 500, (0..10).map { InkPoint(x + it * 10f, y, it * 10L) }, pen)

    private fun editorWithRegion(): Pair<PageEditor, String> {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.addStroke(captured(0f, 200f))
        return editor to checkNotNull(editor.createRegion(setOf(1, 2), RegionRole.TEXT))
    }

    @Test
    fun preciseCutsAStrokeAndItsPiecesKeepWhatItWas() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f, Pen.DRAWING))
        assertTrue(editor.erase(across(50f), 8f, EraserMode.PRECISE))
        val strokes = editor.page.ink.strokes
        assertEquals(listOf(2L, 3L), strokes.map { it.id })
        assertTrue(strokes.all { it.pen == Pen.DRAWING && it.tool == ToolType.STYLUS })
        assertEquals(0L, strokes[1].points.first().elapsedMillis)
        assertTrue("the second piece starts when the pen got there", strokes[1].startTimeMillis > 500)
    }

    @Test
    fun strokeModeTakesTheWholeStroke() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.addStroke(captured(0f, 200f))
        assertTrue(editor.erase(across(50f).map { it.first to it.second / 2 }, 8f, EraserMode.STROKE))
        assertEquals(listOf(2L), editor.page.ink.strokes.map { it.id })
    }

    @Test
    fun aPathOverNothingChangesNothingAndAddsNoUndo() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.undo()
        assertFalse(editor.erase(across(50f), 8f, EraserMode.PRECISE))
        assertTrue(editor.canRedo)
    }

    @Test
    fun theRegionKeepsThePiecesAndGoesOutOfDate() {
        val (editor, id) = editorWithRegion()
        val request = checkNotNull(editor.startRecognition(id))
        editor.applyResult(request, "words")
        assertFalse(editor.page.isStale(editor.page.region(id)!!))

        editor.erase(listOf(50f to 40f, 50f to 60f), 8f, EraserMode.PRECISE)
        val region = editor.page.region(id)!!
        assertEquals(setOf(2L, 3L, 4L), editor.page.membersOf(region))
        assertTrue(editor.page.isStale(region))
    }

    @Test
    fun aResultForTheInkBeforeTheEraseIsDropped() {
        val (editor, id) = editorWithRegion()
        val request = checkNotNull(editor.startRecognition(id))
        editor.erase(listOf(50f to 40f, 50f to 60f), 8f, EraserMode.PRECISE)
        assertFalse(editor.applyResult(request, "late"))
        assertNull(editor.page.region(id)!!.recognizedText)
    }

    @Test
    fun whatWasConfirmedIsKeptButMarkedOutOfDate() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "my words")
        editor.erase(listOf(50f to 40f, 50f to 60f), 8f, EraserMode.PRECISE)
        val region = editor.page.region(id)!!
        assertEquals("my words", region.text)
        assertTrue(editor.page.isStale(region))
    }

    @Test
    fun erasingAllOfARegionRemovesItAndUndoBringsItBack() {
        val (editor, id) = editorWithRegion()
        val before = editor.page
        editor.beginPass()
        editor.erase(listOf(0f to 50f, 100f to 50f), 8f, EraserMode.PRECISE)
        assertNotNull(editor.page.region(id))
        editor.erase(listOf(0f to 200f, 100f to 200f), 8f, EraserMode.PRECISE)
        editor.endPass()
        assertNull(editor.page.region(id))
        assertTrue(editor.page.ink.strokes.isEmpty())
        editor.undo()
        assertEquals(before.ink.strokes, editor.page.ink.strokes)
        assertEquals(before.region(id)?.strokeIds, editor.page.region(id)?.strokeIds)
    }

    @Test
    fun onePassIsOneUndoHoweverOftenItCuts() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        val before = editor.page.ink.strokes
        editor.beginPass()
        for (x in listOf(20f, 50f, 80f)) editor.erase(across(x), 4f, EraserMode.PRECISE)
        editor.endPass()
        assertEquals(4, editor.page.ink.strokes.size)

        assertTrue(editor.undo())
        assertEquals(before, editor.page.ink.strokes)
        assertTrue(editor.redo())
        assertEquals(4, editor.page.ink.strokes.size)
        assertTrue(editor.undo())
        assertTrue("the stroke's own undo is still there", editor.undo())
        assertTrue(editor.page.ink.strokes.isEmpty())
    }

    @Test
    fun anotherPassIsAnotherUndo() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.beginPass()
        editor.erase(across(20f), 4f, EraserMode.PRECISE)
        editor.endPass()
        val afterFirst = editor.page.ink.strokes
        editor.beginPass()
        editor.erase(across(80f), 4f, EraserMode.PRECISE)
        editor.endPass()
        editor.undo()
        assertEquals(afterFirst, editor.page.ink.strokes)
    }

    @Test
    fun aPassThatTouchesNothingLeavesNoUndo() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.undo()
        editor.beginPass()
        editor.erase(across(50f), 8f, EraserMode.PRECISE)
        editor.endPass()
        assertFalse(editor.canUndo)
    }

    @Test
    fun aCancelledPassPutsEverythingBackAndLeavesNoUndo() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        val before = editor.page.ink.strokes
        editor.beginPass()
        editor.erase(across(20f), 4f, EraserMode.PRECISE)
        editor.erase(across(80f), 4f, EraserMode.PRECISE)
        editor.cancelPass()
        assertEquals(before, editor.page.ink.strokes)
        editor.undo()
        assertTrue("that undo took back the stroke, not the erase", editor.page.ink.strokes.isEmpty())
        assertFalse(editor.canUndo)
    }

    @Test
    fun anIdAPieceHadIsNeverUsedAgain() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(captured(0f, 50f))
        editor.erase(across(50f), 8f, EraserMode.PRECISE)
        editor.undo()
        editor.addStroke(captured(0f, 200f))
        assertEquals(4L, editor.page.ink.strokes.last().id)
    }
}
