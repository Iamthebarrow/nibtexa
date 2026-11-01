package io.github.iamthebarrow.nibtexa.core.model.notebook

import io.github.iamthebarrow.nibtexa.core.model.Stroke

/**
 * What Copy keeps (Phase 26): the selected strokes and regions as they were, with their results, pictures and graphs,
 * so pasting them anywhere needs no recognizing again. Ids are those of the page it came from until [PageEditor.paste]
 * gives it new ones.
 */
data class Clip(val strokes: List<Stroke> = emptyList(), val regions: List<Region> = emptyList()) {
    val isEmpty: Boolean get() = strokes.isEmpty() && regions.isEmpty()

    val bounds: Bounds? get() = (strokes.map(Bounds::of) + regions.map { it.bounds }).reduceOrNull(Bounds::union)

    /** The picture files it needs kept. */
    val pictures: Set<String> get() = regions.mapNotNullTo(sortedSetOf()) { it.picture }

    /** Confirmed equations, in reading order. */
    val equations: List<String> get() = regions.filter { it.role == RegionRole.MATH && it.isConfirmed }.map { it.confirmedLatex!! }

    /** Confirmed words, in reading order. */
    val words: List<String> get() = regions.filter { it.role != RegionRole.MATH && it.isConfirmed }.map { it.text!! }

    /** Confirmed equations and words in reading order, as other apps get them. Results not confirmed may be wrong, so they stay here. */
    val plain: String get() = regions.filter { it.isConfirmed }.joinToString("\n") { it.confirmedValue!! }

    /** Some confirmed equation has its handwriting, so the user can choose how it pastes. */
    val hasHandwrittenEquations: Boolean get() = regions.any { it.isWrittenEquation }

    /** Something here can only be shown by drawing it: ink with no confirmed result, a picture or a graph. */
    val isDrawn: Boolean get() {
        val confirmed = regions.filter { it.isConfirmed }.flatMapTo(HashSet()) { it.strokeIds }
        return strokes.any { it.id !in confirmed } || regions.any { !it.isConfirmed && it.typed }
    }

    /** Confirmed equations as typed regions without their ink, which is how an equation pastes unless the user asks. */
    fun typedEquations(): Clip {
        val written = regions.filter { it.isWrittenEquation }
        if (written.isEmpty()) return this
        val dropped = written.flatMapTo(HashSet()) { it.strokeIds }
        return Clip(
            strokes.filter { it.id !in dropped },
            regions.map { if (it.isWrittenEquation) it.copy(typed = true, strokeIds = emptySet(), sourceStrokeIds = emptySet(), auto = false) else it },
        )
    }

    /**
     * Where a paste goes, and at what scale so that it fits [sheet]. On the page it came from ([samePage]) it sits just
     * off the original, while that is in view; otherwise at the top left of the view. [repeat] pastes before this one
     * move it along, so they don't stack. [viewWidth] and [viewHeight] are the page area in dp; 0 when not known.
     * With [at], a place the user chose (Phase 29), its top left goes there instead.
     */
    fun placement(sheet: Sheet, view: PageView, viewWidth: Float, viewHeight: Float, samePage: Boolean, repeat: Int, at: Pair<Float, Float>? = null): Placement? =
        placement(Bounds(0f, 0f, sheet.width, sheet.height), view, viewWidth, viewHeight, samePage, repeat, at)

    /** As above, inside [area], the page's writing area (Phase 30). */
    fun placement(area: Bounds, view: PageView, viewWidth: Float, viewHeight: Float, samePage: Boolean, repeat: Int, at: Pair<Float, Float>? = null): Placement? {
        val b = bounds ?: return null
        val roomX = area.right - area.left
        val roomY = area.bottom - area.top
        val width = b.right - b.left
        val height = b.bottom - b.top
        val scale = minOf(1f, if (width > 0f) roomX / width else 1f, if (height > 0f) roomY / height else 1f)
        val nearby = Clipboard.PASTE_OFFSET * (repeat + 1)
        val seen = viewWidth <= 0f || viewHeight <= 0f ||
            (b.left + nearby in view.left..view.left + viewWidth / view.scale && b.top + nearby in view.top..view.top + viewHeight / view.scale)
        val (left, top) = if (at != null) {
            at
        } else if (samePage && seen) {
            b.left + nearby to b.top + nearby
        } else {
            val along = Clipboard.PASTE_OFFSET * repeat
            maxOf(view.left, area.left) + Clipboard.PASTE_MARGIN + along to maxOf(view.top, area.top) + Clipboard.PASTE_MARGIN + along
        }
        return Placement(
            left.coerceIn(area.left, maxOf(area.left, area.right - width * scale)),
            top.coerceIn(area.top, maxOf(area.top, area.bottom - height * scale)),
            scale,
        )
    }

