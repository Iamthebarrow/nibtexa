package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Pen
import io.github.iamthebarrow.nibtexa.core.model.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEditorTest {

    private fun line(x: Float, y: Float, pen: Pen = Pen.MATH) =
        CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, y, 0), InkPoint(x + 10, y + 10, 8)), pen)

    private fun editorWithRegion(): Pair<PageEditor, String> {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 0f))
        editor.addStroke(line(20f, 0f))
        val id = checkNotNull(editor.createRegion(setOf(1, 2), RegionRole.MATH))
        return editor to id
    }

    private fun PageEditor.region(id: String) = checkNotNull(page.region(id))

    // ---- Regions and conversion (Phase 5 rules) -------------------------------------------

    @Test
    fun createRegionTakesStrokesBoundsAndReadingOrder() {
        val (editor, id) = editorWithRegion()
        val region = editor.region(id)

        assertEquals("r1", id)
        assertEquals(setOf(1L, 2L), region.strokeIds)
        assertEquals(Bounds(0f, 0f, 30f, 10f), region.bounds)
        assertEquals(1, region.readingOrder)
        assertEquals(RecognitionStatus.NOT_REQUESTED, region.status)
        assertFalse(editor.page.isStale(region))
    }

    @Test
    fun createRegionIgnoresUnknownStrokesAndRefusesEmptySelections() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 0f))

        assertNull(editor.createRegion(setOf(99), RegionRole.MATH))
        val id = checkNotNull(editor.createRegion(setOf(1, 99), RegionRole.MATH))
        assertEquals(setOf(1L), editor.region(id).strokeIds)
    }

    @Test
    fun aStrokeBelongsToOneRegionOnly() {
        val (editor, first) = editorWithRegion()
        val second = checkNotNull(editor.createRegion(setOf(2), RegionRole.MATH))

        assertEquals(setOf(1L), editor.region(first).strokeIds)
        assertEquals(setOf(2L), editor.region(second).strokeIds)
        assertEquals(2, editor.region(second).readingOrder)
    }

    @Test
    fun resultIsAppliedForTheCurrentStrokes() {
        val (editor, id) = editorWithRegion()
        val request = checkNotNull(editor.startRecognition(id))

        assertTrue(editor.applyResult(request, "x^{2}"))
        assertEquals(RecognitionStatus.RECOGNIZED, editor.region(id).status)
        assertEquals("x^{2}", editor.region(id).recognizedLatex)
        assertFalse(editor.page.isStale(editor.region(id)))
    }

    @Test
    fun lateResultForOlderInkIsDiscarded() {
        val (editor, id) = editorWithRegion()
        val request = checkNotNull(editor.startRecognition(id))
        editor.addStroke(line(5f, 0f)) // centre inside the region: joins it

        assertFalse(editor.applyResult(request, "x"))
        assertNull(editor.region(id).recognizedLatex)
        assertTrue(editor.page.isStale(editor.region(id)))
    }

    /** Phase 11 (ADR-0007): text and headings are recognized too; figures and excluded ink never. */
    @Test
    fun figuresAreNeverRecognizedButScratchIsForSearch() {
        val editor = PageEditor(Page("p1"))
        listOf(Pen.TEXT, Pen.TEXT, Pen.DRAWING, Pen.SCRATCH).forEachIndexed { i, pen -> editor.addStroke(line(i * 100f, 0f, pen)) }
        val roles = listOf(RegionRole.TEXT, RegionRole.HEADING, RegionRole.FIGURE, RegionRole.EXCLUDED)
        val ids = roles.mapIndexed { i, role -> checkNotNull(editor.createRegion(setOf(i + 1L), role)) }

        assertEquals(listOf(true, true, false, true), ids.map { editor.startRecognition(it) != null })
    }

    @Test
    fun reconversionKeepsTheConfirmedLatexUntilTheUserConfirmsAgain() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "mine")
        editor.applyResult(checkNotNull(editor.startRecognition(id)), "recognized")

        val region = editor.region(id)
        assertEquals("mine", region.confirmedLatex)
        assertEquals("recognized", region.recognizedLatex)
        assertFalse(editor.page.isExportable(region))
    }

    @Test
    fun clearRemovesRegionsAndUndoBringsThemBackConfirmed() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "kept")
        editor.clearInk()

        assertTrue(editor.page.regions.isEmpty())
        assertNull(editor.startRecognition(id))

        editor.undo()
        assertEquals("kept", editor.region(id).confirmedLatex)
        assertTrue(editor.page.isExportable(editor.region(id)))
    }

    @Test
    fun erasingAllOfAConfirmedRegionsInkDeletesIt() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a+b")
        editor.removeStrokes(setOf(1))
        assertTrue(editor.page.region(id) != null) // some ink left

        editor.removeStrokes(setOf(2))
        assertNull(editor.page.region(id))

        editor.undo()
        assertEquals("a+b", editor.region(id).confirmedLatex)
        assertEquals(setOf(1L, 2L), editor.region(id).strokeIds)
    }

    @Test
    fun aRegionWhoseInkMovesToANewRegionIsReplaced() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a+b")
        val other = checkNotNull(editor.createRegion(setOf(1, 2), RegionRole.TEXT))

        assertEquals(listOf(other), editor.page.regions.map { it.id })
        editor.undo()
        assertEquals(listOf(id), editor.page.regions.map { it.id })
    }

    // ---- Undo and redo (Phase 6) ----------------------------------------------------------

    @Test
    fun undoRevertsTheLastActionWhateverItIs() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a+b")

        assertTrue(editor.undo()) // the confirmation
        assertEquals(RecognitionStatus.NOT_REQUESTED, editor.region(id).status)
        assertNull(editor.region(id).confirmedLatex)

        assertTrue(editor.undo()) // the region
        assertNull(editor.page.region(id))
        assertEquals(2, editor.page.ink.strokes.size)

        assertTrue(editor.undo()) // the second stroke
        assertEquals(1, editor.page.ink.strokes.size)

        assertTrue(editor.redo())
        assertTrue(editor.redo())
        assertTrue(editor.redo())
        assertEquals("a+b", editor.region(id).confirmedLatex)
        assertTrue(editor.page.isExportable(editor.region(id)))
    }

    @Test
    fun undoNeverRollsBackARecognitionResult() {
        val (editor, id) = editorWithRegion()
        editor.setCaption(id, "first")
        val request = checkNotNull(editor.startRecognition(id))
        editor.applyResult(request, "x")

        editor.undo() // the caption, not the result
        assertNull(editor.region(id).caption)
        assertEquals("x", editor.region(id).recognizedLatex)
        assertEquals(RecognitionStatus.RECOGNIZED, editor.region(id).status)
    }

    @Test
    fun undoingAStrokeThatJoinedARegionRestoresTheRegion() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a")
        editor.addStroke(line(5f, 0f))
        assertTrue(editor.page.isStale(editor.region(id)))

        editor.undo()
        assertEquals(setOf(1L, 2L), editor.region(id).strokeIds)
        assertTrue(editor.page.isExportable(editor.region(id)))
    }

    @Test
    fun strokeIdsAndRegionIdsAreNeverReused() {
        val (editor, id) = editorWithRegion()
        editor.undo() // region r1
        editor.undo() // stroke 2
        editor.addStroke(line(50f, 50f))
        val again = editor.createRegion(setOf(1), RegionRole.MATH)

        assertEquals(listOf(1L, 3L), editor.page.ink.strokes.map { it.id })
        assertEquals("r2", again)
        assertFalse(editor.canRedo)
        assertEquals("r1", id)
    }

    @Test
    fun erasingIsUndoableAndRestoresMembership() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a")
        assertTrue(editor.removeStrokes(setOf(2)))
        assertTrue(editor.page.isStale(editor.region(id)))

        editor.undo()
        assertEquals(listOf(1L, 2L), editor.page.ink.strokes.map { it.id })
        assertTrue(editor.page.isExportable(editor.region(id)))
        assertFalse(editor.removeStrokes(setOf(99)))
    }

    @Test
    fun movingKeepsIdsSoConfirmedRegionsStayCurrent() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a")
        assertTrue(editor.moveStrokes(setOf(1, 2), 100f, 50f))

        val region = editor.region(id)
        assertEquals(Bounds(100f, 50f, 130f, 60f), region.bounds)
        assertTrue(editor.page.isExportable(region))
        assertEquals(100f, editor.page.ink.strokes.first().points.first().x)

        editor.undo()
        assertEquals(Bounds(0f, 0f, 30f, 10f), editor.region(id).bounds)
    }

    @Test
    fun pasteAddsCopiesWithNewIdsOutsideRegions() {
        val (editor, id) = editorWithRegion()
        val copies = editor.paste(Clip(editor.strokesOf(id)), 0f, 200f).strokes

        assertEquals(setOf(3L, 4L), copies)
        assertEquals(200f, editor.page.ink.strokes.first { it.id == 3L }.points.first().y)
        assertEquals(setOf(1L, 2L), editor.region(id).strokeIds)
        editor.undo()
        assertEquals(2, editor.page.ink.strokes.size)
    }

    @Test
    fun textAndHeadingsSwapWithoutLosingTheConfirmation() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 0f, Pen.TEXT))
        val id = editor.createRegion(setOf(1), RegionRole.TEXT)!!
        editor.confirm(id, "Transfer functions")

        assertTrue(editor.setTextKind(id, 2))
        assertEquals(RegionRole.HEADING, editor.region(id).role)
        assertEquals(2, editor.region(id).headingLevel)
        assertTrue(editor.page.isExportable(editor.region(id)))
        assertFalse(editor.setTextKind(id, 2))
        assertTrue(editor.changeRole(id, RegionRole.TEXT))
        assertEquals("Transfer functions", editor.region(id).text)
        assertTrue(editor.page.isExportable(editor.region(id)))

        editor.undo()
        assertEquals(RegionRole.HEADING, editor.region(id).role)
        editor.undo() // one step back to text, level and all
        assertEquals(RegionRole.TEXT, editor.region(id).role)
        assertEquals(1, editor.region(id).headingLevel)
    }

    @Test
    fun roleChangeNeedsANewConfirmationAndIsUndoable() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "a")
        assertTrue(editor.changeRole(id, RegionRole.TEXT))

        assertEquals(RecognitionStatus.NOT_REQUESTED, editor.region(id).status)
        assertFalse(editor.page.isExportable(editor.region(id)))
        assertTrue(editor.confirm(id, "Some prose"))
        assertEquals("Some prose", editor.region(id).text)
        assertEquals("a", editor.region(id).confirmedLatex)

        editor.undo()
        editor.undo()
        assertEquals(RegionRole.MATH, editor.region(id).role)
        assertTrue(editor.page.isExportable(editor.region(id)))
    }

    @Test
    fun figuresExportTheirInkAndExcludedRegionsNever() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 0f, Pen.DRAWING))
        editor.addStroke(line(0f, 100f, Pen.SCRATCH))
        val figure = checkNotNull(editor.createRegion(setOf(1), RegionRole.FIGURE))
        val scratch = checkNotNull(editor.createRegion(setOf(2), RegionRole.EXCLUDED))

        assertTrue(editor.page.isExportable(editor.region(figure)))
        assertFalse(editor.page.isExportable(editor.region(scratch)))
        assertFalse(editor.confirm(figure, "x"))
    }

    @Test
    fun readingOrderCanBeMovedAndDerivedFromPosition() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 200f)) // bottom
        editor.addStroke(line(30f, 0f)) // top right, on the same line
        editor.addStroke(line(0f, 0f)) // top left
        val bottom = editor.createRegion(setOf(1), RegionRole.MATH)!!
        val topRight = editor.createRegion(setOf(2), RegionRole.MATH)!!
        val topLeft = editor.createRegion(setOf(3), RegionRole.MATH)!!

        // By position from the start, without asking.
        assertEquals(listOf(topLeft, topRight, bottom), editor.page.regionsInReadingOrder.map { it.id })
        assertFalse(editor.page.manualOrder)
        assertFalse(editor.orderByPosition())

        assertTrue(editor.moveInReadingOrder(bottom, 0))
        assertEquals(listOf(bottom, topLeft, topRight), editor.page.regionsInReadingOrder.map { it.id })
        assertTrue(editor.page.manualOrder)
        assertFalse(editor.moveInReadingOrder(bottom, 0))

        // The user's order survives later edits.
        editor.moveStrokes(setOf(1), 0f, -300f)
        assertEquals(listOf(bottom, topLeft, topRight), editor.page.regionsInReadingOrder.map { it.id })
        editor.undo()

        assertTrue(editor.orderByPosition())
        assertFalse(editor.page.manualOrder)
        assertEquals(listOf(topLeft, topRight, bottom), editor.page.regionsInReadingOrder.map { it.id })

        editor.undo()
        assertTrue(editor.page.manualOrder)
        assertEquals(listOf(bottom, topLeft, topRight), editor.page.regionsInReadingOrder.map { it.id })
        editor.undo()
        assertFalse(editor.page.manualOrder)
        assertEquals(listOf(topLeft, topRight, bottom), editor.page.regionsInReadingOrder.map { it.id })
    }

    @Test
    fun movingInkReordersByPosition() {
        val editor = PageEditor(Page("p1"))
        editor.addStroke(line(0f, 0f))
        editor.addStroke(line(0f, 200f))
        val first = editor.createRegion(setOf(1), RegionRole.MATH)!!
        val second = editor.createRegion(setOf(2), RegionRole.MATH)!!
        assertEquals(listOf(first, second), editor.page.regionsInReadingOrder.map { it.id })

        editor.moveStrokes(setOf(1), 0f, 400f)
        assertEquals(listOf(second, first), editor.page.regionsInReadingOrder.map { it.id })
        editor.undo()
        assertEquals(listOf(first, second), editor.page.regionsInReadingOrder.map { it.id })
    }

    @Test
    fun headingLevelAndCaptionAreClampedAndTrimmed() {
        val (editor, id) = editorWithRegion()
        editor.setHeadingLevel(id, 9)
        editor.setCaption(id, "  Direct form  ")

        assertEquals(3, editor.region(id).headingLevel)
        assertEquals("Direct form", editor.region(id).caption)
        assertTrue(editor.setCaption(id, "   "))
        assertNull(editor.region(id).caption)
    }

    @Test
    fun deletingARegionLeavesItsInkAndIsUndoable() {
        val (editor, id) = editorWithRegion()
        editor.confirm(id, "kept")

        assertTrue(editor.deleteRegion(id))
        assertTrue(editor.page.regions.isEmpty())
        assertEquals(2, editor.page.ink.strokes.size)
        editor.undo()
        assertEquals("kept", editor.region(id).confirmedLatex)
    }

    @Test
    fun historyIsBounded() {
        val editor = PageEditor(Page("p1"), historyLimit = 3)
        repeat(5) { editor.addStroke(line(it * 20f, 0f)) }
        repeat(3) { assertTrue(editor.undo()) }
        assertFalse(editor.undo())
        assertEquals(2, editor.page.ink.strokes.size)
    }
}
