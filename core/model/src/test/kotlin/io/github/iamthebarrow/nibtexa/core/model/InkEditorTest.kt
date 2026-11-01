package io.github.iamthebarrow.nibtexa.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkEditorTest {

    private fun stroke(x: Float = 0f) = CapturedStroke(ToolType.STYLUS, 0, listOf(InkPoint(x, 0f, 0)))

    @Test
    fun addedStrokesGetIncreasingIds() {
        val editor = InkEditor()
        editor.addStroke(stroke())
        editor.addStroke(stroke())

        assertEquals(listOf(1L, 2L), editor.document.strokes.map { it.id })
    }

    @Test
    fun undoAndRedoRestoreDocuments() {
        val editor = InkEditor()
        editor.addStroke(stroke(1f))
        editor.addStroke(stroke(2f))

        assertTrue(editor.undo())
        assertEquals(listOf(1f), editor.document.strokes.map { it.points.single().x })
        assertTrue(editor.canRedo)

        assertTrue(editor.redo())
        assertEquals(2, editor.document.strokes.size)
        assertFalse(editor.canRedo)
    }

    @Test
    fun idsAreNotReusedAfterUndo() {
        val editor = InkEditor()
        editor.addStroke(stroke())
        editor.undo()
        editor.addStroke(stroke())

        assertEquals(2L, editor.document.strokes.single().id)
    }

    @Test
    fun newEditClearsRedo() {
        val editor = InkEditor()
        editor.addStroke(stroke())
        editor.undo()
        editor.addStroke(stroke())

        assertFalse(editor.canRedo)
        assertFalse(editor.redo())
    }

    @Test
    fun undoOnEmptyHistoryReturnsFalse() {
        assertFalse(InkEditor().undo())
    }

    @Test
    fun clearIsUndoableAndNoOpWhenEmpty() {
        val editor = InkEditor()
        editor.clear()
        assertFalse(editor.canUndo)

        editor.addStroke(stroke())
        editor.clear()
        assertTrue(editor.document.strokes.isEmpty())

        editor.undo()
        assertEquals(1, editor.document.strokes.size)
    }

    @Test
    fun historyIsBounded() {
        val editor = InkEditor(historyLimit = 3)
        repeat(5) { editor.addStroke(stroke()) }

        var undos = 0
        while (editor.undo()) undos++

        assertEquals(3, undos)
        assertEquals(2, editor.document.strokes.size)
    }
}