    /** The clip alone on a small sheet of its own size, for its picture in the clipboard panel. */
    fun preview(): Pair<Page, Sheet> {
        val b = bounds ?: return Page("clip") to Sheet()
        val margin = Clipboard.PREVIEW_MARGIN
        val editor = PageEditor(Page("clip"))
        editor.paste(this, margin, margin)
        val sheet = Sheet(
            SheetSize.CUSTOM,
            customWidthInches = (b.right - b.left + 2 * margin) / Sheet.UNITS_PER_INCH,
            customHeightInches = (b.bottom - b.top + 2 * margin) / Sheet.UNITS_PER_INCH,
        )
        return editor.page to sheet
    }

    companion object {
        /**
         * The selection on [page]: the strokes in [strokeIds] and the typed regions in [regionIds]. A written region comes
         * with its result when all its ink is selected. If only part of it is, or its result no longer matches its ink,
         * its strokes come loose, to be grouped and recognized like new ink. A recognition still running isn't kept.
         */
        fun of(page: Page, strokeIds: Set<Long>, regionIds: Set<String>): Clip {
            val strokes = page.ink.strokes.filter { it.id in strokeIds }
            val kept = page.regionsInReadingOrder.filter { r ->
                if (r.typed) {
                    r.id in regionIds
                } else {
                    val members = page.membersOf(r)
                    members.isNotEmpty() && strokeIds.containsAll(members) && !page.isStale(r)
                }
            }
            val keptIds = kept.mapTo(HashSet()) { it.id }
            return Clip(
                strokes,
                kept.map { r ->
                    val members = if (r.typed) emptySet() else page.membersOf(r)
                    val running = r.status == RecognitionStatus.RECOGNIZING
                    r.copy(
                        strokeIds = members,
                        status = if (running) RecognitionStatus.NOT_REQUESTED else r.status,
                        sourceStrokeIds = if (running) emptySet() else r.sourceStrokeIds.filterTo(linkedSetOf()) { it in members },
                        // A curve stays linked only to an equation copied with it.
                        graph = r.graph?.let { g -> g.copy(curves = g.curves.map { c -> if (c.source in keptIds) c else c.copy(source = null) }) },
                    )
                },
            )
        }
    }
}

/** Where a paste's top left goes on the page, and its scale. */
data class Placement(val left: Float, val top: Float, val scale: Float)

/** What a paste added: new strokes, and new typed regions (which have no strokes to select them by). */
data class PastedClip(val strokes: Set<Long> = emptySet(), val typedRegions: Set<String> = emptySet())

private val Region.isConfirmed: Boolean get() = status == RecognitionStatus.CONFIRMED && !confirmedValue.isNullOrBlank()

private val Region.isWrittenEquation: Boolean get() = role == RegionRole.MATH && !typed && isConfirmed

/** The rules of the clipboard list (Phase 26). */
object Clipboard {
    /** Entries kept that aren't pinned, the most recent. Pinned ones stay whatever their number. */
    const val MAX_UNPINNED = 50

    const val PASTE_OFFSET = 24f
    const val PASTE_MARGIN = 40f
    const val PREVIEW_MARGIN = 12f

    /** [items] as the list keeps and shows them: pinned first, then the most recent, without unpinned ones over the limit. */
    fun <T> kept(items: List<T>, pinned: (T) -> Boolean, createdAt: (T) -> Long): List<T> {
        val (pins, others) = items.sortedByDescending(createdAt).partition(pinned)
        return pins + others.take(MAX_UNPINNED)
    }
}
