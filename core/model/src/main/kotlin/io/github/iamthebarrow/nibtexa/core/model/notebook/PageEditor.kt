package io.github.iamthebarrow.nibtexa.core.model.notebook

import kotlin.math.abs
import io.github.iamthebarrow.nibtexa.core.model.CapturedStroke
import io.github.iamthebarrow.nibtexa.core.model.InkDocument
import io.github.iamthebarrow.nibtexa.core.model.InkPoint
import io.github.iamthebarrow.nibtexa.core.model.Stroke

/** Identifies one recognition request: the region and the exact strokes it was made for. */
data class RecognitionRequest(val regionId: String, val strokeIds: Set<Long>)

/**
 * Applies edits to a [Page] with command-based undo and redo covering ink and regions.
 *
 * Conversion rules:
 * - a new stroke whose centre falls inside a region of the same role joins it, which makes
 *   the region's result stale;
 * - a stroke belongs to at most one region, so export regions never overlap;
 * - a recognition result is applied only if it was produced for the strokes the region
 *   holds now; a late result for older ink is discarded;
 * - confirming stores the user's value separately from the recognizer's.
 *
 * Automatic regions (ADR-0007): [autoGroup] applies an [AutoGrouping]. Only regions marked
 * [Region.auto] grow by themselves; confirming, re-roling, captioning or remaking a region
 * makes it the user's. Any region left with no strokes (erased, deleted or taken by a new
 * region) is removed in the same step, so undo brings it back (Phase 14).
 *
 * Reading order (Phase 14): until the user moves a region by hand ([Page.manualOrder]), every
 * edit re-orders the regions by position ([AutoRegions.byPosition]), in the same undo entry.
 *
 * History: user actions are undoable; recognition results and failures are not, and undo
 * never rolls them back, because each command reverts only what it changed (strokes added,
 * removed or replaced; regions added or removed; specific region fields). Grouping that
 * follows a new stroke is folded into that stroke's entry, so one undo takes back both.
 */
class PageEditor(initial: Page, private val historyLimit: Int = DEFAULT_HISTORY_LIMIT) {
    var page: Page = initial
        private set

    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    private enum class PassState { NONE, OPEN, HAS_ENTRY }

    private var pass = PassState.NONE

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ---- Ink ------------------------------------------------------------------------------

    fun addStroke(captured: CapturedStroke) {
        val stroke = Stroke(page.ink.nextStrokeId, captured.tool, captured.startTimeMillis, captured.points, captured.pen, captured.kind)
        val bounds = Bounds.of(stroke)
        val role = RegionRole.forPen(stroke.pen)
        // Typed regions (imports, pictures) hold no ink, so a stroke over one stays outside it.
        val host = page.regions.filter { it.role == role && !it.typed && it.bounds.contains(bounds.centerX, bounds.centerY) }
            .minByOrNull { it.readingOrder }
        val changes = mutableListOf<Change>(Change.StrokesAdded(listOf(stroke)))
        if (host != null) changes += Change.RegionFields(host, host.copy(strokeIds = host.strokeIds + stroke.id), Field.STROKES)
        commit(Change.Written(Change.Composite(changes)))
    }

    /**
     * Removes the strokes (erase, delete). Regions keep their claim, so undo restores
     * membership; a region left with no strokes is removed.
     */
    fun removeStrokes(ids: Set<Long>): Boolean {
        val removed = page.ink.strokes.filter { it.id in ids }
        if (removed.isEmpty()) return false
        val remaining = page.ink.strokes.mapNotNullTo(HashSet()) { s -> s.id.takeIf { it !in ids } }
        val emptied = page.regions.filter { r -> r.strokeIds.none(remaining::contains) && r.strokeIds.any(ids::contains) }
        commit(Change.Composite(listOf(Change.StrokesRemoved(removed)) + emptied.map(Change::RegionRemoved)))
        return true
    }

    /**
     * Erases along [path] (Phase 35). What's left of a cut stroke goes in under new ids, so a region's result goes out of
     * date and is read again, and the pieces stay in the stroke's region. A region left with no ink is removed.
     */
    fun erase(path: List<Pair<Float, Float>>, radius: Float, mode: EraserMode): Boolean {
        if (path.isEmpty()) return false
        val near = page.ink.strokes.filter { Eraser.near(it, path, radius) }
        val cut: List<Pair<Stroke, List<List<InkPoint>>>> = when (mode) {
            EraserMode.STROKE -> Lasso.hit(path, radius, near).let { hit -> near.filter { it.id in hit }.map { it to emptyList() } }
            EraserMode.PRECISE -> near.mapNotNull { stroke -> Eraser.pieces(stroke, path, radius)?.let { stroke to it } }
        }
        if (cut.isEmpty()) return false

        var nextId = page.ink.nextStrokeId
        val pieces = cut.associate { (stroke, parts) ->
            stroke.id to parts.map { part ->
                val first = part.first().elapsedMillis
                stroke.copy(
                    id = nextId++,
                    startTimeMillis = stroke.startTimeMillis + first,
                    points = part.map { it.copy(elapsedMillis = it.elapsedMillis - first) },
                )
            }
        }
        val gone = pieces.keys
        val added = pieces.values.flatten()
        val left = page.ink.strokes.mapNotNullTo(HashSet()) { s -> s.id.takeIf { it !in gone } }
        val changes = mutableListOf<Change>(Change.StrokesRemoved(cut.map { it.first }))
        if (added.isNotEmpty()) changes += Change.StrokesAdded(added)
        for (region in page.regions.filter { r -> r.strokeIds.any(gone::contains) }) {
            val mine = region.strokeIds.filter(gone::contains).flatMap { id -> pieces.getValue(id) }
            when {
                mine.isNotEmpty() -> changes += Change.RegionFields(region, region.copy(strokeIds = region.strokeIds - gone + mine.map { it.id }), Field.STROKES)
                region.strokeIds.none(left::contains) -> changes += Change.RegionRemoved(region)
            }
        }
        commit(Change.Composite(changes))
        return true
    }

