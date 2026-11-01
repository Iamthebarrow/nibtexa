package io.github.iamthebarrow.nibtexa.core.model

/**
 * Applies edits to an [InkDocument] with bounded undo and redo.
 *
 * Undo keeps whole-document snapshots. Documents are immutable, so a snapshot is cheap
 * to hold, but each edit copies the stroke list. That is acceptable for single pages in
 * Phase 2; Phase 6 replaces it with command-based history when editing tools arrive.
 */
class InkEditor(
    initial: InkDocument = InkDocument.EMPTY,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
    }

    var document: InkDocument = initial
        private set

    private val undoStack = ArrayDeque<InkDocument>()
    private val redoStack = ArrayDeque<InkDocument>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun addStroke(stroke: CapturedStroke) {
        apply(document.withStroke(stroke))
    }

    /** Removes every stroke. Does nothing (and records no history) if the page is empty. */
    fun clear() {
        if (document.strokes.isEmpty()) return
        apply(document.copy(strokes = emptyList()))
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(document)
        document = previous.keepingIdCounterOf(document)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(document)
        document = next.keepingIdCounterOf(document)
        return true
    }

    /** Snapshots carry the id counter from when they were taken; it must never go backwards. */
    private fun InkDocument.keepingIdCounterOf(current: InkDocument) =
        copy(nextStrokeId = maxOf(nextStrokeId, current.nextStrokeId))

    private fun apply(next: InkDocument) {
        undoStack.addLast(document)
        if (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        document = next
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 100
    }
}