    /**
     * Until [endPass], everything committed is one undo entry (Phase 35): one pass of the eraser, however many strokes it
     * crosses on the way.
     */
    fun beginPass() {
        pass = PassState.OPEN
    }

    fun endPass() {
        pass = PassState.NONE
    }

    /** Takes back everything in the pass, leaving no undo entry: it wasn't an erase after all. */
    fun cancelPass() {
        if (pass == PassState.HAS_ENTRY) undoStack.removeLastOrNull()?.let { page = refreshBounds(it.revert(page)) }
        pass = PassState.NONE
    }

    /**
     * Replaces a stroke's points with a perfect shape's (Phase 23), as its own undo entry, so one undo
     * brings the hand-drawn stroke back. Grouping that follows joins this entry, as it does for a new stroke.
     */
    fun convertStroke(id: Long, points: List<InkPoint>): Boolean {
        val before = page.ink.strokes.firstOrNull { it.id == id } ?: return false
        commit(Change.Written(Change.StrokesReplaced(listOf(before), listOf(before.copy(points = points)))))
        return true
    }

    /** Moves strokes by (dx, dy) canvas units. Ids are kept, so regions stay current. */
    fun moveStrokes(ids: Set<Long>, dx: Float, dy: Float): Boolean {
        if (dx == 0f && dy == 0f) return false
        val before = page.ink.strokes.filter { it.id in ids }
        if (before.isEmpty()) return false
        val after = before.map { s -> s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) }
        commit(Change.StrokesReplaced(before, after))
        return true
    }

    /**
     * Adds a copy of [clip] with new ids, its top left at ([left], [top]) and made [scale] times its size, as one undo
     * (Phase 26). Regions keep their results, so nothing is recognized again, and a curve follows an equation pasted with
     * it. Grouping that follows any loose ink joins this entry, as it does for a new stroke.
     */
    fun paste(clip: Clip, left: Float, top: Float, scale: Float = 1f): PastedClip {
        val from = clip.bounds ?: return PastedClip()
        fun x(value: Float) = left + (value - from.left) * scale
        fun y(value: Float) = top + (value - from.top) * scale
        var nextStroke = page.ink.nextStrokeId
        val strokeIds = clip.strokes.associate { it.id to nextStroke++ }
        val strokes = clip.strokes.map { s -> s.copy(id = strokeIds.getValue(s.id), points = s.points.map { it.copy(x = x(it.x), y = y(it.y)) }) }
        var number = page.nextRegionNumber
        val regionIds = clip.regions.associate { it.id to "r${number++}" }
        var order = page.regions.maxOfOrNull { it.readingOrder } ?: 0
        val regions = clip.regions.map { r ->
            r.copy(
                id = regionIds.getValue(r.id),
                strokeIds = r.strokeIds.mapNotNullTo(linkedSetOf(), strokeIds::get),
                sourceStrokeIds = r.sourceStrokeIds.mapNotNullTo(linkedSetOf(), strokeIds::get),
                bounds = Bounds(x(r.bounds.left), y(r.bounds.top), x(r.bounds.right), y(r.bounds.bottom)),
                readingOrder = ++order,
                graph = r.graph?.let { g -> g.copy(curves = g.curves.map { c -> c.copy(source = c.source?.let(regionIds::get)) }) },
            )
        }
        val added = if (strokes.isEmpty()) emptyList() else listOf<Change>(Change.StrokesAdded(strokes))
        commit(Change.Written(Change.Composite(added + regions.map(Change::RegionAdded))))
        return PastedClip(strokes.mapTo(linkedSetOf()) { it.id }, regions.filter { it.typed }.mapTo(linkedSetOf()) { it.id })
    }

    /**
     * Brings everything on the page inside [area] (Phase 30), together, as one undo ([PageFit.arranged]): what is side by side stays so, words
     * wrap and push down what is below them, and a region's ink moves together, keeping its shape. Loose ink moves as one. Returns whether anything changed.
     */
    fun fitTo(area: Bounds, measure: PageFit.Measure = PageFit.ESTIMATE): Boolean {
        val changes = mutableListOf<Change>()
        val typed = page.regions.filter { it.typed }
        val done = HashSet<Long>()
        val groups = (page.regions.filterNot { it.typed }.map { page.membersOf(it).filter(done::add).toSet() } + setOf(page.ink.strokes.map { it.id }.filter(done::add).toSet()))
            .filter { it.isNotEmpty() }.map { ids -> page.ink.strokes.filter { it.id in ids } }
        val boxes = typed.map { PageFit.Box(it.bounds, PageFit.kindOf(it)) } + groups.map { PageFit.Box(Bounds.of(it)!!, PageFit.Kind.INK) }
        val placed = PageFit.arranged(boxes, area) { i, width -> measure.height(typed[i], width) }
        for ((i, region) in typed.withIndex()) {
            if (changed(placed[i], region.bounds)) changes += Change.RegionFields(region, region.copy(bounds = placed[i]), Field.BOUNDS)
        }
        val before = mutableListOf<io.github.iamthebarrow.nibtexa.core.model.Stroke>()
        val after = mutableListOf<io.github.iamthebarrow.nibtexa.core.model.Stroke>()
        for ((i, strokes) in groups.withIndex()) {
            val from = boxes[typed.size + i].bounds
            val to = placed[typed.size + i]
            if (!changed(to, from)) continue
            val width = from.right - from.left
            val scale = if (width > 0f) (to.right - to.left) / width else if (from.bottom > from.top) (to.bottom - to.top) / (from.bottom - from.top) else 1f
            before += strokes
            after += strokes.map { it.copy(points = PageFit.placed(it.points, from, scale, to.left - from.left, to.top - from.top)) }
        }
        if (before.isNotEmpty()) changes += Change.StrokesReplaced(before, after)
        if (changes.isEmpty()) return false
        commit(Change.Composite(changes))
        return true
    }

    private fun changed(a: Bounds, b: Bounds) = abs(a.left - b.left) > 0.01f || abs(a.top - b.top) > 0.01f || abs(a.right - b.right) > 0.01f || abs(a.bottom - b.bottom) > 0.01f

    /** Removes all ink, and with it every region that had ink. Undo restores both. */
    fun clearInk() {
        removeStrokes(page.ink.strokes.mapTo(HashSet()) { it.id })
    }

    // ---- Regions --------------------------------------------------------------------------

    /**
     * Creates a region from the given strokes (taking them out of any other region) and
     * returns its id, or null if none of the ids is on the page. Regions that lose all their
     * ink on the page to it are removed: the new region replaces them.
     */
    fun createRegion(strokeIds: Set<Long>, role: RegionRole): String? {
        val strokes = page.ink.strokes.filter { it.id in strokeIds }
        val bounds = Bounds.of(strokes) ?: return null
        val ids = strokes.mapTo(linkedSetOf()) { it.id }
        val changes = page.regions.filter { it.strokeIds.any(ids::contains) }
            .map { if (page.membersOf(it).all(ids::contains)) Change.RegionRemoved(it) else Change.RegionFields(it, it.copy(strokeIds = it.strokeIds - ids), Field.STROKES) }
            .toMutableList()
        val id = "r${page.nextRegionNumber}"
        val order = (page.regions.maxOfOrNull { it.readingOrder } ?: 0) + 1
        changes += Change.RegionAdded(Region(id, role, ids, bounds, order))
        commit(Change.Composite(changes))
        return id
    }

    /**
     * Places a picture (Phase 20): a typed figure region showing [file] in [bounds], undoable.
     * Returns its id.
     */
    fun addPicture(file: String, bounds: Bounds): String {
        val id = "r${page.nextRegionNumber}"
        val order = (page.regions.maxOfOrNull { it.readingOrder } ?: 0) + 1
        commit(Change.RegionAdded(Region(id, RegionRole.FIGURE, emptySet(), bounds, order, typed = true, picture = file)))
        return id
    }

    /** Places a graph (Phase 25): a typed figure region drawing [graph] in [bounds], undoable. Returns its id. */
    fun addGraph(graph: Graph, bounds: Bounds): String {
        val id = "r${page.nextRegionNumber}"
        val order = (page.regions.maxOfOrNull { it.readingOrder } ?: 0) + 1
        commit(Change.RegionAdded(Region(id, RegionRole.FIGURE, emptySet(), bounds, order, typed = true, graph = graph)))
        return id
    }

    /** Changes what a graph shows, as one undoable step. */
    fun setGraph(regionId: String, graph: Graph): Boolean {
        if (page.region(regionId)?.graph == null) return false
        return setField(regionId, Field.GRAPH) { it.copy(graph = graph) }
    }

    /** Places a diagram (Phase 31): a typed figure region drawing [diagram] in [bounds], undoable. Returns its id. */
    fun addDiagram(diagram: Diagram, bounds: Bounds): String {
        val id = "r${page.nextRegionNumber}"
        val order = (page.regions.maxOfOrNull { it.readingOrder } ?: 0) + 1
        commit(Change.RegionAdded(Region(id, RegionRole.FIGURE, emptySet(), bounds, order, typed = true, diagram = diagram)))
        return id
    }

    /** Changes a diagram and where it sits, as one undoable step. */
    fun setDiagram(regionId: String, diagram: Diagram, bounds: Bounds): Boolean {
        val region = page.region(regionId) ?: return false
        if (region.diagram == null || (region.diagram == diagram && region.bounds == bounds)) return false
        commit(Change.RegionFields(region, region.copy(diagram = diagram, bounds = bounds), Field.DIAGRAM, Field.BOUNDS))
        return true
    }

    /** Diagrams [merged] into one, as one undoable step with the change to [regionId], which holds them now. */
    fun mergeDiagrams(regionId: String, diagram: Diagram, bounds: Bounds, merged: Set<String>): Boolean {
        val region = page.region(regionId)?.takeIf { it.diagram != null } ?: return false
        val removed = page.regions.filter { it.id in merged }
        commit(Change.Composite(removed.map { Change.RegionRemoved(it) } + Change.RegionFields(region, region.copy(diagram = diagram, bounds = bounds), Field.DIAGRAM, Field.BOUNDS)))
        return true
    }

    /**
     * Graphs following newly confirmed equations ([latex] by the math region's id), for the same undo entry as the
     * confirmation. A graph follows only what is confirmed, not each recognition.
     */
    private fun graphsFollowing(latex: Map<String, String>): List<Change> = page.regions.mapNotNull { region ->
        val graph = region.graph ?: return@mapNotNull null
        val curves = graph.curves.map { c -> c.source?.let(latex::get)?.let { c.copy(latex = it) } ?: c }
        if (curves == graph.curves) null else Change.RegionFields(region, region.copy(graph = graph.copy(curves = curves)), Field.GRAPH)
    }

    /** Resizes or places a typed region (a picture's corner handle, Phase 20). Written regions follow their ink. */
    fun setBounds(regionId: String, bounds: Bounds): Boolean {
        if (page.region(regionId)?.typed != true) return false
        return setField(regionId, Field.BOUNDS) { it.copy(bounds = bounds) }
    }

    /** Deletes the region. Its ink stays on the page. */
    fun deleteRegion(regionId: String): Boolean {
        val region = page.region(regionId) ?: return false
        commit(Change.RegionRemoved(region))
        return true
    }

    /**
     * Changes the role. The region needs confirming again under the new role, except between
     * text and a heading, which are the same words set differently: the confirmation stays.
     */
    fun changeRole(regionId: String, role: RegionRole): Boolean {
        val region = page.region(regionId) ?: return false
        if (region.role == role) return false
        // A typed region has no ink to recognize again: it can only become text or a heading.
        if (region.typed && !(region.role in WORDS && role in WORDS)) return false
        if (region.role in WORDS && role in WORDS) {
            commit(Change.RegionFields(region, region.copy(role = role, auto = false), Field.ROLE, Field.AUTO))
            return true
        }
        val after = region.copy(role = role, status = RecognitionStatus.NOT_REQUESTED, error = null, auto = false)
        commit(Change.RegionFields(region, after, Field.ROLE, Field.CONFIRMATION, Field.AUTO))
        return true
    }

    /**
     * Moves the region to [index] in reading order (0 = first). From then on the page keeps the
     * user's order ([Page.manualOrder]) until [orderByPosition].
     */
    fun moveInReadingOrder(regionId: String, index: Int): Boolean {
        val ordered = page.regionsInReadingOrder.toMutableList()
        val from = ordered.indexOfFirst { it.id == regionId }
        if (from < 0) return false
        val to = index.coerceIn(0, ordered.lastIndex)
        if (from == to) return false
        ordered.add(to, ordered.removeAt(from))
        commit(Change.Composite(listOfNotNull(manualOrder(true)) + orderChanges(ordered)))
        return true
    }

    /**
     * Orders regions by position ([AutoRegions.byPosition]: columns, then lines top to bottom,
     * each left to right), and goes back to following the positions after every edit.
     */
    fun orderByPosition(): Boolean {
        val changes = listOfNotNull(manualOrder(false)) + orderChanges(positionOrder(page))
        if (changes.isEmpty()) return false
        commit(Change.Composite(changes))
        return true
    }

    private fun manualOrder(value: Boolean): Change? = if (page.manualOrder == value) null else Change.ManualOrder(value)

    private fun positionOrder(p: Page): List<Region> {
        val byId = p.regions.associateBy { it.id }
        return AutoRegions.byPosition(p.regions.map { it.id to it.bounds }, AutoRegions.scale(p), p.columns).map(byId::getValue)
    }

    private fun orderChanges(ordered: List<Region>): List<Change> = ordered.mapIndexedNotNull { i, region ->
        if (region.readingOrder == i + 1) null else Change.RegionFields(region, region.copy(readingOrder = i + 1), Field.ORDER)
    }

    /** [change], then re-ordering by position when the page follows positions. */
    private fun withPositionOrder(change: Change): Change {
        val after = refreshBounds(change.apply(page))
        if (after.manualOrder) return change
        val order = orderChanges(positionOrder(after))
        if (order.isEmpty()) return change
        val combined = Change.Composite(listOf(change) + order)
        return if (change is Change.Written) Change.Written(combined) else combined
    }

    /**
     * Makes a text or heading region plain text ([level] null) or a heading of [level] 1–3, in one
     * undoable step, keeping its confirmed words (Phase 15). Either ends a theorem-style block.
     */
    fun setTextKind(regionId: String, level: Int?): Boolean {
        val region = page.region(regionId)?.takeIf { it.role in WORDS } ?: return false
        val role = if (level == null) RegionRole.TEXT else RegionRole.HEADING
        val heading = level?.coerceIn(1, 3) ?: region.headingLevel
        if (region.role == role && region.headingLevel == heading && region.theorem == null) return false
        // Plain text or a heading is not a theorem-style block (Phase 16).
        commit(Change.RegionFields(region, region.copy(role = role, headingLevel = heading, theorem = null, auto = false), Field.ROLE, Field.HEADING, Field.THEOREM, Field.AUTO))
        return true
    }

    fun setHeadingLevel(regionId: String, level: Int): Boolean {
        val region = page.region(regionId) ?: return false
        val clamped = level.coerceIn(1, 3)
        if (region.headingLevel == clamped) return false
        commit(Change.RegionFields(region, region.copy(headingLevel = clamped), Field.HEADING))
        return true
    }

    /** Sets where the region sits across the exported page; null goes back to the default. */
    fun setAlign(regionId: String, align: MathAlign?): Boolean {
        val region = page.region(regionId) ?: return false
        if (region.align == align) return false
        commit(Change.RegionFields(region, region.copy(align = align), Field.ALIGN))
        return true
    }

    fun setCaption(regionId: String, caption: String?): Boolean {
        val region = page.region(regionId) ?: return false
        val value = caption?.trim()?.takeIf { it.isNotEmpty() }
        if (region.caption == value) return false
        commit(Change.RegionFields(region, region.copy(caption = value, auto = false), Field.CAPTION, Field.AUTO))
        return true
    }

    /**
     * Confirms the user's value for the ink the region holds now: LaTeX for math, the
     * transcription for text and headings.
     */
    fun confirm(regionId: String, value: String, marks: List<TextMark> = emptyList()): Boolean {
        val region = page.region(regionId) ?: return false
        if (!region.needsConfirmation) return false
        val members = page.membersOf(region)
        if (members.isEmpty() && !region.typed) return false
        val follow = if (region.role == RegionRole.MATH) graphsFollowing(mapOf(regionId to value)) else emptyList()
        commit(Change.Composite(listOf(confirmation(region, members, value, marks)) + follow))
        return true
    }

    /**
     * Moves a selection of ink and typed regions (Phase 18) by (dx, dy) page units, in one
     * undoable step. Written regions follow their ink; typed ones move themselves.
     */
    fun moveSelection(strokeIds: Set<Long>, regionIds: Set<String>, dx: Float, dy: Float): Boolean {
        if (dx == 0f && dy == 0f) return false
        val before = page.ink.strokes.filter { it.id in strokeIds }
        val after = before.map { s -> s.copy(points = s.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) }
        val typed = page.regions.filter { it.typed && it.id in regionIds }
        if (before.isEmpty() && typed.isEmpty()) return false
        val changes = listOfNotNull(Change.StrokesReplaced(before, after).takeIf { before.isNotEmpty() }) + typed.map { r ->
            val b = r.bounds
            Change.RegionFields(r, r.copy(bounds = Bounds(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)), Field.BOUNDS)
        }
        commit(Change.Composite(changes))
        return true
    }

    /** Deletes regions, typed or written, in one undoable step. Their ink stays. */
    fun deleteRegions(regionIds: Set<String>): Boolean {
        val doomed = page.regions.filter { it.id in regionIds }
        if (doomed.isEmpty()) return false
        commit(Change.Composite(doomed.map(Change::RegionRemoved)))
        return true
    }

    // ---- Structure (Phase 16) ---------------------------------------------------------------

    /**
     * Makes a text or heading region a theorem-style block of [kind] (it becomes text), or plain
     * text again (null), keeping its confirmed words.
     */
    fun setTheorem(regionId: String, kind: TheoremKind?): Boolean {
        val region = page.region(regionId)?.takeIf { it.role in WORDS } ?: return false
        val after = region.copy(role = RegionRole.TEXT, theorem = kind, auto = false)
        if (after == region) return false
        commit(Change.RegionFields(region, after, Field.ROLE, Field.THEOREM, Field.AUTO))
        return true
    }

    /** A list item's depth, 0–2; null follows the position. */
    fun setListLevel(regionId: String, level: Int?): Boolean = setField(regionId, Field.LIST_LEVEL) { it.copy(listLevel = level?.coerceIn(0, 2)) }

    /** Start a paragraph here (true), continue the one before (false), or follow the spacing (null). */
    fun setNewParagraph(regionId: String, value: Boolean?): Boolean = setField(regionId, Field.PARAGRAPH) { it.copy(newParagraph = value) }

    fun setNumbered(regionId: String, value: Boolean): Boolean =
        setField(regionId, Field.NUMBERED) { if (it.role == RegionRole.MATH) it.copy(numbered = value) else it }

    /** A math region in the text (true), on its own line (false), or by position (null). */
    fun setInline(regionId: String, value: Boolean?): Boolean =
        setField(regionId, Field.INLINE) { if (it.role == RegionRole.MATH) it.copy(inline = value) else it }

    fun setPageBreak(regionId: String, value: Boolean): Boolean = setField(regionId, Field.PAGE_BREAK) { it.copy(pageBreak = value) }

    private fun setField(regionId: String, field: Field, change: (Region) -> Region): Boolean {
        val region = page.region(regionId) ?: return false
        val after = change(region)
        if (after == region) return false
        commit(Change.RegionFields(region, after, field))
        return true
    }

    /**
     * Confirms every region whose result is current, as the recognizer returned it ("Confirm
     * all"), in one undoable step. Returns the number confirmed.
     */
    fun confirmAll(): Int {
        val regions = page.regions.filter { it.status == RecognitionStatus.RECOGNIZED && !page.isStale(it) && !it.recognizedValue.isNullOrBlank() }
        val changes = regions.map { confirmation(it, page.membersOf(it), it.recognizedValue!!) }
        val follow = graphsFollowing(regions.filter { it.role == RegionRole.MATH }.associate { it.id to it.recognizedLatex!! })
        if (changes.isNotEmpty()) commit(Change.Composite(changes + follow))
        return changes.size
    }

    private fun confirmation(region: Region, members: Set<Long>, value: String, marks: List<TextMark> = emptyList()): Change {
        val confirmed = region.copy(status = RecognitionStatus.CONFIRMED, sourceStrokeIds = members, error = null, auto = false).let {
            if (region.role == RegionRole.MATH) it.copy(confirmedLatex = value) else it.copy(text = value, marks = TextMarks.normalize(marks, value.length))
        }
        return Change.RegionFields(region, confirmed, Field.CONFIRMATION, Field.AUTO)
    }

    /**
     * Applies automatic grouping: grows the planned automatic regions and creates the new
     * ones (marked [Region.auto]), placed in reading order by position. Strokes already in a
     * region are skipped, so a stale plan cannot steal ink. When the last undoable change was
     * a new stroke, the grouping joins that stroke's entry. Returns the new region ids.
     */
    fun autoGroup(plan: AutoGrouping): List<String> {
        val present = page.ink.strokes.mapTo(HashSet()) { it.id }
        val claimed = page.regions.flatMapTo(HashSet()) { it.strokeIds }
        fun free(ids: Set<Long>) = ids.filterTo(linkedSetOf()) { it in present && it !in claimed }

        val changes = mutableListOf<Change>()
        for ((regionId, ids) in plan.growth) {
            val region = page.region(regionId)?.takeIf { it.auto && it.status != RecognitionStatus.CONFIRMED } ?: continue
            val added = free(ids)
            if (added.isNotEmpty()) changes += Change.RegionFields(region, region.copy(strokeIds = region.strokeIds + added), Field.STROKES)
        }
        val byId = page.ink.strokes.associateBy { it.id }
        var number = page.nextRegionNumber
        val created = plan.newGroups.mapNotNull { group ->
            val ids = free(group.strokeIds).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Region("r${number++}", group.role, ids, Bounds.of(ids.map(byId::getValue))!!, 0, auto = true, headingLevel = group.headingLevel)
        }
        if (changes.isEmpty() && created.isEmpty()) return emptyList()

        val order = AutoRegions.readingOrderWith(page, created.map { it.id to it.bounds }).withIndex().associate { (i, id) -> id to i + 1 }
        page.regions.forEach { r ->
            val o = order.getValue(r.id)
            if (r.readingOrder != o) changes += Change.RegionFields(r, r.copy(readingOrder = o), Field.ORDER)
        }
        created.forEach { changes += Change.RegionAdded(it.copy(readingOrder = order.getValue(it.id))) }
        fold(Change.Composite(changes))
        return created.map { it.id }
    }

    /** The page background. Display only, and not part of the undo history. */
    fun setPaper(paper: Paper) {
        page = page.copy(paper = paper)
    }

    /** This page's own margin, or null for the booklet's (Phase 30). A setting like the paper, not part of the undo history. */
    fun setMargin(margin: PageMargin?) {
        page = page.copy(margin = margin)
    }

    /** Whether the page is in columns (Phase 30). A setting like the paper, not part of the undo history; the reading order follows it. */
    fun setColumns(mode: ColumnMode) {
        page = page.copy(columns = mode)
        if (!page.manualOrder) orderByPosition()
    }

    /** How the page is shown (Phase 17). Display only, and not part of the undo history. */
    fun setView(view: PageView?) {
        page = page.copy(view = view?.clamped())
    }

    // ---- Recognition (not undoable) -------------------------------------------------------

    /** Marks the region as recognizing its current strokes and returns the request to run. */
    fun startRecognition(regionId: String): RecognitionRequest? {
        val region = page.region(regionId) ?: return null
        // Scratch is recognized as text too, for search only (Phase 15).
        if (!region.needsConfirmation && region.role != RegionRole.EXCLUDED) return null
        val members = page.membersOf(region)
        if (members.isEmpty()) return null
        system(regionId) { it.copy(status = RecognitionStatus.RECOGNIZING, sourceStrokeIds = members, error = null) }
        return RecognitionRequest(regionId, members)
    }

    /**
     * Applies a result: LaTeX for math, text for text and headings. Returns false, changing
     * nothing, if the request is no longer current.
     */
    fun applyResult(request: RecognitionRequest, value: String): Boolean = whenCurrent(request) {
        val recognized = it.copy(status = RecognitionStatus.RECOGNIZED, error = null)
        if (it.role == RegionRole.MATH) recognized.copy(recognizedLatex = value) else recognized.copy(recognizedText = value)
    }

    /** Records a failure or cancellation. Returns false if the request is no longer current. */
    fun applyFailure(request: RecognitionRequest, message: String): Boolean = whenCurrent(request) {
        it.copy(status = RecognitionStatus.FAILED, error = message)
    }

    /** Strokes the region holds now, in page order. */
    fun strokesOf(regionId: String): List<Stroke> {
        val region = page.region(regionId) ?: return emptyList()
        val members = page.membersOf(region)
        return page.ink.strokes.filter { it.id in members }
    }

    // ---- History --------------------------------------------------------------------------

    fun undo(): Boolean {
        pass = PassState.NONE
        val change = undoStack.removeLastOrNull() ?: return false
        page = refreshBounds(change.revert(page))
        redoStack.addLast(change)
        return true
    }

    fun redo(): Boolean {
        pass = PassState.NONE
        val change = redoStack.removeLastOrNull() ?: return false
        page = refreshBounds(change.apply(page))
        undoStack.addLast(change)
        return true
    }

    private fun commit(change: Change) {
        commitExactly(withPositionOrder(change))
    }

    private fun commitExactly(change: Change) {
        page = refreshBounds(change.apply(page))
        redoStack.clear()
        val open = undoStack.lastOrNull() as? Change.Pass
        if (pass == PassState.HAS_ENTRY && open != null) {
            open.add(change)
            return
        }
        if (pass == PassState.OPEN) {
            undoStack.addLast(Change.Pass(change))
            pass = PassState.HAS_ENTRY
        } else {
            undoStack.addLast(change)
        }
        if (undoStack.size > historyLimit) undoStack.removeFirst()
    }

    /** Commits [change], joining the last entry when that is a stroke just written (and not undone). */
    private fun fold(change: Change) {
        val ordered = withPositionOrder(change)
        val last = undoStack.lastOrNull()
        if (last is Change.Written && redoStack.isEmpty()) {
            page = refreshBounds(ordered.apply(page))
            undoStack[undoStack.lastIndex] = Change.Written(Change.Composite(listOf(last, ordered)))
        } else {
            commitExactly(ordered)
        }
    }

    private fun system(regionId: String, change: (Region) -> Region) {
        page = page.copy(regions = page.regions.map { if (it.id == regionId) change(it) else it })
    }

    private fun whenCurrent(request: RecognitionRequest, change: (Region) -> Region): Boolean {
        val region = page.region(request.regionId) ?: return false
        val current = region.status == RecognitionStatus.RECOGNIZING &&
            region.sourceStrokeIds == request.strokeIds &&
            page.membersOf(region) == request.strokeIds
        if (current) system(request.regionId, change)
        return current
    }

    /** Bounds follow the strokes present; a region without strokes keeps its last bounds. */
    private fun refreshBounds(p: Page): Page {
        val byId = p.ink.strokes.associateBy { it.id }
        return p.copy(regions = p.regions.map { region ->
            val bounds = Bounds.of(region.strokeIds.mapNotNull { byId[it] })
            if (bounds == null || bounds == region.bounds) region else region.copy(bounds = bounds)
        })
    }

    /** Region fields a [Change.RegionFields] command sets and restores. */
    private enum class Field { ROLE, STROKES, ORDER, HEADING, CAPTION, CONFIRMATION, AUTO, ALIGN, THEOREM, LIST_LEVEL, PARAGRAPH, NUMBERED, INLINE, PAGE_BREAK, BOUNDS, GRAPH, DIAGRAM }

    private sealed interface Change {
        fun apply(page: Page): Page
        fun revert(page: Page): Page

        class StrokesAdded(private val strokes: List<Stroke>) : Change {
            private val ids = strokes.mapTo(HashSet()) { it.id }
            override fun apply(page: Page) = page.copy(ink = InkDocument(
                page.ink.strokes + strokes,
                maxOf(page.ink.nextStrokeId, strokes.maxOf { it.id } + 1),
            ))
            // The id counter never goes back, so undone ids are never reused.
            override fun revert(page: Page) = page.copy(ink = page.ink.copy(strokes = page.ink.strokes.filterNot { it.id in ids }))
        }

        class StrokesRemoved(private val strokes: List<Stroke>) : Change {
            private val inverse = StrokesAdded(strokes)
            override fun apply(page: Page) = inverse.revert(page)
            override fun revert(page: Page) = page.copy(ink = page.ink.copy(strokes = (page.ink.strokes + strokes).sortedBy { it.id }))
        }

        class StrokesReplaced(private val before: List<Stroke>, private val after: List<Stroke>) : Change {
            override fun apply(page: Page) = replace(page, after)
            override fun revert(page: Page) = replace(page, before)
            private fun replace(page: Page, with: List<Stroke>): Page {
                val byId = with.associateBy { it.id }
                return page.copy(ink = page.ink.copy(strokes = page.ink.strokes.map { byId[it.id] ?: it }))
            }
        }

        class RegionAdded(private val region: Region) : Change {
            override fun apply(page: Page) = page.copy(
                regions = page.regions + region,
                nextRegionNumber = maxOf(page.nextRegionNumber, region.id.removePrefix("r").toInt() + 1),
            )
            override fun revert(page: Page) = page.copy(regions = page.regions.filterNot { it.id == region.id })
        }

        class RegionRemoved(private val region: Region) : Change {
            override fun apply(page: Page) = page.copy(regions = page.regions.filterNot { it.id == region.id })
            override fun revert(page: Page) = page.copy(regions = page.regions + region)
        }

        /** Sets the listed fields from [after] (apply) or [before] (revert), leaving all others as they are now. */
        class RegionFields(private val before: Region, private val after: Region, private vararg val fields: Field) : Change {
            override fun apply(page: Page) = set(page, after)
            override fun revert(page: Page) = set(page, before)
            private fun set(page: Page, from: Region) = page.copy(regions = page.regions.map { current ->
                if (current.id != from.id) current else fields.fold(current) { r, field ->
                    when (field) {
                        Field.ROLE -> r.copy(role = from.role)
                        Field.STROKES -> r.copy(strokeIds = from.strokeIds)
                        Field.ORDER -> r.copy(readingOrder = from.readingOrder)
                        Field.HEADING -> r.copy(headingLevel = from.headingLevel)
                        Field.CAPTION -> r.copy(caption = from.caption)
                        Field.CONFIRMATION -> r.copy(
                            status = from.status,
                            sourceStrokeIds = from.sourceStrokeIds,
                            confirmedLatex = from.confirmedLatex,
                            text = from.text,
                            marks = from.marks,
                            error = from.error,
                        )
                        Field.AUTO -> r.copy(auto = from.auto)
                        Field.ALIGN -> r.copy(align = from.align)
                        Field.THEOREM -> r.copy(theorem = from.theorem)
                        Field.LIST_LEVEL -> r.copy(listLevel = from.listLevel)
                        Field.PARAGRAPH -> r.copy(newParagraph = from.newParagraph)
                        Field.NUMBERED -> r.copy(numbered = from.numbered)
                        Field.INLINE -> r.copy(inline = from.inline)
                        Field.PAGE_BREAK -> r.copy(pageBreak = from.pageBreak)
                        Field.BOUNDS -> r.copy(bounds = from.bounds)
                        Field.GRAPH -> r.copy(graph = from.graph)
                        Field.DIAGRAM -> r.copy(diagram = from.diagram)
                    }
                }
            })
        }

        class ManualOrder(private val value: Boolean) : Change {
            override fun apply(page: Page) = page.copy(manualOrder = value)
            override fun revert(page: Page) = page.copy(manualOrder = !value)
        }

        class Composite(private val changes: List<Change>) : Change {
            override fun apply(page: Page) = changes.fold(page) { p, c -> c.apply(p) }
            override fun revert(page: Page) = changes.asReversed().fold(page) { p, c -> c.revert(p) }
        }

        /** One pass of the eraser ([beginPass]): what it did so far, which it adds to as it goes. A list, as one pass can make many changes. */
        class Pass(first: Change) : Change {
            private val changes = mutableListOf(first)
            fun add(change: Change) {
                changes += change
            }
            override fun apply(page: Page) = changes.fold(page) { p, c -> c.apply(p) }
            override fun revert(page: Page) = changes.asReversed().fold(page) { p, c -> c.revert(p) }
        }

        /** A stroke the user just wrote; grouping that follows it may join this entry ([fold]). */
        class Written(private val change: Change) : Change by change
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 200

        /** Roles that hold words: switching between them keeps what was confirmed. */
        private val WORDS = setOf(RegionRole.TEXT, RegionRole.HEADING)
    }
}
